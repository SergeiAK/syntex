package com.brouken.player.skip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.C;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class FileChaptersTest {

    private static FileChapters.Entry ch(long startMs, long endMs, String title) {
        return new FileChapters.Entry(startMs, endMs, title, false);
    }

    private static FileChapters of(FileChapters.Entry... entries) {
        return FileChapters.of(Arrays.asList(entries));
    }

    @Test
    public void namesThatMarkSomethingToSkip() {
        assertEquals(SkipSegment.Category.INTRO, FileChapters.categoryOf("Intro"));
        assertEquals(SkipSegment.Category.INTRO, FileChapters.categoryOf("OPENING"));
        assertEquals(SkipSegment.Category.INTRO, FileChapters.categoryOf("Opening - Kaikai Kitan"));
        assertEquals(SkipSegment.Category.INTRO, FileChapters.categoryOf("Intro (song)"));
        assertEquals(SkipSegment.Category.INTRO, FileChapters.categoryOf("OP"));
        assertEquals(SkipSegment.Category.INTRO, FileChapters.categoryOf("OP2"));
        assertEquals(SkipSegment.Category.INTRO, FileChapters.categoryOf("NCOP 1"));
        assertEquals(SkipSegment.Category.INTRO, FileChapters.categoryOf("Opening 2"));
        assertEquals(SkipSegment.Category.INTRO, FileChapters.categoryOf("Заставка"));
        assertEquals(SkipSegment.Category.INTRO, FileChapters.categoryOf("Опенинг"));
        assertEquals(SkipSegment.Category.RECAP, FileChapters.categoryOf("Previously On"));
        assertEquals(SkipSegment.Category.RECAP, FileChapters.categoryOf("Ранее в сериале"));
        assertEquals(SkipSegment.Category.CREDITS, FileChapters.categoryOf("End Credits"));
        assertEquals(SkipSegment.Category.CREDITS, FileChapters.categoryOf("End-Credits"));
        assertEquals(SkipSegment.Category.CREDITS, FileChapters.categoryOf("ED"));
        assertEquals(SkipSegment.Category.CREDITS, FileChapters.categoryOf("Ending"));
        assertEquals(SkipSegment.Category.CREDITS, FileChapters.categoryOf("Титры"));
        assertEquals(SkipSegment.Category.CREDITS, FileChapters.categoryOf("Эндинг"));
        assertEquals(SkipSegment.Category.PREVIEW, FileChapters.categoryOf("Preview"));
        assertEquals(SkipSegment.Category.PREVIEW, FileChapters.categoryOf("Анонс"));
    }

    @Test
    public void storyChaptersAreNotSkipped() {
        assertNull(FileChapters.categoryOf(null));
        assertNull(FileChapters.categoryOf(""));
        assertNull(FileChapters.categoryOf("Chapter 1"));
        assertNull(FileChapters.categoryOf("Глава 3"));
        assertNull(FileChapters.categoryOf("Introduction to the case"));
        assertNull(FileChapters.categoryOf("Mid-credits scene"));
        assertNull(FileChapters.categoryOf("Post-Credits"));
        assertNull(FileChapters.categoryOf("Part A"));
        assertNull(FileChapters.categoryOf("Opera"));
        assertNull(FileChapters.categoryOf("Edward"));
    }

    @Test
    public void sortedByStartWhateverOrderTheyCameIn() {
        final FileChapters c = of(ch(5000, 9000, "B"), ch(0, 5000, "A"), ch(9000, 12000, "C"));
        assertEquals("A", c.entries().get(0).title);
        assertEquals("B", c.entries().get(1).title);
        assertEquals("C", c.entries().get(2).title);
    }

    @Test
    public void unknownEndIsTheNextStartAndTheLastOneTheDuration() {
        final FileChapters c = of(ch(0, C.TIME_UNSET, "A"), ch(4000, C.TIME_UNSET, "B"));
        assertEquals(4000, c.endMs(0, 10_000));
        assertEquals(10_000, c.endMs(1, 10_000));
        assertEquals(C.TIME_UNSET, c.endMs(1, C.TIME_UNSET));
    }

    @Test
    public void skipSegmentsFromNamedChapters() {
        final FileChapters c = of(ch(0, C.TIME_UNSET, "Prologue"), ch(60_000, C.TIME_UNSET, "Opening"),
                ch(150_000, C.TIME_UNSET, "Part A"), ch(1_260_000, C.TIME_UNSET, "Ending"),
                ch(1_350_000, C.TIME_UNSET, "Preview"));
        final List<SkipSegment> s = c.skipSegments(1_380);
        assertEquals(3, s.size());
        assertEquals(60, s.get(0).startSec, 0);
        assertEquals(150, s.get(0).endSec, 0);
        assertEquals(SkipSegment.Category.INTRO, s.get(0).category);
        assertEquals(SkipSegment.CoordBase.CHAPTER, s.get(0).coordBase);
        assertEquals(SkipSegment.TIME_TRUST_CHAPTER, s.get(0).timeTrust);
        assertEquals(SkipSegment.Type.SKIP, s.get(0).type);
        assertEquals(SkipSegment.Category.CREDITS, s.get(1).category);
        assertEquals(1350, s.get(1).endSec, 0);
        assertEquals(SkipSegment.Category.PREVIEW, s.get(2).category);
        assertEquals(1380, s.get(2).endSec, 0);
    }

    @Test
    public void lastChapterWaitsForTheDuration() {
        final FileChapters c = of(ch(0, C.TIME_UNSET, "Part A"), ch(1_260_000, C.TIME_UNSET, "Credits"));
        assertTrue(c.skipSegments(0).isEmpty());
        assertEquals(1, c.skipSegments(1_380).size());
    }

    @Test
    public void adjacentChaptersOfOneKindMerge() {
        final FileChapters c = of(ch(0, 30_000, "Intro"), ch(30_000, 60_000, "Opening"),
                ch(60_000, 900_000, "Episode"));
        final List<SkipSegment> s = c.skipSegments(900);
        assertEquals(1, s.size());
        assertEquals(0, s.get(0).startSec, 0);
        assertEquals(60, s.get(0).endSec, 0);
    }

    @Test
    public void hiddenChaptersStillSkipButAreNotStepped() {
        final FileChapters c = FileChapters.of(Arrays.asList(
                new FileChapters.Entry(0, 30_000, "Intro", true), ch(30_000, 600_000, "Episode")));
        assertEquals(1, c.skipSegments(600).size());
        assertFalse(c.canStep());
        assertEquals(C.TIME_UNSET, c.nextStartMs(0));
    }

    @Test
    public void stepping() {
        final FileChapters c = of(ch(0, 0, "1"), ch(60_000, 0, "2"), ch(120_000, 0, "3"));
        assertTrue(c.canStep());
        assertEquals(60_000, c.nextStartMs(0));
        assertEquals(120_000, c.nextStartMs(60_000));
        // Just short of a chapter start counts as being there: the next one is the one after.
        assertEquals(120_000, c.nextStartMs(59_500));
        assertEquals(C.TIME_UNSET, c.nextStartMs(130_000));
        // Well into a chapter: back to its start; just past it: to the one before.
        assertEquals(60_000, c.previousStartMs(90_000));
        assertEquals(0, c.previousStartMs(61_000));
        assertEquals(C.TIME_UNSET, c.previousStartMs(1_000));
        assertEquals(0, c.previousStartMs(10_000));
    }

    @Test
    public void lateFirstChapter() {
        final FileChapters c = of(ch(30_000, 0, "1"), ch(60_000, 0, "2"));
        assertEquals(30_000, c.nextStartMs(0));
        assertEquals(C.TIME_UNSET, c.previousStartMs(5_000));
    }

    @Test
    public void oneChapterIsNothingToStep() {
        assertFalse(of(ch(0, 100_000, "Movie")).canStep());
        assertFalse(FileChapters.NONE.canStep());
        assertFalse(FileChapters.NONE.hasSkipChapters());
    }

    @Test
    public void equalityIsByContent() {
        assertEquals(of(ch(0, 1, "a"), ch(5, 6, "b")), of(ch(5, 6, "b"), ch(0, 1, "a")));
    }
}
