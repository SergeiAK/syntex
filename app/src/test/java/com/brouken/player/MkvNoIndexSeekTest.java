package com.brouken.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.text.SubtitleParser;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

/**
 * A Matroska file with no index anywhere — no SeekHead, no Cues, only Clusters. File from
 * -PmediaTestDir: Ducks.Take.Off.1080p.QHD.CRF25.x264-CtrlHD.mkv (230 MB, a Cluster every ~1.5 MB).
 */
public class MkvNoIndexSeekTest {

    private static final String DUCKS = "Ducks.Take.Off.1080p.QHD.CRF25.x264-CtrlHD.mkv";

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
    public void stockMatroskaCannotSeek() throws Exception {
        final ExtractorHarness.Result result =
                ExtractorHarness.readSamples(matroska(), ExtractorHarness.testFile(DUCKS), 50);
        assertNotNull(result.seekMap);
        assertFalse(result.seekMap.isSeekable());
    }

    @Test
    public void linkedMatroskaSeeksToTheClusterBeforeTheTarget() throws Exception {
        final File file = ExtractorHarness.testFile(DUCKS);
        final Extractor extractor = linked();
        final ExtractorHarness.Result result = ExtractorHarness.readSamples(extractor, file, 50);
        final SeekMap seekMap = result.seekMap;
        assertNotNull(seekMap);
        assertTrue(seekMap.isSeekable());
        final long durationUs = seekMap.getDurationUs();
        System.out.println("NOINDEX duration " + durationUs + " us");
        // Forwards, backwards, the start and nearly the end, as a viewer would.
        final long[] targets = {durationUs / 2, durationUs / 4, 0, durationUs * 9 / 10, durationUs / 3,
                5_561_116, 12_000_000, 1_000_000, durationUs - 200_000};
        for (long targetUs : targets) {
            final long position = seekMap.getSeekPoints(targetUs).first.position;
            ExtractorHarness.seekAndRead(extractor, file, result, position, targetUs, 100);
            for (ExtractorHarness.Track t : result.tracks) {
                if (t.samples == 0) continue;
                System.out.println("NOINDEX target " + targetUs + " guess " + position + " type " + t.type
                        + " first " + t.firstTimeUs + " samples " + t.samples);
                // From a Cluster at or a little before the target: within the second the search
                // settles for, and B-frame reordering on top.
                assertTrue("type " + t.type + " at " + t.firstTimeUs + " for " + targetUs,
                        t.firstTimeUs <= targetUs + 300_000);
                assertTrue("type " + t.type + " at " + t.firstTimeUs + " for " + targetUs,
                        t.firstTimeUs >= targetUs - 1_500_000);
                assertTrue(t.timeJumpsBack == 0);
            }
            assertTrue(result.track(C.TRACK_TYPE_VIDEO).samples > 0);
        }
    }
}
