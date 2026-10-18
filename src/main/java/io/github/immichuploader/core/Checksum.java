package io.github.immichuploader.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.LongConsumer;

public final class Checksum {
    private static final int BUFFER_SIZE = 1024 * 1024;

    private Checksum() {
    }

    public static String sha1Base64(Path file) throws IOException {
        return sha1Base64(file, null);
    }

    public static String sha1Base64(Path file, LongConsumer progress) throws IOException {
        return Base64.getEncoder().encodeToString(sha1Bytes(file, progress));
    }

    public static String sha1Hex(Path file) throws IOException {
        return HexFormat.of().formatHex(sha1Bytes(file));
    }

    public static String sha1Base64(byte[] data) {
        return Base64.getEncoder().encodeToString(digest(data));
    }

    public static String sha1Hex(byte[] data) {
        return HexFormat.of().formatHex(digest(data));
    }

    public static String hexFromBase64(String base64) {
        if (base64 == null || base64.isBlank()) {
            return "";
        }
        return HexFormat.of().formatHex(Base64.getDecoder().decode(base64));
    }

    private static byte[] sha1Bytes(Path file) throws IOException {
        return sha1Bytes(file, null);
    }

    private static byte[] sha1Bytes(Path file, LongConsumer progress) throws IOException {
        MessageDigest digest = newSha1();
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                total += read;
                if (progress != null) {
                    progress.accept(total);
                }
            }
        }
        return digest.digest();
    }

    private static byte[] digest(byte[] data) {
        MessageDigest digest = newSha1();
        return digest.digest(data);
    }

    private static MessageDigest newSha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-1", e);
        }
    }
}
