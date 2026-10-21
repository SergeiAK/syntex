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
 * on the same PID); {@code lpcm.m2ts} — 192·20000 bytes of "00019.m2ts" (MPEG-2, two Blu-ray LPCM
 * tracks 0x80). Frame counts are ffmpeg's ({@code -c copy -f framecrc}) on the same heads.
 *
 * <p>Blu-ray audio numbers in plain TS and in M2TS: {@code dtshd-ma.ts} — 188·30000 bytes of
 * "03.split.5.ts" (a disc copied to 188-byte TS: VC-1 0xEA, DTS-HD 0x86, PGS 0x90, "HDMV" in the
 * program map); {@code plain.ts} — 188·20000 bytes of "bbb_1080p_c.ts" (HEVC, AAC; not a disc). No
 * library file carries 0x84 or 0x85, so two are made by rewriting the stream type in the program map
 * (CRC recomputed): {@code eac3-retyped.m2ts} — {@code ac3.m2ts} with 0x81 → 0x84;
 * {@code dtshd-hra-retyped.ts} — {@code dtshd-ma.ts} with 0x86 → 0x85. They check the numbers are
 * read, not a real Dolby Digital Plus stream; the dependent frames are checked on bytes below.
 *
 * <p>Disc demos from kodi.wiki/view/Samples, heads of 192·40000 bytes: {@code eac3-71.m2ts} — "Safari_
 * Dolby_Digital_Plus.m2ts" (Dolby Digital Plus 7.1, 0x84: AC-3 5.1 and E-AC-3 dependent frames in
 * PES packets of their own, alternating); {@code helicopter.m2ts} — "Dolby ATMOS Helicopter.m2ts"
 * (TrueHD 0x83 with its AC-3 core, Dolby Digital Plus 7.1 0x84); {@code dtshd-ma-71.m2ts} — "DTS-HD
 * MA 7.1 'Dredd' Sound Check.m2ts" (0x86); {@code dtsx.m2ts} — "DTSX_Demo_2016.m2ts" (DTS-HD MA with
 * DTS:X, 0x86).
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

    /** 0x80 is Blu-ray LPCM, not a video stream that never gets a format. */
    @Test
    public void lpcmIsLeftOut() throws IOException {
        final ExtractorHarness.Result result = ExtractorHarness.readTracks(
                create(), ExtractorHarness.testFile("lpcm.m2ts"));
        assertTrue("formats known", result.allFormatsKnown());
        assertEquals(1, result.tracks.size());
        assertEquals(MimeTypes.VIDEO_MPEG2, result.mime(C.TRACK_TYPE_VIDEO));
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

    /** Blu-ray Dolby Digital Plus, 0x84: read, the AC-3 frames as before. */
    @Test
    public void eac3NumberIsRead() throws IOException {
        final ExtractorHarness.Result result = ExtractorHarness.readSamples(
                create(), ExtractorHarness.testFile("eac3-retyped.m2ts"), Integer.MAX_VALUE);
        assertEquals(MimeTypes.AUDIO_AC3, result.mime(C.TRACK_TYPE_AUDIO));
        assertAbout(51, result.track(C.TRACK_TYPE_AUDIO).samples);
    }

    /** A disc in 188-byte TS: DTS-HD 0x86 is sound, not an SCTE-35 cue stream. */
    @Test
    public void dtsHdInPlainTsOfADisc() throws IOException {
        for (String name : new String[]{"dtshd-ma.ts", "dtshd-hra-retyped.ts"}) {
            final ExtractorHarness.Result result = ExtractorHarness.readSamples(
                    createPlain(), ExtractorHarness.testFile(name), Integer.MAX_VALUE);
            assertEquals(name, 1, count(result, C.TRACK_TYPE_AUDIO));
            final ExtractorHarness.Track audio = result.track(C.TRACK_TYPE_AUDIO);
            assertEquals(name, MimeTypes.AUDIO_DTS_HD, audio.format.sampleMimeType);
            assertEquals(name, 6, audio.format.channelCount);
            // ffmpeg: 101 DTS frames.
            assertAbout(101, audio.samples);
        }
    }

    /** Without "HDMV" the plain TS extractor reads as the stock one does. */
    @Test
    public void plainTsUnchanged() throws IOException {
        final File file = ExtractorHarness.testFile("plain.ts");
        final ExtractorHarness.Result ours =
                ExtractorHarness.readSamples(createPlain(), file, Integer.MAX_VALUE);
        final ExtractorHarness.Result stock = ExtractorHarness.readSamples(
                new TsExtractor(new DefaultSubtitleParserFactory()), file, Integer.MAX_VALUE);
        assertEquals(stock.tracks.size(), ours.tracks.size());
        for (int type : new int[]{C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO}) {
            assertEquals(stock.mime(type), ours.mime(type));
            assertEquals(stock.track(type).samples, ours.track(type).samples);
        }
        // ffmpeg: 286 video frames, 507 AAC frames.
        assertAbout(286, ours.track(C.TRACK_TYPE_VIDEO).samples);
    }

    /** A real disc Dolby Digital Plus 7.1: one AC-3 5.1 format, never switched, every base frame. */
    @Test
    public void eac3DiscStreamPlaysIn51() throws IOException {
        final ExtractorHarness.Result result = ExtractorHarness.readSamples(
                create(), ExtractorHarness.testFile("eac3-71.m2ts"), Integer.MAX_VALUE);
        final ExtractorHarness.Track audio = result.track(C.TRACK_TYPE_AUDIO);
        assertEquals(MimeTypes.AUDIO_AC3, audio.format.sampleMimeType);
        assertEquals(6, audio.format.channelCount);
        assertEquals("formats", 1, audio.formats);
        // ffmpeg: 218 frames (it counts an AC-3 frame and its dependent one as one).
        assertAbout(218, audio.samples);
    }

    /** TrueHD with its core and a Dolby Digital Plus track: two AC-3 tracks. */
    @Test
    public void trueHdAndEac3OnOneDisc() throws IOException {
        final ExtractorHarness.Result result = ExtractorHarness.readSamples(
                create(), ExtractorHarness.testFile("helicopter.m2ts"), Integer.MAX_VALUE);
        assertEquals(2, count(result, C.TRACK_TYPE_AUDIO));
        for (ExtractorHarness.Track t : result.tracks) {
            if (t.type != C.TRACK_TYPE_AUDIO) continue;
            assertEquals(MimeTypes.AUDIO_AC3, t.format.sampleMimeType);
            assertEquals(1, t.formats);
            // ffmpeg: 118 AC-3 core frames, 118 Dolby Digital Plus frames.
            assertAbout(118, t.samples);
        }
    }

    @Test
    public void dtsHdInM2ts() throws IOException {
        for (String name : new String[]{"dtshd-ma-71.m2ts", "dtsx.m2ts"}) {
            final ExtractorHarness.Result result = ExtractorHarness.readSamples(
                    create(), ExtractorHarness.testFile(name), Integer.MAX_VALUE);
            final ExtractorHarness.Track audio = result.track(C.TRACK_TYPE_AUDIO);
            assertEquals(name, MimeTypes.AUDIO_DTS_HD, audio.format.sampleMimeType);
            assertEquals(name, 8, audio.format.channelCount);
            assertEquals(name, 1, audio.formats);
            // ffmpeg: 346 and 260 frames.
            assertAbout(name.startsWith("dtsx") ? 260 : 346, audio.samples);
        }
    }

    /** AC-3 32 kbit/s (128 bytes), then E-AC-3 frames of 64 bytes, dependent or independent. */
    @Test
    public void dependentFramesAreDropped() {
        final byte[] ac3 = frame(128, 0, 0x00, 8 << 3);
        final byte[] dependent = frame(64, 0x40 | 0, 31, 16 << 3);
        final byte[] independent = frame(64, 0x00 | 0, 31, 16 << 3);
        final byte[] data = concat(ac3, dependent, ac3, dependent, independent, new byte[]{1, 2, 3});
        final int kept = M2tsExtractor.Ac3CoreReader.dropDependentFrames(data, data.length);
        final byte[] expected = concat(ac3, ac3, independent, new byte[]{1, 2, 3});
        assertEquals(expected.length, kept);
        for (int i = 0; i < kept; i++) {
            assertEquals("byte " + i, expected[i], data[i]);
        }
    }

    private static byte[] frame(int size, int byte2, int byte3, int byte5) {
        final byte[] f = new byte[size];
        f[0] = 0x0B;
        f[1] = 0x77;
        f[2] = (byte) byte2;
        f[3] = (byte) byte3;
        f[4] = 0x00; // AC-3: fscod 48 kHz, frmsizecod 0 = 32 kbit/s = 64 words
        f[5] = (byte) byte5;
        for (int i = 6; i < size; i++) f[i] = (byte) (size + i);
        return f;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        final byte[] all = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, all, at, p.length);
            at += p.length;
        }
        return all;
    }

    private static M2tsExtractor create() {
        final ExtractorsFactory none = () -> new Extractor[0];
        return new M2tsExtractor.Factory(none, new DefaultSubtitleParserFactory(),
                DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS,
                1500 * TsExtractor.TS_PACKET_SIZE).create();
    }

    private static Extractor createPlain() {
        final ExtractorsFactory none = () -> new Extractor[0];
        return new M2tsExtractor.Factory(none, new DefaultSubtitleParserFactory(),
                DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS,
                1500 * TsExtractor.TS_PACKET_SIZE).createPlain();
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
