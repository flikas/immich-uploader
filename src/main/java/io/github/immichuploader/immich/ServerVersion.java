package io.github.immichuploader.immich;

import java.util.Locale;

public record ServerVersion(Integer major, Integer minor, Integer patch, String raw) {
    public String display() {
        if (major != null && minor != null && patch != null) {
            return String.format(Locale.ROOT, "%d.%d.%d", major, minor, patch);
        }
        if (raw != null && !raw.isBlank()) {
            return raw;
        }
        return "未知";
    }
}
