package com.brouken.player;

import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;

/**
 * The buffer of the load control it wraps, never more than a share of the heap.
 *
 * <p>Streams are buffered by time here (fifteen to fifty seconds, time over size), and time is not
 * memory: fifteen seconds of a 248 Mbit/s 2160p demo is ~465 MB against a 384 MB heap on the Amlogic
 * box. While the link was slower than the file the buffer never got that far; once SMB read ahead
 * at 320 Mbit/s it filled the heap in twelve seconds, the collector then stalled every allocation for
 * 200 ms twice a second — loading fell to 60-150 Mbit/s and playback stopped three times — and the
 * second play ended in an OutOfMemoryError.
 *
 * <p>So loading pauses once the allocator holds {@link #capBytes()} and picks up again at nine
 * tenths of it — the gap keeps the loader from being switched on and off on every sample at the
 * line. It was three quarters, and on a heavy file that quarter was most of what lay ahead: at
 * 50 Mbit/s, with 26 MB of back buffer in the cap, the buffer swung between 14 and 20 s and was 17 s
 * when the Dune's Wi-Fi went for 30 s (20.10.2026). The wrapped control decides everything else, and
 * is asked as before whenever the cap is not what stops it.
 *
 * <p>The allocator also holds the back buffer, kept from the keyframe before it — and on a sparse GOP
 * that is most of it: the demo has keyframes at 0 and 8.3 s only, and by 6 s it held 274 MB with 50 ms
 * ahead. The cap stays put then, never standing aside for a short buffer: refusing to load under half
 * a second ahead is what makes the player discard the back buffer and ask again (ExoPlayerImplInternal,
 * {@code shouldContinueLoading}). A cap that let the loader on below a second, as the first version
 * did, kept that valve shut — the allocator reached 320 MB. Local playback goes through it too;
 * its own byte budget (~144 MB, size over time) binds first wherever the heap is the Dune's or larger.
 */
final class HeapCappedLoadControl implements LoadControl {

    /** Of the heap the app may grow to; the rest is the decoders' input, the interface and Glide. */
    private static final double HEAP_SHARE = 0.4;
    /** Of the cap that loading picks up again below. */
    private static final double RESUME_SHARE = 0.9;
    /** Ahead of the playhead that a full cap starts playback on. */
    private static final long START_ON_CAP_US = 1_000_000;

    private final LoadControl delegate;
    private final long cap;
    private final long resume;
    private boolean capped;

    HeapCappedLoadControl(final LoadControl delegate) {
        this(delegate, capBytes());
    }

    HeapCappedLoadControl(final LoadControl delegate, final long cap) {
        this.delegate = delegate;
        this.cap = cap;
        this.resume = (long) (cap * RESUME_SHARE);
    }

    /** The most the buffer holds: 154 MB on the Dune's 384 MB heap. */
    static long capBytes() {
        return (long) (Runtime.getRuntime().maxMemory() * HEAP_SHARE);
    }

    @Override
    public boolean shouldContinueLoading(final Parameters parameters) {
        final long held = delegate.getAllocator(parameters.playerId).getTotalBytesAllocated();
        if (held >= cap) {
            capped = true;
        } else if (held < resume) {
            capped = false;
        }
        return !capped && delegate.shouldContinueLoading(parameters);
    }

    @Override
    public void onPrepared(final PlayerId playerId) {
        capped = false;
        delegate.onPrepared(playerId);
    }

    @Override
    public void onTracksSelected(final Parameters parameters, final TrackGroupArray trackGroups,
            final ExoTrackSelection[] trackSelections) {
        delegate.onTracksSelected(parameters, trackGroups, trackSelections);
    }

    @Override
    public void onStopped(final PlayerId playerId) {
        capped = false;
        delegate.onStopped(playerId);
    }

    @Override
    public void onReleased(final PlayerId playerId) {
        capped = false;
        delegate.onReleased(playerId);
    }

    @Override
    public Allocator getAllocator(final PlayerId playerId) {
        return delegate.getAllocator(playerId);
    }

    @Override
    public long getBackBufferDurationUs(final PlayerId playerId) {
        return delegate.getBackBufferDurationUs(playerId);
    }

    @Override
    public boolean retainBackBufferFromKeyframe(final PlayerId playerId) {
        return delegate.retainBackBufferFromKeyframe(playerId);
    }

    @Override
    public boolean shouldContinuePreloading(final PlayerId playerId, final Timeline timeline,
            final MediaSource.MediaPeriodId mediaPeriodId, final long bufferedDurationUs) {
        return delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs);
    }

    /**
     * A second ahead with the cap reached starts playback whatever the wrapped control waits for. Its
     * five seconds after a stall are about what the cap holds of a 2160p demo, and with time over size it
     * has no byte escape of its own — DefaultLoadControl starts on a full byte budget only when size comes
     * first — so the player would wait, not loading, for seconds that cannot come. Playing on drains the
     * buffer to the half second at which the back buffer goes. With less than a second it does not:
     * starting on a tenth of a second was a stall a moment later, 113 of them in one 17 s clip.
     */
    @Override
    public boolean shouldStartPlayback(final Parameters parameters) {
        return (capped && parameters.bufferedDurationUs >= START_ON_CAP_US)
                || delegate.shouldStartPlayback(parameters);
    }
}
