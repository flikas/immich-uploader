package io.github.immichuploader.util;

import java.net.InetAddress;

public final class DeviceId {
    private DeviceId() {
    }

    public static String defaultValue() {
        String host = "unknown-host";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            // Keep the fallback.
        }
        String sanitized = host.replaceAll("[^A-Za-z0-9._-]", "-");
        String value = "immich-uploader-" + sanitized;
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return defaultValue();
        }
        String trimmed = value.trim();
        return trimmed.length() <= 255 ? trimmed : trimmed.substring(0, 255);
    }
}
