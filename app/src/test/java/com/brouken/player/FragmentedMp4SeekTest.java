package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;

import java.io.File;
import java.io.IOException;

import org.junit.Test;

/**
 * A fragmented MP4 with no {@code sidx}, only the {@code mfra} index ffmpeg writes at the end by default.
 * The sample is Chromium's media/test/data/bbb-320x240-2video-2audio.mp4 (16 fragments, ~1 MB), passed in
 * as {@code frag-mfra.mp4} with -PmediaTestDir=...
 */
public class FragmentedMp4SeekTest {

    static {
        ExtractorHarness.prepareJvm();
    }

    @Test
    public void stockExtractorCannotSeek() throws IOException {
        final ExtractorHarness.Result result = ExtractorHarness.readSamples(
                new FragmentedMp4Extractor(new DefaultSubtitleParserFactory(), 0), testFile(), 1);
        assertNotNull(result.seekMap);
        assertFalse(result.seekMap.isSeekable());
    }

    @Test
    public void mfraGivesASeekMap() throws IOException {
        final File file = testFile();
        final FragmentedMp4Extractor extractor = new FragmentedMp4Extractor(
                new DefaultSubtitleParserFactory(), FragmentedMp4Extractor.FLAG_READ_MFRA_FOR_SEEK_MAP);
        final ExtractorHarness.Result result = ExtractorHarness.readSamples(extractor, file, 1);
        assertNotNull(result.seekMap);
        assertTrue(result.seekMap.isSeekable());

        // Halfway in, as a remote's seek does: the read starts at a fragment at or before the target,
        // not at the head of the file.
        final long targetUs = result.seekMap.getDurationUs() / 2;
        final SeekMap.SeekPoints points = result.seekMap.getSeekPoints(targetUs);
        assertTrue(points.first.position > 0);
        ExtractorHarness.seekAndRead(extractor, file, result, points.first.position, points.first.timeUs, 20);
        final ExtractorHarness.Track video = result.track(C.TRACK_TYPE_VIDEO);
        assertNotNull(video);
        assertTrue(video.samples > 0);
        assertTrue(video.firstTimeUs <= targetUs);
        assertTrue(video.firstTimeUs > targetUs - 5_000_000L);
        assertEquals(0, video.timeJumpsBack);
    }

    private static File testFile() {
        return ExtractorHarness.testFile("frag-mfra.mp4");
    }
}
