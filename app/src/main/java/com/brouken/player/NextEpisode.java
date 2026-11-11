package com.brouken.player;

/**
 * When the card that asks before the next episode of a playlist shows, and whether it moves on by itself
 * (Settings → Video → Next episode). The user asked for it 11.11.2026: in a Syntex HUB playlist the next
 * episode followed without a word, and LAMPA's do too. Modelled on Wholphin's "Show next up", "Autoplay
 * next", its delay and its pass-out protection, with the extra moment of a fixed lead before the end.
 */
final class NextEpisode {

    private NextEpisode() {
    }

    static final String PREF_ASK = "nextEpisodeAsk";
    static final String PREF_WHEN = "nextEpisodeWhen";
    static final String PREF_COUNTDOWN = "nextEpisodeCountdown";
    static final String PREF_AWAKE = "nextEpisodeAwake";

    /** At the start of the credits: the credits segment that runs to the end, else this lead. */
    static final String WHEN_CREDITS = "credits";
    /** After the episode has ended, paused on its last frame. */
    static final String WHEN_END = "end";

    /** Where the credits are not known, the card is up this long before the end. */
    static final long CREDITS_FALLBACK_MS = 30_000;

    /**
     * The position at which the card shows, or -1 where only the end of the episode shows it (the
     * end-of-item pause does) or the duration is not known yet.
     *
     * @param when            {@link #WHEN_CREDITS}, {@link #WHEN_END}, or a lead in seconds ("30", "60", "120")
     * @param creditsStartMs  where credits that run to the end begin, or -1 where none are known
     */
    static long showAtMs(final String when, final long durationMs, final long creditsStartMs) {
        if (durationMs <= 0 || WHEN_END.equals(when)) {
            return -1;
        }
        if (WHEN_CREDITS.equals(when) || when == null) {
            if (creditsStartMs >= 0 && creditsStartMs < durationMs) {
                return creditsStartMs;
            }
            return Math.max(0, durationMs - CREDITS_FALLBACK_MS);
        }
        final long leadMs;
        try {
            leadMs = Long.parseLong(when) * 1000;
        } catch (final NumberFormatException e) {
            return Math.max(0, durationMs - CREDITS_FALLBACK_MS);
        }
        return Math.max(0, durationMs - leadMs);
    }

    /**
     * How many seconds the card counts before it moves on, or 0 where it waits for a press: a countdown
     * of 0 is set, or nobody has touched the remote for longer than the pass-out protection allows
     * (Wholphin's, default 2 h) — a viewer asleep since the third episode is not served the tenth.
     *
     * @param awakeHours 0 for no protection
     */
    static int countdownSec(final int countdownSec, final long sinceInputMs, final int awakeHours) {
        if (countdownSec <= 0) {
            return 0;
        }
        if (awakeHours > 0 && sinceInputMs > awakeHours * 3_600_000L) {
            return 0;
        }
        return countdownSec;
    }
}
