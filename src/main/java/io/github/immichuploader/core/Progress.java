package io.github.immichuploader.core;

import io.github.immichuploader.util.Formats;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small dependency-free progress renderer. It emits carriage-return updates on
 * an interactive console and periodic complete lines otherwise, so it also
 * behaves well in CI logs and IDE run windows.
 */
public final class Progress implements AutoCloseable {
    private final PrintStream out;
    private final boolean enabled;
    private final boolean interactive;
    private final boolean ansi;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong total = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong phaseFailed = new AtomicLong();
    private final AtomicLong stageLinkFailures = new AtomicLong();
    private final AtomicLong stageUploaded = new AtomicLong();
    private final AtomicLong stageDuplicates = new AtomicLong();
    private final AtomicLong stageSkipped = new AtomicLong();
    private final AtomicLong stageHashed = new AtomicLong();
    private final AtomicLong stageBytesHashed = new AtomicLong();
    private final AtomicLong stageBytesUploaded = new AtomicLong();
    private final AtomicLong stagePrecheckDuplicates = new AtomicLong();
    private final AtomicLong stagePrecheckErrors = new AtomicLong();
    private final AtomicLong stageResumeSkipped = new AtomicLong();
    private final AtomicLong stageStale = new AtomicLong();
    private final AtomicLong stagePairSkipped = new AtomicLong();
    private final AtomicLong stagePairLinked = new AtomicLong();
    private final AtomicLong stagePairAlreadyLinked = new AtomicLong();
    private final AtomicLong stagePairConflict = new AtomicLong();
    private final AtomicLong readBytes = new AtomicLong();
    private final AtomicLong transferBytes = new AtomicLong();
    private final ConcurrentHashMap<String, Activity> activities = new ConcurrentHashMap<>();
    private volatile List<ThreadSlot> threadSlots = List.of();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long startedNanos = System.nanoTime();

    private volatile boolean phaseActive;
    private volatile String phase = "准备";
    private volatile long phaseStartNanos = System.nanoTime();
    private volatile long lastRenderNanos;
    private volatile int lastLineLength;
    private int renderedLines;

    public Progress(PrintStream out, boolean enabled) {
        this(out, enabled, false);
    }

    public Progress(PrintStream out, boolean enabled, boolean interactive) {
        this.out = out;
        this.enabled = enabled;
        this.interactive = enabled && interactive;
        this.ansi = this.interactive && ansiSupported();
        if (enabled) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "immich-uploader-progress");
                thread.setDaemon(true);
                return thread;
            });
            this.scheduler.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
        } else {
            this.scheduler = null;
        }
    }

    public static Progress silent() {
        return new Progress(System.out, false);
    }

    private static boolean ansiSupported() {
        return ansiSupported(System.getenv("DSH_SHELL"), System.getenv("WT_SESSION"),
                System.getenv("ANSICON"), System.getenv("TERM"));
    }

    static boolean ansiSupported(String dsh, String wtSession, String ansicon, String term) {
        return "1".equals(dsh)
                || wtSession != null
                || ansicon != null
                || (term != null && !term.isBlank() && !"dumb".equalsIgnoreCase(term));
    }

    public void phase(String name, long phaseTotal) {
        beginPhase(name, Math.max(0, phaseTotal));
    }

    public void phaseUnknown(String name) {
        beginPhase(name, -1);
    }

    private void beginPhase(String name, long phaseTotal) {
        if (phaseActive) {
            render(true);
        }
        this.phase = name;
        this.total.set(phaseTotal);
        this.completed.set(0);
        this.bytes.set(0);
        this.readBytes.set(0);
        this.transferBytes.set(0);
        this.activities.clear();
        if (!"上传".equals(name)) {
            this.threadSlots = List.of();
        }
        this.phaseFailed.set(0);
        this.stageLinkFailures.set(0);
        this.stageUploaded.set(0);
        this.stageDuplicates.set(0);
        this.stageSkipped.set(0);
        this.stageHashed.set(0);
        this.stageBytesHashed.set(0);
        this.stageBytesUploaded.set(0);
        this.stagePrecheckDuplicates.set(0);
        this.stagePrecheckErrors.set(0);
        this.stageResumeSkipped.set(0);
        this.stageStale.set(0);
        this.stagePairSkipped.set(0);
        this.stagePairLinked.set(0);
        this.stagePairAlreadyLinked.set(0);
        this.stagePairConflict.set(0);
        this.phaseStartNanos = System.nanoTime();
        this.lastRenderNanos = 0;
        this.phaseActive = true;
        render(true);
    }

    public void advance() {
        completed.incrementAndGet();
    }

    public void configureThreads(int readers, int senders) {
        List<ThreadSlot> slots = new ArrayList<>();
        for (int i = 1; i <= Math.max(0, readers); i++) {
            slots.add(new ThreadSlot("读", "reader-" + i));
        }
        for (int i = 1; i <= Math.max(0, senders); i++) {
            slots.add(new ThreadSlot("传", "sender-" + i));
        }
        this.threadSlots = List.copyOf(slots);
    }

    public void readerStarted(Path path, long size) {
        startActivity("读", path, size);
    }

    public void readerProgress(long bytesRead) {
        progressActivity("读", bytesRead, readBytes);
    }

    public void readerFinished() {
        finishActivity("读");
    }

    public void senderStarted(Path path, long size) {
        startActivity("传", path, size);
    }

    public void senderProgress(long bytesWritten) {
        progressActivity("传", bytesWritten, transferBytes);
    }

    public void senderFinished() {
        finishActivity("传");
    }

    public void uploaded(long byteCount) {
        completed.incrementAndGet();
        bytes.addAndGet(Math.max(0, byteCount));
        stageUploaded.incrementAndGet();
        stageBytesUploaded.addAndGet(Math.max(0, byteCount));
    }

    public void duplicate() {
        completed.incrementAndGet();
        stageDuplicates.incrementAndGet();
    }

    public void failed() {
        failed(1);
    }

    public void failed(long count) {
        long value = Math.max(0, count);
        completed.addAndGet(value);
        phaseFailed.addAndGet(value);
    }

    public void skipped() {
        stageSkipped.incrementAndGet();
        completed.incrementAndGet();
    }

    public void linkFailed() {
        stageLinkFailures.incrementAndGet();
    }

    public void hashed(long byteCount) {
        hashed(1, byteCount);
    }

    public void hashed(long count, long byteCount) {
        stageHashed.addAndGet(Math.max(0, count));
        stageBytesHashed.addAndGet(Math.max(0, byteCount));
    }

    public void precheckDuplicates(long count) {
        long value = Math.max(0, count);
        stagePrecheckDuplicates.addAndGet(value);
        completed.addAndGet(value);
    }

    public void precheckErrors(long count) {
        stagePrecheckErrors.addAndGet(Math.max(0, count));
    }

    public void resumeSkipped(long count) {
        long value = Math.max(0, count);
        stageResumeSkipped.addAndGet(value);
        completed.addAndGet(value);
    }

    public void stale() {
        stageStale.incrementAndGet();
    }

    public void pairSkipped() {
        stagePairSkipped.incrementAndGet();
    }

    public void pairLinked() {
        stagePairLinked.incrementAndGet();
    }

    public void pairAlreadyLinked() {
        stagePairAlreadyLinked.incrementAndGet();
    }

    public void pairConflict() {
        stagePairConflict.incrementAndGet();
    }

    public StageSnapshot stage() {
        render(true);
        return new StageSnapshot(
                stageUploaded.get(),
                stageDuplicates.get(),
                stageSkipped.get(),
                phaseFailed.get(),
                stageHashed.get(),
                stageBytesHashed.get(),
                stageBytesUploaded.get(),
                stagePrecheckDuplicates.get(),
                stagePrecheckErrors.get(),
                stageResumeSkipped.get(),
                stageStale.get(),
                stagePairSkipped.get(),
                stagePairLinked.get(),
                stagePairAlreadyLinked.get(),
                stagePairConflict.get());
    }

    public long skippedCount() {
        return stageSkipped.get() + stageResumeSkipped.get();
    }

    public long duplicateCount() {
        return stageDuplicates.get() + stagePrecheckDuplicates.get();
    }

    public long processedCount() {
        return stageUploaded.get() + duplicateCount() + phaseFailed.get()
                + stageSkipped.get() + stageResumeSkipped.get();
    }

    private boolean unknownProcessed() {
        return total.get() < 0;
    }

    public void println(String message) {
        if (!enabled || message == null) {
            return;
        }
        synchronized (out) {
            if (ansi) {
                clearRenderedBlock();
            } else {
                clearInteractiveLine();
            }
            out.println(message);
            out.flush();
        }
    }

    private void startActivity(String role, Path path, long size) {
        String key = activityKey(role);
        activities.put(key, new Activity(role, Thread.currentThread().getName(),
                path, Math.max(0, size), System.nanoTime()));
    }

    private void progressActivity(String role, long value, AtomicLong totalBytes) {
        Activity activity = activities.get(activityKey(role));
        if (activity == null) {
            return;
        }
        long previous = activity.done.getAndUpdate(old -> Math.max(old, Math.max(0, value)));
        long delta = Math.max(0, Math.max(0, value) - previous);
        if (delta > 0) {
            totalBytes.addAndGet(delta);
        }
    }

    private void finishActivity(String role) {
        activities.remove(activityKey(role));
    }

    private static String activityKey(String role) {
        return role + ":" + Thread.currentThread().getName();
    }

    private String activityLines(long now) {
        if (activities.isEmpty() && threadSlots.isEmpty()) {
            return "";
        }
        StringBuilder lines = new StringBuilder(256);
        Set<String> rendered = new HashSet<>();
        for (ThreadSlot slot : threadSlots) {
            Activity activity = activities.get(slot.key());
            if (activity == null) {
                lines.append("  [").append(slot.name()).append("] 空闲")
                        .append(System.lineSeparator());
            } else {
                appendActivity(lines, activity, now);
                rendered.add(slot.key());
            }
        }
        for (Activity activity : activities.values()) {
            String key = activity.role + ":" + activity.thread;
            if (!rendered.contains(key)) {
                appendActivity(lines, activity, now);
            }
        }
        return lines.toString();
    }

    private static void appendActivity(StringBuilder lines, Activity activity, long now) {
        long done = Math.min(activity.size, activity.done.get());
        double elapsed = Math.max(0.001, (now - activity.startNanos) / 1_000_000_000.0);
        lines.append("  [").append(activity.thread).append("] ")
                .append(Formats.shortPath(activity.path))
                .append("  ").append(Formats.bytes(activity.size))
                .append("  ").append(Formats.rate(done / elapsed))
                .append("  ").append(Formats.percent(done, activity.size))
                .append(System.lineSeparator());
    }

    private void tick() {
        if (closed.get()) {
            return;
        }
        render(false);
    }

    private void render(boolean force) {
        if (!enabled || (closed.get() && !force)) {
            return;
        }
        long now = System.nanoTime();
        if (!force && !interactive && now - lastRenderNanos < TimeUnit.SECONDS.toNanos(2)) {
            return;
        }
        lastRenderNanos = now;

        long done = unknownProcessed() ? processedCount() : completed.get();
        long all = total.get();
        boolean unknownTotal = all < 0;
        long byteCount = bytes.get();
        long readByteCount = readBytes.get();
        long transferByteCount = transferBytes.get();
        double elapsedSeconds = Math.max(0.001, (now - phaseStartNanos) / 1_000_000_000.0);
        double rate = byteCount / elapsedSeconds;
        long remaining = unknownTotal ? 0 : Math.max(0, all - done);
        long etaMillis = done > 0 && remaining > 0
                ? (long) (elapsedSeconds * 1000.0 * remaining / done)
                : 0;

        StringBuilder line = new StringBuilder(128);
        line.append('[').append(phase).append("] ");
        if (unknownTotal) {
            line.append("已处理=").append(done);
        } else {
            line.append(done).append('/').append(all)
                    .append(" (").append(Formats.percent(done, all)).append(')');
        }

        if (readByteCount > 0) {
            line.append("  读=").append(Formats.rate(readByteCount / elapsedSeconds));
        }
        if (transferByteCount > 0) {
            line.append("  传=").append(Formats.rate(transferByteCount / elapsedSeconds));
        } else if (byteCount > 0) {
            line.append("  上传=").append(Formats.rate(rate));
        }
        if (!unknownTotal && remaining > 0 && done > 0) {
            line.append("  剩余 ").append(Formats.eta(etaMillis));
        } else if (!unknownTotal && all > 0 && done >= all) {
            line.append("  完成");
        }

        long uploadedCount = stageUploaded.get();
        long duplicateCount = stageDuplicates.get() + stagePrecheckDuplicates.get();
        long failedCount = phaseFailed.get();
        long linkFailedCount = stageLinkFailures.get();
        long skippedCount = stageSkipped.get() + stageResumeSkipped.get();

        if ("上传".equals(phase)) {
            line.append("  上传=").append(uploadedCount)
                    .append(" 重复=").append(duplicateCount)
                    .append(" 失败=").append(failedCount)
                    .append(" 补链失败=").append(linkFailedCount)
                    .append(" 跳过=").append(skippedCount);
        } else {
            if (failedCount > 0) {
                line.append("  失败=").append(failedCount);
            }
            if (skippedCount > 0) {
                line.append("  跳过=").append(skippedCount);
            }
        }

        String activityText = activityLines(now);
        synchronized (out) {
            if (interactive && ansi) {
                clearRenderedBlock();
                out.print(line);
                for (String activityLine : activityText.lines().toList()) {
                    out.print(System.lineSeparator());
                    out.print(activityLine);
                    renderedLines++;
                }
                renderedLines++;
                out.flush();
            } else if (interactive) {
                // A real console without ANSI support can only redraw one line.
                clearInteractiveLine();
                out.print(line);
                out.flush();
                lastLineLength = line.length();
            } else {
                out.println(line);
                if (!activityText.isEmpty()) {
                    out.print(activityText);
                }
                out.flush();
            }
        }
    }

    private void clearRenderedBlock() {
        if (!interactive || !ansi || renderedLines <= 0) {
            return;
        }
        out.print('\r');
        if (renderedLines > 1) {
            out.print("\u001b[" + (renderedLines - 1) + "A");
        }
        for (int i = 0; i < renderedLines; i++) {
            out.print("\u001b[2K");
            if (i < renderedLines - 1) {
                out.print('\n');
            }
        }
        if (renderedLines > 1) {
            out.print("\u001b[" + (renderedLines - 1) + "A");
        }
        out.print('\r');
        renderedLines = 0;
    }

    private void clearInteractiveLine() {
        if (!interactive || lastLineLength <= 0) {
            return;
        }
        out.print('\r');
        out.print(" ".repeat(lastLineLength));
        out.print('\r');
        lastLineLength = 0;
    }

    private record ThreadSlot(String role, String name) {
        private String key() {
            return role + ":" + name;
        }
    }

    private static final class Activity {
        private final String role;
        private final String thread;
        private final Path path;
        private final long size;
        private final long startNanos;
        private final AtomicLong done = new AtomicLong();

        private Activity(String role, String thread, Path path, long size, long startNanos) {
            this.role = role;
            this.thread = thread;
            this.path = path;
            this.size = size;
            this.startNanos = startNanos;
        }

    }

    public record StageSnapshot(
            long uploaded,
            long duplicates,
            long skipped,
            long failed,
            long hashed,
            long bytesHashed,
            long bytesUploaded,
            long precheckDuplicates,
            long precheckErrors,
            long resumeSkipped,
            long stale,
            long pairSkipped,
            long pairLinked,
            long pairAlreadyLinked,
            long pairConflict
    ) {
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (!enabled) {
            return;
        }
        render(true);
        activities.clear();
        synchronized (out) {
            if (interactive) {
                out.println();
            }
            out.flush();
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "Progress[%s %d/%d]", phase, completed.get(), total.get());
    }
}
