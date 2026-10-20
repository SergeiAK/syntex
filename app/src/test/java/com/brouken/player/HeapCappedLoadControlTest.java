package com.brouken.player;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.upstream.Allocation;
import androidx.media3.exoplayer.upstream.Allocator;

import org.junit.Test;

public class HeapCappedLoadControlTest {

    private static final long CAP = 100;

    /** Holds what it is told to and always wants more: the time-over-size control on a fast link. */
    private static final class Greedy implements LoadControl {

        int held;

        final Allocator allocator = new Allocator() {
            @Override
            public Allocation allocate() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void release(final Allocation allocation) {
            }

            @Override
            public void release(final AllocationNode allocationNode) {
            }

            @Override
            public void trim() {
            }

            @Override
            public int getTotalBytesAllocated() {
                return held;
            }

            @Override
            public int getIndividualAllocationLength() {
                return 1;
            }
        };

        @Override
        public Allocator getAllocator(final PlayerId playerId) {
            return allocator;
        }

        @Override
        public boolean shouldContinueLoading(final Parameters parameters) {
            return true;
        }

        @Override
        public boolean shouldStartPlayback(final Parameters parameters) {
            // Five seconds after a stall, and no byte escape.
            return parameters.bufferedDurationUs >= 5_000_000;
        }
    }

    private static LoadControl.Parameters ahead(final long bufferedUs) {
        return new LoadControl.Parameters(PlayerId.UNSET, Timeline.EMPTY, LoadControl.EMPTY_MEDIA_PERIOD_ID,
                0, bufferedUs, 1f, true, true, 0, 0);
    }

    @Test
    public void loadsUpToTheCapAndPausesThere() {
        final Greedy greedy = new Greedy();
        final HeapCappedLoadControl control = new HeapCappedLoadControl(greedy, CAP);
        greedy.held = 99;
        assertTrue(control.shouldContinueLoading(ahead(10_000_000)));
        greedy.held = 100;
        assertFalse(control.shouldContinueLoading(ahead(10_000_000)));
    }

    @Test
    public void picksUpAgainOnlyBelowThreeQuarters() {
        final Greedy greedy = new Greedy();
        final HeapCappedLoadControl control = new HeapCappedLoadControl(greedy, CAP);
        greedy.held = 100;
        control.shouldContinueLoading(ahead(10_000_000));
        greedy.held = 80;
        assertFalse(control.shouldContinueLoading(ahead(10_000_000)));
        greedy.held = 74;
        assertTrue(control.shouldContinueLoading(ahead(10_000_000)));
        greedy.held = 90;
        assertTrue(control.shouldContinueLoading(ahead(10_000_000)));
    }

    @Test
    public void staysPutUnderHalfASecondSoThePlayerDropsTheBackBuffer() {
        final Greedy greedy = new Greedy();
        final HeapCappedLoadControl control = new HeapCappedLoadControl(greedy, CAP);
        greedy.held = 150;
        assertFalse(control.shouldContinueLoading(ahead(100_000)));
        // The player has discarded the back buffer and asks again.
        greedy.held = 20;
        assertTrue(control.shouldContinueLoading(ahead(100_000)));
    }

    @Test
    public void aFullCapStartsPlaybackOnASecondAhead() {
        final Greedy greedy = new Greedy();
        final HeapCappedLoadControl control = new HeapCappedLoadControl(greedy, CAP);
        greedy.held = 100;
        control.shouldContinueLoading(ahead(3_000_000));
        assertTrue(control.shouldStartPlayback(ahead(3_000_000)));
        assertFalse(control.shouldStartPlayback(ahead(200_000)));
    }

    @Test
    public void belowTheCapTheWrappedControlDecidesTheStart() {
        final Greedy greedy = new Greedy();
        final HeapCappedLoadControl control = new HeapCappedLoadControl(greedy, CAP);
        greedy.held = 50;
        control.shouldContinueLoading(ahead(3_000_000));
        assertFalse(control.shouldStartPlayback(ahead(3_000_000)));
        assertTrue(control.shouldStartPlayback(ahead(5_000_000)));
    }
}
