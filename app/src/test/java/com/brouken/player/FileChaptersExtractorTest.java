package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.media3.extractor.text.SubtitleParser;

import com.brouken.player.skip.FileChapters;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The samples in resources/chapters were made with ffmpeg 24.10.2026 from a 12 s 64x36 MPEG-4 clip
 * and one ffmetadata file: Recap 0–2 s, "Opening - Song Title" 2–5 s, Part A 5–10 s, Ending 10–12 s.
 * ffmpeg gives Matroska chapters random UIDs, so Media3 hands them over in no particular order.
 */
public class FileChaptersExtractorTest {

    @BeforeClass
    public static void setUp() {
        ExtractorHarness.prepareJvm();
    }

    private static File sample(final String name) throws URISyntaxException {
        return new File(FileChaptersExtractorTest.class.getResource("/chapters/" + name).toURI());
    }

    /** The tracks as the player would report them, from the formats the extractor gave. */
    private static Tracks tracksOf(final ExtractorHarness.Result result) {
        final List<Tracks.Group> groups = new ArrayList<>();
        for (ExtractorHarness.Track t : result.tracks) {
            assertNotNull(t.format);
            groups.add(new Tracks.Group(new TrackGroup(t.format), false,
                    new int[]{C.FORMAT_HANDLED}, new boolean[]{true}));
        }
        return new Tracks(groups);
    }

    private static FileChapters read(final Extractor extractor, final String name) throws Exception {
        return FileChapters.from(tracksOf(ExtractorHarness.readTracks(extractor, sample(name))));
    }

    private static void assertSample(final FileChapters chapters) {
        final List<Long> starts = new ArrayList<>();
        final List<String> titles = new ArrayList<>();
        for (FileChapters.Entry e : chapters.entries()) {
            starts.add(e.startMs);
            titles.add(e.title);
        }
        assertEquals(Arrays.asList(0L, 2000L, 5000L, 10000L), starts);
        assertEquals(Arrays.asList("Recap", "Opening - Song Title", "Part A", "Ending"), titles);
        assertTrue(chapters.hasSkipChapters());
        assertTrue(chapters.canStep());
        assertEquals(5000, chapters.nextStartMs(2500));
    }

    @Test
    public void matroskaChaptersReachTheTrackFormat() throws Exception {
        assertSample(read(new MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED, 0), "chapters.mkv"));
    }

    @Test
    public void mp4ChaptersReachTheTrackFormat() throws Exception {
        assertSample(read(new Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED), "chapters.mp4"));
    }

    @Test
    public void noTracksNoChapters() {
        assertTrue(FileChapters.from(new Tracks(Collections.<Tracks.Group>emptyList())).isEmpty());
        assertTrue(FileChapters.from((Tracks) null).isEmpty());
        assertTrue(FileChapters.from(new Tracks(Collections.singletonList(new Tracks.Group(
                new TrackGroup(new Format.Builder().build()), false, new int[]{C.FORMAT_HANDLED},
                new boolean[]{true})))).isEmpty());
    }
}
