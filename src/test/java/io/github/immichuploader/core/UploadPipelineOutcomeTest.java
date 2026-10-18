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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UploadPipelineOutcomeTest {
    @TempDir
    Path temp;

    @Test
    void countsUploadDuplicateAndSkipAsProcessedOutcomes() throws Exception {
        Path root = temp.resolve("photos");
        for (int i = 0; i < 3; i++) {
            TestMediaFactory.jpeg(root.resolve("photo-" + i + ".jpg"));
        }

        UploadSettings settings = new UploadSettings(
                "http://test", "device", 1, 10, false, false,
                UploadSettings.VerifyScope.RUN, LivePhotoMode.OFF, true);
        ScanConfig config = new ScanConfig(
                List.of(root), true, false, MediaExtensions.DEFAULT,
                true, true, MediaFilter.ALL, LivePhotoMode.OFF,
                1, 20 * 1024 * 1024, 10, 4);

        try (RecordStore store = RecordStore.open(temp.resolve("record.tsv"), "http://test")) {
            UploadPipeline pipeline = new UploadPipeline(new OutcomeApi(), store, settings,
                    Progress.silent(), config);
            RunSummary summary = pipeline.run();

            assertEquals(3, summary.scanned());
            assertEquals(2, summary.uploaded());
            assertEquals(1, summary.precheckDuplicates());
            assertEquals(0, summary.skipped());
            assertEquals(0, summary.failed());
            assertEquals(3, summary.uploaded()
                    + summary.precheckDuplicates() + summary.failed() + summary.skipped());
        }
    }

    private static final class OutcomeApi implements ImmichApi {
        private final AtomicInteger uploads = new AtomicInteger();

        @Override
        public ServerVersion serverVersion() {
            return null;
        }

        @Override
        public List<BulkCheckResult> bulkUploadCheck(List<BulkCheckItem> items) {
            return items.stream().map(item -> {
                int index = Integer.parseInt(item.id());
                if (index == 1) {
                    return new BulkCheckResult(item.id(), "reject", "duplicate", "existing", false);
                }
                return new BulkCheckResult(item.id(), "accept", "", "", false);
            }).toList();
        }

        @Override
        public AssetUploadResult uploadAsset(AssetUploadRequest request) {
            return new AssetUploadResult("asset-" + uploads.incrementAndGet(), "created");
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
