package io.github.immichuploader.state;

import io.github.immichuploader.core.MediaExtensions;
import io.github.immichuploader.core.MediaKind;
import io.github.immichuploader.core.MediaRole;
import io.github.immichuploader.util.Tsv;

import java.nio.file.Path;
import java.util.Locale;

/**
 * One successfully processed file. The record is append-only; on resume the
 * last entry for a path wins.
 */
public record RecordEntry(
        long uploadedAtMillis,
        RecordStatus status,
        MediaKind kind,
        MediaRole role,
        String pairKey,
        Path path,
        long size,
        long modifiedAtMillis,
        String sha1Base64,
        String assetId,
        String livePhotoVideoId,
        String server,
        String action,
        String message
) {
    public RecordEntry {
        status = status == null ? RecordStatus.UPLOADED : status;
        kind = kind == null ? MediaKind.OTHER : kind;
        role = role == null ? MediaRole.NORMAL : role;
        pairKey = pairKey == null ? "" : pairKey;
        sha1Base64 = sha1Base64 == null ? "" : sha1Base64;
        assetId = assetId == null ? "" : assetId;
        livePhotoVideoId = livePhotoVideoId == null ? "" : livePhotoVideoId;
        server = server == null ? "" : server;
        action = action == null ? "" : action;
        message = message == null ? "" : message;
    }

    public boolean complete() {
        if (!status.isSuccess() || assetId.isBlank()) {
            return false;
        }
        return role != MediaRole.LIVE_IMAGE || !livePhotoVideoId.isBlank();
    }

    public RecordEntry withLivePhotoVideoId(String videoId) {
        return new RecordEntry(uploadedAtMillis, status, kind, role, pairKey, path, size,
                modifiedAtMillis, sha1Base64, assetId, videoId, server, action, message);
    }

    public String toTsv() {
        return Tsv.join(
                Long.toString(uploadedAtMillis),
                status.name(),
                kind.name(),
                role.name(),
                pairKey,
                path.toAbsolutePath().normalize().toString(),
                Long.toString(size),
                Long.toString(modifiedAtMillis),
                sha1Base64,
                assetId,
                livePhotoVideoId,
                server,
                action,
                message
        );
    }

    public static RecordEntry fromTsv(String line) {
        String[] fields = Tsv.split(line);
        if (fields.length >= 14) {
            return parseV2(fields);
        }
        if (fields.length >= 10) {
            return parseV1(fields);
        }
        throw new IllegalArgumentException("记录列数不足: " + fields.length);
    }

    private static RecordEntry parseV2(String[] fields) {
        try {
            return new RecordEntry(
                    Long.parseLong(fields[0]),
                    RecordStatus.valueOf(fields[1].toUpperCase(Locale.ROOT)),
                    MediaKind.valueOf(fields[2].toUpperCase(Locale.ROOT)),
                    MediaRole.valueOf(fields[3].toUpperCase(Locale.ROOT)),
                    Tsv.unescape(fields[4]),
                    Path.of(Tsv.unescape(fields[5])),
                    Long.parseLong(fields[6]),
                    Long.parseLong(fields[7]),
                    Tsv.unescape(fields[8]),
                    Tsv.unescape(fields[9]),
                    Tsv.unescape(fields[10]),
                    Tsv.unescape(fields[11]),
                    Tsv.unescape(fields[12]),
                    Tsv.unescape(fields[13])
            );
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("无法解析 v2 记录: " + e.getMessage(), e);
        }
    }

    private static RecordEntry parseV1(String[] fields) {
        try {
            Path path = Path.of(Tsv.unescape(fields[2]));
            String extension = MediaExtensions.extensionOf(path.getFileName().toString());
            MediaKind kind = MediaExtensions.kindOf(extension);
            return new RecordEntry(
                    Long.parseLong(fields[0]),
                    RecordStatus.valueOf(fields[1].toUpperCase(Locale.ROOT)),
                    kind == null ? MediaKind.OTHER : kind,
                    MediaRole.NORMAL,
                    "",
                    path,
                    Long.parseLong(fields[3]),
                    Long.parseLong(fields[4]),
                    Tsv.unescape(fields[5]),
                    Tsv.unescape(fields[6]),
                    "",
                    Tsv.unescape(fields[7]),
                    Tsv.unescape(fields[8]),
                    Tsv.unescape(fields[9])
            );
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("无法解析 v1 记录: " + e.getMessage(), e);
        }
    }
}
