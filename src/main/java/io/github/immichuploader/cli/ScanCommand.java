package io.github.immichuploader.cli;

import io.github.immichuploader.core.MediaScanResult;
import io.github.immichuploader.core.MediaScanner;
import io.github.immichuploader.util.Formats;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(
        name = "scan",
        mixinStandardHelpOptions = true,
        description = "只扫描目录，不连接 Immich"
)
public final class ScanCommand implements Callable<Integer> {
    private static final Logger LOG = LogManager.getLogger(ScanCommand.class);

    @Mixin
    private ScanOptions scan;

    @Override
    public Integer call() {
        List<Path> directories = scan.resolveDirectories();
        Set<String> extensions = scan.extensionSet();
        LOG.info("scan 启动: dirs={}", directories);
        long started = System.currentTimeMillis();
        MediaScanResult result;
        try (ScanProgressPrinter scanProgress = new ScanProgressPrinter(System.out)) {
            result = MediaScanner.scan(
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

        System.out.println("扫描完成: 媒体=" + result.media().size()
                + " 图片=" + result.imageCount()
                + " 视频=" + result.videoCount()
                + " Live Photo对=" + result.pairs().size()
                + " 跳过=" + result.skipped().size()
                + " 问题=" + result.issues().size()
                + " 耗时=" + Formats.duration(System.currentTimeMillis() - started));
        LOG.info("scan 完成: 媒体={} 图片={} 视频={} 跳过={} 问题={} 耗时={}",
                result.media().size(), result.imageCount(), result.videoCount(),
                result.skipped().size(), result.issues().size(),
                Formats.duration(System.currentTimeMillis() - started));
        for (MediaScanResult.Issue issue : result.issues()) {
            System.out.println("[问题] " + issue.path() + ": " + issue.message());
        }
        for (String warning : result.warnings()) {
            System.out.println("[警告] " + warning);
        }
        for (var media : result.media()) {
            System.out.println("[" + media.kind().label() + "/" + media.role().label() + "] "
                    + media.path() + " (" + Formats.bytes(media.size()) + ")");
        }
        return result.issues().isEmpty() ? 0 : 2;
    }
}
