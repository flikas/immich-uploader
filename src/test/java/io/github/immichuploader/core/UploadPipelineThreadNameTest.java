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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadPipelineThreadNameTest {
    @TempDir
    Path temp;

    @Test
    void usesReadableReaderAndSenderThreadNames() throws Exception {
        Path root = temp.resolve("photos");
        TestMediaFactory.jpeg(root.resolve("photo.jpg"));

        UploadSettings settings = new UploadSettings(
                "http://test", "device", 1, 10, false, false,
                UploadSettings.VerifyScope.RUN, LivePhotoMode.OFF, true);
        ScanConfig config = new ScanConfig(
                List.of(root), true, false, MediaExtensions.DEFAULT,
                true, true, MediaFilter.ALL, LivePhotoMode.OFF,
                1, 20 * 1024 * 1024, 10, 4);
        NamedApi api = new NamedApi();

        try (RecordStore store = RecordStore.open(temp.resolve("record.tsv"), "http://test")) {
            RunSummary summary = new UploadPipeline(api, store, settings,
                    Progress.silent(), config).run();
            assertEquals(1, summary.uploaded());
        }

        assertTrue(api.readerName.get().startsWith("reader-"), api.readerName.get());
        assertEquals("sender-1", api.senderName.get());
    }

    private static final class NamedApi implements ImmichApi {
        private final AtomicReference<String> readerName = new AtomicReference<>();
        private final AtomicReference<String> senderName = new AtomicReference<>();

        @Override
        public ServerVersion serverVersion() {
            return null;
        }

        @Override
        public List<BulkCheckResult> bulkUploadCheck(List<BulkCheckItem> items) {
            readerName.set(Thread.currentThread().getName());
            return items.stream()
                    .map(item -> new BulkCheckResult(item.id(), "accept", "", "", false))
                    .toList();
        }

        @Override
        public AssetUploadResult uploadAsset(AssetUploadRequest request) {
            senderName.set(Thread.currentThread().getName());
            return new AssetUploadResult("asset-1", "created");
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
