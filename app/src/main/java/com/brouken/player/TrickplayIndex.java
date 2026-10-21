package com.brouken.player;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where one frame of a Jellyfin trickplay set is, by position — the arithmetic only, with nothing to
 * read or decode, so it can be tested on the JVM.
 *
 * <p>Jellyfin with "save trickplay images next to media" on writes, beside {@code Film.mkv}:
 * <pre>
 *     Film.trickplay/320 - 10x10/0.jpg, 1.jpg, ...
 * </pre>
 * Each sheet is a grid of {@code columns × rows} frames {@code width} pixels wide, in reading order,
 * one frame per interval from the start of the film. Measured on the owner's library (K:/Mult,
 * "Anastasia", 1:34:11): 6 sheets of 3200×1320, the last one filled to its 65th cell and black after
 * that — 565 frames, the count Jellyfin's API gives for the item.
 *
 * <p>The folder does not say the interval. Jellyfin's default is 10 s and it is what the owner's server
 * uses, so it is taken when the number of sheets agrees with it, and failing that the other round
 * intervals Jellyfin offers are tried; only then is it worked out from the length, as the middle of
 * what the sheet count allows. The black tail of the last sheet is not counted instead: a film that
 * ends on black credits would read as a shorter set.
 */
final class TrickplayIndex {

    /** "320 - 10x10": width, then the grid. */
    private static final Pattern LAYOUT = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)\\s*x\\s*(\\d+)");
    private static final long[] INTERVALS_MS = {10_000, 5_000, 1_000, 2_000, 15_000, 20_000, 30_000, 60_000};
    /** The frame is shown at 320 px of a 1080p screen; a wider set costs bytes for nothing. */
    static final int PREFERRED_WIDTH = 320;

    final int width;
    final int columns;
    final int rows;
    final int sheets;
    final long intervalMs;

    private TrickplayIndex(final int width, final int columns, final int rows, final int sheets,
                           final long intervalMs) {
        this.width = width;
        this.columns = columns;
        this.rows = rows;
        this.sheets = sheets;
        this.intervalMs = intervalMs;
    }

    /** The set's folder for a video: Jellyfin names it after the file without its extension. */
    static String folderFor(final String videoName) {
        final int dot = videoName.lastIndexOf('.');
        return (dot > 0 ? videoName.substring(0, dot) : videoName) + ".trickplay";
    }

    /** The layout a folder name states, as {width, columns, rows}, or null for anything else. */
    @Nullable
    static int[] layout(@Nullable final String folderName) {
        if (folderName == null) {
            return null;
        }
        final Matcher m = LAYOUT.matcher(folderName.trim());
        if (!m.matches()) {
            return null;
        }
        try {
            final int[] layout = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))};
            return layout[0] > 0 && layout[1] > 0 && layout[2] > 0 ? layout : null;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /**
     * Of the widths a set was made at, the one to read: the preferred width if there is one, else the
     * narrowest wider than it, else the widest narrower. Index into {@code folderNames}, or -1.
     */
    static int pick(final String[] folderNames) {
        int best = -1;
        int bestWidth = 0;
        for (int i = 0; i < folderNames.length; i++) {
            final int[] layout = layout(folderNames[i]);
            if (layout == null) {
                continue;
            }
            final int w = layout[0];
            if (best < 0 || better(w, bestWidth)) {
                best = i;
                bestWidth = w;
            }
        }
        return best;
    }

    private static boolean better(final int w, final int than) {
        final boolean wide = w >= PREFERRED_WIDTH;
        final boolean thanWide = than >= PREFERRED_WIDTH;
        if (wide != thanWide) {
            return wide;
        }
        return wide ? w < than : w > than;
    }

    /** True for a sheet's file name: "0.jpg", "12.jpg". */
    static int sheetNumber(@Nullable final String name) {
        if (name == null || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".jpg")) {
            return -1;
        }
        final String number = name.substring(0, name.length() - 4);
        if (number.isEmpty() || number.length() > 6) {
            return -1;
        }
        for (int i = 0; i < number.length(); i++) {
            if (!Character.isDigit(number.charAt(i))) {
                return -1;
            }
        }
        return Integer.parseInt(number);
    }

    /**
     * @param sheets the number of sheets, counted from 0 without gaps
     * @return null when the set cannot be the film's: no sheets, or more of them than its length
     *         could fill at any interval worth having
     */
    @Nullable
    static TrickplayIndex of(final String folderName, final int sheets, final long durationMs) {
        final int[] layout = layout(folderName);
        if (layout == null || sheets <= 0 || durationMs <= 0) {
            return null;
        }
        final long perSheet = (long) layout[1] * layout[2];
        for (final long interval : INTERVALS_MS) {
            final long frames = (durationMs + interval - 1) / interval;
            if (frames > (sheets - 1) * perSheet && frames <= sheets * perSheet) {
                return new TrickplayIndex(layout[0], layout[1], layout[2], sheets, interval);
            }
        }
        final long estimate = Math.round(durationMs / ((sheets - 0.5) * perSheet));
        return estimate >= 500
                ? new TrickplayIndex(layout[0], layout[1], layout[2], sheets, estimate) : null;
    }

    int perSheet() {
        return columns * rows;
    }

    /** The frame for a position, as {sheet, column, row}: the last one at or before it. */
    int[] locate(final long positionMs) {
        final long last = (long) sheets * perSheet() - 1;
        final long frame = Math.max(0, Math.min(last, positionMs / intervalMs));
        final int sheet = (int) (frame / perSheet());
        final int cell = (int) (frame % perSheet());
        return new int[] {sheet, cell % columns, cell / columns};
    }
}
