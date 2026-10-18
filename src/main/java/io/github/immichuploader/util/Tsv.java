package io.github.immichuploader.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal escaping for tab-separated record files. Paths on Windows can contain
 * almost any character except NUL, so tabs/newlines must be escaped even though
 * they are uncommon.
 */
public final class Tsv {
    private Tsv() {
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    public static String unescape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case '\\' -> out.append('\\');
                case 't' -> out.append('\t');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                default -> out.append('\\').append(next);
            }
        }
        return out.toString();
    }

    public static String join(String... fields) {
        List<String> escaped = new ArrayList<>(fields.length);
        for (String field : fields) {
            escaped.add(escape(field));
        }
        return String.join("\t", escaped);
    }

    public static String[] split(String line) {
        return line.split("\t", -1);
    }
}
