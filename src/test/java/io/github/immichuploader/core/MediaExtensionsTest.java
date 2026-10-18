package io.github.immichuploader.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaExtensionsTest {
    @Test
    void classifiesCommonAndObscureExtensions() {
        assertEquals(MediaKind.IMAGE, MediaExtensions.kindOf("JPG"));
        assertEquals(MediaKind.IMAGE, MediaExtensions.kindOf("heic"));
        assertEquals(MediaKind.IMAGE, MediaExtensions.kindOf("dng"));
        assertEquals(MediaKind.IMAGE, MediaExtensions.kindOf("avifs"));
        assertEquals(MediaKind.VIDEO, MediaExtensions.kindOf("MOV"));
        assertEquals(MediaKind.VIDEO, MediaExtensions.kindOf("m2ts"));
        assertEquals(MediaKind.VIDEO, MediaExtensions.kindOf("mjpeg"));
        assertEquals(MediaKind.VIDEO, MediaExtensions.kindOf("insv"));
        assertEquals(MediaKind.VIDEO, MediaExtensions.kindOf("braw"));
        assertNull(MediaExtensions.kindOf("txt"));
    }

    @Test
    void extensionIsCaseInsensitiveAndHandlesNoExtension() {
        assertEquals("jpg", MediaExtensions.extensionOf("A.JPG"));
        assertEquals("", MediaExtensions.extensionOf("README"));
        assertEquals("", MediaExtensions.extensionOf("trailing."));
    }

    @Test
    void ambiguousExtensionsAreMarked() {
        assertTrue(MediaExtensions.isAmbiguous("ts"));
        assertTrue(MediaExtensions.isAmbiguous("raw"));
        assertTrue(MediaExtensions.isAmbiguous("h265"));
        assertFalse(MediaExtensions.isAmbiguous("mp4"));
    }

    @Test
    void livePhotoEligibility() {
        assertTrue(MediaExtensions.isLiveImage("heic"));
        assertTrue(MediaExtensions.isLiveImage("JPG"));
        assertFalse(MediaExtensions.isLiveImage("png"));
        assertTrue(MediaExtensions.isLiveVideo("mov"));
        assertTrue(MediaExtensions.isLiveVideo("MP4"));
        assertFalse(MediaExtensions.isLiveVideo("avi"));
        assertTrue(MediaExtensions.isStrictLiveImage("heif"));
        assertFalse(MediaExtensions.isStrictLiveImage("jpg"));
        assertTrue(MediaExtensions.isStrictLiveVideo("qt"));
        assertFalse(MediaExtensions.isStrictLiveVideo("mp4"));
    }
}
