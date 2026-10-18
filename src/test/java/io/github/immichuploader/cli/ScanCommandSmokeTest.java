package io.github.immichuploader.cli;

import io.github.immichuploader.Main;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScanCommandSmokeTest {
    @TempDir
    Path temp;

    @Test
    void scansWithoutNetwork() throws Exception {
        Files.writeString(temp.resolve("photo.jpg"), "data");
        assertEquals(0, Main.execute(new String[]{"scan", "--dir", temp.toString()}));
    }

    @Test
    void printsVersion() {
        assertEquals(0, Main.execute(new String[]{"--version"}));
    }
}
