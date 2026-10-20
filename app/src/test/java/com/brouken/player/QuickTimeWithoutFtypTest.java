package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;

import java.io.File;
import java.io.IOException;

import org.junit.Test;

/**
 * A QuickTime file that starts with {@code moov} and keeps its AAC description in {@code wave}. The
 * sample is the raw head of "Сплин - Остаемся зимовать.mov" — {@code head -c 2M}, not ffmpeg, which
 * would write the missing ftyp — passed in as {@code mov-no-ftyp.mov} with -PmediaTestDir=...;
 * without it only {@link #fieldIsThere()} runs.
 */
public class QuickTimeWithoutFtypTest {

    static {
        ExtractorHarness.prepareJvm();
    }

    /** Holds Media3 updates to the private field the wrapper writes. */
    @Test
    public void fieldIsThere() {
        assertTrue(QuickTimeWithoutFtyp.available());
    }

    @Test
    public void stockExtractorLosesTheSound() throws IOException {
        final ExtractorHarness.Result result = ExtractorHarness.readTracks(
                new Mp4Extractor(new DefaultSubtitleParserFactory()), testFile());
        assertEquals(MimeTypes.VIDEO_MP4V, result.mime(C.TRACK_TYPE_VIDEO));
        assertNull(result.mime(C.TRACK_TYPE_AUDIO));
    }

    @Test
    public void wrappedExtractorKeepsTheSound() throws IOException {
        final Extractor[] extractors = QuickTimeWithoutFtyp.Factory.wrap(
                new Extractor[]{new Mp4Extractor(new DefaultSubtitleParserFactory())});
        final ExtractorHarness.Result result = ExtractorHarness.readTracks(extractors[0], testFile());
        assertEquals(MimeTypes.VIDEO_MP4V, result.mime(C.TRACK_TYPE_VIDEO));
        assertEquals(MimeTypes.AUDIO_AAC, result.mime(C.TRACK_TYPE_AUDIO));
    }

    private static File testFile() {
        return ExtractorHarness.testFile("mov-no-ftyp.mov");
    }
}
