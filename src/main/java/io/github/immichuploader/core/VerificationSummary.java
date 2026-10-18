package io.github.immichuploader.core;

import io.github.immichuploader.state.RecordEntry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class VerificationSummary {
    public long startedAt = System.currentTimeMillis();
    public long finishedAt;
    public int total;
    public int matched;
    public int differentAsset;
    public int trashed;
    public int missing;
    public int localChangedServerOld;
    public int localChangedNewExists;
    public int localChangedMissing;
    public int linkMissing;
    public int linkMismatch;
    public int videoMissing;
    public int fileMissing;
    public int hashErrors;
    public int apiErrors;

    private final List<Item> items = new CopyOnWriteArrayList<>();

    public void add(Item item) {
        items.add(item);
        total++;
        switch (item.status()) {
            case MATCHED -> matched++;
            case MATCHED_DIFFERENT_ASSET -> differentAsset++;
            case TRASHED -> trashed++;
            case MISSING -> missing++;
            case LOCAL_CHANGED_SERVER_OLD -> localChangedServerOld++;
            case LOCAL_CHANGED_NEW_EXISTS -> localChangedNewExists++;
            case LOCAL_CHANGED_MISSING -> localChangedMissing++;
            case LINK_MISSING -> linkMissing++;
            case LINK_MISMATCH -> linkMismatch++;
            case VIDEO_MISSING -> videoMissing++;
            case FILE_MISSING -> fileMissing++;
            case HASH_ERROR -> hashErrors++;
            case API_ERROR -> apiErrors++;
        }
    }

    public List<Item> items() {
        return List.copyOf(items);
    }

    public long durationMillis() {
        return Math.max(0, (finishedAt == 0 ? System.currentTimeMillis() : finishedAt) - startedAt);
    }

    public boolean ok(boolean strict) {
        return ok(strict, false);
    }

    /**
     * Same as {@link #ok(boolean)}, but ignores missing Live Photo links.
     * Used when the upload already recorded a non-fatal link failure.
     */
    public boolean okIgnoringLinkMissing(boolean strict) {
        return ok(strict, true);
    }

    private boolean ok(boolean strict, boolean ignoreLinkMissing) {
        boolean baseline = missing == 0
                && localChangedMissing == 0
                && (ignoreLinkMissing || linkMissing == 0)
                && linkMismatch == 0
                && videoMissing == 0
                && fileMissing == 0
                && hashErrors == 0
                && apiErrors == 0;
        if (!strict) {
            return baseline;
        }
        return baseline
                && differentAsset == 0
                && trashed == 0
                && localChangedServerOld == 0
                && localChangedNewExists == 0;
    }

    public int exitCode(boolean strict) {
        return ok(strict) ? 0 : 2;
    }

    public enum Status {
        MATCHED,
        MATCHED_DIFFERENT_ASSET,
        TRASHED,
        MISSING,
        LOCAL_CHANGED_SERVER_OLD,
        LOCAL_CHANGED_NEW_EXISTS,
        LOCAL_CHANGED_MISSING,
        LINK_MISSING,
        LINK_MISMATCH,
        VIDEO_MISSING,
        FILE_MISSING,
        HASH_ERROR,
        API_ERROR
    }

    public record Item(
            RecordEntry record,
            Status status,
            String actualSha1,
            String serverAssetId,
            String note
    ) {
        public Item {
            actualSha1 = actualSha1 == null ? "" : actualSha1;
            serverAssetId = serverAssetId == null ? "" : serverAssetId;
            note = note == null ? "" : note;
        }
    }
}
