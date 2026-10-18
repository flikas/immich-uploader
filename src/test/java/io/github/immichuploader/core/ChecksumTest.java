package io.github.immichuploader.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChecksumTest {
    @TempDir
    Path temp;

    @Test
    void knownSha1Vector() throws Exception {
        Path file = temp.resolve("abc.txt");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);
        assertEquals("qZk+NkcGgWq6PiVxeFDCbJzQ2J0=", Checksum.sha1Base64(file));
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Checksum.sha1Hex(file));
    }

    @Test
    void reportsBytesReadWhileHashing() throws Exception {
        Path file = temp.resolve("progress.bin");
        Files.write(file, new byte[3 * 1024 * 1024 + 17]);
        AtomicLong seen = new AtomicLong();

        Checksum.sha1Base64(file, seen::set);

        assertEquals(Files.size(file), seen.get());
        assertTrue(seen.get() > 0);
    }

    @Test
    void hexFromBase64() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d",
                Checksum.hexFromBase64("qZk+NkcGgWq6PiVxeFDCbJzQ2J0="));
    }
}
