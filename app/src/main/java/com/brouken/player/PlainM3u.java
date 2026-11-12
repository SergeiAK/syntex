package com.brouken.player;

import androidx.annotation.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A plain M3U: a list of files, not an HLS stream. TorrServer hands one over for "Continue" and
 * "Play all" ({@code /playlist?hash=…}, {@code /playlistall/all.m3u}, {@code /stream/….m3u?…&m3u}),
 * and so do other servers' "play folder" links. Under an HLS mime the player took it for a live
 * stream whose segments were whole films. Here it is told apart — {@code #EXTM3U} with no
 * {@code #EXT-X-} tag anywhere — and read as titles and addresses, which then play as a playlist.
 *
 * <p>Strings only, no {@link android.net.Uri}, so the unit tests run on the JVM.
 */
final class PlainM3u {

    /** The largest list read: a season's worth of lines is a few kilobytes. */
    static final int MAX_BYTES = 512 * 1024;

    static final class Entry {
        final String uri;
        @Nullable final String title;

        Entry(final String uri, @Nullable final String title) {
            this.uri = uri;
            this.title = title;
        }
    }

    private PlainM3u() {
    }

    /**
     * Whether an address is worth asking whether it is a plain list: http(s), and a path or query
     * that says M3U without saying HLS. A {@code .m3u8} is always HLS and is never asked.
     */
    static boolean isCandidate(@Nullable final String uri) {
        if (uri == null) {
            return false;
        }
        final String lower = uri.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }
        final int q = lower.indexOf('?');
        final String path = q < 0 ? lower : lower.substring(0, q);
        final String query = q < 0 ? "" : lower.substring(q + 1);
        if (path.endsWith(".m3u8")) {
            return false;
        }
        return path.endsWith(".m3u") || path.endsWith("/playlist") || path.contains("/playlistall/")
                || ("&" + query + "&").contains("&m3u&") || ("&" + query + "&").contains("&m3u=");
    }

    /**
     * The entries of a plain list, addresses resolved against {@code base}; null when the text is not
     * one — no {@code #EXTM3U} header, an {@code #EXT-X-} tag (HLS), or no entry at all.
     */
    @Nullable
    static List<Entry> parse(final String text, final String base) {
        String body = text;
        if (body.startsWith("\uFEFF")) {
            body = body.substring(1);
        }
        if (!body.trim().startsWith("#EXTM3U") || body.contains("#EXT-X-")) {
            return null;
        }
        final List<Entry> entries = new ArrayList<>();
        String title = null;
        for (final String raw : body.split("\r?\n|\r")) {
            final String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#EXTINF:")) {
                final int comma = line.indexOf(',');
                title = comma >= 0 ? line.substring(comma + 1).trim() : null;
                if (title != null && title.isEmpty()) {
                    title = null;
                }
            } else if (!line.startsWith("#")) {
                final String resolved = resolve(base, line);
                if (resolved != null) {
                    entries.add(new Entry(resolved, title));
                }
                title = null;
            }
        }
        return entries.isEmpty() ? null : entries;
    }

    @Nullable
    private static String resolve(final String base, final String line) {
        try {
            return URI.create(base).resolve(line.replace(" ", "%20")).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
