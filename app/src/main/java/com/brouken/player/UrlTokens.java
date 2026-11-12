package com.brouken.player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Access tokens written into a URL's host or path rather than its query, hidden for the log and reports.
 * KinoPub puts them there: {@code /hls/<token>.mp4/master.m3u8}, {@code /manifest/hls4/<token>/8224.m3u8},
 * {@code /pd/<base64>/subtitles/…}, a UUID as the CDN host's first label (12.11.2026, logs of 0154).
 * A run of 24 or more letters, digits, {@code _ - =} with at least one letter and one digit is taken for
 * one; a file name keeps its dots and percent escapes, which break such a run, so it stays readable.
 */
final class UrlTokens {

    static final String MARK = "<token>";

    private static final Pattern URL = Pattern.compile("(?:https?|rtsps?)://[^\s,)}\]\"']+");
    private static final Pattern RUN = Pattern.compile("[A-Za-z0-9_=-]{24,}");
    private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");

    private UrlTokens() {
    }

    /** The text with every token-like run inside its URLs replaced by {@link #MARK}. */
    static String hide(final String text) {
        if (text == null || text.indexOf("://") < 0) {
            return text;
        }
        final Matcher url = URL.matcher(text);
        final StringBuffer out = new StringBuffer(text.length());
        while (url.find()) {
            url.appendReplacement(out, Matcher.quoteReplacement(hideRuns(url.group())));
        }
        url.appendTail(out);
        return out.toString();
    }

    private static String hideRuns(final String url) {
        final Matcher run = RUN.matcher(url);
        final StringBuffer out = new StringBuffer(url.length());
        while (run.find()) {
            final String found = run.group();
            final boolean token = LETTER.matcher(found).find() && DIGIT.matcher(found).find();
            run.appendReplacement(out, Matcher.quoteReplacement(token ? MARK : found));
        }
        run.appendTail(out);
        return out.toString();
    }
}
