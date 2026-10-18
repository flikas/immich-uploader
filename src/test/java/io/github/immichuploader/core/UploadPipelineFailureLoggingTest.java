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
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadPipelineFailureLoggingTest {
    @TempDir
    Path temp;

    @Test
    void logsUploadExceptionWithStackTrace() throws Exception {
        Path root = temp.resolve("photos");
        TestMediaFactory.jpeg(root.resolve("photo.jpg"));

        Logger logger = (Logger) LogManager.getLogger(UploadPipeline.class);
        Level previous = logger.getLevel();
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        logger.setLevel(Level.ERROR);
        logger.addAppender(appender);
        try (RecordStore store = RecordStore.open(temp.resolve("record.tsv"), "http://test")) {
            UploadSettings settings = new UploadSettings(
                    "http://test", "device", 1, 10, false, false,
                    UploadSettings.VerifyScope.RUN, LivePhotoMode.OFF, true);
            ScanConfig config = new ScanConfig(
                    List.of(root), true, false, MediaExtensions.DEFAULT,
                    true, true, MediaFilter.ALL, LivePhotoMode.OFF,
                    1, 20 * 1024 * 1024, 10, 4);
            RunSummary summary = new UploadPipeline(new FailingApi(), store, settings,
                    Progress.silent(), config).run();

            assertEquals(1, summary.failed());
            assertTrue(appender.events.stream().anyMatch(event ->
                    event.getThrown() != null
                            && event.getMessage().getFormattedMessage().contains("模拟上传失败")));
        } finally {
            logger.removeAppender(appender);
            appender.stop();
            logger.setLevel(previous);
        }
    }

    private static final class FailingApi implements ImmichApi {
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
            throw new RuntimeException("模拟上传失败");
        }

        @Override
        public AssetInfo getAsset(String assetId) {
            return new AssetInfo(assetId, "IMAGE", "", false, "timeline");
        }

        @Override
        public void linkLivePhoto(String imageAssetId, String videoAssetId) {
        }
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("capture-failure", null, null, false);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
