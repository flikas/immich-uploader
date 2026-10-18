package io.github.immichuploader.core;

import java.nio.file.Path;
import java.util.List;

public record MediaScanResult(
        List<ScannedMedia> media,
        List<LivePhotoPair> pairs,
        List<Issue> issues,
        List<String> warnings,
        List<SkippedFile> skipped,
        int visitedFiles
) {
    public MediaScanResult {
        media = List.copyOf(media);
        pairs = List.copyOf(pairs);
        issues = List.copyOf(issues);
        warnings = List.copyOf(warnings);
        skipped = List.copyOf(skipped);
    }

    public long imageCount() {
        return media.stream().filter(item -> item.kind() == MediaKind.IMAGE).count();
    }

    public long videoCount() {
        return media.stream().filter(item -> item.kind() == MediaKind.VIDEO).count();
    }

    public record Issue(Path path, String message) {
    }
}
