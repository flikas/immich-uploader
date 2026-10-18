package io.github.immichuploader;

import io.github.immichuploader.core.MediaDetector;
import io.github.immichuploader.core.MediaKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMediaFactoryTest {
    @TempDir
    Path temp;

    @Test
    void createsRecognizableJpegPngAndMp4() throws Exception {
        Path jpeg = TestMediaFactory.jpeg(temp.resolve("a.jpg"));
        Path png = TestMediaFactory.png(temp.resolve("a.png"));
        Path mp4 = TestMediaFactory.mp4(temp.resolve("a.mp4"));

        assertTrue(Files.size(jpeg) > 0);
        assertTrue(Files.size(png) > 0);
        assertTrue(Files.size(mp4) > 0);
        assertEquals(MediaKind.IMAGE, MediaDetector.magic(jpeg));
        assertEquals(MediaKind.IMAGE, MediaDetector.magic(png));
        assertEquals(MediaKind.VIDEO, MediaDetector.magic(mp4));
    }
}
