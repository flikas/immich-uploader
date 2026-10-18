package io.github.immichuploader.immich;

import java.util.Locale;

public class ImmichApiException extends RuntimeException {
    private final int statusCode;
    private final String responseBody;

    public ImmichApiException(String message, int statusCode, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? "" : responseBody;
    }

    public ImmichApiException(String message, int statusCode, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? "" : responseBody;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }

    public boolean endpointMissing() {
        return statusCode == 404 || statusCode == 405;
    }

    public boolean unsupportedFormat() {
        if (statusCode != 400 && statusCode != 415) {
            return false;
        }
        String text = (getMessage() + " " + responseBody).toLowerCase(Locale.ROOT);
        return text.contains("unsupported")
                || text.contains("not supported")
                || text.contains("invalid file type")
                || text.contains("不支持");
    }

    public boolean missingPermission(String permission) {
        if (statusCode != 403 || permission == null || permission.isBlank()) {
            return false;
        }
        String text = (getMessage() + " " + responseBody).toLowerCase(Locale.ROOT);
        return text.contains(permission.toLowerCase(Locale.ROOT));
    }

}
