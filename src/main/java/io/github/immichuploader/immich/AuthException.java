package io.github.immichuploader.immich;

import io.github.immichuploader.UserError;

public final class AuthException extends UserError {
    private final int statusCode;

    public AuthException(int statusCode) {
        super("Immich 认证失败 (HTTP " + statusCode
                + ")。请检查 --server、--api-key 或环境变量 IMMICH_API_KEY，并确认该 Key 有上传权限。");
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
