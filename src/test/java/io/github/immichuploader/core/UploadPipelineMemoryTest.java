package io.github.immichuploader.core;

import io.github.immichuploader.TestMediaFactory;
import io.github.immichuploader.immich.AssetInfo;
import io.github.immichuploader.immich.AssetUploadRequest;
import io.github.immichuploader.immich.AssetUploadResult;
import io.github.immichuploader.immich.BulkCheckItem;
import io.github.immichuploader.immich.BulkCheckResult;
import io.github.immichuploader.immich.ImmichApi;
import io.github.immichuploader.immich.ServerVersion;
import io.github.immichuploader.state.RecordStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UploadPipelineMemoryTest {
    @TempDir
    Path temp;

    @Test
    void doesNotRetainCompletedTasksOrTheirBuffers() throws Exception {
        Path root = temp.resolve("photos");
        for (int i = 0; i < 60; i++) {
            TestMediaFactory.jpeg(root.resolve("photo-" + i + ".jpg"));
        }
        TestMediaFactory.jpeg(root.resolve("live.jpg"));
        TestMediaFactory.mp4(root.resolve("live.mov"));

        UploadSettings settings = new UploadSettings(
                "http://test", "device", 2, 20, false, false,
                UploadSettings.VerifyScope.RUN, LivePhotoMode.AUTO, true);
        ScanConfig config = new ScanConfig(
                List.of(root), true, false, MediaExtensions.DEFAULT,
                true, true, MediaFilter.ALL, LivePhotoMode.AUTO,
                1, 20 * 1024 * 1024, 10, 4);

        try (RecordStore store = RecordStore.open(temp.resolve("record.tsv"), "http://test")) {
            UploadPipeline pipeline = new UploadPipeline(new FakeApi(), store, settings,
                    Progress.silent(), config);
            RunSummary summary = pipeline.run();

            assertEquals(62, summary.uploaded());
        }
    }

    private static final class FakeApi implements ImmichApi {
        private int nextId;

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
            return new AssetUploadResult("asset-" + nextId++, "created");
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
