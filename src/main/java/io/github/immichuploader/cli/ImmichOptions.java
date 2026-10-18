package io.github.immichuploader.cli;

import io.github.immichuploader.UserError;
import io.github.immichuploader.immich.ImmichClient;
import io.github.immichuploader.util.ServerUrl;
import picocli.CommandLine.Option;

public final class ImmichOptions {
    @Option(names = {"-s", "--server"}, paramLabel = "URL",
            description = "Immich 地址，例如 http://192.168.1.10:2283；也可用 IMMICH_SERVER_URL")
    private String server;

    @Option(names = {"-k", "--api-key"}, paramLabel = "KEY",
            description = "Immich API Key；也可用 IMMICH_API_KEY")
    private String apiKey;

    @Option(names = "--insecure", description = "信任所有 TLS 证书（仅测试环境）")
    private boolean insecure;

    @Option(names = "--connect-timeout", defaultValue = "15",
            description = "连接超时秒数（默认 ${DEFAULT-VALUE}）")
    private int connectTimeoutSeconds;

    @Option(names = "--request-timeout", defaultValue = "300",
            description = "请求超时秒数（默认 ${DEFAULT-VALUE}）")
    private int requestTimeoutSeconds;

    @Option(names = "--max-retries", defaultValue = "3",
            description = "429/5xx/网络错误最大重试次数（默认 ${DEFAULT-VALUE}）")
    private int maxRetries;

    public String rawServer() {
        return firstNonBlank(server, System.getenv("IMMICH_SERVER_URL"));
    }

    public String resolveServer() {
        String value = rawServer();
        if (value == null) {
            throw new UserError("缺少 Immich 地址：请使用 --server 或环境变量 IMMICH_SERVER_URL");
        }
        try {
            return ServerUrl.normalize(value);
        } catch (IllegalArgumentException e) {
            throw new UserError("Immich 地址无效: " + e.getMessage());
        }
    }

    public String resolveApiKey() {
        String value = firstNonBlank(apiKey, System.getenv("IMMICH_API_KEY"));
        if (value == null) {
            throw new UserError("缺少 API Key：请使用 --api-key 或环境变量 IMMICH_API_KEY");
        }
        return value;
    }

    public ImmichClient buildClient() {
        if (connectTimeoutSeconds < 1 || requestTimeoutSeconds < 1) {
            throw new UserError("超时时间必须大于 0");
        }
        if (maxRetries < 0) {
            throw new UserError("--max-retries 不能为负数");
        }
        return new ImmichClient(
                resolveServer(),
                resolveApiKey(),
                connectTimeoutSeconds,
                requestTimeoutSeconds,
                maxRetries,
                insecure
        );
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
