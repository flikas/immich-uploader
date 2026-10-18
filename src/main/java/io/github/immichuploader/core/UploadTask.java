package io.github.immichuploader.core;

import java.nio.file.Path;

public abstract class UploadTask {
    public final ScannedMedia media;
    public final String sha1Base64;
    public final String sha1Hex;
    public final String contentType;
    public final byte[] data;

    protected UploadTask(
            ScannedMedia media,
            String sha1Base64,
            String sha1Hex,
            String contentType,
            byte[] data
    ) {
        this.media = media;
        this.sha1Base64 = sha1Base64;
        this.sha1Hex = sha1Hex;
        this.contentType = contentType;
        this.data = data;
    }

    public Path path() {
        return media.path();
    }

    public long size() {
        return media.size();
    }

    public static final class Normal extends UploadTask {
        public Normal(ScannedMedia media, String sha1Base64, String sha1Hex,
                      String contentType, byte[] data) {
            super(media, sha1Base64, sha1Hex, contentType, data);
        }
    }

    public static final class LivePhoto extends UploadTask {
        public final AssetPart image;
        public final AssetPart video;
        public final String imageAssetId;
        public final String videoAssetId;

        public LivePhoto(AssetPart image, AssetPart video) {
            this(image, video, "", "");
        }

        public LivePhoto(AssetPart image, AssetPart video,
                         String imageAssetId, String videoAssetId) {
            super(image.media, image.sha1Base64, image.sha1Hex,
                    image.contentType, null);
            this.image = image;
            this.video = video;
            this.imageAssetId = imageAssetId == null ? "" : imageAssetId;
            this.videoAssetId = videoAssetId == null ? "" : videoAssetId;
        }
    }

    public static final class AssetPart {
        public final ScannedMedia media;
        public final String sha1Base64;
        public final String sha1Hex;
        public final String contentType;
        public final byte[] data;

        public AssetPart(ScannedMedia media, String sha1Base64, String sha1Hex,
                         String contentType, byte[] data) {
            this.media = media;
            this.sha1Base64 = sha1Base64;
            this.sha1Hex = sha1Hex;
            this.contentType = contentType;
            this.data = data;
        }
    }
}
