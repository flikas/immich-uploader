package io.github.immichuploader.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MediaDetectorTest {
    @TempDir
    Path temp;

    @Test
    void detectsJpegByMagicWhenExtensionIsUnknown() throws Exception {
        Path file = temp.resolve("mystery.bin");
        Files.write(file, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0});
        assertEquals(MediaKind.IMAGE, MediaDetector.detect(file, true, false));
    }

    @Test
    void detectsPngAndTiffRaw() throws Exception {
        Path png = temp.resolve("x.noext");
        Files.write(png, new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A});
        assertEquals(MediaKind.IMAGE, MediaDetector.magic(png));

        Path raw = temp.resolve("camera.raw");
        Files.write(raw, new byte[]{'I', 'I', 0x2A, 0x00, 0, 0});
        assertEquals(MediaKind.IMAGE, MediaDetector.detect(raw, true, false));
    }

    @Test
    void detectsIsoMediaBrands() throws Exception {
        Path mp4 = temp.resolve("clip.bin");
        Files.write(mp4, ftyp("isom"));
        assertEquals(MediaKind.VIDEO, MediaDetector.magic(mp4));

        Path heic = temp.resolve("photo.bin");
        Files.write(heic, ftyp("heic"));
        assertEquals(MediaKind.IMAGE, MediaDetector.magic(heic));
    }

    @Test
    void rejectsTypeScriptWithTsExtension() throws Exception {
        Path file = temp.resolve("note.ts");
        Files.writeString(file, "const value = 1;", StandardCharsets.UTF_8);
        assertNull(MediaDetector.detect(file, true, false));
    }

    @Test
    void acceptsMpegTransportStreamWithTsExtension() throws Exception {
        Path file = temp.resolve("stream.ts");
        byte[] data = new byte[200];
        data[0] = 0x47;
        data[188] = 0x47;
        Files.write(file, data);
        assertEquals(MediaKind.VIDEO, MediaDetector.detect(file, true, false));
    }

    @Test
    void allFilesFallsBackToOther() throws Exception {
        Path file = temp.resolve("notes.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        assertEquals(MediaKind.OTHER, MediaDetector.detect(file, true, true));
    }

    @Test
    void noMagicSkipsUnknownExtension() throws Exception {
        Path file = temp.resolve("mystery.bin");
        Files.write(file, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0});
        assertNull(MediaDetector.detect(file, false, false));
    }

    private static byte[] ftyp(String brand) {
        byte[] data = new byte[16];
        data[3] = 0x18;
        data[4] = 'f';
        data[5] = 't';
        data[6] = 'y';
        data[7] = 'p';
        for (int i = 0; i < 4; i++) {
            data[8 + i] = (byte) brand.charAt(i);
        }
        return data;
    }
}
