package io.github.immichuploader.cli;

import picocli.CommandLine.Option;

import java.nio.file.Path;

public final class StateOptions {
    private static final String DEFAULT_RECORD = "<state-dir>/upload-record.tsv";
    private static final String DEFAULT_REPORT_DIR = "<state-dir>/reports";

    @Option(names = "--state-dir", defaultValue = ".immich-uploader",
            description = "状态目录（默认 ${DEFAULT-VALUE}）")
    private Path stateDir;

    @Option(names = "--record", defaultValue = DEFAULT_RECORD,
            description = "上传记录文件（默认 ${DEFAULT-VALUE}）")
    private String record;

    @Option(names = "--report-dir", defaultValue = DEFAULT_REPORT_DIR,
            description = "HTML 报告目录（默认 ${DEFAULT-VALUE}）")
    private String reportDir;

    public Path stateDir() {
        return stateDir.toAbsolutePath().normalize();
    }

    public Path recordFile() {
        if (record == null || record.isBlank() || DEFAULT_RECORD.equals(record)) {
            return stateDir().resolve("upload-record.tsv");
        }
        return Path.of(record).toAbsolutePath().normalize();
    }

    public Path reportDir() {
        if (reportDir == null || reportDir.isBlank() || DEFAULT_REPORT_DIR.equals(reportDir)) {
            return stateDir().resolve("reports");
        }
        return Path.of(reportDir).toAbsolutePath().normalize();
    }
}
