package io.github.immichuploader.core;

import io.github.immichuploader.immich.AssetInfo;
import io.github.immichuploader.immich.AssetUploadRequest;
import io.github.immichuploader.immich.AssetUploadResult;
import io.github.immichuploader.immich.BulkCheckItem;
import io.github.immichuploader.immich.BulkCheckResult;
import io.github.immichuploader.immich.ImmichApi;
import io.github.immichuploader.immich.ImmichApiException;
import io.github.immichuploader.immich.ServerVersion;
import io.github.immichuploader.state.RecordEntry;
import io.github.immichuploader.state.RecordStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationServiceTest {
    @TempDir
    Path temp;

    @Test
    void checksSha1AndLivePhotoLink() throws Exception {
        Path file = temp.resolve("IMG_0001.JPG");
        Files.writeString(file, "image-data");
        String sha1 = Checksum.sha1Base64(file);
        RecordEntry record = new RecordEntry(
                System.currentTimeMillis(),
                RecordStatus.UPLOADED,
                MediaKind.IMAGE,
                MediaRole.LIVE_IMAGE,
                "pair",
                file,
                Files.size(file),
                Files.getLastModifiedTime(file).toMillis(),
                sha1,
                "image-asset",
                "video-asset",
                "http://immich/api",
                "live-photo-image",
                ""
        );

        FakeApi api = new FakeApi(Map.of(
                "image-asset", new AssetInfo("image-asset", "IMAGE", "video-asset", false, "timeline")
        ), "image-asset");
        VerificationSummary summary = new VerificationService(api, 1, 10, Progress.silent(), true)
                .verify(List.of(record));
        assertEquals(1, summary.total);
        assertEquals(1, summary.matched);
        assertEquals(0, summary.linkMissing);

        api.assets = Map.of(
                "image-asset", new AssetInfo("image-asset", "IMAGE", "", false, "timeline")
        );
        api.resultAssetId = "image-asset";
        VerificationSummary missing = new VerificationService(api, 1, 10, Progress.silent(), true)
                .verify(List.of(record));
        assertEquals(1, missing.linkMissing);
        assertTrue(!missing.ok(false));
    }

    @Test
    void reportsMissingLocalFile() {
        RecordEntry record = new RecordEntry(
                System.currentTimeMillis(), RecordStatus.UPLOADED, MediaKind.IMAGE, MediaRole.NORMAL, "",
                temp.resolve("gone.jpg"), 10, 100, "sha1", "asset", "", "http://immich/api", "uploaded", ""
        );
        VerificationSummary summary = new VerificationService(
                new FakeApi(Map.of(), "asset"), 1, 10, Progress.silent(), true).verify(List.of(record));
        assertEquals(1, summary.fileMissing);
        assertTrue(!summary.ok(false));
    }

    private static final class FakeApi implements ImmichApi {
        Map<String, AssetInfo> assets;
        String resultAssetId;

        FakeApi(Map<String, AssetInfo> assets, String resultAssetId) {
            this.assets = assets;
            this.resultAssetId = resultAssetId;
        }

        @Override
        public ServerVersion serverVersion() {
            return null;
        }

        @Override
        public List<BulkCheckResult> bulkUploadCheck(List<BulkCheckItem> items) {
            return items.stream()
                    .map(item -> new BulkCheckResult(item.id(), "reject", "duplicate", resultAssetId, false))
                    .toList();
        }

        @Override
        public AssetUploadResult uploadAsset(AssetUploadRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AssetInfo getAsset(String assetId) {
            AssetInfo info = assets.get(assetId);
            if (info == null) {
                throw new ImmichApiException("not found", 404, "");
            }
            return info;
        }

        @Override
        public void linkLivePhoto(String imageAssetId, String videoAssetId) {
            throw new UnsupportedOperationException();
        }
    }
}
