package io.github.immichuploader.core;

public enum LivePhotoMode {
    AUTO,
    STRICT,
    OFF;

    public boolean enabled() {
        return this != OFF;
    }
}
