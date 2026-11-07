package com.brouken.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LineSyncTest {

    /** A line every 2 s from 0 s to 198 s. */
    private static long[] starts() {
        final long[] s = new long[100];
        for (int i = 0; i < s.length; i++) s[i] = i * 2_000_000L;
        return s;
    }

    @Test
    public void linesAroundWhatIsOnScreen() {
        // At 101 s with no offset, line 50 (100 s) is on screen.
        final int[][] r = LineSync.around(starts(), 101_000_000L, 0, 180);
        assertEquals(50 - LineSync.BEFORE + 1, r[0][0]);
        assertEquals(50 + LineSync.AFTER, r[0][r[0].length - 1]);
        assertEquals(50, r[0][r[1][0]]);
    }

    @Test
    public void theOffsetMovesWhereTheFileStands() {
        // Subtitles 10 s late: at 101 s the file shows 91 s, line 45.
        final int[][] r = LineSync.around(starts(), 101_000_000L, 10, 180);
        assertEquals(45, r[0][r[1][0]]);
    }

    @Test
    public void beforeTheFirstLine() {
        final int[][] r = LineSync.around(new long[]{5_000_000L, 9_000_000L}, 1_000_000L, 0, 180);
        assertArrayEquals(new int[]{0, 1}, r[0]);
        assertEquals(0, r[1][0]);
    }

    @Test
    public void outOfReachIsLeftOut() {
        // Reach of 5 s at 101 s: only lines starting 96..106 s.
        final int[][] r = LineSync.around(starts(), 101_000_000L, 0, 5);
        assertArrayEquals(new int[]{48, 49, 50, 51, 52, 53}, r[0]);
        assertEquals(50, r[0][r[1][0]]);
    }

    @Test
    public void offsetPutsTheLineNow() {
        assertEquals(1.25, LineSync.offsetFor(100_000_000L, 101_250_000L), 1e-9);
        assertEquals(-3.0, LineSync.offsetFor(104_000_000L, 101_000_000L), 1e-9);
    }

    @Test
    public void oneLineFlattensAndCuts() {
        assertEquals("Где ты был?", LineSync.oneLine("Где ты\n  был? ", 80));
        assertEquals("abcd…", LineSync.oneLine("abcdefgh", 5));
    }

    @Test
    public void emptyFile() {
        assertEquals(0, LineSync.around(new long[0], 1_000_000L, 0, 180)[0].length);
    }
}
