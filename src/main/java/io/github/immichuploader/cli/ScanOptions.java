package io.github.immichuploader.cli;

import io.github.immichuploader.UserError;
import io.github.immichuploader.core.LivePhotoMode;
import io.github.immichuploader.core.MediaExtensions;
import io.github.immichuploader.core.MediaFilter;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ScanOptions {
    private static final String DEFAULT_EXTENSIONS = "内置清单";
    @Option(names = {"-d", "--dir", "--folder"}, paramLabel = "DIR",
            description = "图片/视频目录，可重复指定")
    private List<Path> directories = new ArrayList<>();

    @Parameters(paramLabel = "DIR", arity = "0..*", description = "图片/视频目录（可多个）")
    private List<Path> positionalDirectories = new ArrayList<>();

    @Option(names = "--no-recursive", description = "不递归子目录")
    private boolean noRecursive;

    @Option(names = "--follow-links", description = "跟随符号链接")
    private boolean followLinks;

    @Option(names = "--extensions", split = "[,;]", defaultValue = DEFAULT_EXTENSIONS,
            description = "覆盖默认扩展名清单（默认 ${DEFAULT-VALUE}），逗号或分号分隔")
    private List<String> extensions = new ArrayList<>();

    @Option(names = "--no-all-files",
            description = "关闭默认的全文件模式，只扫描允许清单内或可识别为图片/视频的文件")
    private boolean noAllFiles;

    @Option(names = "--no-magic", description = "关闭文件头识别，只按扩展名/MIME 判断")
    private boolean noMagic;

    @Option(names = "--media", defaultValue = "ALL",
            description = "媒体过滤: ALL / IMAGE / VIDEO（默认 ${DEFAULT-VALUE}）")
    private MediaFilter mediaFilter;

    @Option(names = "--live-photo", defaultValue = "AUTO",
            description = "Live Photo 配对: AUTO / STRICT / OFF（默认 ${DEFAULT-VALUE}）")
    private LivePhotoMode livePhotoMode;

    public List<Path> resolveDirectories() {
        List<Path> all = new ArrayList<>(directories);
        all.addAll(positionalDirectories);
        if (all.isEmpty()) {
            throw new UserError("缺少媒体目录：请使用 --dir 或位置参数指定一个或多个目录");
        }
        Map<String, Path> unique = new LinkedHashMap<>();
        for (Path path : all) {
            Path absolute = path.toAbsolutePath().normalize();
            if (!Files.exists(absolute)) {
                throw new UserError("目录不存在: " + absolute);
            }
            if (!Files.isDirectory(absolute)) {
                throw new UserError("不是目录: " + absolute);
            }
            String key = absolute.toString();
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                key = key.toLowerCase(Locale.ROOT);
            }
            unique.putIfAbsent(key, absolute);
        }
        return List.copyOf(unique.values());
    }

    public boolean recursive() {
        return !noRecursive;
    }

    public boolean followLinks() {
        return followLinks;
    }

    public boolean allFiles() {
        // 全文件模式默认开启；显式扩展名过滤或 --no-all-files 会关闭它。
        return !noAllFiles && !customExtensions();
    }

    public boolean magicDetection() {
        return !noMagic;
    }

    public MediaFilter mediaFilter() {
        return mediaFilter == null ? MediaFilter.ALL : mediaFilter;
    }

    public LivePhotoMode livePhotoMode() {
        return livePhotoMode == null ? LivePhotoMode.AUTO : livePhotoMode;
    }

    public Set<String> extensionSet() {
        return customExtensions() ? MediaExtensions.normalize(extensions) : MediaExtensions.DEFAULT;
    }

    private boolean customExtensions() {
        return !extensions.isEmpty()
                && !(extensions.size() == 1 && DEFAULT_EXTENSIONS.equals(extensions.get(0)));
    }
}
