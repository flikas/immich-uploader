package io.github.immichuploader.core;

import io.github.immichuploader.state.RecordEntry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class RunSummary extends RunStats {
    private static final Logger LOG = LogManager.getLogger(RunSummary.class);

    public final long startedAt = System.currentTimeMillis();
    public long finishedAt;
    public boolean fatal;
    public String fatalMessage;
    public VerificationSummary verification;

    private final List<Failure> failures = new CopyOnWriteArrayList<>();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final List<SkippedFile> skippedFiles = new CopyOnWriteArrayList<>();
    private final Set<Path> successfulPaths = ConcurrentHashMap.newKeySet();
    private final List<RecordEntry> successes = new CopyOnWriteArrayList<>();
    private final AtomicInteger nonFatalFailures = new AtomicInteger();

    public void addFailure(Path path, String stage, String message) {
        failures.add(new Failure(path, stage, message));
        recordFailure();
        LOG.error("上传失败 [{}] {}: {}", stage, path, message);
    }

    /**
     * A failure that belongs in the report and failure count but does not
     * change the process exit code, such as a Live Photo link failure.
     */
    public void addNonFatalFailure(Path path, String stage, String message) {
        failures.add(new Failure(path, stage, message));
        recordFailure();
        nonFatalFailures.incrementAndGet();
        LOG.warn("非致命失败 [{}] {}: {}", stage, path, message);
    }

    public int nonFatalFailures() {
        return nonFatalFailures.get();
    }

    public int fatalFailures() {
        return Math.max(0, failed() - nonFatalFailures());
    }

    public void addSkip(SkippedFile skippedFile) {
        if (skippedFile != null) {
            skippedFiles.add(skippedFile);
            recordSkip();
        }
    }

    public void addWarning(String message) {
        if (message != null && !message.isBlank()) {
            warnings.add(message);
        }
    }

    public void addSuccess(RecordEntry entry) {
        if (entry == null) {
            return;
        }
        successes.add(entry);
        successfulPaths.add(entry.path());
    }

    public void applyStage(Progress.StageSnapshot stage) {
        if (stage == null) {
            return;
        }
        recordUploaded((int) stage.uploaded(), stage.bytesUploaded());
        recordUploadDuplicates((int) stage.duplicates());
        recordHashed((int) stage.hashed(), stage.bytesHashed());
        recordPrecheckDuplicates((int) stage.precheckDuplicates());
        recordPrecheckErrors((int) stage.precheckErrors());
        recordResumeSkip((int) stage.resumeSkipped());
        recordStale((int) stage.stale());
        recordPairSkipped((int) stage.pairSkipped());
        recordPairLinked((int) stage.pairLinked());
        recordPairAlreadyLinked((int) stage.pairAlreadyLinked());
        recordPairConflict((int) stage.pairConflict());
    }

    public void markSuccessful(Path path) {
        if (path != null) {
            successfulPaths.add(path);
        }
    }

    public void fail(String message) {
        fatal = true;
        fatalMessage = message;
        LOG.error("致命错误: {}", message);
    }

    public List<Failure> failures() {
        return List.copyOf(failures);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public List<SkippedFile> skippedFiles() {
        return List.copyOf(skippedFiles);
    }

    public List<RecordEntry> successes() {
        return List.copyOf(successes);
    }

    public Set<Path> successfulPaths() {
        return Set.copyOf(successfulPaths);
    }

    public long durationMillis() {
        return Math.max(0, (finishedAt == 0 ? System.currentTimeMillis() : finishedAt) - startedAt);
    }

    public boolean complete() {
        return !fatal
                && fatalFailures() == 0
                && scanIssues() == 0
                && pairsConflict() == 0
                && (verification == null
                || verification.ok(false)
                || (nonFatalFailures() > 0 && verification.okIgnoringLinkMissing(false)));
    }

    public int exitCode() {
        if (fatal) {
            return 1;
        }
        return complete() ? 0 : 2;
    }

    public record Failure(Path path, String stage, String message) {
    }
}
