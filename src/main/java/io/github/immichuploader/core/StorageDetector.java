package io.github.immichuploader.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort OS metadata probe. If anything is unknown we deliberately fall
 * back to one reader; no media files are read for detection.
 */
public final class StorageDetector {
    private static final Logger LOG = LogManager.getLogger(StorageDetector.class);

    private StorageDetector() {
    }

    public static int readerThreads(List<Path> roots, int override) {
        if (override > 0) {
            return Math.min(8, override);
        }
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).startsWith("windows");
        if (!windows) {
            return 1;
        }
        for (Path root : roots) {
            String type = windowsMediaType(root);
            if (!"SSD".equals(type)) {
                LOG.info("存储识别: {} -> {}", root, type == null ? "unknown" : type);
                return 1;
            }
        }
        return 4;
    }

    private static String windowsMediaType(Path root) {
        try {
            Path abs = root.toAbsolutePath().normalize();
            Path fileRoot = abs.getRoot();
            if (fileRoot == null) {
                return null;
            }
            String drive = fileRoot.toString().replace("\\", "").replace("/", "");
            if (drive.length() != 1) {
                return null;
            }
            String script = "try {"
                    + "$p=Get-Partition -DriveLetter " + drive + " -ErrorAction Stop;"
                    + "$d=Get-Disk -Number $p.DiskNumber -ErrorAction Stop;"
                    + "(Get-PhysicalDisk -DeviceNumber $d.Number -ErrorAction Stop).MediaType"
                    + "} catch { '' }";
            Process process = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (line == null) {
                    return null;
                }
                String value = line.trim().toUpperCase(Locale.ROOT);
                if (value.contains("SSD")) {
                    return "SSD";
                }
                if (value.contains("HDD")) {
                    return "HDD";
                }
            }
        } catch (Exception e) {
            LOG.debug("存储识别失败: {}", root, e);
        }
        return null;
    }
}
