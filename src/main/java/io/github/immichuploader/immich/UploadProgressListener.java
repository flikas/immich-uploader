package io.github.immichuploader.immich;

/**
 * Receives cumulative bytes written for one asset upload.
 * The callback can run on the HTTP client thread and must be cheap/thread-safe.
 */
@FunctionalInterface
public interface UploadProgressListener {
    void onProgress(long bytesWritten, long totalBytes);
}
