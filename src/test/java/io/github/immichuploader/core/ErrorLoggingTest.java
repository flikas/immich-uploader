package io.github.immichuploader.core;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorLoggingTest {
    @TempDir
    Path temp;

    @Test
    void logsScanFailures() {
        Logger logger = (Logger) LogManager.getLogger(MediaScanner.class);
        Level previous = logger.getLevel();
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        logger.setLevel(Level.ERROR);
        logger.addAppender(appender);
        try {
            MediaScanner.scan(
                    List.of(temp.resolve("missing")),
                    true, false, MediaExtensions.DEFAULT,
                    true, true, MediaFilter.ALL, LivePhotoMode.OFF);
            assertTrue(appender.events.stream()
                    .anyMatch(event -> event.getMessage().getFormattedMessage().contains("扫描失败")));
        } finally {
            logger.removeAppender(appender);
            appender.stop();
            logger.setLevel(previous);
        }
    }

    @Test
    void logsUploadFailures() {
        Logger logger = (Logger) LogManager.getLogger(RunSummary.class);
        Level previous = logger.getLevel();
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        logger.setLevel(Level.ERROR);
        logger.addAppender(appender);
        try {
            RunSummary summary = new RunSummary();
            summary.addFailure(temp.resolve("photo.jpg"), "UPLOAD", "boom");
            assertTrue(appender.events.stream()
                    .anyMatch(event -> event.getMessage().getFormattedMessage().contains("boom")));
        } finally {
            logger.removeAppender(appender);
            appender.stop();
            logger.setLevel(previous);
        }
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("capture", null, null, false);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
