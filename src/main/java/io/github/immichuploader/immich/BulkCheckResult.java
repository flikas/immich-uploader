package io.github.immichuploader.immich;

public record BulkCheckResult(
        String id,
        String action,
        String reason,
        String assetId,
        boolean trashed
) {
    public boolean accepted() {
        return "accept".equalsIgnoreCase(action);
    }

    public boolean duplicate() {
        return "reject".equalsIgnoreCase(action) && "duplicate".equalsIgnoreCase(reason);
    }
}
