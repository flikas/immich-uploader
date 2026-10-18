package io.github.immichuploader.state;

import io.github.immichuploader.core.MediaKind;
import io.github.immichuploader.core.MediaRole;
import io.github.immichuploader.core.ScannedMedia;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordStoreTest {
    @TempDir
    Path temp;

    @Test
    void roundTripsV2Record() throws Exception {
        Path record = temp.resolve("record.tsv");
        ScannedMedia media = media("photo.jpg", MediaKind.IMAGE, MediaRole.LIVE_IMAGE, "pair-1");
        RecordEntry entry = entry(media, RecordStatus.UPLOADED, "asset-1", "video-1", "linked");

        try (RecordStore store = RecordStore.open(record, "http://immich/api")) {
            store.append(entry);
        }

        List<RecordEntry> entries = RecordStore.read(record);
        assertEquals(1, entries.size());
        RecordEntry read = entries.get(0);
        assertEquals(MediaRole.LIVE_IMAGE, read.role());
        assertEquals("video-1", read.livePhotoVideoId());
        assertEquals("asset-1", read.assetId());
        assertEquals(media.path(), read.path());
        assertTrue(read.complete());
    }

    @Test
    void incompleteLiveImageCanBeFoundButNotResumed() throws Exception {
        Path record = temp.resolve("record.tsv");
        ScannedMedia media = media("photo.jpg", MediaKind.IMAGE, MediaRole.LIVE_IMAGE, "pair-1");
        RecordEntry entry = entry(media, RecordStatus.DUPLICATE, "asset-1", "", "link failed");

        try (RecordStore store = RecordStore.open(record, "http://immich/api")) {
            store.append(entry);
        }
        try (RecordStore store = RecordStore.open(record, "http://immich/api")) {
            assertTrue(store.findRecord(media).isPresent());
            assertFalse(store.findResume(media).isPresent());
        }
    }

    @Test
    void resumeRequiresSameSizeAndModifiedTime() throws Exception {
        Path record = temp.resolve("record.tsv");
        ScannedMedia media = media("video.mp4", MediaKind.VIDEO, MediaRole.NORMAL, "");
        try (RecordStore store = RecordStore.open(record, "http://immich/api")) {
            store.append(entry(media, RecordStatus.UPLOADED, "asset-1", "", "uploaded"));
        }

        ScannedMedia changed = new ScannedMedia(media.path(), media.size() + 1,
                media.modifiedAtMillis(), media.createdAtMillis(), media.kind(), media.role(), media.pairKey());
        try (RecordStore store = RecordStore.open(record, "http://immich/api")) {
            assertTrue(store.findResume(media).isPresent());
            assertFalse(store.findResume(changed).isPresent());
        }
    }

    @Test
    void readsV1Records() {
        String line = "1700000000000\tUPLOADED\tC:\\photos\\a.jpg\t10\t100\tqZk=\tasset-1\thttp://immich/api\tuploaded\t";
        RecordEntry entry = RecordEntry.fromTsv(line);
        assertEquals(MediaKind.IMAGE, entry.kind());
        assertEquals(MediaRole.NORMAL, entry.role());
        assertEquals("", entry.livePhotoVideoId());
    }

    @Test
    void preventsConcurrentWriters() throws Exception {
        Path record = temp.resolve("record.tsv");
        try (RecordStore ignored = RecordStore.open(record, "http://immich/api")) {
            assertThrows(RecordLockedException.class,
                    () -> RecordStore.open(record, "http://immich/api"));
        }
    }

    @Test
    void repairsTrailingPartialLine() throws Exception {
        Path record = temp.resolve("record.tsv");
        ScannedMedia media = media("photo.jpg", MediaKind.IMAGE, MediaRole.NORMAL, "");
        try (RecordStore store = RecordStore.open(record, "http://immich/api")) {
            store.append(entry(media, RecordStatus.UPLOADED, "asset-1", "", "uploaded"));
        }
        Files.writeString(record, "partial-line-without-newline", java.nio.file.StandardOpenOption.APPEND);

        try (RecordStore store = RecordStore.open(record, "http://immich/api")) {
            store.append(entry(media, RecordStatus.UPLOADED, "asset-2", "", "second"));
            assertTrue(store.malformedLines() >= 1);
        }
        assertEquals(2, RecordStore.read(record).size());
    }

    private ScannedMedia media(String filename, MediaKind kind, MediaRole role, String pairKey) throws Exception {
        Path file = temp.resolve(filename);
        Files.writeString(file, "data");
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        return new ScannedMedia(
                file.toRealPath(),
                attrs.size(),
                attrs.lastModifiedTime().toMillis(),
                attrs.creationTime().toMillis(),
                kind,
                role,
                pairKey
        );
    }

    private RecordEntry entry(
            ScannedMedia media,
            RecordStatus status,
            String assetId,
            String videoId,
            String message
    ) {
        return new RecordEntry(
                System.currentTimeMillis(),
                status,
                media.kind(),
                media.role(),
                media.pairKey(),
                media.path(),
                media.size(),
                media.modifiedAtMillis(),
                "qZk+NkcGgWq6PiVxeFDCbJzQ2J0=",
                assetId,
                videoId,
                "http://immich/api",
                "test",
                message
        );
    }
}
