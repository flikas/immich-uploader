package io.github.immichuploader.report;

import io.github.immichuploader.core.LivePhotoMode;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public record ReportContext(
        String toolVersion,
        String server,
        String serverVersion,
        List<Path> directories,
        Set<String> extensions,
        Path recordFile,
        String deviceId,
        boolean recursive,
        boolean allFiles,
        boolean magicDetection,
        LivePhotoMode livePhotoMode,
        boolean verify,
        boolean verifyLinks
) {
    public ReportContext {
        directories = directories == null ? List.of() : List.copyOf(directories);
        extensions = extensions == null ? Set.of() : Set.copyOf(extensions);
        server = server == null ? "" : server;
        serverVersion = serverVersion == null ? "" : serverVersion;
        deviceId = deviceId == null ? "" : deviceId;
        livePhotoMode = livePhotoMode == null ? LivePhotoMode.AUTO : livePhotoMode;
    }
}
