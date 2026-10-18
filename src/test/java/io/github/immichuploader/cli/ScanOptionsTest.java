package io.github.immichuploader.cli;

import io.github.immichuploader.core.MediaExtensions;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanOptionsTest {
    @Test
    void allFilesIsEnabledByDefault() {
        ScanOptions options = new ScanOptions();
        new CommandLine(options).parseArgs();
        assertTrue(options.allFiles());
        assertEquals(MediaExtensions.DEFAULT, options.extensionSet());
    }

    @Test
    void noAllFilesDisablesIt() {
        ScanOptions options = new ScanOptions();
        new CommandLine(options).parseArgs("--no-all-files");
        assertFalse(options.allFiles());
    }

    @Test
    void extensionsImplicitlyDisableAllFiles() {
        ScanOptions options = new ScanOptions();
        new CommandLine(options).parseArgs("--extensions", "jpg,png");
        assertFalse(options.allFiles());
    }
}
