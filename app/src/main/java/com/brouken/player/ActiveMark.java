package com.brouken.player;

/**
 * How a control-row button that is on (subtitles shown, a quality picked by hand, an update waiting) says so
 * (Settings → Interface → Active button mark). The accent on the glyph alone was all it had, and in the
 * Monochrome theme the accent is the glyph's own white (user, 11.11.2026). A mark under the glyph reads in
 * every theme; on a focused button it turns dark with the glyph, inside the focus shape.
 */
enum ActiveMark {
    /** A 3.2dp dot under the glyph. The default. */
    DOT("dot"),
    /** A 12dp dash under the glyph. */
    DASH("dash");

    static final String PREF_KEY = "activeMark";

    /** How far the glyph is raised, in the 40dp button's dp, so glyph and mark centre in the shape together. */
    static final float GLYPH_RAISE_DP = 2.5f;
    /** Where the mark's centre sits below the button's centre, in the same dp. */
    static final float MARK_BELOW_CENTRE_DP = 13.5f;

    final String key;

    ActiveMark(final String key) {
        this.key = key;
    }

    static ActiveMark of(final String key) {
        for (final ActiveMark mark : values()) {
            if (mark.key.equals(key)) {
                return mark;
            }
        }
        return DOT;
    }
}
