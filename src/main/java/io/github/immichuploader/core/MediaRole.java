package io.github.immichuploader.core;

public enum MediaRole {
    NORMAL,
    LIVE_IMAGE,
    LIVE_VIDEO;

    public String label() {
        return switch (this) {
            case NORMAL -> "普通";
            case LIVE_IMAGE -> "Live 图片";
            case LIVE_VIDEO -> "Live 视频";
        };
    }
}
