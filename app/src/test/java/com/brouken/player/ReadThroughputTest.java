package com.brouken.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ReadThroughputTest {

    private static final long MS = 1_000_000L;

    @Test
    public void zeroBeforeTheFirstRead() {
        assertEquals(0, new ReadThroughput().bitrate());
    }

    @Test
    public void bytesOverTheTimeSpentReading() {
        final ReadThroughput meter = new ReadThroughput();
        // 1 MiB in 80 ms of waiting, twice: 104.9 Mbit/s whatever the gap between the reads.
        meter.add(1_000, 1 << 20, 80 * MS);
        meter.add(3_000, 1 << 20, 80 * MS);
        assertEquals(104_857_600L, meter.bitrate());
    }

    @Test
    public void readsServedFromMemoryCountTheirBytesAgainstTheWaitThatFetchedThem() {
        final ReadThroughput meter = new ReadThroughput();
        // The SMB window: one fetch waits for the whole mebibyte and hands out a slice of it, the
        // slices after it come from memory. The rate is the mebibyte over that one wait.
        meter.add(1_000, 4096, 80 * MS);
        for (int i = 1; i < 256; i++) {
            meter.add(1_000 + i, 4096, 0);
        }
        assertEquals(104_857_600L, meter.bitrate());
    }

    @Test
    public void anIdleLoaderKeepsTheLastFigure() {
        final ReadThroughput meter = new ReadThroughput();
        meter.add(1_000, 1 << 20, 80 * MS);
        // A full buffer: nothing read for a minute. The link is as fast as it last was.
        assertEquals(104_857_600L, meter.bitrate());
    }

    @Test
    public void oldReadsFallOutOfTheWindow() {
        final ReadThroughput meter = new ReadThroughput();
        meter.add(1_000, 1 << 20, 80 * MS);
        // Twice as slow now; once the fast read is older than the window, only the slow one counts.
        meter.add(10_000, 1 << 20, 160 * MS);
        assertEquals(52_428_800L, meter.bitrate());
    }

    @Test
    public void resetForgetsTheLastFigure() {
        final ReadThroughput meter = new ReadThroughput();
        meter.add(1_000, 1 << 20, 80 * MS);
        meter.reset();
        assertEquals(0, meter.bitrate());
    }
}
