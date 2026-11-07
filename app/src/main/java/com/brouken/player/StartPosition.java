package com.brouken.player;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

/**
 * The start position another app sends, under the names players disagree on: {@code position} (ours,
 * MX Player's), {@code startfrom} and {@code resume_position} (Nova and the apps written for it), in
 * milliseconds, as an Integer or a Long. Everything downstream reads {@code position} as an int, so
 * whatever came is written back under that name once, as the intent arrives.
 */
final class StartPosition {

    static final String POSITION = "position";
    static final String START_FROM = "startfrom";
    static final String RESUME_POSITION = "resume_position";

    private StartPosition() {
    }

    /**
     * Rewrites the intent's start position as an int {@code position}; nothing when it already is one or
     * no name carries one. Returns the name it was read from, for the log, or null.
     */
    @Nullable
    @SuppressWarnings("deprecation") // Bundle.get: the type is exactly what is not agreed on
    static String normalize(final Intent intent) {
        final Bundle extras = intent.getExtras();
        if (extras == null) {
            return null;
        }
        final Object own = extras.get(POSITION);
        if (own instanceof Integer) {
            return null;
        }
        final String[] names = {POSITION, START_FROM, RESUME_POSITION};
        for (final String name : names) {
            final long ms = millis(extras.get(name));
            if (ms >= 0) {
                intent.putExtra(POSITION, (int) ms);
                return name;
            }
        }
        return null;
    }

    /** An Integer or a Long that fits an int and is not negative, else -1. */
    static long millis(@Nullable final Object value) {
        final long ms;
        if (value instanceof Integer) {
            ms = (Integer) value;
        } else if (value instanceof Long) {
            ms = (Long) value;
        } else {
            return -1;
        }
        return ms >= 0 && ms <= Integer.MAX_VALUE ? ms : -1;
    }
}
