package io.github.immichuploader.core;

import io.github.immichuploader.state.RecordEntry;
import io.github.immichuploader.state.RecordStatus;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsCoverageTest {
    @Test
    void runStatsCoversEveryCounterAndSnapshot() {
        RunStats stats = new RunStats();
        stats.recordScan(11, 10, 6, 4, 2, 3);
        stats.recordSkip();
        stats.recordResumeSkip(2);
        stats.recordStale();
        stats.recordHashed(100);
        stats.recordHashed(2, 50);
        stats.recordPrecheckDuplicates(3);
        stats.recordPrecheckErrors(4);
        stats.recordUploaded(10);
        stats.recordUploaded(2, 20);
        stats.recordUploadDuplicate();
        stats.recordUploadDuplicates(2);
        stats.recordFailure();
        stats.recordPairSkipped();
        stats.recordPairSkipped(2);
        stats.recordPairLinked();
        stats.recordPairLinked(2);
        stats.recordPairAlreadyLinked();
        stats.recordPairAlreadyLinked(2);
        stats.recordPairConflict();
        stats.recordPairConflict(2);

        assertEquals(11, stats.visitedFiles());
        assertEquals(10, stats.scanned());
        assertEquals(6, stats.imageCount());
        assertEquals(4, stats.videoCount());
        assertEquals(2, stats.scanIssues());
        assertEquals(2, stats.resumeSkipped());
        assertEquals(1, stats.skipped());
        assertEquals(1, stats.stale());
        assertEquals(3, stats.hashed());
        assertEquals(150, stats.bytesHashed());
        assertEquals(3, stats.precheckDuplicates());
        assertEquals(4, stats.precheckErrors());
        assertEquals(3, stats.uploaded());
        assertEquals(30, stats.bytesUploaded());
        assertEquals(3, stats.serverDuplicates());
        assertEquals(6, stats.duplicates());
        assertEquals(1, stats.failed());
        assertEquals(3, stats.pairsDetected());
        assertEquals(3, stats.pairsSkipped());
        assertEquals(3, stats.pairsLinked());
        assertEquals(3, stats.pairsAlreadyLinked());
        assertEquals(3, stats.pairsConflict());

        RunStats.Snapshot snapshot = stats.snapshot();
        assertEquals(11, snapshot.visitedFiles());
        assertEquals(10, snapshot.scanned());
        assertEquals(6, snapshot.imageCount());
        assertEquals(4, snapshot.videoCount());
        assertEquals(2, snapshot.scanIssues());
        assertEquals(2, snapshot.resumeSkipped());
        assertEquals(1, snapshot.skipped());
        assertEquals(1, snapshot.stale());
        assertEquals(3, snapshot.hashed());
        assertEquals(150, snapshot.bytesHashed());
        assertEquals(3, snapshot.precheckDuplicates());
        assertEquals(4, snapshot.precheckErrors());
        assertEquals(3, snapshot.uploaded());
        assertEquals(30, snapshot.bytesUploaded());
        assertEquals(3, snapshot.serverDuplicates());
        assertEquals(6, snapshot.duplicates());
        assertEquals(1, snapshot.failed());
        assertEquals(3, snapshot.pairsDetected());
        assertEquals(3, snapshot.pairsSkipped());
        assertEquals(3, snapshot.pairsLinked());
        assertEquals(3, snapshot.pairsAlreadyLinked());
        assertEquals(3, snapshot.pairsConflict());
    }

    @Test
    void progressConsoleDetectionCoversAnsiAndRefreshBranches() {
        assertTrue(Progress.ansiSupported("1", null, null, null));
        assertTrue(Progress.ansiSupported(null, "wt", null, null));
        assertTrue(Progress.ansiSupported(null, null, "ansicon", null));
        assertTrue(Progress.ansiSupported(null, null, null, "xterm-256color"));
        assertFalse(Progress.ansiSupported(null, null, null, "dumb"));
        assertFalse(Progress.ansiSupported(null, null, null, null));
    }

    @Test
    void runSummaryCoversCollectionsAndLifecycle() {
        RunSummary summary = new RunSummary();
        summary.addFailure(Path.of("a.jpg"), "UPLOAD", "bad");
        summary.addSkip(new SkippedFile(Path.of("b.jpg"), MediaKind.IMAGE, "SCAN", "skip"));
        summary.addSkip(null);
        summary.addWarning("warning");
        summary.addWarning(" ");
        summary.addWarning(null);
        summary.addSuccess(null);
        summary.addSuccess(record("c.jpg"));
        summary.markSuccessful(null);
        summary.markSuccessful(Path.of("d.jpg"));
        summary.fail("fatal");

        assertEquals(1, summary.failed());
        assertEquals(1, summary.skipped());
        assertEquals(1, summary.failures().size());
        assertEquals(1, summary.skippedFiles().size());
        assertEquals(1, summary.warnings().size());
        assertEquals(1, summary.successes().size());
        assertEquals(2, summary.successfulPaths().size());
        assertTrue(summary.fatal);
        assertEquals("fatal", summary.fatalMessage);
        assertEquals(1, summary.exitCode());
        assertFalse(summary.complete());
        assertTrue(summary.durationMillis() >= 0);

        summary.finishedAt = summary.startedAt + 10;
        assertEquals(10, summary.durationMillis());

        RunSummary clean = new RunSummary();
        assertTrue(clean.complete());
        assertEquals(0, clean.exitCode());
        clean.applyStage(null);
        clean.finishedAt = 1;
        assertTrue(clean.durationMillis() >= 0);
        clean.verification = new VerificationSummary();
        assertTrue(clean.complete());
    }

    @Test
    void runSummaryCompleteCoversAllConditionsAndExitCodes() {
        RunSummary clean = new RunSummary();
        assertTrue(clean.complete());
        assertEquals(0, clean.exitCode());

        clean.recordFailure();
        assertFalse(clean.complete());
        assertEquals(2, clean.exitCode());

        RunSummary scanIssue = new RunSummary();
        scanIssue.recordScan(1, 1, 1, 0, 1, 0);
        assertFalse(scanIssue.complete());

        RunSummary conflict = new RunSummary();
        conflict.recordPairConflict();
        assertFalse(conflict.complete());

        RunSummary badVerification = new RunSummary();
        badVerification.verification = new VerificationSummary();
        badVerification.verification.add(new VerificationSummary.Item(
                record("bad.jpg"), VerificationSummary.Status.MISSING, "", "", ""));
        assertFalse(badVerification.complete());

        badVerification.fail("fatal");
        assertEquals(1, badVerification.exitCode());
    }

    @Test
    void nonFatalLinkFailureIsCountedButDoesNotFailExitCode() {
        RunSummary summary = new RunSummary();
        summary.addNonFatalFailure(Path.of("linked.jpg"), "LIVE_PHOTO_LINK", "missing asset.update");
        assertEquals(1, summary.failed());
        assertEquals(1, summary.nonFatalFailures());
        assertEquals(0, summary.fatalFailures());
        assertEquals(1, summary.failures().size());
        assertTrue(summary.complete());
        assertEquals(0, summary.exitCode());

        VerificationSummary linkMissing = new VerificationSummary();
        linkMissing.add(new VerificationSummary.Item(
                record("linked.jpg"), VerificationSummary.Status.LINK_MISSING, "", "", ""));
        summary.verification = linkMissing;
        assertFalse(linkMissing.ok(false));
        assertTrue(linkMissing.okIgnoringLinkMissing(false));
        assertTrue(linkMissing.okIgnoringLinkMissing(true));
        assertTrue(summary.complete());

        summary.verification.add(new VerificationSummary.Item(
                record("other.jpg"), VerificationSummary.Status.MISSING, "", "", ""));
        assertFalse(summary.complete());
    }

    @Test
    void verificationOkCoversEveryBaselineAndStrictCondition() {
        for (VerificationSummary.Status status : new VerificationSummary.Status[]{
                VerificationSummary.Status.MISSING,
                VerificationSummary.Status.LOCAL_CHANGED_MISSING,
                VerificationSummary.Status.LINK_MISSING,
                VerificationSummary.Status.LINK_MISMATCH,
                VerificationSummary.Status.VIDEO_MISSING,
                VerificationSummary.Status.FILE_MISSING,
                VerificationSummary.Status.HASH_ERROR,
                VerificationSummary.Status.API_ERROR}) {
            VerificationSummary summary = new VerificationSummary();
            summary.add(new VerificationSummary.Item(record("x.jpg"), status, "", "", ""));
            assertFalse(summary.ok(false), status.name());
            assertFalse(summary.ok(true), status.name());
        }

        for (VerificationSummary.Status status : new VerificationSummary.Status[]{
                VerificationSummary.Status.MATCHED_DIFFERENT_ASSET,
                VerificationSummary.Status.TRASHED,
                VerificationSummary.Status.LOCAL_CHANGED_SERVER_OLD,
                VerificationSummary.Status.LOCAL_CHANGED_NEW_EXISTS}) {
            VerificationSummary summary = new VerificationSummary();
            summary.add(new VerificationSummary.Item(record("x.jpg"), status, "", "", ""));
            assertTrue(summary.ok(false), status.name());
            assertFalse(summary.ok(true), status.name());
        }
    }

    @Test
    void verificationSummaryCoversAllStatusesAndModes() {
        VerificationSummary summary = new VerificationSummary();
        for (VerificationSummary.Status status : VerificationSummary.Status.values()) {
            summary.add(new VerificationSummary.Item(record("x.jpg"), status, null, null, null));
        }
        assertEquals(VerificationSummary.Status.values().length, summary.total);
        assertEquals(1, summary.matched);
        assertEquals(1, summary.differentAsset);
        assertEquals(1, summary.trashed);
        assertEquals(1, summary.missing);
        assertEquals(1, summary.localChangedServerOld);
        assertEquals(1, summary.localChangedNewExists);
        assertEquals(1, summary.localChangedMissing);
        assertEquals(1, summary.linkMissing);
        assertEquals(1, summary.linkMismatch);
        assertEquals(1, summary.videoMissing);
        assertEquals(1, summary.fileMissing);
        assertEquals(1, summary.hashErrors);
        assertEquals(1, summary.apiErrors);
        assertEquals(summary.total, summary.items().size());
        assertFalse(summary.ok(false));
        assertFalse(summary.ok(true));
        assertEquals(2, summary.exitCode(false));
        assertTrue(summary.durationMillis() >= 0);
        summary.finishedAt = summary.startedAt + 5;
        assertEquals(5, summary.durationMillis());

        VerificationSummary clean = new VerificationSummary();
        clean.add(new VerificationSummary.Item(record("ok.jpg"), VerificationSummary.Status.MATCHED, "", "", ""));
        assertTrue(clean.ok(false));
        assertTrue(clean.ok(true));
        assertEquals(0, clean.exitCode(true));
        clean.finishedAt = 1;
        assertTrue(clean.durationMillis() >= 0);
    }

    @Test
    void progressCoversStagesRenderingAndLifecycle() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Progress progress = new Progress(new PrintStream(output, true), true);
        progress.phase("哈希", 2);
        progress.advance();
        progress.hashed(10);
        progress.precheckDuplicates(2);
        progress.precheckErrors(1);
        progress.resumeSkipped(1);
        progress.stale();
        progress.pairSkipped();
        progress.pairLinked();
        progress.pairAlreadyLinked();
        progress.pairConflict();
        progress.skipped();
        progress.failed();
        progress.stage();
        assertEquals(2, progress.skippedCount());
        assertEquals(2, progress.duplicateCount());

        progress.phase("上传", 2);
        progress.uploaded(10);
        progress.duplicate();
        progress.failed();
        progress.stage();
        assertEquals(1, progress.duplicateCount());
        invoke(progress, "render", new Class<?>[]{boolean.class}, false);
        progress.println("line");
        progress.println(null);
        assertNotNull(progress.toString());

        set(progress, "interactive", true);
        progress.stage();
        progress.stage();
        progress.println("interactive");
        invoke(progress, "clearInteractiveLine");

        invoke(progress, "tick");
        progress.close();
        invoke(progress, "tick");
        invoke(progress, "render", new Class<?>[]{boolean.class}, false);
        progress.close();

        Progress disabled = new Progress(new PrintStream(new ByteArrayOutputStream()), false);
        disabled.phase("上传", 0);
        disabled.println("ignored");
        invoke(disabled, "render", new Class<?>[]{boolean.class}, true);
        disabled.close();

        Progress unknown = new Progress(new PrintStream(new ByteArrayOutputStream()), true);
        unknown.phaseUnknown("上传");
        unknown.stage();
        invoke(unknown, "render", new Class<?>[]{boolean.class}, false);
        set(unknown, "interactive", true);
        unknown.close();
        set(unknown, "lastLineLength", 4);
        invoke(unknown, "clearInteractiveLine");
        unknown.close();
    }

    private static RecordEntry record(String name) {
        return new RecordEntry(1, RecordStatus.UPLOADED, MediaKind.IMAGE, MediaRole.NORMAL,
                "", Path.of(name), 1, 1, "sha", "asset", "", "server", "uploaded", "");
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(target, args);
    }

    private static void invoke(Object target, String name) throws Exception {
        invoke(target, name, new Class<?>[0]);
    }
}
