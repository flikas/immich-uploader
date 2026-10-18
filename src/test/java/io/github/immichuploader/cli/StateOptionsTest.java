package io.github.immichuploader.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StateOptionsTest {
    @Test
    void derivesRecordAndReportFromDefaultStateDir() {
        StateOptions options = new StateOptions();
        new CommandLine(options).parseArgs();

        Path stateDir = options.stateDir();
        assertEquals(stateDir.resolve("upload-record.tsv"), options.recordFile());
        assertEquals(stateDir.resolve("reports"), options.reportDir());
    }

    @Test
    void derivesRecordAndReportFromCustomStateDir() {
        StateOptions options = new StateOptions();
        new CommandLine(options).parseArgs("--state-dir", "custom-state");

        Path stateDir = options.stateDir();
        assertEquals(stateDir.resolve("upload-record.tsv"), options.recordFile());
        assertEquals(stateDir.resolve("reports"), options.reportDir());
    }

    @Test
    void explicitRecordAndReportOverrideDefaults() {
        StateOptions options = new StateOptions();
        new CommandLine(options).parseArgs(
                "--state-dir", "custom-state",
                "--record", "custom-record.tsv",
                "--report-dir", "custom-reports");

        assertEquals(Path.of("custom-record.tsv").toAbsolutePath().normalize(), options.recordFile());
        assertEquals(Path.of("custom-reports").toAbsolutePath().normalize(), options.reportDir());
    }
}
