package io.github.immichuploader.core;

import java.nio.file.Path;

public record ScannedMedia(
        Path path,
        long size,
        long modifiedAtMillis,
        long createdAtMillis,
        MediaKind kind,
        MediaRole role,
        String pairKey
) {
    public ScannedMedia {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        kind = kind == null ? MediaKind.OTHER : kind;
        role = role == null ? MediaRole.NORMAL : role;
        pairKey = pairKey == null ? "" : pairKey;
    }

    public ScannedMedia withRole(MediaRole newRole, String newPairKey) {
        return new ScannedMedia(path, size, modifiedAtMillis, createdAtMillis, kind, newRole, newPairKey);
    }

    public boolean isLivePart() {
        return role == MediaRole.LIVE_IMAGE || role == MediaRole.LIVE_VIDEO;
    }
}
