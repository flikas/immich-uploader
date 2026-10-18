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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadPipelinePrecheckTest {
    @TempDir
    Path temp;

    @Test
    void precheckBatchIsDerivedFromHeapAndReaderCount() {
        int cacheLimit = 20 * 1024 * 1024;
        int readers = 4;
        long heapBudget = Runtime.getRuntime().maxMemory() / 2;
        int expected = (int) Math.max(1, Math.min(500L, heapBudget / cacheLimit / readers));

        assertEquals(expected, UploadPipeline.resolvePrecheckBatch(cacheLimit, readers, 0));
        assertEquals(Math.min(expected, 37),
                UploadPipeline.resolvePrecheckBatch(cacheLimit, readers, 37));
    }

    @Test
    void sendsBothLivePhotoHashesInTheSamePrecheckAndCarriesIdsInTask() throws Exception {
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
        PrecheckApi api = new PrecheckApi();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (RecordStore store = RecordStore.open(temp.resolve("record.tsv"), "http://test");
             Progress progress = new Progress(new PrintStream(output, true), true)) {
            RunSummary summary = new UploadPipeline(api, store, settings,
                    progress, config).run();

            assertEquals(1, summary.uploaded());
            assertEquals(1, summary.precheckDuplicates());
            assertEquals(2, api.checked.size(), "image and video checksums must share one batch");
            assertNotEquals(api.checked.get(0).checksum(), api.checked.get(1).checksum());
            assertEquals(1, api.uploads.size());
            assertEquals("existing-video", api.uploads.get(0).livePhotoVideoId());
        }
        String console = output.toString();
        assertTrue(console.contains("precheckBatch=10"), console);
        assertTrue(console.contains("senderQueue=4"), console);
    }

    private static final class PrecheckApi implements ImmichApi {
        private final List<BulkCheckItem> checked = new ArrayList<>();
        private final List<AssetUploadRequest> uploads = new ArrayList<>();

        @Override
        public ServerVersion serverVersion() {
            return null;
        }

        @Override
        public List<BulkCheckResult> bulkUploadCheck(List<BulkCheckItem> items) {
            checked.addAll(items);
            return List.of(
                    new BulkCheckResult(items.get(0).id(), "accept", "", "", false),
                    new BulkCheckResult(items.get(1).id(), "reject", "duplicate", "existing-video", false)
            );
        }

        @Override
        public AssetUploadResult uploadAsset(AssetUploadRequest request) {
            uploads.add(request);
            return new AssetUploadResult("image-new", "created");
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
