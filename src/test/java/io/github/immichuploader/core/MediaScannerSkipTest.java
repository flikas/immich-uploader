package io.github.immichuploader.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaScannerSkipTest {
    @TempDir
    Path temp;

    @Test
    void recordsExtensionAndAmbiguitySkips() throws Exception {
        Files.writeString(temp.resolve("photo.jpg"), "image");
        Files.writeString(temp.resolve("notes.txt"), "notes");
        Files.writeString(temp.resolve("note.ts"), "const x = 1;");

        MediaScanResult result = MediaScanner.scan(
                List.of(temp), true, false, MediaExtensions.DEFAULT,
                true, false, MediaFilter.ALL, LivePhotoMode.OFF);

        assertEquals(1, result.media().size());
        assertEquals(2, result.skipped().size());
        assertTrue(result.skipped().stream()
                .anyMatch(s -> s.path().getFileName().toString().equals("notes.txt")
                        && s.reason().contains("扩展名不在允许清单")));
        assertTrue(result.skipped().stream()
                .anyMatch(s -> s.path().getFileName().toString().equals("note.ts")
                        && s.reason().contains("扩展名有歧义")));
    }

    @Test
    void reportsScanProgress() throws Exception {
        Files.writeString(temp.resolve("photo.jpg"), "image");
        Files.writeString(temp.resolve("notes.txt"), "notes");
        List<ScanProgress> progress = new ArrayList<>();

        MediaScanner.scan(
                List.of(temp), true, false, MediaExtensions.DEFAULT,
                true, false, MediaFilter.ALL, LivePhotoMode.OFF, progress::add);

        assertTrue(progress.size() >= 2);
        ScanProgress last = progress.get(progress.size() - 1);
        assertEquals(2, last.visited());
        assertEquals(1, last.media());
        assertEquals(1, last.skipped());
    }

    @Test
    void recordsMediaFilterSkips() throws Exception {
        Files.writeString(temp.resolve("photo.jpg"), "image");
        Files.writeString(temp.resolve("clip.mp4"), "video");

        MediaScanResult result = MediaScanner.scan(
                List.of(temp), true, false, MediaExtensions.DEFAULT,
                true, false, MediaFilter.VIDEO, LivePhotoMode.OFF);

        assertEquals(1, result.media().size());
        assertEquals(1, result.skipped().size());
        assertTrue(result.skipped().get(0).reason().contains("媒体类型过滤"));
    }

    @Test
    void allFilesIncludesUnknownAsOther() throws Exception {
        Files.writeString(temp.resolve("notes.txt"), "notes");
        Files.writeString(temp.resolve("photo.jpg"), "image");

        MediaScanResult result = MediaScanner.scan(
                List.of(temp), true, false, MediaExtensions.DEFAULT,
                true, true, MediaFilter.ALL, LivePhotoMode.OFF);

        assertEquals(2, result.media().size());
        assertTrue(result.media().stream().anyMatch(m -> m.kind() == MediaKind.OTHER));
        assertTrue(result.skipped().isEmpty());
    }
}
