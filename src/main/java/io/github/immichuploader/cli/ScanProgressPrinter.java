package io.github.immichuploader.cli;

import io.github.immichuploader.core.ScanProgress;

import java.io.PrintStream;
import java.util.function.Consumer;

/**
 * Throttled scan progress output. Interactive consoles get a single updating
 * line; redirected output gets periodic complete lines suitable for CI logs.
 */
final class ScanProgressPrinter implements Consumer<ScanProgress>, AutoCloseable {
    private static final long INTERACTIVE_INTERVAL_NANOS = 200_000_000L;
    private static final long REDIRECTED_INTERVAL_NANOS = 1_000_000_000L;
    private static final int INTERACTIVE_STEP = 200;
    private static final int REDIRECTED_STEP = 500;

    private final PrintStream out;
    private final boolean interactive;
    private final long intervalNanos;
    private final int step;

    private long lastRenderNanos;
    private int lastVisited = -1;
    private int lastLineLength;
    private ScanProgress lastProgress;

    ScanProgressPrinter(PrintStream out) {
        this.out = out;
        this.interactive = System.console() != null;
        this.intervalNanos = interactive ? INTERACTIVE_INTERVAL_NANOS : REDIRECTED_INTERVAL_NANOS;
        this.step = interactive ? INTERACTIVE_STEP : REDIRECTED_STEP;
        out.println("[扫描] 开始遍历...");
        out.flush();
    }

    @Override
    public void accept(ScanProgress progress) {
        lastProgress = progress;
        long now = System.nanoTime();
        int delta = progress.visited() - Math.max(0, lastVisited);
        if (lastVisited >= 0 && delta < step && now - lastRenderNanos < intervalNanos) {
            return;
        }
        if (progress.visited() == lastVisited) {
            return;
        }
        lastVisited = progress.visited();
        lastRenderNanos = now;
        print(progress);
    }

    private void print(ScanProgress progress) {
        String line = "[扫描] 已访问=" + progress.visited()
                + " 媒体=" + progress.media()
                + " 跳过=" + progress.skipped()
                + " 问题=" + progress.issues();
        synchronized (out) {
            if (interactive) {
                clearLine();
                out.print(line);
                out.flush();
                lastLineLength = line.length();
            } else {
                out.println(line);
                out.flush();
            }
        }
    }

    private void clearLine() {
        if (!interactive || lastLineLength <= 0) {
            return;
        }
        out.print('\r');
        out.print(" ".repeat(lastLineLength));
        out.print('\r');
        lastLineLength = 0;
    }

    @Override
    public void close() {
        if (lastProgress != null && lastProgress.visited() != lastVisited) {
            print(lastProgress);
        }
        if (interactive && lastLineLength > 0) {
            synchronized (out) {
                out.println();
                out.flush();
                lastLineLength = 0;
            }
        }
    }
}
