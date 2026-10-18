package io.github.immichuploader.state;

public enum RecordStatus {
    UPLOADED,
    DUPLICATE;

    public boolean isSuccess() {
        return this == UPLOADED || this == DUPLICATE;
    }
}
