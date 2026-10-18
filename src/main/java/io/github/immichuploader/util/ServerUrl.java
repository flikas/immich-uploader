package io.github.immichuploader.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Normalizes the Immich base URL. The CLI accepts both
 * {@code http://host:2283} and {@code http://host:2283/api}; the API client
 * always works with a base that ends in {@code /api}.
 */
public final class ServerUrl {
    private ServerUrl() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("服务器地址不能为空");
        }
        String value = raw.trim();
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("服务器地址格式错误: " + value);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("服务器地址必须以 http:// 或 https:// 开头");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("服务器地址缺少主机名");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("服务器地址不能包含用户名或密码");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("服务器地址不能包含查询参数或片段");
        }

        String path = uri.getPath() == null ? "" : uri.getPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) {
            path = "/api";
        } else if (!path.endsWith("/api")) {
            path = path + "/api";
        }

        try {
            return new URI(
                    scheme.toLowerCase(Locale.ROOT),
                    null,
                    uri.getHost(),
                    uri.getPort(),
                    path,
                    null,
                    null
            ).toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("无法规范化服务器地址: " + value, e);
        }
    }

    public static String resolve(String raw) {
        return normalize(raw);
    }
}
