package io.github.immichuploader.util;

import java.util.Locale;

public final class Html {
    private Html() {
    }

    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    public static String attr(String value) {
        return escape(value);
    }

    public static String lowerAttr(String value) {
        return escape(value == null ? "" : value.toLowerCase(Locale.ROOT));
    }
}
