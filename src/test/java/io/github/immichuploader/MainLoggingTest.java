package io.github.immichuploader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainLoggingTest {
    @TempDir
    Path temp;

    @Test
    void writesNormalStartupLogToConfiguredFile() throws Exception {
        Path logDir = temp.resolve("logs");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(
                java,
                "-Dlog.dir=" + logDir,
                "-cp", System.getProperty("java.class.path"),
                "io.github.immichuploader.Main",
                "--help")
                .redirectErrorStream(true)
                .start();

        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "child process timed out");
        assertEquals(0, process.exitValue());

        Path logFile = logDir.resolve("immich-uploader.log");
        assertTrue(Files.exists(logFile), "log file was not created: " + logFile);
        String log = Files.readString(logFile, StandardCharsets.UTF_8);
        assertTrue(log.contains("命令启动:"), log);
        assertTrue(log.contains("命令完成:"), log);
    }
}
