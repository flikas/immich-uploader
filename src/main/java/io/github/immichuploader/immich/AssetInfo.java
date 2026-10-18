package io.github.immichuploader.immich;

public record AssetInfo(
        String id,
        String type,
        String livePhotoVideoId,
        boolean trashed,
        String visibility
) {
    public AssetInfo {
        id = id == null ? "" : id;
        type = type == null ? "" : type;
        livePhotoVideoId = livePhotoVideoId == null ? "" : livePhotoVideoId;
        visibility = visibility == null ? "" : visibility;
    }
}
