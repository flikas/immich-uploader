package io.github.immichuploader.core;

/**
 * Snapshot of the scan phase. Used by the CLI to keep long directory walks
 * observable without coupling the scanner to a particular output format.
 */
public record ScanProgress(int visited, int media, int skipped, int issues) {
}
