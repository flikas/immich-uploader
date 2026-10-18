package io.github.immichuploader.cli;

import io.github.immichuploader.UserError;
import io.github.immichuploader.core.LivePhotoMode;
import io.github.immichuploader.core.Progress;
import io.github.immichuploader.core.VerificationService;
import io.github.immichuploader.core.VerificationSummary;
import io.github.immichuploader.immich.ImmichClient;
import io.github.immichuploader.report.HtmlReportWriter;
import io.github.immichuploader.report.ReportContext;
import io.github.immichuploader.state.RecordEntry;
import io.github.immichuploader.state.RecordStore;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(
        name = "verify",
        mixinStandardHelpOptions = true,
        description = "对记录中的全部资产执行 SHA1 和 Live Photo 关联核对"
)
public final class VerifyCommand implements Callable<Integer> {
    private static final Logger LOG = LogManager.getLogger(VerifyCommand.class);

    @Mixin
    private ImmichOptions immich;

    @Mixin
    private StateOptions state;

    @Option(names = "--concurrency", defaultValue = "4", description = "并发数（默认 ${DEFAULT-VALUE}）")
    private int concurrency;

    @Option(names = "--batch-size", defaultValue = "500",
            description = "bulk-upload-check 批量大小（默认 ${DEFAULT-VALUE}）")
    private int batchSize;

    @Option(names = "--no-verify-links", description = "不检查 Live Photo 关联")
    private boolean noVerifyLinks;

    @Option(names = "--strict", description = "把回收站、assetId 不一致等警告也视为失败")
    private boolean strict;

    private boolean verifyLinksEnabled() {
        return !noVerifyLinks;
    }

    @Override
    public Integer call() throws Exception {
        if (concurrency < 1 || concurrency > 64) {
            throw new UserError("--concurrency 必须在 1 到 64 之间");
        }
        if (batchSize < 1 || batchSize > 1000) {
            throw new UserError("--batch-size 必须在 1 到 1000 之间");
        }
        Path recordFile = state.recordFile();
        if (!Files.exists(recordFile)) {
            throw new UserError("记录文件不存在: " + recordFile);
        }

        String server = immich.resolveServer();
        LOG.info("verify 启动: record={} server={} concurrency={} batchSize={}",
                recordFile, server, concurrency, batchSize);
        ImmichClient client = immich.buildClient();
        try {
            String serverVersion = "未知";
            var version = client.serverVersion();
            if (version != null) {
                serverVersion = version.display();
            }

            try (Progress progress = new Progress(System.out, true,
                    System.console() != null || "1".equals(System.getenv("DSH_SHELL")));
                 RecordStore store = RecordStore.open(recordFile, server)) {
                List<RecordEntry> entries = store.latestForServerList();
                VerificationService service = new VerificationService(
                        client, concurrency, batchSize, progress, verifyLinksEnabled());
                VerificationSummary summary = service.verify(entries);
                summary.finishedAt = System.currentTimeMillis();
                LOG.info("核对阶段完成: 总数={} 匹配={} 缺失={} 关联缺失={} 关联不一致={}",
                        summary.total, summary.matched, summary.missing,
                        summary.linkMissing, summary.linkMismatch);

                ReportContext context = new ReportContext(
                        "1.0.0",
                        server,
                        serverVersion,
                        List.of(),
                        Set.of(),
                        recordFile,
                        "-",
                        false,
                        false,
                        false,
                        LivePhotoMode.AUTO,
                        false,
                        verifyLinksEnabled()
                );
                Path report = HtmlReportWriter.write(state.reportDir(), context, null, summary, true);
                LOG.info("HTML 报告已生成: {}", report);

                System.out.println();
                System.out.println("核对完成: 总数=" + summary.total
                        + " 匹配=" + summary.matched
                        + " 回收站=" + summary.trashed
                        + " 缺失=" + summary.missing
                        + " 本地变化=" + (summary.localChangedServerOld
                        + summary.localChangedNewExists
                        + summary.localChangedMissing)
                        + " 关联缺失=" + summary.linkMissing
                        + " 关联不一致=" + summary.linkMismatch
                        + " 文件丢失=" + summary.fileMissing
                        + " 哈希错误=" + summary.hashErrors
                        + " API错误=" + summary.apiErrors);
                System.out.println("HTML 报告: " + report);
                int exitCode = summary.exitCode(strict);
                LOG.info("verify 完成: exitCode={}", exitCode);
                return exitCode;
            }
        } finally {
            client.close();
        }
    }
}
