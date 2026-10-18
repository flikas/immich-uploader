package io.github.immichuploader.core;

import io.github.immichuploader.immich.AssetInfo;
import io.github.immichuploader.immich.AssetUploadRequest;
import io.github.immichuploader.immich.AssetUploadResult;
import io.github.immichuploader.immich.AuthException;
import io.github.immichuploader.immich.BulkCheckItem;
import io.github.immichuploader.immich.BulkCheckResult;
import io.github.immichuploader.immich.ImmichApi;
import io.github.immichuploader.immich.ImmichApiException;
import io.github.immichuploader.immich.UploadProgressListener;
import io.github.immichuploader.state.RecordEntry;
import io.github.immichuploader.state.RecordStatus;
import io.github.immichuploader.state.RecordStore;
import io.github.immichuploader.util.Formats;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class UploadPipeline {
    private static final Logger LOG = LogManager.getLogger(UploadPipeline.class);
    private static final int DEFAULT_SENDER_QUEUE = 32;

    private final ImmichApi api;
    private final RecordStore records;
    private final UploadSettings settings;
    private final Progress progress;
    private final ScanConfig config;
    private final RunSummary summary = new RunSummary();

    private final Set<Path> seenRealPaths = ConcurrentHashMap.newKeySet();
    private final Set<Path> seenRealDirectories = ConcurrentHashMap.newKeySet();
    private final AtomicInteger visited = new AtomicInteger();
    private final AtomicInteger mediaCount = new AtomicInteger();
    private final AtomicInteger imageCount = new AtomicInteger();
    private final AtomicInteger videoCount = new AtomicInteger();
    private final AtomicInteger pairCount = new AtomicInteger();
    private final ConcurrentLinkedQueue<MediaScanResult.Issue> issues = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> warnings = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<SkippedFile> skipped = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RunSummary.Failure> failures = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean fatal = new AtomicBoolean();
    private final AtomicBoolean livePhotoLinkPermissionDenied = new AtomicBoolean();
    private final AtomicInteger taskIds = new AtomicInteger();
    private final AtomicInteger readerIds = new AtomicInteger();
    private final AtomicInteger senderIds = new AtomicInteger();

    private Map<Path, RecordEntry> resumeIndex = Map.of();
    private ThreadPoolExecutor senders;
    private int perWorkBatch;

    public UploadPipeline(
            ImmichApi api,
            RecordStore records,
            UploadSettings settings,
            Progress progress,
            ScanConfig config
    ) {
        this.api = api;
        this.records = records;
        this.settings = settings;
        this.progress = progress;
        this.config = config;
    }

    public RunSummary run() {
        resumeIndex = new HashMap<>();
        for (RecordEntry entry : records.latestForServerList()) {
            resumeIndex.put(entry.path().toAbsolutePath().normalize(), entry);
        }

        int cacheLimit = Math.max(1, config.cacheMaxFileSize());
        int readers = Math.max(1, config.readerThreads());
        perWorkBatch = resolvePrecheckBatch(cacheLimit, readers, config.precheckBatchSize());
        int senderQueue = config.senderQueueLength() > 0
                ? config.senderQueueLength()
                : DEFAULT_SENDER_QUEUE;
        long maxHeap = Runtime.getRuntime().maxMemory();
        String precheckSource = config.precheckBatchSize() > 0
                ? "override/capped" : "heap";
        String queueSource = config.senderQueueLength() > 0
                ? "override" : "default";
        senders = new ThreadPoolExecutor(
                settings.senderThreads(), settings.senderThreads(),
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, senderQueue)),
                task -> {
                    Thread thread = new Thread(task, "sender-" + senderIds.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new BlockingQueuePolicy());

        LOG.info("上传配置: readers={} senders={} cacheMaxFileSize={} maxHeap={} heapBudget={} precheckBatch={} precheckBatchSource={} senderQueue={} senderQueueSource={}",
                readers, settings.senderThreads(), cacheLimit, maxHeap, maxHeap / 2,
                perWorkBatch, precheckSource, senderQueue, queueSource);
        LOG.info("上传流水线开始: readers={} senders={} cacheMaxFileSize={} precheckBatch={} senderQueue={}",
                readers, settings.senderThreads(), cacheLimit, perWorkBatch, senderQueue);
        progress.println(String.format(java.util.Locale.ROOT,
                "上传配置: readers=%d senders=%d precheckBatch=%d senderQueue=%d cacheMax=%s heap=%s",
                readers, settings.senderThreads(), perWorkBatch, senderQueue,
                Formats.bytes(cacheLimit), Formats.bytes(maxHeap)));
        progress.configureThreads(readers, settings.senderThreads());
        progress.phaseUnknown("上传");
        ForkJoinPool pool = new ForkJoinPool(readers, poolContext -> {
            ForkJoinWorkerThread worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(poolContext);
            worker.setName("reader-" + readerIds.incrementAndGet());
            worker.setDaemon(true);
            return worker;
        }, null, false);
        try {
            for (Path root : config.roots()) {
                pool.invoke(new DirectoryTask(root.toAbsolutePath().normalize()));
            }
        } finally {
            pool.shutdown();
            senders.shutdown();
            try {
                senders.awaitTermination(1, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        summary.recordScan(visited.get(), mediaCount.get(), imageCount.get(), videoCount.get(),
                issues.size(), pairCount.get());
        summary.applyStage(progress.stage());
        for (MediaScanResult.Issue issue : issues) {
            summary.addWarning("扫描: " + issue.path() + ": " + issue.message());
        }
        for (String warning : warnings) {
            summary.addWarning(warning);
        }
        for (SkippedFile skippedFile : skipped) {
            summary.addSkip(skippedFile);
        }
        for (RunSummary.Failure failure : failures) {
            if ("LIVE_PHOTO_LINK".equals(failure.stage())) {
                summary.addNonFatalFailure(failure.path(), failure.stage(), failure.message());
            } else {
                summary.addFailure(failure.path(), failure.stage(), failure.message());
            }
        }
        LOG.info("上传流水线完成: 扫描={} 上传={} 重复={} 失败={} 跳过={} LivePhoto对={}",
                summary.scanned(), summary.uploaded(), summary.duplicates(),
                summary.failed(), summary.skipped(), summary.pairsDetected());
        return summary;
    }

    private final class DirectoryTask extends RecursiveAction {
        private final Path directory;

        private DirectoryTask(Path directory) {
            this.directory = directory;
        }

        @Override
        protected void compute() {
            if (fatal.get()) {
                return;
            }
            List<ScannedMedia> media = new ArrayList<>();
            List<DirectoryTask> children = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path entry : stream) {
                    if (fatal.get()) {
                        break;
                    }
                    BasicFileAttributes attrs;
                    try {
                        attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                    } catch (IOException e) {
                        addIssue(entry, e.getMessage());
                        continue;
                    }
                    if (attrs.isDirectory()) {
                        if (config.recursive() && (!entry.getFileName().toString().equals("."))) {
                            Path real = realDirectory(entry);
                            if (real != null && seenRealDirectories.add(real)) {
                                children.add(new DirectoryTask(entry));
                            }
                        }
                    } else if (attrs.isRegularFile()) {
                        ScannedMedia scanned = scanFile(entry, attrs);
                        if (scanned != null) {
                            media.add(scanned);
                        }
                    }
                }
            } catch (IOException e) {
                addIssue(directory, e.getMessage());
            }

            for (DirectoryTask child : children) {
                child.fork();
            }

            LivePhotoPairer.Result paired = LivePhotoPairer.pair(media, config.livePhotoMode());
            for (String warning : paired.warnings()) {
                warnings.add(warning);
            }
            pairCount.addAndGet(paired.pairs().size());

            PrecheckBatch batch = new PrecheckBatch();
            try {
                for (ScannedMedia item : paired.media()) {
                    if (!item.isLivePart()) {
                        processNormal(item, batch);
                    }
                }
                for (LivePhotoPair pair : paired.pairs()) {
                    processPair(pair, batch);
                }
            } finally {
                batch.flush();
                for (DirectoryTask child : children) {
                    child.join();
                }
            }
        }
    }

    private ScannedMedia scanFile(Path entry, BasicFileAttributes attrs) {
        visited.incrementAndGet();
        String extension = MediaExtensions.extensionOf(entry.getFileName().toString());
        Path absolute = entry.toAbsolutePath().normalize();
        boolean customExtensions = !config.extensions().equals(MediaExtensions.DEFAULT);
        boolean includeUnknown = config.allFiles() || customExtensions;
        if (!config.allFiles() && !extension.isEmpty() && !config.extensions().contains(extension)) {
            skipped.add(new SkippedFile(absolute, MediaKind.OTHER, "SCAN",
                    "扩展名不在允许清单: ." + extension));
            return null;
        }
        try {
            MediaKind kind = MediaDetector.detect(entry, config.useMagic(), includeUnknown);
            if (kind == null) {
                if (!(includeUnknown && (config.allFiles() || !extension.isEmpty()))) {
                    skipped.add(new SkippedFile(absolute, MediaKind.OTHER, "SCAN",
                            "无法识别为图片/视频: ." + extension));
                    return null;
                }
                kind = MediaKind.OTHER;
            }
            if (!config.filter().accepts(kind)) {
                skipped.add(new SkippedFile(absolute, kind, "SCAN",
                        "媒体类型过滤: " + kind.label()));
                return null;
            }
            Path real = realFile(entry);
            if (real == null || !seenRealPaths.add(real)) {
                return null;
            }
            ScannedMedia media = new ScannedMedia(
                    real,
                    attrs.size(),
                    attrs.lastModifiedTime().toMillis(),
                    safeCreated(attrs),
                    kind,
                    MediaRole.NORMAL,
                    "");
            mediaCount.incrementAndGet();
            if (kind == MediaKind.IMAGE) {
                imageCount.incrementAndGet();
            } else if (kind == MediaKind.VIDEO) {
                videoCount.incrementAndGet();
            }
            return media;
        } catch (RuntimeException e) {
            addIssue(absolute, e.getMessage());
            return null;
        }
    }

    private void processNormal(ScannedMedia media, PrecheckBatch batch) {
        if (fatal.get()) {
            return;
        }
        RecordEntry resume = settings.resume() ? resumeIndex.get(media.path()) : null;
        if (resume != null && resume.complete()) {
            progress.resumeSkipped(1);
            return;
        }
        if (resume != null) {
            progress.stale();
        }
        progress.readerStarted(media.path(), media.size());
        try {
            byte[] data = cachedBytes(media);
            String sha1;
            if (data != null) {
                sha1 = Checksum.sha1Base64(data);
                progress.readerProgress(media.size());
            } else {
                sha1 = Checksum.sha1Base64(media.path(), progress::readerProgress);
            }
            progress.hashed(media.size());
            batch.addNormal(media, sha1, Checksum.hexFromBase64(sha1),
                    contentType(media.path()), data);
        } catch (Exception e) {
            LOG.error("读取/哈希失败 [{}]: {}", media.path(), e.getMessage(), e);
            failures.add(new RunSummary.Failure(media.path(), "HASH", e.getMessage()));
            progress.failed();
        } finally {
            progress.readerFinished();
        }
    }

    private void processPair(LivePhotoPair pair, PrecheckBatch batch) {
        if (fatal.get()) {
            return;
        }
        RecordEntry imageResume = settings.resume() ? resumeIndex.get(pair.image().path()) : null;
        RecordEntry videoResume = settings.resume() ? resumeIndex.get(pair.video().path()) : null;
        if (imageResume != null && imageResume.complete()
                && videoResume != null && videoResume.complete()) {
            progress.pairSkipped();
            progress.resumeSkipped(2);
            return;
        }
        if (imageResume != null && !imageResume.complete()) {
            progress.stale();
        }
        if (videoResume != null && !videoResume.complete()) {
            progress.stale();
        }
        try {
            boolean cacheBoth = pair.image().size() + pair.video().size() <= config.cacheMaxFileSize();
            byte[] imageData;
            String imageSha;
            progress.readerStarted(pair.image().path(), pair.image().size());
            try {
                imageData = cacheBoth ? cachedBytes(pair.image()) : null;
                imageSha = imageData != null
                        ? Checksum.sha1Base64(imageData)
                        : Checksum.sha1Base64(pair.image().path(), progress::readerProgress);
                if (imageData != null) {
                    progress.readerProgress(pair.image().size());
                }
            } finally {
                progress.readerFinished();
            }

            byte[] videoData;
            String videoSha;
            progress.readerStarted(pair.video().path(), pair.video().size());
            try {
                videoData = cacheBoth ? cachedBytes(pair.video()) : null;
                videoSha = videoData != null
                        ? Checksum.sha1Base64(videoData)
                        : Checksum.sha1Base64(pair.video().path(), progress::readerProgress);
                if (videoData != null) {
                    progress.readerProgress(pair.video().size());
                }
            } finally {
                progress.readerFinished();
            }

            progress.hashed(2, pair.image().size() + pair.video().size());
            UploadTask.AssetPart image = new UploadTask.AssetPart(
                    pair.image(), imageSha, Checksum.hexFromBase64(imageSha),
                    contentType(pair.image().path()), imageData);
            UploadTask.AssetPart video = new UploadTask.AssetPart(
                    pair.video(), videoSha, Checksum.hexFromBase64(videoSha),
                    contentType(pair.video().path()), videoData);
            batch.addLivePhoto(image, video);
        } catch (Exception e) {
            LOG.error("读取/哈希 Live Photo 失败 [{}]: {}", pair.image().path(), e.getMessage(), e);
            failures.add(new RunSummary.Failure(pair.image().path(), "HASH", e.getMessage()));
            progress.failed(2);
        }
    }

    private byte[] cachedBytes(ScannedMedia media) {
        if (media.size() > config.cacheMaxFileSize()) {
            return null;
        }
        try {
            return Files.readAllBytes(media.path());
        } catch (IOException e) {
            throw new RuntimeException("读取文件失败: " + e.getMessage(), e);
        }
    }

    private final class PrecheckBatch {
        private final List<BatchItem> pending = new ArrayList<>();

        void addNormal(ScannedMedia media, String sha1Base64, String sha1Hex,
                       String contentType, byte[] data) {
            pending.add(new PendingNormal(media, sha1Base64, sha1Hex, contentType, data));
            maybeFlush();
        }

        void addLivePhoto(UploadTask.AssetPart image, UploadTask.AssetPart video) {
            pending.add(new PendingLivePhoto(image, video));
            maybeFlush();
        }

        private void maybeFlush() {
            if (pending.size() >= perWorkBatch) {
                flush();
            }
        }

        void flush() {
            if (pending.isEmpty()) {
                return;
            }
            List<BatchItem> current = new ArrayList<>(pending);
            pending.clear();
            if (fatal.get()) {
                return;
            }

            List<BulkCheckItem> items = new ArrayList<>();
            List<PendingSlot> slots = new ArrayList<>();
            for (BatchItem item : current) {
                if (item instanceof PendingLivePhoto live) {
                    String imageId = Integer.toString(taskIds.getAndIncrement());
                    String videoId = Integer.toString(taskIds.getAndIncrement());
                    items.add(new BulkCheckItem(imageId, live.image.sha1Base64));
                    items.add(new BulkCheckItem(videoId, live.video.sha1Base64));
                    slots.add(new PendingSlot(item, imageId, videoId));
                } else {
                    PendingNormal normal = (PendingNormal) item;
                    String id = Integer.toString(taskIds.getAndIncrement());
                    items.add(new BulkCheckItem(id, normal.sha1Base64));
                    slots.add(new PendingSlot(item, id, ""));
                }
            }

            Map<String, BulkCheckResult> results = new HashMap<>();
            try {
                for (BulkCheckResult result : api.bulkUploadCheck(items)) {
                    results.put(result.id(), result);
                }
            } catch (RuntimeException e) {
                LOG.error("bulk-upload-check 失败，将直接上传本批文件", e);
                progress.precheckErrors(items.size());
                warnings.add("bulk-upload-check 失败，将直接上传本批文件: " + e.getMessage());
            }

            for (PendingSlot slot : slots) {
                if (fatal.get()) {
                    return;
                }
                if (slot.pending instanceof PendingLivePhoto live) {
                    BulkCheckResult image = results.get(slot.imageId);
                    BulkCheckResult video = results.get(slot.videoId);
                    String imageAsset = image != null && image.duplicate() ? image.assetId() : "";
                    String videoAsset = video != null && video.duplicate() ? video.assetId() : "";
                    if (image != null && image.duplicate()) {
                        progress.precheckDuplicates(1);
                    }
                    if (video != null && video.duplicate()) {
                        progress.precheckDuplicates(1);
                    }
                    submit(new UploadTask.LivePhoto(live.image, live.video, imageAsset, videoAsset));
                } else {
                    PendingNormal normal = (PendingNormal) slot.pending;
                    BulkCheckResult result = results.get(slot.imageId);
                    if (result != null && result.duplicate()) {
                        if (result.trashed()) {
                            skipped.add(new SkippedFile(normal.media.path(), normal.media.kind(),
                                    "CHECK", "服务端回收站已有相同文件"));
                            progress.skipped();
                        } else {
                            progress.precheckDuplicates(1);
                            append(normal.media, normal.sha1Base64, RecordStatus.DUPLICATE,
                                    result.assetId(), "", "duplicate", "");
                        }
                        continue;
                    }
                    submit(new UploadTask.Normal(normal.media, normal.sha1Base64,
                            normal.sha1Hex, normal.contentType, normal.data));
                }
            }
        }
    }

    private interface BatchItem {
    }

    private record PendingNormal(
            ScannedMedia media,
            String sha1Base64,
            String sha1Hex,
            String contentType,
            byte[] data
    ) implements BatchItem {
    }

    private record PendingLivePhoto(
            UploadTask.AssetPart image,
            UploadTask.AssetPart video
    ) implements BatchItem {
    }

    private record PendingSlot(BatchItem pending, String imageId, String videoId) {
    }

    private static final class BlockingQueuePolicy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable runnable, ThreadPoolExecutor executor) {
            if (executor.isShutdown()) {
                throw new RejectedExecutionException("Sender 已关闭");
            }
            try {
                executor.getQueue().put(runnable);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException("等待 Sender 队列时被中断", e);
            }
        }
    }

    private void submit(UploadTask task) {
        if (fatal.get()) {
            return;
        }
        try {
            senders.execute(() -> {
            if (fatal.get()) {
                return;
            }
            try {
                if (task instanceof UploadTask.LivePhoto live) {
                    uploadPair(live);
                } else {
                    uploadNormal(task);
                }
            } catch (AuthException e) {
                LOG.error("上传认证失败 [{}]: {}", task.path(), e.getMessage(), e);
                fatal.set(true);
                summary.fail(e.getMessage());
                failures.add(new RunSummary.Failure(task.path(), "UPLOAD", e.getMessage()));
                progress.failed(task instanceof UploadTask.LivePhoto ? 2 : 1);
            } catch (ImmichApiException e) {
                if (e.missingPermission("asset.upload")) {
                    LOG.error("API Key 缺少 asset.upload，终止上传 [{}]: {}",
                            task.path(), e.getMessage(), e);
                    fatal.set(true);
                    summary.fail("API Key 缺少 asset.upload，已终止上传");
                    failures.add(new RunSummary.Failure(task.path(), "UPLOAD", e.getMessage()));
                    progress.failed(task instanceof UploadTask.LivePhoto ? 2 : 1);
                } else if (e.unsupportedFormat()) {
                    if (task instanceof UploadTask.LivePhoto live) {
                        skipped.add(new SkippedFile(live.image.media.path(), live.image.media.kind(),
                                "UPLOAD", "Immich 不支持此格式"));
                        skipped.add(new SkippedFile(live.video.media.path(), live.video.media.kind(),
                                "UPLOAD", "Immich 不支持此格式"));
                        progress.skipped();
                        progress.skipped();
                    } else {
                        skipped.add(new SkippedFile(task.media.path(), task.media.kind(),
                                "UPLOAD", "Immich 不支持此格式"));
                        progress.skipped();
                    }
                } else {
                    LOG.error("上传失败 [{}] {}", task.path(), e.getMessage(), e);
                    failures.add(new RunSummary.Failure(task.path(), "UPLOAD", e.getMessage()));
                    progress.failed(task instanceof UploadTask.LivePhoto ? 2 : 1);
                }
            } catch (Exception e) {
                LOG.error("上传异常 [{}]: {}", task.path(), e.getMessage(), e);
                failures.add(new RunSummary.Failure(task.path(), "UPLOAD", e.getMessage()));
                progress.failed(task instanceof UploadTask.LivePhoto ? 2 : 1);
            }
            });
        } catch (RuntimeException e) {
            throw e;
        }
    }

    private void uploadNormal(UploadTask task) {
        AssetUploadResult result = uploadPart(task, "", "");
        boolean duplicate = "duplicate".equalsIgnoreCase(result.status());
        append(task.media, task.sha1Base64,
                duplicate ? RecordStatus.DUPLICATE : RecordStatus.UPLOADED,
                result.assetId(), "", duplicate ? "duplicate" : "uploaded", "");
        if (duplicate) {
            progress.duplicate();
        } else {
            progress.uploaded(task.size());
        }
    }

    private void uploadPair(UploadTask.LivePhoto task) {
        String imageId = task.imageAssetId.isBlank() ? null : task.imageAssetId;
        String videoId = task.videoAssetId.isBlank() ? null : task.videoAssetId;
        boolean videoAvailable = videoId != null;

        if (videoId == null) {
            try {
                AssetUploadResult video = uploadPart(task.video, "", "");
                videoId = video.assetId();
                videoAvailable = true;
                boolean duplicate = "duplicate".equalsIgnoreCase(video.status());
                append(task.video.media, task.video.sha1Base64,
                        duplicate ? RecordStatus.DUPLICATE : RecordStatus.UPLOADED,
                        videoId, "", duplicate ? "duplicate" : "live-photo-video", "");
                if (duplicate) {
                    progress.duplicate();
                } else {
                    progress.uploaded(task.video.media.size());
                }
            } catch (ImmichApiException e) {
                if (!e.unsupportedFormat()) {
                    throw e;
                }
                skipped.add(new SkippedFile(task.video.media.path(), task.video.media.kind(),
                        "UPLOAD", "Immich 不支持此格式"));
                progress.skipped();
                videoAvailable = false;
            }
        } else {
            append(task.video.media, task.video.sha1Base64, RecordStatus.DUPLICATE,
                    videoId, "", "duplicate", "");
        }

        if (imageId == null) {
            String linkVideoId = videoAvailable ? videoId : "";
            try {
                AssetUploadResult image = uploadPart(task.image, linkVideoId, "");
                imageId = image.assetId();
                boolean duplicate = "duplicate".equalsIgnoreCase(image.status());
                append(task.image.media, task.image.sha1Base64,
                        duplicate ? RecordStatus.DUPLICATE : RecordStatus.UPLOADED,
                        imageId, linkVideoId,
                        duplicate ? "duplicate" : (videoAvailable ? "live-photo" : "uploaded"), "");
                if (duplicate) {
                    progress.duplicate();
                } else {
                    progress.uploaded(task.image.media.size());
                }
            } catch (ImmichApiException e) {
                if (!e.unsupportedFormat()) {
                    throw e;
                }
                skipped.add(new SkippedFile(task.image.media.path(), task.image.media.kind(),
                        "UPLOAD", "Immich 不支持此格式"));
                progress.skipped();
                return;
            }
        } else {
            append(task.image.media, task.image.sha1Base64, RecordStatus.DUPLICATE,
                    imageId, videoAvailable ? videoId : "", "duplicate", "");
        }

        if (!videoAvailable) {
            return;
        }
        AssetInfo info = api.getAsset(imageId);
        if (info.livePhotoVideoId().isBlank()) {
            if (livePhotoLinkPermissionDenied.get()) {
                recordLinkFailure(task, imageId,
                        "已确认缺少 asset.update，跳过补链");
                return;
            }
            try {
                api.linkLivePhoto(imageId, videoId);
                progress.pairLinked();
            } catch (ImmichApiException e) {
                if (e.missingPermission("asset.update")) {
                    livePhotoLinkPermissionDenied.set(true);
                }
                recordLinkFailure(task, imageId, e.getMessage());
            } catch (RuntimeException e) {
                recordLinkFailure(task, imageId, e.getMessage());
            }
        } else if (info.livePhotoVideoId().equals(videoId)) {
            progress.pairAlreadyLinked();
        } else {
            progress.pairConflict();
            failures.add(new RunSummary.Failure(task.image.media.path(), "LIVE_PHOTO",
                    "图片已关联其他视频，未覆盖"));
        }
    }

    private void recordLinkFailure(UploadTask.LivePhoto task, String imageId, String message) {
        LOG.error("Live Photo 补链失败 [{}]: {}", task.image.media.path(), message);
        failures.add(new RunSummary.Failure(task.image.media.path(),
                "LIVE_PHOTO_LINK", message));
        progress.linkFailed();
        try {
            records.append(new RecordEntry(
                    System.currentTimeMillis(),
                    RecordStatus.UPLOADED,
                    task.image.media.kind(),
                    task.image.media.role(),
                    task.image.media.pairKey(),
                    task.image.media.path(),
                    task.image.media.size(),
                    task.image.media.modifiedAtMillis(),
                    task.image.sha1Base64,
                    imageId,
                    "",
                    settings.server(),
                    "link-failed",
                    message));
        } catch (IOException e) {
            LOG.error("写入补链失败记录失败 [{}]: {}", task.image.media.path(), e.getMessage(), e);
        }
    }

    private AssetUploadResult uploadPart(UploadTask task, String livePhotoVideoId, String visibility) {
        progress.senderStarted(task.path(), task.size());
        try {
            return api.uploadAsset(request(task, livePhotoVideoId, visibility,
                    (written, total) -> progress.senderProgress(written)));
        } finally {
            progress.senderFinished();
        }
    }

    private AssetUploadResult uploadPart(UploadTask.AssetPart part, String livePhotoVideoId, String visibility) {
        progress.senderStarted(part.media.path(), part.media.size());
        try {
            return api.uploadAsset(request(part, livePhotoVideoId, visibility,
                    (written, total) -> progress.senderProgress(written)));
        } finally {
            progress.senderFinished();
        }
    }

    private AssetUploadRequest request(UploadTask task, String livePhotoVideoId, String visibility) {
        return request(task, livePhotoVideoId, visibility, null);
    }

    private AssetUploadRequest request(
            UploadTask task, String livePhotoVideoId, String visibility,
            UploadProgressListener listener
    ) {
        return request(task.media, task.sha1Base64, livePhotoVideoId, visibility, task.data,
                task.contentType, task.sha1Hex, listener);
    }

    private AssetUploadRequest request(UploadTask.AssetPart part, String livePhotoVideoId, String visibility) {
        return request(part, livePhotoVideoId, visibility, null);
    }

    private AssetUploadRequest request(
            UploadTask.AssetPart part, String livePhotoVideoId, String visibility,
            UploadProgressListener listener
    ) {
        return request(part.media, part.sha1Base64, livePhotoVideoId, visibility, part.data,
                part.contentType, part.sha1Hex, listener);
    }

    private AssetUploadRequest request(
            ScannedMedia media, String sha1, String livePhotoVideoId, String visibility,
            byte[] data, String contentType, String sha1Hex
    ) {
        return request(media, sha1, livePhotoVideoId, visibility, data, contentType, sha1Hex, null);
    }

    private AssetUploadRequest request(
            ScannedMedia media, String sha1, String livePhotoVideoId, String visibility,
            byte[] data, String contentType, String sha1Hex, UploadProgressListener listener
    ) {
        return new AssetUploadRequest(
                media.path(),
                media.path().getFileName().toString(),
                sha1,
                "iu-" + sha1Hex,
                settings.deviceId(),
                Instant.ofEpochMilli(media.createdAtMillis()),
                Instant.ofEpochMilli(media.modifiedAtMillis()),
                livePhotoVideoId,
                visibility,
                data,
                listener);
    }

    private void append(ScannedMedia media, String sha1, RecordStatus status, String assetId,
                        String livePhotoVideoId, String action, String message) {
        try {
            RecordEntry entry = new RecordEntry(
                    System.currentTimeMillis(), status, media.kind(), media.role(),
                    media.pairKey(), media.path(), media.size(), media.modifiedAtMillis(),
                    sha1, assetId, livePhotoVideoId, settings.server(), action, message);
            records.append(entry);
            summary.addSuccess(entry);
        } catch (IOException e) {
            throw new RuntimeException("写入记录失败: " + e.getMessage(), e);
        }
    }

    static int resolvePrecheckBatch(int cacheLimit, int readers, int configured) {
        int safeCacheLimit = Math.max(1, cacheLimit);
        int safeReaders = Math.max(1, readers);
        long halfHeap = Runtime.getRuntime().maxMemory() / 2;
        int byHeap = (int) Math.max(1, Math.min(500L,
                halfHeap / safeCacheLimit / safeReaders));
        if (configured <= 0) {
            return byHeap;
        }
        return Math.min(byHeap, Math.min(500, configured));
    }

    private void addIssue(Path path, String message) {
        issues.add(new MediaScanResult.Issue(path, message == null ? "I/O 错误" : message));
    }

    private String contentType(Path path) {
        try {
            String value = Files.probeContentType(path);
            return value == null ? "application/octet-stream" : value;
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    private Path realFile(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            addIssue(path, e.getMessage());
            return null;
        }
    }

    private Path realDirectory(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            addIssue(path, e.getMessage());
            return null;
        }
    }

    private static long safeCreated(BasicFileAttributes attrs) {
        long created = attrs.creationTime().toMillis();
        if (created <= 0 || created > System.currentTimeMillis() + 86_400_000L) {
            return attrs.lastModifiedTime().toMillis();
        }
        return created;
    }
}
