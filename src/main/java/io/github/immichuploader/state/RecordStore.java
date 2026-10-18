package io.github.immichuploader.state;

import io.github.immichuploader.core.MediaRole;
import io.github.immichuploader.core.ScannedMedia;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Append-only, line-oriented upload record. It doubles as the resume index and
 * as a human-readable audit log.
 *
 * <p>The file is locked for the lifetime of the store so two uploader runs
 * cannot corrupt it. Every successful append is flushed to disk before the
 * method returns, which keeps resume state durable across a crash.</p>
 */
public final class RecordStore implements AutoCloseable {
    public static final String HEADER = "# immich-uploader record v2";
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).startsWith("windows");

    private final Path recordFile;
    private final FileChannel channel;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final String server;
    private final Object writeMutex = new Object();
    private final Map<String, RecordEntry> latest = new HashMap<>();
    private int malformedLines;
    private int totalLines;

    private RecordStore(
            Path recordFile,
            FileChannel channel,
            FileChannel lockChannel,
            FileLock lock,
            String server
    ) {
        this.recordFile = recordFile;
        this.channel = channel;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.server = server == null ? "" : server;
    }

    public static RecordStore open(Path recordFile, String server) throws IOException {
        Path absolute = recordFile.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path lockFile = absolute.resolveSibling(absolute.getFileName() + ".lock");

        FileChannel lockChannel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock fileLock;
        try {
            fileLock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            closeQuietly(lockChannel);
            throw new RecordLockedException("记录文件已被另一个进程或线程占用: " + absolute);
        }
        if (fileLock == null) {
            closeQuietly(lockChannel);
            throw new RecordLockedException("记录文件已被另一个进程占用: " + absolute);
        }

        FileChannel data = null;
        try {
            data = FileChannel.open(absolute,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            if (data.size() == 0) {
                writeFully(data, Header.bytes());
            } else {
                ensureTrailingNewline(data);
            }
            RecordStore store = new RecordStore(absolute, data, lockChannel, fileLock, server);
            store.load();
            data.position(data.size());
            return store;
        } catch (IOException | RuntimeException e) {
            if (data != null) {
                closeQuietly(data);
            }
            releaseQuietly(fileLock);
            closeQuietly(lockChannel);
            throw e;
        }
    }

    /**
     * Reads records without creating or locking the file. Intended for
     * {@code --dry-run} and tests.
     */
    public static List<RecordEntry> read(Path recordFile) throws IOException {
        Path absolute = recordFile.toAbsolutePath().normalize();
        if (!Files.exists(absolute)) {
            return List.of();
        }
        List<RecordEntry> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(absolute, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                parseLine(line, result);
            }
        }
        return List.copyOf(result);
    }

    private void load() throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(recordFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;
                List<RecordEntry> parsed = new ArrayList<>(1);
                parseLine(line, parsed);
                if (parsed.isEmpty() && !line.isBlank() && !line.startsWith("#")) {
                    malformedLines++;
                }
                for (RecordEntry entry : parsed) {
                    latest.put(key(entry.path()), entry);
                }
            }
        }
    }

    private static void parseLine(String line, List<RecordEntry> target) {
        if (line == null || line.isBlank() || line.startsWith("#")) {
            return;
        }
        try {
            target.add(RecordEntry.fromTsv(line));
        } catch (RuntimeException ignored) {
            // A partially written final line is expected after a hard crash.
        }
    }

    /**
     * Returns a matching record even if a Live Photo image still needs to be
     * linked. The caller can use the assetId to finish the link without
     * uploading the file again.
     */
    public Optional<RecordEntry> findRecord(ScannedMedia media) {
        RecordEntry entry = latest.get(key(media.path()));
        if (entry == null || !entry.status().isSuccess()) {
            return Optional.empty();
        }
        if (!server.equals(entry.server())) {
            return Optional.empty();
        }
        if (entry.size() != media.size() || entry.modifiedAtMillis() != media.modifiedAtMillis()) {
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    /**
     * True only when the recorded asset is fully complete. For a Live Photo
     * image this means the image is linked to its video.
     */
    public Optional<RecordEntry> findResume(ScannedMedia media) {
        return findRecord(media).filter(RecordEntry::complete);
    }

    public Optional<RecordEntry> latestForPath(Path path) {
        return Optional.ofNullable(latest.get(key(path)));
    }

    public Optional<RecordEntry> latestForPath(Path path, String requiredServer) {
        RecordEntry entry = latest.get(key(path));
        if (entry == null || !requiredServer.equals(entry.server())) {
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    public List<RecordEntry> latestForServerList() {
        List<RecordEntry> result = new ArrayList<>();
        for (RecordEntry entry : latest.values()) {
            if (server.equals(entry.server())) {
                result.add(entry);
            }
        }
        result.sort(Comparator.comparing(entry -> entry.path().toString(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    public List<RecordEntry> latestForPaths(Collection<Path> paths) {
        List<RecordEntry> result = new ArrayList<>();
        for (Path path : paths) {
            RecordEntry entry = latest.get(key(path));
            if (entry != null && server.equals(entry.server())) {
                result.add(entry);
            }
        }
        result.sort(Comparator.comparing(entry -> entry.path().toString(), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(result);
    }

    public void append(RecordEntry entry) throws IOException {
        byte[] line = (entry.toTsv() + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (writeMutex) {
            channel.position(channel.size());
            writeFully(channel, line);
            channel.force(true);
            latest.put(key(entry.path()), entry);
        }
    }

    public int malformedLines() {
        return malformedLines;
    }

    public int totalLines() {
        return totalLines;
    }

    public Path recordFile() {
        return recordFile;
    }

    public String server() {
        return server;
    }

    public static boolean isLiveImage(RecordEntry entry) {
        return entry != null && entry.role() == MediaRole.LIVE_IMAGE;
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        try {
            channel.close();
        } catch (IOException e) {
            first = e;
        }
        try {
            lock.release();
        } catch (IOException e) {
            if (first == null) {
                first = e;
            }
        }
        try {
            lockChannel.close();
        } catch (IOException e) {
            if (first == null) {
                first = e;
            }
        }
        if (first != null) {
            throw first;
        }
    }

    private static void ensureTrailingNewline(FileChannel channel) throws IOException {
        long size = channel.size();
        if (size <= 0) {
            return;
        }
        ByteBuffer one = ByteBuffer.allocate(1);
        int read = channel.read(one, size - 1);
        if (read == 1 && one.get(0) != '\n') {
            channel.position(size);
            writeFully(channel, new byte[]{'\n'});
            channel.force(true);
        }
    }

    private static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void releaseQuietly(FileLock lock) {
        try {
            lock.release();
        } catch (IOException ignored) {
            // Best effort during failure cleanup.
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Best effort during failure cleanup.
        }
    }

    private static String key(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        return WINDOWS ? value.toLowerCase(Locale.ROOT) : value;
    }

    private static final class Header {
        private static byte[] bytes() {
            return (HEADER + "\n").getBytes(StandardCharsets.UTF_8);
        }
    }
}
