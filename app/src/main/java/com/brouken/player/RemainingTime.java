package com.brouken.player;

/**
 * How much of the viewer's own time a video still takes: what is left of the file, at the speed it plays.
 *
 * <p>The countdown on the bar and the "until …" clock both say this, and both have to say it the same way:
 * at 1.5x an hour of film is forty minutes in front of the screen, and a bar reading "-1:00:00" next to an
 * end forty minutes away contradicts itself. Just+ 2.4.5 fixed the same mismatch. Pure, so it is tested
 * without a player.
 */
final class RemainingTime {

    private RemainingTime() {
    }

    /**
     * @param durationMs the length of the file
     * @param positionMs where playback is in it
     * @param speed      the speed the player actually plays at; anything not above zero counts as 1x
     * @return the wall-clock time until the end, never negative
     */
    static long wallClockMs(long durationMs, long positionMs, float speed) {
        final long left = Math.max(0, durationMs - positionMs);
        if (!(speed > 0f)) {
            return left;
        }
        return Math.round(left / (double) speed);
    }
}
