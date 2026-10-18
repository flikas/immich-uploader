package io.github.immichuploader.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class MediaScanner {
    private static final Logger LOG = LogManager.getLogger(MediaScanner.class);
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).startsWith("windows");

    private MediaScanner() {
    }

    public static MediaScanResult scan(
            List<Path> roots,
            boolean recursive,
            boolean followLinks,
            Set<String> extensions,
            boolean useMagic,
            boolean allFiles,
            MediaFilter filter,
            LivePhotoMode livePhotoMode
    ) {
        return scan(roots, recursive, followLinks, extensions, useMagic, allFiles,
                filter, livePhotoMode, progress -> {
                });
    }

    public static MediaScanResult scan(
            List<Path> roots,
            boolean recursive,
            boolean followLinks,
            Set<String> extensions,
            boolean useMagic,
            boolean allFiles,
            MediaFilter filter,
            LivePhotoMode livePhotoMode,
            Consumer<ScanProgress> progress
    ) {
        Map<String, ScannedMedia> media = new LinkedHashMap<>();
        List<MediaScanResult.Issue> issues = new ArrayList<>();
        List<SkippedFile> skipped = new ArrayList<>();
        Consumer<ScanProgress> report = progress == null ? ignored -> {
        } : progress;
        int[] visited = {0};
        Set<FileVisitOption> visitOptions = followLinks
                ? EnumSet.of(FileVisitOption.FOLLOW_LINKS)
                : EnumSet.noneOf(FileVisitOption.class);
        boolean customExtensions = !extensions.equals(MediaExtensions.DEFAULT);
        boolean includeUnknown = allFiles || customExtensions;

        for (Path root : roots) {
            Path absoluteRoot = root.toAbsolutePath().normalize();
            if (!Files.exists(absoluteRoot)) {
                addIssue(issues, absoluteRoot, "目录不存在");
                continue;
            }
            if (!Files.isDirectory(absoluteRoot)) {
                addIssue(issues, absoluteRoot, "不是目录");
                continue;
            }
            if (!Files.isReadable(absoluteRoot)) {
                addIssue(issues, absoluteRoot, "目录不可读");
                continue;
            }

            try {
                Files.walkFileTree(absoluteRoot, visitOptions, Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (!recursive && !dir.equals(absoluteRoot)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!attrs.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }
                        visited[0]++;
                        String extension = MediaExtensions.extensionOf(file.getFileName().toString());
                        Path absolute = file.toAbsolutePath().normalize();
                        if (!allFiles && !extension.isEmpty() && !extensions.contains(extension)) {
                            skipped.add(new SkippedFile(absolute, MediaKind.OTHER, "SCAN",
                                    "扩展名不在允许清单: ." + extension));
                            report.accept(new ScanProgress(visited[0], media.size(), skipped.size(), issues.size()));
                            return FileVisitResult.CONTINUE;
                        }
                        if (!Files.isReadable(file)) {
                            addIssue(issues, absolute, "文件不可读");
                            skipped.add(new SkippedFile(absolute, MediaKind.OTHER, "SCAN", "文件不可读"));
                            report.accept(new ScanProgress(visited[0], media.size(), skipped.size(), issues.size()));
                            return FileVisitResult.CONTINUE;
                        }

                        MediaKind kind = MediaDetector.detect(file, useMagic, includeUnknown);
                        if (kind == null) {
                            if (includeUnknown && (allFiles || !extension.isEmpty())) {
                                kind = MediaKind.OTHER;
                            } else {
                                String reason;
                                if (MediaExtensions.isAmbiguous(extension)) {
                                    reason = "扩展名有歧义，内容未识别为图片/视频: ." + extension;
                                } else if (extension.isEmpty()) {
                                    reason = "文件没有扩展名，且未能识别为图片/视频";
                                } else {
                                    reason = "无法识别为图片/视频: ." + extension;
                                }
                                skipped.add(new SkippedFile(absolute, MediaKind.OTHER, "SCAN", reason));
                                report.accept(new ScanProgress(visited[0], media.size(), skipped.size(), issues.size()));
                                return FileVisitResult.CONTINUE;
                            }
                        }
                        if (!filter.accepts(kind)) {
                            String only = filter == MediaFilter.IMAGE ? "图片" : "视频";
                            skipped.add(new SkippedFile(absolute, kind, "SCAN",
                                    "媒体类型过滤: " + kind.label() + "（当前仅" + only + "）"));
                            report.accept(new ScanProgress(visited[0], media.size(), skipped.size(), issues.size()));
                            return FileVisitResult.CONTINUE;
                        }

                        Path real = realPath(file, issues);
                        long size = attrs.size();
                        long modified = attrs.lastModifiedTime().toMillis();
                        long created = attrs.creationTime().toMillis();
                        if (created <= 0 || created > System.currentTimeMillis() + 86_400_000L) {
                            created = modified;
                        }
                        String key = key(real);
                        media.putIfAbsent(key, new ScannedMedia(
                                real, size, modified, created, kind, MediaRole.NORMAL, ""));
                        report.accept(new ScanProgress(visited[0], media.size(), skipped.size(), issues.size()));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        String message = exc instanceof FileSystemLoopException
                                ? "符号链接循环: " + exc.getMessage()
                                : exc.getMessage();
                        addIssue(issues, file.toAbsolutePath().normalize(),
                                message == null ? exc.toString() : message);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException e) {
                addIssue(issues, absoluteRoot, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }

        List<ScannedMedia> raw = new ArrayList<>(media.values());
        raw.sort(Comparator.comparing(item -> item.path().toString(), String.CASE_INSENSITIVE_ORDER));
        LivePhotoPairer.Result paired = LivePhotoPairer.pair(raw, livePhotoMode);
        List<String> warnings = new ArrayList<>(paired.warnings());
        for (String warning : warnings) {
            LOG.warn("Live Photo 配对警告: {}", warning);
        }
        report.accept(new ScanProgress(visited[0], paired.media().size(), skipped.size(), issues.size()));
        return new MediaScanResult(paired.media(), paired.pairs(), issues, warnings, skipped, visited[0]);
    }

    private static void addIssue(List<MediaScanResult.Issue> issues, Path path, String message) {
        issues.add(new MediaScanResult.Issue(path, message));
        LOG.error("扫描失败: {}: {}", path, message);
    }

    private static Path realPath(Path file, List<MediaScanResult.Issue> issues) {
        try {
            return file.toRealPath();
        } catch (IOException e) {
            Path fallback = file.toAbsolutePath().normalize();
            addIssue(issues, fallback, "无法解析真实路径: " + e.getMessage());
            return fallback;
        }
    }

    private static String key(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        return WINDOWS ? value.toLowerCase(Locale.ROOT) : value;
    }
}
