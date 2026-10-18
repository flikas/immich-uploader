package io.github.immichuploader.report;

import io.github.immichuploader.core.LivePhotoMode;
import io.github.immichuploader.core.MediaKind;
import io.github.immichuploader.core.MediaRole;
import io.github.immichuploader.core.RunSummary;
import io.github.immichuploader.core.SkippedFile;
import io.github.immichuploader.core.VerificationSummary;
import io.github.immichuploader.state.RecordEntry;
import io.github.immichuploader.state.RecordStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlReportWriterTest {
    @TempDir
    Path temp;

    @Test
    void writesFilterableReportAndEscapesContent() throws Exception {
        RunSummary summary = new RunSummary();
        summary.recordScan(3, 2, 1, 1, 0, 1);
        summary.recordUploaded(0);
        summary.recordPrecheckDuplicates(2);
        summary.recordUploadDuplicate();
        summary.recordUploadDuplicate();
        summary.recordUploadDuplicate();
        summary.recordPairLinked();
        summary.finishedAt = System.currentTimeMillis();

        Path suspicious = Path.of("C:\\photos\\a&b'c.jpg");
        RecordEntry entry = new RecordEntry(
                System.currentTimeMillis(),
                RecordStatus.UPLOADED,
                MediaKind.IMAGE,
                MediaRole.LIVE_IMAGE,
                "pair-1",
                suspicious,
                123,
                100,
                "qZk+NkcGgWq6PiVxeFDCbJzQ2J0=",
                "asset-1",
                "video-1",
                "http://immich/api",
                "live-photo-image",
                "<script>alert(1)</script>"
        );
        summary.addSuccess(entry);
        summary.addSkip(new SkippedFile(
                Path.of("C:\\photos\\notes.txt"),
                MediaKind.OTHER,
                "SCAN",
                "扩展名不在允许清单: .txt"
        ));

        VerificationSummary verification = new VerificationSummary();
        verification.add(new VerificationSummary.Item(
                entry, VerificationSummary.Status.MATCHED, entry.sha1Base64(), "asset-1", ""));
        verification.add(new VerificationSummary.Item(
                entry, VerificationSummary.Status.LINK_MISSING, entry.sha1Base64(), "asset-1", "未关联"));
        verification.finishedAt = System.currentTimeMillis();

        ReportContext context = new ReportContext(
                "1.0.0",
                "http://immich/api",
                "1.136.0",
                List.of(temp),
                Set.of("mp4", "jpg", "heic"),
                temp.resolve("record.tsv"),
                "device-1",
                true,
                false,
                true,
                LivePhotoMode.AUTO,
                true,
                true
        );
        Path report = HtmlReportWriter.write(temp.resolve("reports"), context, summary, verification, false);
        String html = Files.readString(report);

        assertTrue(html.contains("data-tab=\"success\""));
        assertTrue(html.contains("data-tab=\"skipped\""));
        assertTrue(html.contains("<div class=\"k\">遍历文件</div><div class=\"v \">3</div>"));
        assertTrue(html.contains("<div class=\"k\">扫描媒体</div><div class=\"v \">2</div>"));
        assertTrue(html.contains("<div class=\"k\">预检已存在</div><div class=\"v \">2</div>"));
        assertTrue(html.contains("<div class=\"k\">服务端已存在</div><div class=\"v ok\">5</div>"));
        assertTrue(html.contains("<div class=\"k\">失败</div><div class=\"v \">0</div>"));
        assertTrue(html.contains("data-filter-text"));
        assertTrue(html.contains("Live Photo"));
        assertTrue(html.contains("关联缺失"));
        assertTrue(html.contains("跳过清单"));
        assertTrue(html.contains("<th>扩展名</th>"));
        assertTrue(html.contains("<td>.txt</td>"));
        assertTrue(html.contains("扩展名不在允许清单: .txt"));
        assertTrue(html.contains("heic, jpg, mp4"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("a&amp;b&#39;c.jpg"));
        assertFalse(html.contains("<script>alert(1)</script>"));
        assertTrue(html.contains("显示"));
    }
}
