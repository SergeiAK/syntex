package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.ts.TsExtractor;

import java.io.IOException;
import java.util.List;

import org.junit.Test;

/**
 * Broadcast 1080i H.264 in TS: one PES per frame, PTS every 40 ms, both field pictures of the frame
 * inside it. {@code 1080i-fields.ts} — 188·30000 bytes of "Еда для всей семьи с Джейми (2016) S01E03"
 * (the file that stutters on Dune; ffmpeg pairs its fields into 25 frames a second, 40 ms apart).
 *
 * <p>Stock Media3 1.11 cuts the PES into one sample per field and gives both the PES time: the
 * decoder gets every frame as two input buffers with the same timestamp. This pins that behaviour.
 */
public class InterlacedTsFieldsTest {

    static {
        ExtractorHarness.prepareJvm();
    }

    @Test
    public void stockGivesBothFieldsThePesTime() throws IOException {
        final ExtractorHarness.Result result = ExtractorHarness.readSamples(
                new TsExtractor(new DefaultSubtitleParserFactory()),
                ExtractorHarness.testFile("1080i-fields.ts"), Integer.MAX_VALUE);
        final List<Long> t = result.track(C.TRACK_TYPE_VIDEO).timesUs;
        assertTrue(t.size() > 200);
        for (int i = 0; i + 1 < t.size(); i += 2) {
            assertEquals("pair at " + i, t.get(i), t.get(i + 1));
            if (i + 2 < t.size()) assertEquals(40_000, t.get(i + 2) - t.get(i));
        }
    }
}
