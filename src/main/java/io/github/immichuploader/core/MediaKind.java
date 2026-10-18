package io.github.immichuploader.core;

public enum MediaKind {
    IMAGE,
    VIDEO,
    OTHER;

    public String label() {
        return switch (this) {
            case IMAGE -> "图片";
            case VIDEO -> "视频";
            case OTHER -> "其他";
        };
    }
}
