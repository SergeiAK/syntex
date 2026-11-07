package com.brouken.player;

import androidx.annotation.Nullable;

/**
 * The subtitle offset set by hand, kept for the file and for its series ({@link TrackMemory#seriesKey}).
 * A file opened again comes back with its own offset; a file not seen before takes its series' last one,
 * since the episodes of one release are usually out by the same amount. Each of the two subtitle lines
 * has its own, as in the panel.
 *
 * <p>Zero is kept like any other value for the series — set back to zero, the series is in sync — but a
 * file at zero is written as no entry, so the store only grows with the files that needed a shift.
 *
 * <p>Pure Java over {@link Store}, so it is tested on the JVM; the player gives it a SharedPreferences.
 */
final class SubtitleOffsetMemory {

    /** Where the values live: a SharedPreferences in the app, a map in the tests. */
    interface Store {
        @Nullable
        String get(String key);

        /** {@code value} null removes the key. */
        void put(String key, @Nullable String value);
    }

    /** What was found for a file, and whether it came from the file itself or from its series. */
    static final class Found {
        final double sec;
        final boolean fromSeries;

        Found(double sec, boolean fromSeries) {
            this.sec = sec;
            this.fromSeries = fromSeries;
        }
    }

    static final int MAIN = 1;
    static final int SECONDARY = 2;

    private final Store store;

    SubtitleOffsetMemory(Store store) {
        this.store = store;
    }

    /** The file's offset, else the series', else null; {@code line} is {@link #MAIN} or {@link #SECONDARY}. */
    @Nullable
    Found find(@Nullable String fileKey, @Nullable String seriesKey, int line) {
        final Double file = read(fileKey == null ? null : "file:" + line + ":" + fileKey);
        if (file != null) {
            return new Found(file, false);
        }
        final Double series = read(seriesKey == null ? null : "series:" + line + ":" + seriesKey);
        return series == null ? null : new Found(series, true);
    }

    /** Writes the offset chosen by hand for the file and its series. */
    void remember(@Nullable String fileKey, @Nullable String seriesKey, int line, double sec) {
        final String value = Math.abs(sec) < 0.0005 ? null : Double.toString(sec);
        if (fileKey != null) {
            store.put("file:" + line + ":" + fileKey, value);
        }
        if (seriesKey != null) {
            store.put("series:" + line + ":" + seriesKey, value == null ? "0" : value);
        }
    }

    @Nullable
    private Double read(@Nullable String key) {
        if (key == null) {
            return null;
        }
        final String value = store.get(key);
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
