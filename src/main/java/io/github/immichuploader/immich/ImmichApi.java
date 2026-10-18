package io.github.immichuploader.immich;

import java.util.List;

public interface ImmichApi {
    /**
     * Best-effort version lookup. Returns {@code null} when the endpoint is not
     * available; version information must never block an upload.
     */
    ServerVersion serverVersion();

    List<BulkCheckResult> bulkUploadCheck(List<BulkCheckItem> items);

    AssetUploadResult uploadAsset(AssetUploadRequest request);

    AssetInfo getAsset(String assetId);

    /**
     * Links an existing image asset to a video asset. Immich hides the video
     * from the timeline as part of this operation.
     */
    void linkLivePhoto(String imageAssetId, String videoAssetId);
}
