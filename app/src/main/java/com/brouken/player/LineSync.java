package com.brouken.player;

/**
 * Subtitle timing by a heard line: the lines of the file around the moment the list was opened, and
 * the offset that puts the one the viewer just heard at that moment. The cheap, by-hand half of
 * synchronisation — the viewer's ear does the matching, the list only has to hold the right lines.
 *
 * <p>The lines are taken around where the file stands under the current offset, since that is what is
 * on screen; more before than after, as a line is picked once it has been heard. Only lines the
 * panel's own reach can put at that moment are offered.
 *
 * <p>Pure Java over the cue start times, so it is tested on the JVM.
 */
final class LineSync {

    static final int BEFORE = 12;
    static final int AFTER = 6;

    private LineSync() {
    }

    /**
     * Indices into {@code startsUs} (ascending) to offer, ascending; {@code [0]} of the result's second
     * array is the one to focus — the last line that has started on screen, or the first offered.
     */
    static int[][] around(long[] startsUs, long nowUs, double offsetSec, double maxSec) {
        final long fileUs = nowUs - Math.round(offsetSec * 1_000_000);
        int current = -1;
        for (int i = 0; i < startsUs.length && startsUs[i] <= fileUs; i++) {
            current = i;
        }
        final int from = Math.max(0, current - BEFORE + 1);
        final int to = Math.min(startsUs.length - 1, Math.max(current, 0) + AFTER);
        final long reachUs = Math.round(maxSec * 1_000_000);
        int n = 0;
        final int[] picked = new int[Math.max(0, to - from + 1)];
        int focus = -1;
        for (int i = from; i <= to && startsUs.length > 0; i++) {
            if (Math.abs(nowUs - startsUs[i]) > reachUs) continue;
            if (i <= current || focus < 0 && i == from) focus = n;
            picked[n++] = i;
        }
        final int[] out = java.util.Arrays.copyOf(picked, n);
        return new int[][]{out, {Math.max(0, focus)}};
    }

    /** The offset, in seconds, that shows a line starting at {@code startUs} at {@code nowUs}. */
    static double offsetFor(long startUs, long nowUs) {
        // Rounded to the panel's finest step a slider can show (0.01 s); the ear is no finer.
        return Math.round((nowUs - startUs) / 10_000.0) / 100.0;
    }

    /** One line of the list: the cue's text on one line, cut to {@code max} characters. */
    static String oneLine(CharSequence text, int max) {
        final String flat = text.toString().replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1).trim() + "…";
    }
}
