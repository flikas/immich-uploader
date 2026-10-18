package io.github.immichuploader.core;

import io.github.immichuploader.immich.AssetInfo;
import io.github.immichuploader.immich.AuthException;
import io.github.immichuploader.immich.BulkCheckItem;
import io.github.immichuploader.immich.BulkCheckResult;
import io.github.immichuploader.immich.ImmichApi;
import io.github.immichuploader.immich.ImmichApiException;
import io.github.immichuploader.state.RecordEntry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class VerificationService {
    private static final Logger LOG = LogManager.getLogger(VerificationService.class);

    private final ImmichApi api;
    private final int concurrency;
    private final int batchSize;
    private final Progress progress;
    private final boolean verifyLinks;

    public VerificationService(
            ImmichApi api,
            int concurrency,
            int batchSize,
            Progress progress,
            boolean verifyLinks
    ) {
        this.api = api;
        this.concurrency = Math.max(1, concurrency);
        this.batchSize = Math.max(1, batchSize);
        this.progress = progress;
        this.verifyLinks = verifyLinks;
    }

    public VerificationSummary verify(List<RecordEntry> entries) {
        VerificationSummary summary = new VerificationSummary();
        int total = entries == null ? 0 : entries.size();
        LOG.info("核对阶段开始: 总数={} concurrency={} batchSize={}", total, concurrency, batchSize);
        if (entries == null || entries.isEmpty()) {
            summary.finishedAt = System.currentTimeMillis();
            LOG.info("核对阶段完成: 总数=0");
            return summary;
        }

        List<Candidate> candidates = rehash(entries, summary);
        if (!candidates.isEmpty()) {
            check(candidates, summary);
        }
        summary.finishedAt = System.currentTimeMillis();
        LOG.info("核对阶段完成: 总数={} 匹配={} 缺失={} 关联缺失={} 关联不一致={}",
                summary.total, summary.matched, summary.missing,
                summary.linkMissing, summary.linkMismatch);
        return summary;
    }

    private List<Candidate> rehash(List<RecordEntry> entries, VerificationSummary summary) {
        LOG.info("核对哈希阶段开始: 总数={}", entries.size());
        progress.phase("核对哈希", entries.size());
        List<Candidate> candidates = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(concurrency, threadFactory("immich-verify-hash"));
        try {
            List<Future<RehashOutcome>> futures = new ArrayList<>(entries.size());
            for (RecordEntry entry : entries) {
                futures.add(pool.submit(() -> rehashOne(entry)));
            }

            int index = 0;
            for (Future<RehashOutcome> future : futures) {
                RehashOutcome outcome;
                try {
                    outcome = future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("核对被中断", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    summary.add(new VerificationSummary.Item(
                            entries.get(index),
                            VerificationSummary.Status.HASH_ERROR,
                            "",
                            "",
                            "计算 SHA1 失败: " + cause.getMessage()
                    ));
                    progress.failed();
                    index++;
                    continue;
                }

                if (outcome.errorStatus != null) {
                    summary.add(new VerificationSummary.Item(
                            outcome.entry,
                            outcome.errorStatus,
                            outcome.actualSha1,
                            "",
                            outcome.error
                    ));
                    progress.failed();
                } else {
                    boolean changed = !outcome.actualSha1.equals(outcome.entry.sha1Base64());
                    candidates.add(new Candidate(index, outcome.entry, outcome.actualSha1, changed));
                    progress.advance();
                }
                index++;
            }
        } finally {
            pool.shutdownNow();
        }
        LOG.info("核对哈希阶段完成: 候选={}", candidates.size());
        return candidates;
    }

    private RehashOutcome rehashOne(RecordEntry entry) {
        Path path = entry.path();
        if (!Files.isRegularFile(path)) {
            return RehashOutcome.error(entry, VerificationSummary.Status.FILE_MISSING, "文件不存在或不是普通文件");
        }
        if (entry.sha1Base64().isBlank()) {
            return RehashOutcome.error(entry, VerificationSummary.Status.HASH_ERROR, "记录缺少 SHA1");
        }
        try {
            String actual = Checksum.sha1Base64(path);
            return RehashOutcome.success(entry, actual);
        } catch (IOException e) {
            return RehashOutcome.error(
                    entry,
                    VerificationSummary.Status.HASH_ERROR,
                    "计算 SHA1 失败: " + e.getMessage()
            );
        }
    }

    private void check(List<Candidate> candidates, VerificationSummary summary) {
        LOG.info("服务端比对阶段开始: 总数={}", candidates.size());
        progress.phase("服务端比对", candidates.size());
        for (int start = 0; start < candidates.size(); start += batchSize) {
            int end = Math.min(candidates.size(), start + batchSize);
            List<Candidate> batch = candidates.subList(start, end);
            List<BulkCheckItem> items = new ArrayList<>(batch.size() * 2);
            for (Candidate candidate : batch) {
                items.add(new BulkCheckItem(recordId(candidate.index), candidate.entry.sha1Base64()));
                if (candidate.changed) {
                    items.add(new BulkCheckItem(actualId(candidate.index), candidate.actualSha1));
                }
            }

            try {
                List<BulkCheckResult> results = api.bulkUploadCheck(items);
                Map<String, BulkCheckResult> byId = new HashMap<>();
                for (BulkCheckResult result : results) {
                    byId.put(result.id(), result);
                }
                for (Candidate candidate : batch) {
                    BulkCheckResult recorded = byId.get(recordId(candidate.index));
                    BulkCheckResult actual = candidate.changed
                            ? byId.get(actualId(candidate.index))
                            : recorded;
                    addResult(summary, candidate, recorded, actual);
                    progress.advance();
                }
            } catch (AuthException e) {
                throw e;
            } catch (RuntimeException e) {
                LOG.warn("bulk-upload-check 核对批次失败: {}", e.getMessage());
                for (Candidate candidate : batch) {
                    summary.add(new VerificationSummary.Item(
                            candidate.entry,
                            VerificationSummary.Status.API_ERROR,
                            candidate.actualSha1,
                            "",
                            "bulk-upload-check 失败: " + e.getMessage()
                    ));
                    progress.failed();
                }
            }
        }
        LOG.info("服务端比对阶段完成: 结果={}", summary.total);
    }

    private void addResult(
            VerificationSummary summary,
            Candidate candidate,
            BulkCheckResult recorded,
            BulkCheckResult actual
    ) {
        RecordEntry entry = candidate.entry;
        if (recorded == null || actual == null) {
            summary.add(new VerificationSummary.Item(
                    entry,
                    VerificationSummary.Status.API_ERROR,
                    candidate.actualSha1,
                    "",
                    "bulk-upload-check 响应缺少对应 id"
            ));
            return;
        }

        boolean recordedFound = recorded.duplicate();
        boolean actualFound = actual.duplicate();

        if (!candidate.changed) {
            if (recordedFound) {
                String serverAssetId = recorded.assetId();
                if (recorded.trashed()) {
                    summary.add(new VerificationSummary.Item(
                            entry,
                            VerificationSummary.Status.TRASHED,
                            candidate.actualSha1,
                            serverAssetId,
                            "服务端存在相同 SHA1，但资产在回收站"
                    ));
                } else if (assetIdMismatch(entry, recorded)) {
                    addChecked(summary, new VerificationSummary.Item(
                            entry,
                            VerificationSummary.Status.MATCHED_DIFFERENT_ASSET,
                            candidate.actualSha1,
                            serverAssetId,
                            "SHA1 匹配，但服务端 assetId 与记录不同"
                    ));
                } else {
                    addChecked(summary, new VerificationSummary.Item(
                            entry,
                            VerificationSummary.Status.MATCHED,
                            candidate.actualSha1,
                            serverAssetId,
                            ""
                    ));
                }
                return;
            }

            if (recorded.accepted()) {
                summary.add(new VerificationSummary.Item(
                        entry,
                        VerificationSummary.Status.MISSING,
                        candidate.actualSha1,
                        "",
                        "服务端未找到相同 SHA1"
                ));
            } else {
                summary.add(new VerificationSummary.Item(
                        entry,
                        VerificationSummary.Status.API_ERROR,
                        candidate.actualSha1,
                        "",
                        "未知的 bulk-upload-check 结果: " + recorded.action() + "/" + recorded.reason()
                ));
            }
            return;
        }

        if (recordedFound) {
            summary.add(new VerificationSummary.Item(
                    entry,
                    VerificationSummary.Status.LOCAL_CHANGED_SERVER_OLD,
                    candidate.actualSha1,
                    recorded.assetId(),
                    "本地内容已变化；服务端仍存在记录中的旧 SHA1"
            ));
        } else if (actualFound) {
            summary.add(new VerificationSummary.Item(
                    entry,
                    VerificationSummary.Status.LOCAL_CHANGED_NEW_EXISTS,
                    candidate.actualSha1,
                    actual.assetId(),
                    "本地内容已变化；服务端存在新的 SHA1"
            ));
        } else {
            summary.add(new VerificationSummary.Item(
                    entry,
                    VerificationSummary.Status.LOCAL_CHANGED_MISSING,
                    candidate.actualSha1,
                    "",
                    "本地内容已变化，且记录中的旧 SHA1 与新 SHA1 都不在服务端"
            ));
        }
    }

    private void addChecked(VerificationSummary summary, VerificationSummary.Item item) {
        if (!verifyLinks || item.record().role() != MediaRole.LIVE_IMAGE) {
            summary.add(item);
            return;
        }
        if (item.record().livePhotoVideoId().isBlank()) {
            summary.add(new VerificationSummary.Item(
                    item.record(),
                    VerificationSummary.Status.LINK_MISSING,
                    item.actualSha1(),
                    item.serverAssetId(),
                    "记录中的 Live Photo 图片没有关联视频 ID"
            ));
            return;
        }

        String imageAssetId = item.serverAssetId().isBlank()
                ? item.record().assetId()
                : item.serverAssetId();
        if (imageAssetId.isBlank()) {
            summary.add(new VerificationSummary.Item(
                    item.record(),
                    VerificationSummary.Status.LINK_MISSING,
                    item.actualSha1(),
                    "",
                    "缺少图片 assetId，无法检查关联"
            ));
            return;
        }

        try {
            AssetInfo info = api.getAsset(imageAssetId);
            if (info.trashed()) {
                summary.add(new VerificationSummary.Item(
                        item.record(),
                        VerificationSummary.Status.TRASHED,
                        item.actualSha1(),
                        imageAssetId,
                        "图片资产在回收站"
                ));
            } else if (info.livePhotoVideoId().isBlank()) {
                summary.add(new VerificationSummary.Item(
                        item.record(),
                        VerificationSummary.Status.LINK_MISSING,
                        item.actualSha1(),
                        imageAssetId,
                        "服务端图片未关联 Live Photo 视频"
                ));
            } else if (!info.livePhotoVideoId().equals(item.record().livePhotoVideoId())) {
                summary.add(new VerificationSummary.Item(
                        item.record(),
                        VerificationSummary.Status.LINK_MISMATCH,
                        item.actualSha1(),
                        imageAssetId,
                        "服务端关联视频 " + info.livePhotoVideoId()
                                + "，记录为 " + item.record().livePhotoVideoId()
                ));
            } else {
                summary.add(item);
            }
        } catch (AuthException e) {
            throw e;
        } catch (ImmichApiException e) {
            VerificationSummary.Status status = e.statusCode() == 404
                    ? VerificationSummary.Status.MISSING
                    : VerificationSummary.Status.API_ERROR;
            summary.add(new VerificationSummary.Item(
                    item.record(),
                    status,
                    item.actualSha1(),
                    imageAssetId,
                    "检查 Live Photo 关联失败: " + e.getMessage()
            ));
        }
    }

    private static boolean assetIdMismatch(RecordEntry entry, BulkCheckResult result) {
        return !entry.assetId().isBlank()
                && !result.assetId().isBlank()
                && !entry.assetId().equals(result.assetId());
    }

    private static String recordId(int index) {
        return "r" + index;
    }

    private static String actualId(int index) {
        return "c" + index;
    }

    private static ThreadFactory threadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private record Candidate(int index, RecordEntry entry, String actualSha1, boolean changed) {
    }

    private record RehashOutcome(
            RecordEntry entry,
            String actualSha1,
            String error,
            VerificationSummary.Status errorStatus
    ) {
        static RehashOutcome success(RecordEntry entry, String actualSha1) {
            return new RehashOutcome(entry, actualSha1, "", null);
        }

        static RehashOutcome error(RecordEntry entry, VerificationSummary.Status status, String message) {
            return new RehashOutcome(entry, "", message, status);
        }
    }
}
