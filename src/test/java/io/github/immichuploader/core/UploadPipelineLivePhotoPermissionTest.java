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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadPipelineLivePhotoPermissionTest {
    @TempDir
    Path temp;

    @Test
    void countsLinkFailureAsNonFatalWhenKeyLacksAssetUpdate() throws Exception {
        Path root = temp.resolve("photos");
        for (int i = 0; i < 2; i++) {
            TestMediaFactory.jpeg(root.resolve("live-" + i + ".jpg"));
            TestMediaFactory.mp4(root.resolve("live-" + i + ".mov"));
        }

        UploadSettings settings = new UploadSettings(
                "http://test", "device", 1, 10, false, false,
                UploadSettings.VerifyScope.RUN, LivePhotoMode.AUTO, true);
        ScanConfig config = new ScanConfig(
                List.of(root), true, false, MediaExtensions.DEFAULT,
                true, true, MediaFilter.ALL, LivePhotoMode.AUTO,
                1, 20 * 1024 * 1024, 10, 4);
        MissingUpdateApi api = new MissingUpdateApi();

        try (RecordStore store = RecordStore.open(temp.resolve("record.tsv"), "http://test")) {
            RunSummary summary = new UploadPipeline(api, store, settings,
                    Progress.silent(), config).run();

            assertEquals(2, summary.failed());
            assertEquals(0, summary.pairsSkipped());
            assertEquals(1, api.linkCalls.get(), "confirmed missing permission must stop further link attempts");
            assertEquals(2, summary.failures().size());
            assertEquals("LIVE_PHOTO_LINK", summary.failures().get(0).stage());
            assertEquals(0, summary.exitCode());

            var imageRecord = store.latestForPath(root.resolve("live-0.jpg")).orElseThrow();
            assertEquals("link-failed", imageRecord.action());
            assertFalse(imageRecord.complete());
            assertTrue(imageRecord.livePhotoVideoId().isBlank());
        }
    }

    private static final class MissingUpdateApi implements ImmichApi {
        private final AtomicInteger linkCalls = new AtomicInteger();

        @Override
        public ServerVersion serverVersion() {
            return null;
        }

        @Override
        public List<BulkCheckResult> bulkUploadCheck(List<BulkCheckItem> items) {
            return items.stream()
                    .map(item -> new BulkCheckResult(
                            item.id(), "reject", "duplicate", "existing-" + item.id(), false))
                    .toList();
        }

        @Override
        public AssetUploadResult uploadAsset(AssetUploadRequest request) {
            throw new AssertionError("precheck duplicates must not be uploaded");
        }

        @Override
        public AssetInfo getAsset(String assetId) {
            return new AssetInfo(assetId, "IMAGE", "", false, "timeline");
        }

        @Override
        public void linkLivePhoto(String imageAssetId, String videoAssetId) {
            linkCalls.incrementAndGet();
            throw new ImmichApiException(
                    "Immich link-live-photo 权限不足 (HTTP 403)",
                    403,
                    "{\"message\":\"Missing required permission: asset.update\"}"
            );
        }
    }
}
