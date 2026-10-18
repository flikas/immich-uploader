package io.github.immichuploader.core;

import io.github.immichuploader.TestMediaFactory;
import io.github.immichuploader.immich.AssetInfo;
import io.github.immichuploader.immich.AssetUploadRequest;
import io.github.immichuploader.immich.AssetUploadResult;
import io.github.immichuploader.immich.BulkCheckItem;
import io.github.immichuploader.immich.BulkCheckResult;
import io.github.immichuploader.immich.ImmichApi;
import io.github.immichuploader.immich.ImmichApiException;
import io.github.immichuploader.immich.ServerVersion;
import io.github.immichuploader.state.RecordStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadPipelineLivePhotoUnsupportedTest {
    @TempDir
    Path temp;

    @Test
    void skipsUnsupportedVideoButUploadsImageAsNormalAsset() throws Exception {
        Path root = temp.resolve("photos");
        TestMediaFactory.jpeg(root.resolve("live.jpg"));
        TestMediaFactory.mp4(root.resolve("live.mov"));

        UploadSettings settings = new UploadSettings(
                "http://test", "device", 1, 10, false, false,
                UploadSettings.VerifyScope.RUN, LivePhotoMode.AUTO, true);
        ScanConfig config = new ScanConfig(
                List.of(root), true, false, MediaExtensions.DEFAULT,
                true, true, MediaFilter.ALL, LivePhotoMode.AUTO,
                1, 20 * 1024 * 1024, 10, 4);
        UnsupportedVideoApi api = new UnsupportedVideoApi();

        try (RecordStore store = RecordStore.open(temp.resolve("record.tsv"), "http://test")) {
            RunSummary summary = new UploadPipeline(api, store, settings,
                    Progress.silent(), config).run();

            assertEquals(1, summary.uploaded());
            assertEquals(1, summary.skipped());
            assertEquals(0, summary.failed());
            assertEquals(1, summary.skippedFiles().size());
            assertEquals(1, api.uploads.size());
            assertTrue(api.uploads.get(0).livePhotoVideoId().isBlank());
        }
    }

    private static final class UnsupportedVideoApi implements ImmichApi {
        private final List<AssetUploadRequest> uploads = new ArrayList<>();

        @Override
        public ServerVersion serverVersion() {
            return null;
        }

        @Override
        public List<BulkCheckResult> bulkUploadCheck(List<BulkCheckItem> items) {
            return items.stream()
                    .map(item -> new BulkCheckResult(item.id(), "accept", "", "", false))
                    .toList();
        }

        @Override
        public AssetUploadResult uploadAsset(AssetUploadRequest request) {
            if (request.filename().toLowerCase().endsWith(".mov")) {
                throw new ImmichApiException("unsupported file type", 400, "unsupported");
            }
            uploads.add(request);
            return new AssetUploadResult("image-1", "created");
        }

        @Override
        public AssetInfo getAsset(String assetId) {
            return new AssetInfo(assetId, "IMAGE", "", false, "timeline");
        }

        @Override
        public void linkLivePhoto(String imageAssetId, String videoAssetId) {
        }
    }
}
