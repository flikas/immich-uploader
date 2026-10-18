package io.github.immichuploader.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressStatsTest {
    @Test
    void rendersCountsFromCurrentStageOnly() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Progress progress = new Progress(new PrintStream(output, true), true)) {
            progress.phase("上传", 10);
            progress.uploaded(5);
            progress.uploaded(7);
            progress.duplicate();
            progress.failed();
            progress.stage();
        }

        String text = output.toString();
        assertTrue(text.contains("上传=2"), text);
        assertTrue(text.contains("重复=1"), text);
        assertTrue(text.contains("失败=1"), text);
    }

    @Test
    void unknownTotalDoesNotRenderZeroPercent() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Progress progress = new Progress(new PrintStream(output, true), true)) {
            progress.phaseUnknown("上传");
            progress.uploaded(5);
            progress.stage();
        }

        String text = output.toString();
        assertTrue(text.contains("已处理=1"), text);
        assertTrue(!text.contains("0/0"), text);
        assertTrue(!text.contains("100%"), text);
    }

    @Test
    void rendersFinalStateBeforeStartingNextPhase() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Progress progress = new Progress(new PrintStream(output, true), true)) {
            progress.phase("核对哈希", 2);
            progress.advance();
            progress.advance();
            progress.phase("服务端比对", 2);
        }

        String text = output.toString();
        assertTrue(text.contains("[核对哈希] 2/2 (100%)"), text);
    }

    @Test
    void showsPrecheckDuplicatesAndCountsThemAsProcessed() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Progress progress = new Progress(new PrintStream(output, true), true)) {
            progress.phaseUnknown("上传");
            progress.precheckDuplicates(3);
            progress.stage();
        }

        String text = output.toString();
        assertTrue(text.contains("已处理=3"), text);
        assertTrue(text.contains("重复=3"), text);
    }

    @Test
    void rendersReaderAndSenderActivityWithRateAndPercent() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Progress progress = new Progress(new PrintStream(output, true), true)) {
            progress.configureThreads(1, 1);
            progress.phaseUnknown("上传");
            Thread reader = new Thread(() -> {
                progress.readerStarted(Path.of("photo.jpg"), 1000);
                progress.readerProgress(500);
                progress.stage();
                progress.readerProgress(1000);
                progress.readerFinished();
            }, "reader-1");
            reader.start();
            reader.join();
            Thread sender = new Thread(() -> {
                progress.senderStarted(Path.of("photo.jpg"), 1000);
                progress.senderProgress(500);
                progress.stage();
                progress.senderProgress(1000);
                progress.senderFinished();
            }, "sender-1");
            sender.start();
            sender.join();
        }

        String text = output.toString();
        assertTrue(text.contains("[reader-1]"), text);
        assertTrue(text.contains("[sender-1]"), text);
        assertTrue(text.contains("photo.jpg"), text);
        assertTrue(text.contains("50%"), text);
        assertTrue(text.contains("读="), text);
        assertTrue(text.contains("传="), text);
    }

    @Test
    void rendersIdleReaderAndSenderSlots() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Progress progress = new Progress(new PrintStream(output, true), true)) {
            progress.configureThreads(2, 3);
            progress.phaseUnknown("上传");
            progress.stage();
        }

        String text = output.toString();
        assertTrue(text.contains("[reader-1] 空闲"), text);
        assertTrue(text.contains("[reader-2] 空闲"), text);
        assertTrue(text.contains("[sender-1] 空闲"), text);
        assertTrue(text.contains("[sender-2] 空闲"), text);
        assertTrue(text.contains("[sender-3] 空闲"), text);
    }

    @Test
    void interactiveModeRedrawsActivityBlock() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Progress progress = new Progress(new PrintStream(output, true), true, true);
        progress.configureThreads(1, 1);
        progress.phaseUnknown("上传");
        progress.readerStarted(Path.of("reader.jpg"), 1000);
        progress.senderStarted(Path.of("sender.jpg"), 2000);
        progress.stage();
        progress.readerFinished();
        progress.senderFinished();
        progress.readerProgress(1);
        progress.senderProgress(1);
        progress.close();

        String text = output.toString();
        assertTrue(text.contains("[reader-1] 空闲"), text);
        assertTrue(text.contains("[sender-1] 空闲"), text);
        assertTrue(text.contains("reader.jpg"), text);
        assertTrue(text.contains("sender.jpg"), text);
        assertTrue(text.contains("\u001b["), text);
        assertTrue(text.contains("\u001b[2K"), text);
    }

    @Test
    void interactiveOutputRedrawsInsteadOfAppendingLines() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Progress progress = new Progress(new PrintStream(output, true), true, true);
        progress.phaseUnknown("上传");
        progress.uploaded(1);
        progress.stage();
        progress.close();

        String text = output.toString();
        assertTrue(text.contains("\r"), text);
        assertEquals(1, text.chars().filter(ch -> ch == '\n').count(), text);
    }

    @Test
    void linkFailuresAreCountedSeparatelyFromProcessedFiles() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Progress progress = new Progress(new PrintStream(output, true), true)) {
            progress.phaseUnknown("上传");
            progress.uploaded(1);
            progress.duplicate();
            progress.linkFailed();
            progress.stage();
        }

        String text = output.toString();
        assertTrue(text.contains("已处理=2"), text);
        assertTrue(text.contains("补链失败=1"), text);
    }

    @Test
    void livePhotoHashingCountsBothAssetParts() {
        Progress progress = new Progress(new PrintStream(new ByteArrayOutputStream()), false);
        progress.phaseUnknown("上传");
        progress.hashed(2, 100);
        assertEquals(2, progress.stage().hashed());
        assertEquals(100, progress.stage().bytesHashed());
        progress.close();
    }

    @Test
    void processedCountIsTheSumOfVisibleOutcomes() {
        Progress progress = new Progress(new PrintStream(new ByteArrayOutputStream()), false);
        progress.phaseUnknown("上传");
        progress.uploaded(10);
        progress.duplicate();
        progress.failed();
        progress.skipped();
        progress.precheckDuplicates(2);
        progress.resumeSkipped(1);

        assertEquals(7, progress.processedCount());
        assertEquals(3, progress.duplicateCount());
        progress.close();
    }

    @Test
    void summaryIsUpdatedOnlyWhenStageIsApplied() {
        RunSummary summary = new RunSummary();
        Progress progress = new Progress(new PrintStream(new ByteArrayOutputStream()), false);
        progress.phase("上传", 2);
        progress.uploaded(5);
        progress.uploaded(7);
        progress.duplicate();
        progress.failed();

        assertEquals(0, summary.uploaded());
        assertEquals(0, summary.bytesUploaded());
        assertEquals(0, summary.duplicates());

        summary.applyStage(progress.stage());

        assertEquals(2, summary.uploaded());
        assertEquals(12, summary.bytesUploaded());
        assertEquals(1, summary.duplicates());
    }
}
