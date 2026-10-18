package io.github.immichuploader.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LivePhotoPairerTest {
    @TempDir
    Path temp;

    @Test
    void pairsSameStemImageAndVideo() {
        ScannedMedia image = media("IMG_0001.HEIC", MediaKind.IMAGE);
        ScannedMedia video = media("IMG_0001.MOV", MediaKind.VIDEO);
        LivePhotoPairer.Result result = LivePhotoPairer.pair(List.of(image, video), LivePhotoMode.AUTO);

        assertEquals(1, result.pairs().size());
        LivePhotoPair pair = result.pairs().get(0);
        assertEquals(MediaRole.LIVE_IMAGE, pair.image().role());
        assertEquals(MediaRole.LIVE_VIDEO, pair.video().role());
        assertTrue(pair.pairKey().contains("img_0001"));
    }

    @Test
    void caseInsensitiveStem() {
        ScannedMedia image = media("PXL_1234.JPG", MediaKind.IMAGE);
        ScannedMedia video = media("pxl_1234.mp4", MediaKind.VIDEO);
        LivePhotoPairer.Result result = LivePhotoPairer.pair(List.of(image, video), LivePhotoMode.AUTO);
        assertEquals(1, result.pairs().size());
    }

    @Test
    void strictModeDoesNotPairJpeg() {
        ScannedMedia image = media("IMG_0001.JPG", MediaKind.IMAGE);
        ScannedMedia video = media("IMG_0001.MOV", MediaKind.VIDEO);
        LivePhotoPairer.Result result = LivePhotoPairer.pair(List.of(image, video), LivePhotoMode.STRICT);
        assertEquals(0, result.pairs().size());
    }

    @Test
    void differentDirectoriesAreNotPaired() {
        ScannedMedia image = media(temp.resolve("one").resolve("IMG_0001.HEIC"), MediaKind.IMAGE);
        ScannedMedia video = media(temp.resolve("two").resolve("IMG_0001.MOV"), MediaKind.VIDEO);
        LivePhotoPairer.Result result = LivePhotoPairer.pair(List.of(image, video), LivePhotoMode.AUTO);
        assertEquals(0, result.pairs().size());
    }

    @Test
    void ambiguousCandidatesAreNotPaired() {
        ScannedMedia image1 = media("IMG_0001.HEIC", MediaKind.IMAGE);
        ScannedMedia image2 = media("IMG_0001.JPG", MediaKind.IMAGE);
        ScannedMedia video = media("IMG_0001.MOV", MediaKind.VIDEO);
        LivePhotoPairer.Result result = LivePhotoPairer.pair(List.of(image1, image2, video), LivePhotoMode.AUTO);
        assertEquals(0, result.pairs().size());
        assertEquals(1, result.warnings().size());
    }

    @Test
    void offModeDoesNothing() {
        ScannedMedia image = media("IMG_0001.HEIC", MediaKind.IMAGE);
        ScannedMedia video = media("IMG_0001.MOV", MediaKind.VIDEO);
        LivePhotoPairer.Result result = LivePhotoPairer.pair(List.of(image, video), LivePhotoMode.OFF);
        assertEquals(0, result.pairs().size());
        assertEquals(MediaRole.NORMAL, result.media().get(0).role());
    }

    private ScannedMedia media(String filename, MediaKind kind) {
        return media(temp.resolve(filename), kind);
    }

    private ScannedMedia media(Path path, MediaKind kind) {
        return new ScannedMedia(path, 10, 100, 100, kind, MediaRole.NORMAL, "");
    }
}
