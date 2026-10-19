package com.brouken.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RemainingTimeTest {

    private static final long MIN = 60_000;

    @Test
    public void normalSpeedIsWhatIsLeftOfTheFile() {
        assertEquals(60 * MIN, RemainingTime.wallClockMs(120 * MIN, 60 * MIN, 1f));
    }

    @Test
    public void fasterTakesLessOfTheViewersTime() {
        assertEquals(40 * MIN, RemainingTime.wallClockMs(120 * MIN, 60 * MIN, 1.5f));
        assertEquals(30 * MIN, RemainingTime.wallClockMs(120 * MIN, 60 * MIN, 2f));
    }

    @Test
    public void slowerTakesMore() {
        assertEquals(120 * MIN, RemainingTime.wallClockMs(120 * MIN, 60 * MIN, 0.5f));
    }

    @Test
    public void pastTheEndIsZero() {
        assertEquals(0, RemainingTime.wallClockMs(60 * MIN, 61 * MIN, 1.5f));
    }

    @Test
    public void noSpeedCountsAsNormal() {
        assertEquals(60 * MIN, RemainingTime.wallClockMs(120 * MIN, 60 * MIN, 0f));
        assertEquals(60 * MIN, RemainingTime.wallClockMs(120 * MIN, 60 * MIN, Float.NaN));
    }
}
