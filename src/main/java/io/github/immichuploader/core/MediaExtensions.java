package io.github.immichuploader.core;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class MediaExtensions {
    public static final Set<String> IMAGE = Set.of(
            "jpg", "jpeg", "jpe", "jfif", "jif", "jfi",
            "png", "apng", "gif", "webp", "bmp", "dib", "tif", "tiff",
            "heic", "heics", "heif", "heifs", "hif", "avif", "avifs", "jxl",
            "jp2", "j2k", "j2c", "jpc", "jph", "jpf", "jpx", "jpm", "mj2",
            "tga", "icb", "vda", "vst", "pcx", "pcc", "ppm", "pgm", "pbm", "pnm", "pam", "pfm",
            "xbm", "xpm", "ico", "cur", "ani", "dds", "hdr", "rgbe", "exr", "sxr", "mxr",
            "dpx", "cin", "psd", "psb", "svg", "svgz", "mpo", "jps", "qoi", "flif", "bpg",
            "wdp", "hdp", "jxr", "ktx", "ktx2", "astc", "thm", "dcm", "dicom", "xcf",
            "dng", "cr2", "cr3", "crw", "crx", "nef", "nrw", "arw", "srf", "sr2", "sr1",
            "raf", "rw2", "rwl", "raw", "orf", "pef", "srw", "x3f", "3fr", "fff", "erf",
            "k25", "kdc", "dcr", "mef", "mos", "mrw", "iiq", "cap", "eip", "mdc", "pxn", "rwz"
    );

    public static final Set<String> VIDEO = Set.of(
            "mp4", "m4v", "mov", "qt", "avi", "divx", "xvid", "mkv", "webm", "wmv", "asf",
            "flv", "f4v", "mpg", "mpeg", "mpe", "m1v", "m2v", "mpv", "m2p",
            "mjpg", "mjpeg", "mjp", "ts", "mts", "m2ts", "m2t", "vob", "ogv", "ogm",
            "3gp", "3g2", "3gp2", "3gpp", "3gpp2", "rm", "rmvb", "dv", "hdv", "mod", "tod",
            "mxf", "nut", "y4m", "amv", "mtv", "svi", "insv", "insp", "lrv", "braw", "crm", "ari",
            "r3d", "h264", "h265", "hevc", "av1", "vp8", "vp9", "mpeg1", "mpeg2", "mpeg4", "264", "265"
    );

    /**
     * These extensions are also used by non-media files (for example .ts is
     * TypeScript). They are accepted only when a media signature is found.
     */
    public static final Set<String> AMBIGUOUS = Set.of(
            "ts", "raw", "264", "265", "h264", "h265", "hevc", "av1", "vp8", "vp9",
            "mpeg1", "mpeg2", "mpeg4", "yuv", "rgb"
    );

    public static final Set<String> DEFAULT;

    static {
        LinkedHashSet<String> all = new LinkedHashSet<>(IMAGE);
        all.addAll(VIDEO);
        DEFAULT = Set.copyOf(all);
    }

    private MediaExtensions() {
    }

    public static Set<String> normalize(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return DEFAULT;
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String part : value.split("[,;\\s]+")) {
                String normalized = part.trim().toLowerCase(Locale.ROOT);
                while (normalized.startsWith(".")) {
                    normalized = normalized.substring(1);
                }
                if (!normalized.isEmpty()) {
                    result.add(normalized);
                }
            }
        }
        return result.isEmpty() ? DEFAULT : Set.copyOf(result);
    }

    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static MediaKind kindOf(String extension) {
        String value = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        if (IMAGE.contains(value)) {
            return MediaKind.IMAGE;
        }
        if (VIDEO.contains(value)) {
            return MediaKind.VIDEO;
        }
        return null;
    }

    public static boolean isAmbiguous(String extension) {
        return extension != null && AMBIGUOUS.contains(extension.toLowerCase(Locale.ROOT));
    }

    public static boolean isLiveImage(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "heic", "heif", "hif", "jpg", "jpeg", "jpe", "jfif" -> true;
            default -> false;
        };
    }

    public static boolean isStrictLiveImage(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "heic", "heif", "hif" -> true;
            default -> false;
        };
    }

    public static boolean isLiveVideo(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "mov", "qt", "mp4", "m4v" -> true;
            default -> false;
        };
    }

    public static boolean isStrictLiveVideo(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "mov", "qt" -> true;
            default -> false;
        };
    }
}
