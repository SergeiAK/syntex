package com.brouken.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NextEpisodeTest {

    private static final long HOUR = 3_600_000L;

    @Test
    public void creditsShowTheCardWhereTheyStart() {
        assertEquals(2_520_000, NextEpisode.showAtMs("credits", 2_600_000, 2_520_000));
    }

    @Test
    public void unknownCreditsFallBackToThirtySecondsBeforeTheEnd() {
        assertEquals(2_570_000, NextEpisode.showAtMs("credits", 2_600_000, -1));
        // Credits "starting" past the end are no credits.
        assertEquals(2_570_000, NextEpisode.showAtMs("credits", 2_600_000, 2_700_000));
        assertEquals(2_570_000, NextEpisode.showAtMs(null, 2_600_000, -1));
    }

    @Test
    public void aLeadCountsBackFromTheEnd() {
        assertEquals(2_540_000, NextEpisode.showAtMs("60", 2_600_000, 2_520_000));
        assertEquals(2_480_000, NextEpisode.showAtMs("120", 2_600_000, -1));
        assertEquals(0, NextEpisode.showAtMs("120", 90_000, -1));
    }

    @Test
    public void theEndAndAnUnknownDurationLeaveItToTheEndOfItemPause() {
        assertEquals(-1, NextEpisode.showAtMs("end", 2_600_000, 2_520_000));
        assertEquals(-1, NextEpisode.showAtMs("credits", 0, -1));
    }

    @Test
    public void countdownWaitsWhenSetToOrWhenNobodyIsAwake() {
        assertEquals(15, NextEpisode.countdownSec(15, HOUR, 2));
        assertEquals(0, NextEpisode.countdownSec(0, 0, 2));
        assertEquals(0, NextEpisode.countdownSec(15, 2 * HOUR + 1, 2));
        assertEquals(15, NextEpisode.countdownSec(15, 10 * HOUR, 0));
    }
}
