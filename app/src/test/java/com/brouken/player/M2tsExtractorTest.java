package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.TsExtractor;

import java.io.File;
import java.io.IOException;

import org.junit.Test;

/**
 * Blu-ray M2TS through the stock TS extractor over a 188-byte view. Samples are raw heads of library
 * files ({@code head -c}, a multiple of 192 — ffmpeg would remux them), passed in with
 * -PmediaTestDir=...: {@code ac3.m2ts} — 192·50000 bytes of "Casino.50.m2ts" (H.264, AC-3 0x81);
 * {@code truehd.m2ts} — 192·60000 bytes of "H264_TrueHD.m2ts" (H.264, TrueHD 0x83 with its AC-3 core
 * on the same PID). Frame counts are ffmpeg's ({@code -c copy -f framecrc}) on the same heads.
 */
public class M2tsExtractorTest {

    static {
        ExtractorHarness.prepareJvm();
    }

    @Test
    public void positionsMapBothWays() {
        assertEquals(0, M2tsExtractor.toFile(0));
        assertEquals(192, M2tsExtractor.toFile(188));
        assertEquals(4 + 10, M2tsExtractor.toFile(10));
        assertEquals(192 * 7 + 4 + 100, M2tsExtractor.toFile(188 * 7 + 100));
        for (long view : new long[]{0, 1, 187, 188, 189, 188 * 1000 + 55}) {
            assertEquals(view, M2tsExtractor.toView(M2tsExtractor.toFile(view)));
        }
        // Inside the timestamp: the packet's first byte.
        assertEquals(188, M2tsExtractor.toView(192 + 2));
    }

    @Test
    public void stockTsRefusesIt() throws IOException {
        final File file = ExtractorHarness.testFile("ac3.m2ts");
        assertFalse(ExtractorHarness.sniffs(new TsExtractor(new DefaultSubtitleParserFactory()), file));
        assertTrue(ExtractorHarness.sniffs(create(), file));
    }

    @Test
    public void ac3TrackPlays() throws IOException {
        final ExtractorHarness.Result result = ExtractorHarness.readSamples(
                create(), ExtractorHarness.testFile("ac3.m2ts"), Integer.MAX_VALUE);
        assertEquals(MimeTypes.VIDEO_H264, result.mime(C.TRACK_TYPE_VIDEO));
        assertEquals(MimeTypes.AUDIO_AC3, result.mime(C.TRACK_TYPE_AUDIO));
        assertAbout(41, result.track(C.TRACK_TYPE_VIDEO).samples);
        assertAbout(51, result.track(C.TRACK_TYPE_AUDIO).samples);
    }

    @Test
    public void trueHdGivesItsAc3Core() throws IOException {
        final ExtractorHarness.Result result = ExtractorHarness.readSamples(
                create(), ExtractorHarness.testFile("truehd.m2ts"), Integer.MAX_VALUE);
        assertEquals(MimeTypes.VIDEO_H264, result.mime(C.TRACK_TYPE_VIDEO));
        assertEquals(1, count(result, C.TRACK_TYPE_AUDIO));
        final ExtractorHarness.Track audio = result.track(C.TRACK_TYPE_AUDIO);
        assertEquals(MimeTypes.AUDIO_AC3, audio.format.sampleMimeType);
        assertEquals(6, audio.format.channelCount);
        assertAbout(56, result.track(C.TRACK_TYPE_VIDEO).samples);
        // ffmpeg: 72 AC-3 frames, 2663 TrueHD units. Not one TrueHD unit taken for an AC-3 frame.
        assertAbout(72, audio.samples);
    }

    @Test
    public void seeksIntoTheMiddle() throws IOException {
        final File file = ExtractorHarness.testFile("truehd.m2ts");
        final M2tsExtractor m2ts = create();
        final ExtractorHarness.Result result =
                ExtractorHarness.readSamples(m2ts, file, Integer.MAX_VALUE);
        final SeekMap seekMap = result.seekMap;
        assertNotNull(seekMap);
        assertTrue("seekable", seekMap.isSeekable());
        final long durationUs = seekMap.getDurationUs();
        assertTrue("duration " + durationUs, durationUs > 2_000_000 && durationUs < 2_600_000);

        final long targetUs = durationUs / 2;
        final long position = seekMap.getSeekPoints(targetUs).first.position;
        assertTrue("position " + position, position >= 0 && position < file.length());
        ExtractorHarness.seekAndRead(m2ts, file, result, position, targetUs, 30);
        final ExtractorHarness.Track video = result.track(C.TRACK_TYPE_VIDEO);
        assertTrue("video after seek", video.samples > 0);
        assertTrue("first video sample at " + video.firstTimeUs + " for " + targetUs,
                Math.abs(video.firstTimeUs - targetUs) < 500_000);
    }

    private static M2tsExtractor create() {
        final ExtractorsFactory none = () -> new Extractor[0];
        return new M2tsExtractor.Factory(none, new DefaultSubtitleParserFactory(),
                DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS,
                1500 * TsExtractor.TS_PACKET_SIZE).create();
    }

    private static int count(ExtractorHarness.Result result, int type) {
        int n = 0;
        for (ExtractorHarness.Track t : result.tracks) {
            if (t.type == type) n++;
        }
        return n;
    }

    /** The last frame of a cut file may be held back or cut through. */
    private static void assertAbout(int expected, int actual) {
        assertTrue("expected about " + expected + ", got " + actual, Math.abs(expected - actual) <= 1);
    }
}
