package io.github.immichuploader.core;

public record UploadSettings(
        String server,
        String deviceId,
        int senderThreads,
        int batchSize,
        boolean resume,
        boolean verify,
        VerifyScope verifyScope,
        LivePhotoMode livePhotoMode,
        boolean verifyLivePhotoLinks
) {
    public enum VerifyScope {
        RUN,
        ALL
    }

    public UploadSettings {
        if (senderThreads < 1) {
            throw new IllegalArgumentException("Sender 线程数必须至少为 1");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("批量大小必须至少为 1");
        }
        verifyScope = verifyScope == null ? VerifyScope.RUN : verifyScope;
        livePhotoMode = livePhotoMode == null ? LivePhotoMode.AUTO : livePhotoMode;
    }
}
