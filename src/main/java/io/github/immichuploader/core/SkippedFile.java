package io.github.immichuploader.core;

import java.nio.file.Path;

/**
 * A file that was intentionally not uploaded. Skipped files are reported
 * separately from failures because they are often expected (extension filter,
 * media filter, unsupported server format).
 */
public record SkippedFile(Path path, MediaKind kind, String stage, String reason) {
    public SkippedFile {
        kind = kind == null ? MediaKind.OTHER : kind;
        stage = stage == null ? "SCAN" : stage;
        reason = reason == null ? "" : reason;
    }
}
