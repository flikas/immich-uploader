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
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadPipelineUploadPermissionTest {
    @TempDir
    Path temp;

    @Test
    void missingUploadPermissionStopsTheRunImmediately() throws Exception {
        Path root = temp.resolve("photos");
        TestMediaFactory.jpeg(root.resolve("a.jpg"));
        TestMediaFactory.jpeg(root.resolve("b.jpg"));

        UploadSettings settings = new UploadSettings(
                "http://test", "device", 1, 10, false, false,
                UploadSettings.VerifyScope.RUN, LivePhotoMode.OFF, true);
        ScanConfig config = new ScanConfig(
                List.of(root), true, false, MediaExtensions.DEFAULT,
                true, true, MediaFilter.ALL, LivePhotoMode.OFF,
                1, 20 * 1024 * 1024, 10, 4);
        MissingUploadApi api = new MissingUploadApi();

        try (RecordStore store = RecordStore.open(temp.resolve("record.tsv"), "http://test")) {
            RunSummary summary = new UploadPipeline(api, store, settings,
                    Progress.silent(), config).run();

            assertTrue(summary.fatal);
            assertEquals(1, summary.exitCode());
            assertEquals(1, api.uploads.get(), "no further upload may be attempted");
        }
    }

    private static final class MissingUploadApi implements ImmichApi {
        private final AtomicInteger uploads = new AtomicInteger();

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
            uploads.incrementAndGet();
            throw new ImmichApiException(
                    "Immich upload 权限不足 (HTTP 403)",
                    403,
                    "{\"message\":\"Missing required permission: asset.upload\"}"
            );
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
