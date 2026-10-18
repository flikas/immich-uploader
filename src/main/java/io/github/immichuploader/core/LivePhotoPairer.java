package io.github.immichuploader.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LivePhotoPairer {
    private LivePhotoPairer() {
    }

    public static Result pair(List<ScannedMedia> media, LivePhotoMode mode) {
        if (mode == null || mode == LivePhotoMode.OFF) {
            return new Result(List.copyOf(media), List.of(), List.of());
        }

        Map<String, List<ScannedMedia>> groups = new LinkedHashMap<>();
        for (ScannedMedia item : media) {
            if (!isCandidate(item, mode)) {
                continue;
            }
            String key = pairKey(item.path());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
        }

        Map<Path, ScannedMedia> updated = new LinkedHashMap<>();
        for (ScannedMedia item : media) {
            updated.put(item.path(), item);
        }

        List<LivePhotoPair> pairs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, List<ScannedMedia>> entry : groups.entrySet()) {
            List<ScannedMedia> images = entry.getValue().stream()
                    .filter(item -> item.kind() == MediaKind.IMAGE)
                    .toList();
            List<ScannedMedia> videos = entry.getValue().stream()
                    .filter(item -> item.kind() == MediaKind.VIDEO)
                    .toList();

            if (images.isEmpty() || videos.isEmpty()) {
                continue;
            }
            if (images.size() > 1 || videos.size() > 1) {
                warnings.add("Live Photo 候选歧义，未配对: " + entry.getKey()
                        + " (图片=" + images.size() + ", 视频=" + videos.size() + ")");
                continue;
            }

            ScannedMedia image = images.get(0);
            ScannedMedia video = videos.get(0);
            String key = entry.getKey();
            ScannedMedia pairedImage = image.withRole(MediaRole.LIVE_IMAGE, key);
            ScannedMedia pairedVideo = video.withRole(MediaRole.LIVE_VIDEO, key);
            updated.put(image.path(), pairedImage);
            updated.put(video.path(), pairedVideo);
            pairs.add(new LivePhotoPair(key, pairedImage, pairedVideo));
        }

        List<ScannedMedia> result = new ArrayList<>(updated.values());
        result.sort(Comparator.comparing(item -> item.path().toString(), String.CASE_INSENSITIVE_ORDER));
        pairs.sort(Comparator.comparing(pair -> pair.image().path().toString(), String.CASE_INSENSITIVE_ORDER));
        return new Result(List.copyOf(result), List.copyOf(pairs), List.copyOf(warnings));
    }

    private static boolean isCandidate(ScannedMedia item, LivePhotoMode mode) {
        if (item.kind() == MediaKind.IMAGE) {
            String extension = MediaExtensions.extensionOf(item.path().getFileName().toString());
            return mode == LivePhotoMode.STRICT
                    ? MediaExtensions.isStrictLiveImage(extension)
                    : MediaExtensions.isLiveImage(extension);
        }
        if (item.kind() == MediaKind.VIDEO) {
            String extension = MediaExtensions.extensionOf(item.path().getFileName().toString());
            return mode == LivePhotoMode.STRICT
                    ? MediaExtensions.isStrictLiveVideo(extension)
                    : MediaExtensions.isLiveVideo(extension);
        }
        return false;
    }

    private static String pairKey(Path path) {
        Path parent = path.getParent();
        String parentText = parent == null ? "" : parent.toString();
        String filename = path.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String stem = dot < 0 ? filename : filename.substring(0, dot);
        return parentText + "|" + stem.toLowerCase(Locale.ROOT);
    }

    public record Result(
            List<ScannedMedia> media,
            List<LivePhotoPair> pairs,
            List<String> warnings
    ) {
        public Result {
            media = List.copyOf(media);
            pairs = List.copyOf(pairs);
            warnings = List.copyOf(warnings);
        }
    }
}
