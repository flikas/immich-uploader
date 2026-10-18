package io.github.immichuploader.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Extension-first media detection with a magic-byte fallback. The fallback is
 * what lets files with unusual or missing extensions still be uploaded.
 */
public final class MediaDetector {
    private static final int HEAD_SIZE = 512;
    private static final Set<String> IMAGE_BRANDS = Set.of(
            "heic", "heix", "hevc", "heim", "heis", "hevm", "hevs", "mif1", "msf1",
            "avif", "avis", "jxl "
    );

    private MediaDetector() {
    }

    public static MediaKind detect(Path path, boolean useMagic, boolean allFiles) {
        String extension = MediaExtensions.extensionOf(path.getFileName().toString());
        MediaKind byExtension = MediaExtensions.kindOf(extension);
        if (byExtension != null && !MediaExtensions.isAmbiguous(extension)) {
            return byExtension;
        }

        if (useMagic) {
            MediaKind byMagic = magic(path);
            if (byMagic != null) {
                return byMagic;
            }
        }

        // Windows often reports video/mp2t for every .ts file. For ambiguous
        // extensions, require a content signature instead of trusting MIME.
        if (MediaExtensions.isAmbiguous(extension)) {
            return allFiles ? MediaKind.OTHER : null;
        }

        try {
            String mime = Files.probeContentType(path);
            if (mime != null) {
                String normalized = mime.toLowerCase(Locale.ROOT);
                if (normalized.startsWith("image/")) {
                    return MediaKind.IMAGE;
                }
                if (normalized.startsWith("video/")) {
                    return MediaKind.VIDEO;
                }
            }
        } catch (IOException ignored) {
            // Fall through to the extension / all-files decision.
        }

        if (byExtension != null) {
            return byExtension;
        }
        return allFiles ? MediaKind.OTHER : null;
    }

    public static MediaKind magic(Path path) {
        byte[] head;
        try (InputStream in = Files.newInputStream(path)) {
            head = in.readNBytes(HEAD_SIZE);
        } catch (IOException e) {
            return null;
        }
        if (head.length < 4) {
            return null;
        }

        if (startsWith(head, 0xFF, 0xD8, 0xFF)) {
            return MediaKind.IMAGE;
        }
        if (startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return MediaKind.IMAGE;
        }
        if (ascii(head, 0, 6).equals("GIF87a") || ascii(head, 0, 6).equals("GIF89a")) {
            return MediaKind.IMAGE;
        }
        if (head[0] == 'B' && head[1] == 'M') {
            return MediaKind.IMAGE;
        }
        if (startsWith(head, 0x49, 0x49, 0x2A, 0x00) || startsWith(head, 0x4D, 0x4D, 0x00, 0x2A)) {
            return MediaKind.IMAGE; // TIFF and most camera RAW formats
        }
        if (ascii(head, 0, 4).equals("RIFF") && ascii(head, 8, 4).equals("WEBP")) {
            return MediaKind.IMAGE;
        }
        if (ascii(head, 0, 4).equals("RIFF") && ascii(head, 8, 4).equals("AVI ")) {
            return MediaKind.VIDEO;
        }
        if (startsWith(head, 0x1A, 0x45, 0xDF, 0xA3)) {
            return MediaKind.VIDEO; // Matroska / WebM
        }
        if (ascii(head, 0, 3).equals("FLV")) {
            return MediaKind.VIDEO;
        }
        if (startsWith(head, 0x00, 0x00, 0x01, 0xBA)
                || startsWith(head, 0x00, 0x00, 0x01, 0xB3)) {
            return MediaKind.VIDEO; // MPEG program stream / elementary stream
        }
        if (head[0] == 0x47 && (head.length <= 188 || head[188] == 0x47)) {
            return MediaKind.VIDEO; // MPEG-TS
        }
        if (head.length > 196 && head[4] == 0x47 && head[196] == 0x47) {
            return MediaKind.VIDEO; // M2TS
        }
        if (ascii(head, 0, 4).equals("OggS")) {
            return MediaKind.VIDEO; // ogv/ogm
        }
        if (startsWith(head, 0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11)) {
            return MediaKind.VIDEO; // ASF / WMV
        }
        if (ascii(head, 0, 4).equals(".RMF")) {
            return MediaKind.VIDEO; // RealMedia
        }
        if (startsWith(head, 0x06, 0x0E, 0x2B, 0x34, 0x02, 0x05, 0x01, 0x01)) {
            return MediaKind.VIDEO; // MXF
        }
        if (startsWith(head, 0x1F, 0x07, 0x00)) {
            return MediaKind.VIDEO; // DV
        }
        if (startsWith(head, 0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87, 0x0A)) {
            return MediaKind.IMAGE; // JPEG 2000
        }
        if (startsWith(head, 0xFF, 0x0A) || ascii(head, 0, 12).contains("JXL ")) {
            return MediaKind.IMAGE; // JPEG XL
        }
        if (ascii(head, 0, 4).equals("8BPS")) {
            return MediaKind.IMAGE; // Photoshop
        }
        if (ascii(head, 0, 4).equals("DDS ")) {
            return MediaKind.IMAGE;
        }
        if (ascii(head, 0, 4).equals("qoif")) {
            return MediaKind.IMAGE;
        }
        if (ascii(head, 0, 4).equals("FLIF")) {
            return MediaKind.IMAGE;
        }
        if (startsWith(head, 0x42, 0x50, 0x47, 0xFB)) {
            return MediaKind.IMAGE; // BPG
        }
        if (startsWith(head, 0x76, 0x2F, 0x31, 0x01)) {
            return MediaKind.IMAGE; // OpenEXR
        }
        if (ascii(head, 0, 4).equals("DKIF")) {
            return MediaKind.VIDEO; // IVF (VP8/VP9/AV1)
        }
        if (startsWith(head, 0x00, 0x00, 0x00, 0x01) || startsWith(head, 0x00, 0x00, 0x01)) {
            return MediaKind.VIDEO; // H.264/H.265 Annex B
        }
        if (ascii(head, 0, 4).equals("BRAW")) {
            return MediaKind.VIDEO;
        }

        if (ascii(head, 4, 4).equals("ftyp")) {
            return ftypKind(head);
        }

        String text = new String(head, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        if (text.contains("<svg") || text.startsWith("<?xml") && text.contains("<svg")) {
            return MediaKind.IMAGE;
        }
        return null;
    }

    private static MediaKind ftypKind(byte[] head) {
        // The major brand is at offset 8; compatible brands follow. Any known
        // image brand wins, otherwise ISO base media is treated as video.
        int end = Math.min(head.length - 3, 64);
        for (int offset = 8; offset < end; offset += 4) {
            String brand = ascii(head, offset, 4);
            if (IMAGE_BRANDS.contains(brand)) {
                return MediaKind.IMAGE;
            }
        }
        return MediaKind.VIDEO;
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != (prefix[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] data, int offset, int length) {
        if (offset < 0 || offset + length > data.length) {
            return "";
        }
        return new String(Arrays.copyOfRange(data, offset, offset + length), StandardCharsets.ISO_8859_1);
    }
}
