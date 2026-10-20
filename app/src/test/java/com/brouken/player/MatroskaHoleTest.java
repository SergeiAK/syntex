package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.media3.common.C;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.text.SubtitleParser;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

/**
 * A Matroska file with a stretch of zeros in the middle — what a torrent backend hands over for a piece
 * it does not have. Files from -PmediaTestDir: cut.mkv (the first six seconds of
 * jellyfish-120-mbps-4k-uhd-h264.mkv, {@code ffmpeg -t 6 -c copy}) and hole-zero.mkv (the same with one
 * MiB zeroed from byte 51789912, halfway).
 */
public class MatroskaHoleTest {

    @BeforeClass
    public static void jvm() {
        ExtractorHarness.prepareJvm();
    }

    private static Extractor matroska() {
        return new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, 0);
    }

    private static Extractor linked() {
        return new MkvCuesLink.Factory(() -> new Extractor[] {matroska()}).createExtractors()[0];
    }

    @Test
    public void stockMatroskaStopsAtTheZeros() throws Exception {
        final File file = ExtractorHarness.testFile("hole-zero.mkv");
        try {
            ExtractorHarness.readSamples(matroska(), file, Integer.MAX_VALUE);
            fail("read through the zeros");
        } catch (IllegalStateException e) {
            assertEquals("No valid varint length mask found", e.getMessage());
        }
    }

    @Test
    public void linkedMatroskaPlaysOnPastTheZeros() throws Exception {
        final ExtractorHarness.Track whole = ExtractorHarness.readSamples(
                linked(), ExtractorHarness.testFile("cut.mkv"), Integer.MAX_VALUE).track(C.TRACK_TYPE_VIDEO);
        final ExtractorHarness.Track holed = ExtractorHarness.readSamples(
                linked(), ExtractorHarness.testFile("hole-zero.mkv"), Integer.MAX_VALUE).track(C.TRACK_TYPE_VIDEO);
        assertNotNull(whole);
        assertNotNull(holed);
        System.out.println("HOLE whole " + whole.samples + " samples to " + whole.lastTimeUs
                + " us, holed " + holed.samples + " to " + holed.lastTimeUs + " us");
        // To the end of the file, missing what the hole and the rest of its Cluster held: a second or
        // two of a 120 Mbit/s remux, out of six.
        assertEquals(whole.lastTimeUs, holed.lastTimeUs);
        assertTrue(holed.samples < whole.samples);
        assertTrue(holed.samples > whole.samples * 2 / 3);
        assertEquals(0, holed.timeJumpsBack);
    }
}
