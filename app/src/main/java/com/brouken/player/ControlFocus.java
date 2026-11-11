package com.brouken.player;

/**
 * How the player's control row marks the button the remote is on (Settings → Interface → Button focus).
 *
 * <p>The glyphs are mostly wide rectangles (HD, picture-in-picture, subtitles) whose corners all but touch a
 * 36dp disc; the user asked for the alternatives as a setting rather than pick one from a mock-up
 * (11.11.2026). Sizes are in the phone's dp for a 40dp button; {@link #extraDp} widens the button for the
 * marks that do not fit in 40, and the icon keeps its size because the padding grows with it.
 */
enum ControlFocus {
    /**
     * A 44dp disc in a 48dp button around the glyph of before: air around the wide ones. The default, shown
     * as "Circle" — the 36dp disc of before 3.3 and the frame-only mark were dropped (user, 11.11.2026).
     */
    CIRCLE_LARGE("circle_large", 8),
    /** A 36dp square with 10dp corners, the shape most glyphs have. */
    SQUARE("square", 0),
    /** The 36dp disc and a thin ring around it across a gap, in a 44dp button. */
    CIRCLE_RING("circle_ring", 4),
    /** A 34dp square and a thin frame around it across a gap, in a 44dp button. */
    SQUARE_RING("square_ring", 4);

    static final String PREF_KEY = "controlFocus";

    final String key;
    final int extraDp;

    ControlFocus(final String key, final int extraDp) {
        this.key = key;
        this.extraDp = extraDp;
    }

    /** A stored value from the list, or the default for anything else (a dropped mark, a typo). */
    static ControlFocus of(final String key) {
        for (final ControlFocus focus : values()) {
            if (focus.key.equals(key)) {
                return focus;
            }
        }
        return CIRCLE_LARGE;
    }

    /** Round marks press with a round wash; the square ones with a square one. */
    boolean round() {
        return this == CIRCLE_LARGE || this == CIRCLE_RING;
    }
}
