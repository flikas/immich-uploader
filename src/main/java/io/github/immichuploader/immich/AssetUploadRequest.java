package io.github.immichuploader.immich;

import java.nio.file.Path;
import java.time.Instant;

public record AssetUploadRequest(
        Path file,
        String filename,
        String checksumBase64,
        String deviceAssetId,
        String deviceId,
        Instant createdAt,
        Instant modifiedAt,
        String livePhotoVideoId,
        String visibility,
        byte[] data,
        UploadProgressListener progressListener
) {
    public AssetUploadRequest {
        livePhotoVideoId = livePhotoVideoId == null ? "" : livePhotoVideoId;
        visibility = visibility == null ? "" : visibility;
    }

    public AssetUploadRequest(
            Path file,
            String filename,
            String checksumBase64,
            String deviceAssetId,
            String deviceId,
            Instant createdAt,
            Instant modifiedAt,
            String livePhotoVideoId,
            String visibility
    ) {
        this(file, filename, checksumBase64, deviceAssetId, deviceId,
                createdAt, modifiedAt, livePhotoVideoId, visibility, null, null);
    }

    public AssetUploadRequest(
            Path file,
            String filename,
            String checksumBase64,
            String deviceAssetId,
            String deviceId,
            Instant createdAt,
            Instant modifiedAt,
            String livePhotoVideoId,
            String visibility,
            byte[] data
    ) {
        this(file, filename, checksumBase64, deviceAssetId, deviceId,
                createdAt, modifiedAt, livePhotoVideoId, visibility, data, null);
    }
}
