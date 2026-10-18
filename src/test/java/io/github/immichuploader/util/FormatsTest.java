package io.github.immichuploader.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormatsTest {
    @Test
    void shortPathLeavesShortPathsUnchanged() {
        Path path = Path.of("photos", "a.jpg");
        assertEquals(path.toString(), Formats.shortPath(path, 60));
    }

    @Test
    void shortPathKeepsFilenameAndDropsLeadingDirectories() {
        Path path = Path.of("root", "Pictures", "2026", "10", "top-01",
                "l1", "l2", "l3", "l4", "l5", "LIVE_0040.JPG");
        String shortened = Formats.shortPath(path, 40);

        assertTrue(shortened.startsWith("..." + java.io.File.separator), shortened);
        assertTrue(shortened.endsWith("LIVE_0040.JPG"), shortened);
        assertTrue(shortened.length() <= 40, shortened);
    }

    @Test
    void shortPathHandlesTinyLimitWithoutOverflow() {
        Path path = Path.of("C:\\Users\\me\\Pictures\\photo.jpg");
        String shortened = Formats.shortPath(path, 3);
        assertEquals(3, shortened.length());
    }
}
