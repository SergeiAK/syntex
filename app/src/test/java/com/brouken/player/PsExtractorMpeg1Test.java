package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.mp3.Mp3Extractor;
import androidx.media3.extractor.SeekMap;
import com.brouken.player.ps.PsExtractor;

import java.io.File;
import java.io.IOException;

import org.junit.Test;

/**
 * MPEG-1 program streams through the fork's copy of PsExtractor. Samples are raw heads of library clips,
 * cut at the last pack start before 3,000,000 bytes (a PES cut through is lost whole, ffmpeg keeps its
 * frames), passed in with -PmediaTestDir=...: {@code mpeg1-a.mpg} — "Земфира - Мы
 * разбиваемся.mpg" (640x512, square pixels, MP2 48 kHz); {@code mpeg1-b.mpg} — "Король и Шут - Месть
 * Гарри.mpg" (720x560, pixel aspect code 8, MP2 44.1 kHz), the one Media3 opened as an MP3. And
 * {@code mpeg2.vob}, MPEG-2 PS for regression: {@code ffmpeg -t 3 -i 00019.m2ts -map 0:0 -map 0:1
 * -c:v copy -c:a mp2 -f vob}. Frame counts are ffmpeg's ({@code -c copy -f framecrc}).
 */
public class PsExtractorMpeg1Test {

    static {
        ExtractorHarness.prepareJvm();
    }

    @Test
    public void stockPsRefusesMpeg1() throws IOException {
        final File file = ExtractorHarness.testFile("mpeg1-a.mpg");
        assertFalse(ExtractorHarness.sniffs(new androidx.media3.extractor.ts.PsExtractor(), file));
        assertTrue(ExtractorHarness.sniffs(new PsExtractor(), file));
    }

    /**
     * The PS copy takes the stock one's place, ahead of the MP3 extractor, which would take this file
     * too. In the default order PS comes before MP3; DefaultExtractorsFactory itself cannot run here
     * (Uri.EMPTY is null in the android.jar stub).
     */
    @Test
    public void mpeg1IsNotTakenForMp3() throws IOException {
        final File file = ExtractorHarness.testFile("mpeg1-b.mpg");
        assertTrue("MP3 would take it", ExtractorHarness.sniffs(new Mp3Extractor(), file));
        final ExtractorsFactory stockOrder = () -> new Extractor[]{
                new androidx.media3.extractor.ts.PsExtractor(), new Mp3Extractor()};
        Extractor taker = null;
        for (Extractor e : PsExtractor.replacing(stockOrder).createExtractors()) {
            if (ExtractorHarness.sniffs(e, file)) {
                taker = e;
                break;
            }
        }
        assertNotNull(taker);
        assertTrue(taker.getClass().getName(), taker instanceof PsExtractor);
    }

    @Test
    public void squarePixelClip() throws IOException {
        final ExtractorHarness.Result result = ExtractorHarness.readSamples(
                new PsExtractor(), ExtractorHarness.testFile("mpeg1-a.mpg"), Integer.MAX_VALUE);
        final ExtractorHarness.Track video = result.track(C.TRACK_TYPE_VIDEO);
        assertEquals(MimeTypes.VIDEO_MPEG2, video.format.sampleMimeType);
        assertEquals(640, video.format.width);
        assertEquals(512, video.format.height);
        assertEquals(1f, video.format.pixelWidthHeightRatio, 0.001f);
        assertEquals(MimeTypes.AUDIO_MPEG_L2, result.mime(C.TRACK_TYPE_AUDIO));
        assertAbout(125, video.samples);
        assertAbout(207, result.track(C.TRACK_TYPE_AUDIO).samples);
    }

    @Test
    public void palClipKeepsItsPixelAspect() throws IOException {
        final ExtractorHarness.Result result = ExtractorHarness.readSamples(
                new PsExtractor(), ExtractorHarness.testFile("mpeg1-b.mpg"), Integer.MAX_VALUE);
        final ExtractorHarness.Track video = result.track(C.TRACK_TYPE_VIDEO);
        assertEquals(720, video.format.width);
        assertEquals(560, video.format.height);
        // ffmpeg: SAR 178:163.
        assertEquals(178f / 163f, video.format.pixelWidthHeightRatio, 0.002f);
        assertEquals(44100, result.track(C.TRACK_TYPE_AUDIO).format.sampleRate);
        assertAbout(427, video.samples);
        // Two of every three video packets carry no PTS; none of their pictures may fall back to 0.
        assertEquals(0, video.timeJumpsBack);
        assertTrue("last video at " + video.lastTimeUs, video.lastTimeUs > 16_000_000);
        assertAbout(649, result.track(C.TRACK_TYPE_AUDIO).samples);
    }

    @Test
    public void mpeg1Seeks() throws IOException {
        final File file = ExtractorHarness.testFile("mpeg1-b.mpg");
        final PsExtractor ps = new PsExtractor();
        final ExtractorHarness.Result result = ExtractorHarness.readSamples(ps, file, Integer.MAX_VALUE);
        final SeekMap seekMap = result.seekMap;
        assertNotNull(seekMap);
        assertTrue("seekable", seekMap.isSeekable());
        final long durationUs = seekMap.getDurationUs();
        // ffmpeg: 0 .. 17.0 s on the cut.
        assertTrue("duration " + durationUs, durationUs > 16_000_000 && durationUs < 18_000_000);

        final long targetUs = 10_000_000;
        final long position = seekMap.getSeekPoints(targetUs).first.position;
        ExtractorHarness.seekAndRead(ps, file, result, position, targetUs, 40);
        final ExtractorHarness.Track video = result.track(C.TRACK_TYPE_VIDEO);
        assertTrue("video after seek", video.samples > 0);
        assertTrue("first video sample at " + video.firstTimeUs,
                Math.abs(video.firstTimeUs - targetUs) < 1_000_000);
    }

    /** The MPEG-2 path of the copy is the stock one: same tracks, same counts, square-pixel rule off. */
    @Test
    public void mpeg2StillPlays() throws IOException {
        final ExtractorHarness.Result result = ExtractorHarness.readSamples(
                new PsExtractor(), ExtractorHarness.testFile("mpeg2.vob"), Integer.MAX_VALUE);
        final ExtractorHarness.Track video = result.track(C.TRACK_TYPE_VIDEO);
        assertEquals(MimeTypes.VIDEO_MPEG2, video.format.sampleMimeType);
        assertEquals(1920, video.format.width);
        assertEquals(1f, video.format.pixelWidthHeightRatio, 0.001f);
        assertEquals(MimeTypes.AUDIO_MPEG_L2, result.mime(C.TRACK_TYPE_AUDIO));
        assertAbout(91, video.samples);
        assertAbout(125, result.track(C.TRACK_TYPE_AUDIO).samples);
        assertTrue("seekable", result.seekMap != null && result.seekMap.isSeekable());
    }

    private static void assertAbout(int expected, int actual) {
        assertTrue("expected about " + expected + ", got " + actual, Math.abs(expected - actual) <= 1);
    }
}
