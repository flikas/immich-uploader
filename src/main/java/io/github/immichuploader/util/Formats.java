package io.github.immichuploader.util;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class Formats {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private Formats() {
    }

    public static String dateTime(long epochMillis) {
        return DATE_TIME.format(Instant.ofEpochMilli(epochMillis));
    }

    public static String duration(long millis) {
        if (millis < 0) {
            millis = 0;
        }
        Duration duration = Duration.ofMillis(millis);
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    public static String eta(long remainingMillis) {
        return duration(remainingMillis);
    }

    public static String bytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    public static String rate(double bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            return "-";
        }
        return bytes((long) bytesPerSecond) + "/s";
    }

    public static String percent(long completed, long total) {
        if (total <= 0) {
            return "100%";
        }
        long percent = Math.min(100, Math.max(0, completed * 100 / total));
        return percent + "%";
    }

    public static String shortPath(Path path) {
        return shortPath(path, 60);
    }

    public static String shortPath(Path path, int maxLength) {
        if (path == null) {
            return "";
        }
        String full = path.toString();
        if (maxLength <= 0 || full.length() <= maxLength) {
            return full;
        }
        if (maxLength <= 3) {
            return full.substring(full.length() - maxLength);
        }
        char separator = full.indexOf('\\') >= 0 ? '\\' : '/';
        int keep = maxLength - 4;
        int start = full.length() - keep;
        int separatorAt = full.indexOf(separator, start);
        String suffix = separatorAt >= 0 && separatorAt + 1 < full.length()
                ? full.substring(separatorAt + 1)
                : full.substring(start);
        String shortened = "..." + separator + suffix;
        return shortened.length() <= maxLength
                ? shortened
                : shortened.substring(0, maxLength);
    }
}
