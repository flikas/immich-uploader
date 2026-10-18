package io.github.immichuploader.cli;

import io.github.immichuploader.UserError;
import io.github.immichuploader.core.MediaScanResult;
import io.github.immichuploader.core.MediaScanner;
import io.github.immichuploader.core.Progress;
import io.github.immichuploader.core.RunSummary;
import io.github.immichuploader.core.ScanConfig;
import io.github.immichuploader.core.StorageDetector;
import io.github.immichuploader.core.UploadPipeline;
import io.github.immichuploader.core.UploadSettings;
import io.github.immichuploader.core.VerificationService;
import io.github.immichuploader.core.VerificationSummary;
import io.github.immichuploader.immich.ImmichClient;
import io.github.immichuploader.report.HtmlReportWriter;
import io.github.immichuploader.report.ReportContext;
import io.github.immichuploader.state.RecordEntry;
import io.github.immichuploader.state.RecordStore;
import io.github.immichuploader.util.DeviceId;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(
        name = "upload",
        mixinStandardHelpOptions = true,
        description = "扫描目录并上传图片/视频到 Immich"
)
public final class UploadCommand implements Callable<Integer> {
    private static final Logger LOG = LogManager.getLogger(UploadCommand.class);
    private static final String DEFAULT_DEVICE_ID = "主机名";

    @Mixin
    private ImmichOptions immich;

    @Mixin
    private ScanOptions scan;

    @Mixin
    private StateOptions state;

    @Option(names = "--sender-threads", defaultValue = "2", description = "Sender 上传线程数（默认 ${DEFAULT-VALUE}）")
    private int senderThreads;

    @Option(names = "--batch-size", defaultValue = "500",
            description = "bulk-upload-check 批量大小（默认 ${DEFAULT-VALUE}）")
    private int batchSize;

    @Option(names = "--reader-threads", defaultValue = "0",
            description = "Reader/ForkJoin 线程数；0=自动（SSD 4，HDD/未知 1）")
    private int readerThreads;

    @Option(names = "--precheck-batch-size", defaultValue = "0",
            description = "每个目录任务的预检批上限；0=按堆自动，最大 500")
    private int precheckBatchSize;

    @Option(names = "--cache-max-file-size", defaultValue = "20M",
            description = "单文件缓存上限（默认 ${DEFAULT-VALUE}）")
    private String cacheMaxFileSize;

    @Option(names = "--sender-queue-length", defaultValue = "0",
            description = "Sender 队列长度；0=按堆自动")
    private int senderQueueLength;



    @Option(names = "--device-id", defaultValue = DEFAULT_DEVICE_ID,
            description = "Immich deviceId（默认 ${DEFAULT-VALUE}）")
    private String deviceId;

    @Option(names = "--dry-run", description = "只扫描并显示结果，不连接服务器、不上传")
    private boolean dryRun;

    @Option(names = "--no-resume", description = "忽略已有记录，重新处理所有文件")
    private boolean noResume;

    @Option(names = "--no-verify", description = "上传后不执行核对")
    private boolean noVerify;

    @Option(names = "--verify-scope", defaultValue = "RUN",
            description = "上传后核对范围: RUN（本次）或 ALL（全部记录）（默认 ${DEFAULT-VALUE}）")
    private UploadSettings.VerifyScope verifyScope;

    @Option(names = "--no-verify-links", description = "不检查 Live Photo 关联")
    private boolean noVerifyLinks;

    private boolean verifyEnabled() {
        return !noVerify;
    }

    private boolean verifyLinksEnabled() {
        return !noVerifyLinks;
    }

    @Override
    public Integer call() throws Exception {
        if (senderThreads < 1 || senderThreads > 64) {
            throw new UserError("--sender-threads 必须在 1 到 64 之间");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new UserError("--batch-size 必须在 1 到 1000 之间");
        }
        if (readerThreads < 0 || readerThreads > 8) {
            throw new UserError("--reader-threads 必须在 0 到 8 之间");
        }
        if (precheckBatchSize < 0 || precheckBatchSize > 500) {
            throw new UserError("--precheck-batch-size 必须在 0 到 500 之间");
        }
        if (senderQueueLength < 0 || senderQueueLength > 256) {
            throw new UserError("--sender-queue-length 必须在 0 到 256 之间");
        }

        List<Path> directories = scan.resolveDirectories();
        Set<String> extensions = scan.extensionSet();
        String normalizedServer = dryRun
                ? (immich.rawServer() == null ? "(dry-run)" : immich.resolveServer())
                : immich.resolveServer();
        String effectiveDeviceId = DEFAULT_DEVICE_ID.equals(deviceId)
                ? DeviceId.defaultValue()
                : DeviceId.normalize(deviceId);

        LOG.info("upload 启动: dirs={} server={} senderThreads={} readerThreads={} cacheMaxFileSize={}",
                directories, normalizedServer, senderThreads, readerThreads, cacheMaxFileSize);

        if (dryRun) {
            LOG.info("dry-run 扫描阶段开始: dirs={}", directories);
            MediaScanResult scanResult;
            try (ScanProgressPrinter scanProgress = new ScanProgressPrinter(System.out)) {
                scanResult = MediaScanner.scan(
                        directories,
                        scan.recursive(),
                        scan.followLinks(),
                        extensions,
                        scan.magicDetection(),
                        scan.allFiles(),
                        scan.mediaFilter(),
                        scan.livePhotoMode(),
                        scanProgress
                );
            }
            System.out.println("扫描完成: 媒体=" + scanResult.media().size()
                    + " 图片=" + scanResult.imageCount()
                    + " 视频=" + scanResult.videoCount()
                    + " Live Photo对=" + scanResult.pairs().size()
                    + " 跳过=" + scanResult.skipped().size()
                    + " 问题=" + scanResult.issues().size());
            LOG.info("dry-run 扫描阶段完成: 媒体={} 图片={} 视频={} 跳过={} 问题={}",
                    scanResult.media().size(), scanResult.imageCount(), scanResult.videoCount(),
                    scanResult.skipped().size(), scanResult.issues().size());
            for (MediaScanResult.Issue issue : scanResult.issues()) {
                System.out.println("[问题] " + issue.path() + ": " + issue.message());
            }
            for (String warning : scanResult.warnings()) {
                System.out.println("[警告] " + warning);
            }
            for (var skipped : scanResult.skipped()) {
                System.out.println("[跳过] " + skipped.path() + ": " + skipped.reason());
            }
            if (Files.exists(state.recordFile())) {
                List<RecordEntry> records = RecordStore.read(state.recordFile());
                System.out.println("记录文件: " + state.recordFile() + "（" + records.size() + " 条记录）");
            }
            System.out.println("[DRY RUN] 未连接 Immich，未上传，未写入记录。");
            int exitCode = scanResult.issues().isEmpty() ? 0 : 2;
            LOG.info("upload dry-run 完成: exitCode={}", exitCode);
            return exitCode;
        }

        int cacheLimit = parseSize(cacheMaxFileSize, "--cache-max-file-size");
        int resolvedReaders = StorageDetector.readerThreads(directories, readerThreads);
        ScanConfig scanConfig = new ScanConfig(
                directories,
                scan.recursive(),
                scan.followLinks(),
                extensions,
                scan.magicDetection(),
                scan.allFiles(),
                scan.mediaFilter(),
                scan.livePhotoMode(),
                resolvedReaders,
                cacheLimit,
                precheckBatchSize,
                senderQueueLength
        );

        ImmichClient client = immich.buildClient();
        String serverVersion = "未知";
        try {
            var version = client.serverVersion();
            if (version != null) {
                serverVersion = version.display();
            }

            Path recordFile = state.recordFile();
            Path reportDir = state.reportDir();
            try (Progress progress = new Progress(System.out, true,
                    System.console() != null || "1".equals(System.getenv("DSH_SHELL")));
                 RecordStore store = RecordStore.open(recordFile, normalizedServer)) {
                UploadSettings settings = new UploadSettings(
                        normalizedServer,
                        effectiveDeviceId,
                        senderThreads,
                        batchSize,
                        !noResume,
                        verifyEnabled(),
                        verifyScope,
                        scan.livePhotoMode(),
                        verifyLinksEnabled()
                );

                UploadPipeline pipeline = new UploadPipeline(client, store, settings, progress, scanConfig);
                RunSummary summary = pipeline.run();
                LOG.info("上传阶段完成: 扫描={} 上传={} 重复={} 失败={} 跳过={}",
                        summary.scanned(), summary.uploaded(), summary.duplicates(),
                        summary.failed(), summary.skipped());

                if (verifyEnabled() && !summary.fatal) {
                    try {
                        List<RecordEntry> entries = verifyScope == UploadSettings.VerifyScope.ALL
                                ? store.latestForServerList()
                                : store.latestForPaths(summary.successfulPaths());
                        if (!entries.isEmpty()) {
                            VerificationService verification = new VerificationService(
                                    client, senderThreads, batchSize, progress, verifyLinksEnabled());
                            summary.verification = verification.verify(entries);
                            LOG.info("核对阶段完成: 总数={} 匹配={} 缺失={} 关联缺失={} 关联不一致={}",
                                    summary.verification.total, summary.verification.matched,
                                    summary.verification.missing, summary.verification.linkMissing,
                                    summary.verification.linkMismatch);
                        }
                    } catch (RuntimeException e) {
                        summary.fail("上传后核对失败: " + e.getMessage());
                    }
                }
                summary.finishedAt = System.currentTimeMillis();

                ReportContext context = new ReportContext(
                        "1.0.0",
                        normalizedServer,
                        serverVersion,
                        directories,
                        extensions,
                        recordFile,
                        effectiveDeviceId,
                        scan.recursive(),
                        scan.allFiles(),
                        scan.magicDetection(),
                        scan.livePhotoMode(),
                        verifyEnabled(),
                        verifyLinksEnabled()
                );

                Path report = null;
                try {
                    report = HtmlReportWriter.write(reportDir, context, summary, summary.verification, false);
                    LOG.info("HTML 报告已生成: {}", report);
                } catch (IOException e) {
                    summary.fail("生成 HTML 报告失败: " + e.getMessage());
                }

                printSummary(summary, recordFile, report);
                int exitCode = summary.exitCode();
                LOG.info("upload 完成: exitCode={} uploaded={} duplicates={} failed={} skipped={}",
                        exitCode, summary.uploaded(), summary.duplicates(), summary.failed(), summary.skipped());
                return exitCode;
            }
        } finally {
            client.close();
        }
    }

    private static int parseSize(String value, String option) {
        if (value == null || value.isBlank()) {
            throw new UserError(option + " 不能为空");
        }
        String text = value.trim().toUpperCase(java.util.Locale.ROOT);
        long multiplier = 1;
        if (text.endsWith("K") || text.endsWith("KB")) {
            multiplier = 1024L;
            text = text.replace("KB", "").replace("K", "");
        } else if (text.endsWith("M") || text.endsWith("MB")) {
            multiplier = 1024L * 1024L;
            text = text.replace("MB", "").replace("M", "");
        } else if (text.endsWith("G") || text.endsWith("GB")) {
            multiplier = 1024L * 1024L * 1024L;
            text = text.replace("GB", "").replace("G", "");
        } else if (text.endsWith("B")) {
            text = text.substring(0, text.length() - 1);
        }
        try {
            long result = Long.parseLong(text.trim()) * multiplier;
            if (result < 1 || result > Integer.MAX_VALUE) {
                throw new UserError(option + " 超出范围: " + value);
            }
            return (int) result;
        } catch (NumberFormatException e) {
            throw new UserError(option + " 格式无效: " + value);
        }
    }

    private static void printSummary(RunSummary summary, Path recordFile, Path report) {
        System.out.println();
        System.out.println("上传完成: 遍历=" + summary.visitedFiles()
                + " 扫描=" + summary.scanned()
                + " 图片=" + summary.imageCount()
                + " 视频=" + summary.videoCount()
                + " 断点跳过=" + summary.resumeSkipped()
                + " 格式/扩展名跳过=" + summary.skipped()
                + " 上传=" + summary.uploaded()
                + " 服务端已存在=" + summary.duplicates()
                + " 失败=" + summary.failed()
                + " LivePhoto对=" + summary.pairsDetected()
                + " 已关联=" + (summary.pairsLinked() + summary.pairsAlreadyLinked() + summary.pairsSkipped())
                + " 冲突=" + summary.pairsConflict());
        if (summary.verification != null) {
            VerificationSummary v = summary.verification;
            System.out.println("核对: 总数=" + v.total
                    + " 匹配=" + v.matched
                    + " 缺失=" + v.missing
                    + " 关联缺失=" + v.linkMissing
                    + " 关联不一致=" + v.linkMismatch
                    + " 其他失败=" + (v.fileMissing + v.hashErrors + v.apiErrors + v.localChangedMissing));
        }
        if (summary.fatal) {
            System.out.println("致命错误: " + summary.fatalMessage);
        }
        for (String warning : summary.warnings()) {
            System.out.println("[警告] " + warning);
        }
        System.out.println("记录文件: " + recordFile);
        if (report != null) {
            System.out.println("HTML 报告: " + report);
        }
    }
}
