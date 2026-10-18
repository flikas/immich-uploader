package io.github.immichuploader.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public record ScanConfig(
        List<Path> roots,
        boolean recursive,
        boolean followLinks,
        Set<String> extensions,
        boolean useMagic,
        boolean allFiles,
        MediaFilter filter,
        LivePhotoMode livePhotoMode,
        int readerThreads,
        int cacheMaxFileSize,
        int precheckBatchSize,
        int senderQueueLength
) {
    public ScanConfig {
        roots = List.copyOf(roots);
        extensions = Set.copyOf(extensions);
    }
}
