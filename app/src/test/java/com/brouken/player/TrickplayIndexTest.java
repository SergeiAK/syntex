package com.brouken.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TrickplayIndexTest {

    /** "Anastasia" on the owner's library: 1:34:11, 6 sheets "320 - 10x10", 565 frames at 10 s. */
    private static final long ANASTASIA_MS = (94 * 60 + 11) * 1000L;

    @Test
    public void folderIsNamedAfterTheFileWithoutItsExtension() {
        assertEquals("Анастасия (1997) {tmdb-9444} - [1080p Blu-ray].trickplay",
                TrickplayIndex.folderFor("Анастасия (1997) {tmdb-9444} - [1080p Blu-ray].mkv"));
        assertEquals("Film.2024.trickplay", TrickplayIndex.folderFor("Film.2024.mp4"));
        assertEquals("noext.trickplay", TrickplayIndex.folderFor("noext"));
    }

    @Test
    public void layoutFromTheFolderName() {
        assertArrayEquals(new int[] {320, 10, 10}, TrickplayIndex.layout("320 - 10x10"));
        assertArrayEquals(new int[] {480, 5, 8}, TrickplayIndex.layout("480-5x8"));
        assertNull(TrickplayIndex.layout("Thumbs"));
        assertNull(TrickplayIndex.layout("0 - 10x10"));
        assertNull(TrickplayIndex.layout(null));
    }

    @Test
    public void picksThePreferredWidthThenTheNearestWider() {
        assertEquals(1, TrickplayIndex.pick(new String[] {"640 - 10x10", "320 - 10x10", "160 - 10x10"}));
        assertEquals(1, TrickplayIndex.pick(new String[] {"640 - 10x10", "480 - 10x10", "160 - 10x10"}));
        assertEquals(1, TrickplayIndex.pick(new String[] {"120 - 10x10", "240 - 10x10", "junk"}));
        assertEquals(-1, TrickplayIndex.pick(new String[] {"junk"}));
    }

    @Test
    public void sheetNames() {
        assertEquals(0, TrickplayIndex.sheetNumber("0.jpg"));
        assertEquals(12, TrickplayIndex.sheetNumber("12.JPG"));
        assertEquals(-1, TrickplayIndex.sheetNumber("Thumbs.db"));
        assertEquals(-1, TrickplayIndex.sheetNumber(".jpg"));
        assertEquals(-1, TrickplayIndex.sheetNumber("1a.jpg"));
    }

    @Test
    public void tenSecondsWhenTheSheetCountAgrees() {
        final TrickplayIndex index = TrickplayIndex.of("320 - 10x10", 6, ANASTASIA_MS);
        assertNotNull(index);
        assertEquals(10_000, index.intervalMs);
        assertEquals(320, index.width);
    }

    @Test
    public void locatesTheFrameAtOrBeforeThePosition() {
        final TrickplayIndex index = TrickplayIndex.of("320 - 10x10", 6, ANASTASIA_MS);
        // 43:35 is frame 261: sheet 2, cell 61 — column 1 of row 6.
        assertArrayEquals(new int[] {2, 1, 6}, index.locate((43 * 60 + 35) * 1000L));
        assertArrayEquals(new int[] {0, 0, 0}, index.locate(0));
        assertArrayEquals(new int[] {0, 0, 0}, index.locate(9_999));
        assertArrayEquals(new int[] {0, 1, 0}, index.locate(10_000));
        // Past the end, the last frame the sheets can hold rather than a sheet that is not there.
        assertArrayEquals(new int[] {5, 9, 9}, index.locate(ANASTASIA_MS * 2));
        assertArrayEquals(new int[] {0, 0, 0}, index.locate(-5_000));
    }

    @Test
    public void anotherRoundIntervalWhenTenSecondsCannotBe() {
        // 1:34:11 at 5 s is 1131 frames: 12 sheets of 100.
        final TrickplayIndex index = TrickplayIndex.of("320 - 10x10", 12, ANASTASIA_MS);
        assertEquals(5_000, index.intervalMs);
    }

    @Test
    public void estimatedFromTheLengthWhenNoRoundIntervalFits() {
        // 40 sheets for 1:34:11 is 1.41 s a frame: none of the round intervals give 40 sheets.
        final TrickplayIndex index = TrickplayIndex.of("320 - 10x10", 40, ANASTASIA_MS);
        assertNotNull(index);
        assertEquals(1431, index.intervalMs);
    }

    @Test
    public void nothingForASetThatCannotBeTheFilms() {
        assertNull(TrickplayIndex.of("320 - 10x10", 0, ANASTASIA_MS));
        assertNull(TrickplayIndex.of("junk", 6, ANASTASIA_MS));
        assertNull(TrickplayIndex.of("320 - 10x10", 6, 0));
        // A ten-minute file with 50 sheets would be a frame every 0.12 s: not this film's set.
        assertNull(TrickplayIndex.of("320 - 10x10", 50, 600_000));
    }
}
