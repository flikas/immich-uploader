package io.github.immichuploader.core;

public enum MediaFilter {
    ALL,
    IMAGE,
    VIDEO;

    public boolean accepts(MediaKind kind) {
        return switch (this) {
            case ALL -> true;
            case IMAGE -> kind == MediaKind.IMAGE;
            case VIDEO -> kind == MediaKind.VIDEO;
        };
    }
}
