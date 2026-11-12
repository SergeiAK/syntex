package com.brouken.player.skip;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Typed launcher segments ({@code type}) and how they are weighed against the lookup. */
public class LauncherSegmentsTest {

    private static final double DURATION = 3700;

    private static SkipSegment net(double start, double end, SkipSegment.Category category, boolean confirmed) {
        final SkipSegment seg = new SkipSegment(start, end, SkipSegment.Type.SKIP, category,
                SkipSegment.CoordBase.DURATION_AWARE, SkipSegment.TIME_TRUST_DURATION_AWARE);
        seg.confirmed = confirmed;
        return seg;
    }

    private static List<SkipSegment> launcher(String json) {
        return new IntentSegmentsSource(json).getSegments(DURATION);
    }

    @Test
    public void typeIsReadIntoTheCategory() {
        final IntentSegmentsSource source = new IntentSegmentsSource("{\"skip\":["
                + "{\"start\":28,\"end\":90,\"type\":\"intro\"},"
                + "{\"start\":3632,\"end\":3699,\"type\":\"Outro\"},"
                + "{\"start\":0,\"end\":53,\"type\":\"recap\"},"
                + "{\"start\":3699,\"end\":3700,\"type\":\"preview\"}]}");
        assertTrue(source.isTyped());
        final List<SkipSegment> segments = source.getSegments(DURATION);
        assertEquals(4, segments.size());
        assertEquals(SkipSegment.Category.INTRO, segments.get(0).category);
        assertEquals(SkipSegment.Category.CREDITS, segments.get(1).category);
        assertEquals(SkipSegment.Category.RECAP, segments.get(2).category);
        assertEquals(SkipSegment.Category.PREVIEW, segments.get(3).category);
        assertEquals(SkipSegment.TIME_TRUST_CHAPTER, segments.get(0).timeTrust);
    }

    @Test
    public void aRecapAroundTheFoundIntroIsDropped() {
        // «American Dad» S5E7, 12.11.2026: Jellyfin's recap 0-67, the lookup's intro 48-60, no recap at all.
        final List<SkipSegment> merged = SegmentFinder.mergeLauncher(
                launcher("{\"skip\":[{\"start\":0,\"end\":67,\"type\":\"recap\"}]}"),
                Arrays.asList(net(48, 60, SkipSegment.Category.INTRO, true),
                        net(1097, 1298, SkipSegment.Category.CREDITS, false)));
        assertEquals(2, merged.size());
        assertEquals(SkipSegment.Category.INTRO, merged.get(0).category);
        assertEquals(48, merged.get(0).startSec, 0.001);
        assertEquals(SkipSegment.Category.CREDITS, merged.get(1).category);
    }

    @Test
    public void aRecapBeforeTheFoundIntroStays() {
        final List<SkipSegment> merged = SegmentFinder.mergeLauncher(
                launcher("{\"skip\":[{\"start\":0,\"end\":40,\"type\":\"recap\"}]}"),
                Arrays.asList(net(48, 60, SkipSegment.Category.INTRO, true)));
        assertEquals(2, merged.size());
        assertEquals(SkipSegment.Category.RECAP, merged.get(0).category);
    }

    @Test
    public void commercialGoesWithTheAds() {
        final List<SkipSegment> segments = launcher(
                "{\"skip\":[{\"start\":600,\"end\":660,\"type\":\"commercial\"}]}");
        assertEquals(1, segments.size());
        assertEquals(SkipSegment.Type.AD, segments.get(0).type);
    }

    @Test
    public void lampaShapeStaysUntyped() {
        final IntentSegmentsSource source = new IntentSegmentsSource(
                "{\"duration_ms\":3700000,\"skip\":[{\"start\":28,\"end\":90}],\"ad\":[{\"start\":600,\"end\":660}]}");
        assertFalse(source.isTyped());
        final List<SkipSegment> segments = source.getSegments(DURATION);
        assertEquals(SkipSegment.Category.UNKNOWN, segments.get(0).category);
        assertEquals(SkipSegment.TIME_TRUST_ABSOLUTE, segments.get(0).timeTrust);
    }

    @Test
    public void unknownWordReadsAsNoType() {
        assertFalse(new IntentSegmentsSource(
                "{\"skip\":[{\"start\":28,\"end\":90,\"type\":\"opening\"}]}").isTyped());
    }

    @Test
    public void typeSurvivesTheRemapToALongerFile() {
        final List<SkipSegment> segments = new IntentSegmentsSource("{\"duration_ms\":3600000,\"skip\":["
                + "{\"start\":120,\"end\":180,\"type\":\"intro\"},"
                + "{\"start\":3540,\"end\":3600,\"type\":\"outro\"}]}").getSegments(DURATION);
        assertEquals(2, segments.size());
        assertEquals(SkipSegment.Category.INTRO, segments.get(0).category);
        assertEquals(SkipSegment.Category.CREDITS, segments.get(1).category);
    }

    @Test
    public void agreeingTakesKeepTheLauncherTimingConfirmed() {
        final List<SkipSegment> merged = SegmentFinder.mergeLauncher(
                launcher("{\"skip\":[{\"start\":28,\"end\":90,\"type\":\"intro\"}]}"),
                Arrays.asList(net(35, 92, SkipSegment.Category.INTRO, false)));
        assertEquals(1, merged.size());
        assertEquals(28, merged.get(0).startSec, 0.001);
        assertTrue(merged.get(0).confirmed);
    }

    @Test
    public void confirmedLookupOutvotesALoneLauncherTake() {
        final List<SkipSegment> merged = SegmentFinder.mergeLauncher(
                launcher("{\"skip\":[{\"start\":0,\"end\":53,\"type\":\"recap\"}]}"),
                Arrays.asList(net(300, 340, SkipSegment.Category.RECAP, true)));
        assertEquals(1, merged.size());
        assertEquals(300, merged.get(0).startSec, 0.001);
    }

    @Test
    public void oneAgainstOneGoesToTheLauncher() {
        final List<SkipSegment> merged = SegmentFinder.mergeLauncher(
                launcher("{\"skip\":[{\"start\":28,\"end\":90,\"type\":\"intro\"}]}"),
                Arrays.asList(net(400, 460, SkipSegment.Category.INTRO, false)));
        assertEquals(1, merged.size());
        assertEquals(28, merged.get(0).startSec, 0.001);
        assertFalse(merged.get(0).confirmed);
    }

    @Test
    public void eachSideFillsWhatTheOtherDidNotName() {
        final List<SkipSegment> merged = SegmentFinder.mergeLauncher(
                launcher("{\"skip\":[{\"start\":0,\"end\":53,\"type\":\"recap\"}]}"),
                Arrays.asList(net(60, 120, SkipSegment.Category.INTRO, true),
                        net(3630, 3700, SkipSegment.Category.CREDITS, false)));
        assertEquals(3, merged.size());
        assertEquals(SkipSegment.Category.RECAP, merged.get(0).category);
        assertEquals(SkipSegment.Category.INTRO, merged.get(1).category);
        assertEquals(SkipSegment.Category.CREDITS, merged.get(2).category);
    }

    @Test
    public void adsPassThroughAndInputsStayUnchanged() {
        final List<SkipSegment> from = launcher("{\"skip\":["
                + "{\"start\":28,\"end\":90,\"type\":\"intro\"},"
                + "{\"start\":600,\"end\":660,\"type\":\"commercial\"}]}");
        final List<SkipSegment> online = new ArrayList<>(Arrays.asList(
                net(30, 90, SkipSegment.Category.INTRO, false)));
        final List<SkipSegment> merged = SegmentFinder.mergeLauncher(from, online);
        assertEquals(2, merged.size());
        assertEquals(SkipSegment.Type.AD, merged.get(1).type);
        assertFalse(from.get(0).confirmed);
        assertEquals(1, online.size());
    }
}
