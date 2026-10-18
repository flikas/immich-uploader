package io.github.immichuploader.state;

import java.io.IOException;

public final class RecordLockedException extends IOException {
    public RecordLockedException(String message) {
        super(message);
    }
}
