package com.brouken.player.skip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.media3.common.C;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ChapterSegmentsSourceTest {

    private static final double DURATION = 1440; // a 24-minute episode

    private static FileChapters chapters(FileChapters.Entry... entries) {
        return FileChapters.of(Arrays.asList(entries));
    }

    private static FileChapters.Entry ch(long startMs, String title) {
        return new FileChapters.Entry(startMs, C.TIME_UNSET, title, false);
    }

    private static SkipSource fixed(SkipSegment... segments) {
        return d -> new ArrayList<>(Arrays.asList(segments));
    }

    private static SkipSegment net(double start, double end, SkipSegment.Category category) {
        return new SkipSegment(start, end, SkipSegment.Type.SKIP, category,
                SkipSegment.CoordBase.ABSOLUTE, SkipSegment.TIME_TRUST_ABSOLUTE);
    }

    /** "Opening" is the only named chapter: the intro is the chapter's, the credits the other source's. */
    private static final FileChapters OPENING_ONLY = chapters(ch(0, "Prologue"), ch(90_000, "Opening"),
            ch(180_000, "Part A"), ch(720_000, "Part B"));

    @Test
    public void creditsComeFromTheOtherSourceWhenChaptersNameOnlyTheIntro() {
        final List<SkipSegment> s = new ChapterSegmentsSource(OPENING_ONLY, fixed(
                net(85, 175, SkipSegment.Category.INTRO),
                net(1310, 1400, SkipSegment.Category.CREDITS))).getSegments(DURATION);
        assertEquals(2, s.size());
        assertEquals(90, s.get(0).startSec, 0);
        assertEquals(SkipSegment.CoordBase.CHAPTER, s.get(0).coordBase);
        assertEquals(1310, s.get(1).startSec, 0);
        assertEquals(SkipSegment.Category.CREDITS, s.get(1).category);
    }

    @Test
    public void anIntroElsewhereIsStillTheChaptersKind() {
        // The other source puts its intro somewhere the chapters do not: the chapter is the file's own.
        final List<SkipSegment> s = new ChapterSegmentsSource(OPENING_ONLY, fixed(
                net(400, 480, SkipSegment.Category.INTRO))).getSegments(DURATION);
        assertEquals(1, s.size());
        assertEquals(90, s.get(0).startSec, 0);
    }

    @Test
    public void overlapDropsWhateverTheKind() {
        // A recap that runs into the chapter intro would skip part of it twice.
        final List<SkipSegment> s = new ChapterSegmentsSource(OPENING_ONLY, fixed(
                net(60, 100, SkipSegment.Category.RECAP))).getSegments(DURATION);
        assertEquals(1, s.size());
        assertEquals(SkipSegment.Category.INTRO, s.get(0).category);
    }

    @Test
    public void launcherSegmentsAreSortedByPosition() {
        // Intent segments carry no kind: one ending early is an intro, one in the closing stretch credits.
        final List<SkipSegment> s = new ChapterSegmentsSource(OPENING_ONLY, fixed(
                new SkipSegment(88, 178, SkipSegment.Type.SKIP),
                new SkipSegment(1305, 1440, SkipSegment.Type.SKIP))).getSegments(DURATION);
        assertEquals(2, s.size());
        assertEquals(90, s.get(0).startSec, 0);
        assertEquals(1305, s.get(1).startSec, 0);
    }

    @Test
    public void adsStayUnlessTheyOverlap() {
        final List<SkipSegment> s = new ChapterSegmentsSource(OPENING_ONLY, fixed(
                new SkipSegment(600, 630, SkipSegment.Type.AD),
                new SkipSegment(100, 110, SkipSegment.Type.AD))).getSegments(DURATION);
        assertEquals(2, s.size());
        assertEquals(SkipSegment.Type.AD, s.get(1).type);
        assertEquals(600, s.get(1).startSec, 0);
    }

    @Test
    public void noOtherSource() {
        assertEquals(1, new ChapterSegmentsSource(OPENING_ONLY, null).getSegments(DURATION).size());
    }

    @Test
    public void kindUnknownWithoutTheDuration() {
        assertNull(ChapterSegmentsSource.kindOf(new SkipSegment(1305, 1440, SkipSegment.Type.SKIP), 0));
        assertEquals(SkipSegment.Category.CREDITS,
                ChapterSegmentsSource.kindOf(new SkipSegment(1305, 1440, SkipSegment.Type.SKIP), DURATION));
        assertEquals(SkipSegment.Category.RECAP,
                ChapterSegmentsSource.kindOf(net(0, 30, SkipSegment.Category.RECAP), 0));
    }
}
