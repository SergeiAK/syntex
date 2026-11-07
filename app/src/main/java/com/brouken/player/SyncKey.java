package com.brouken.player;

import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * What a file is remembered by: its address, less the part that names the machine.
 *
 * <p>One film on the NAS is {@code smb://192.168.1.40/video/Film.mkv} on the box that added the
 * share by its address and {@code smb://nas/video/Film.mkv} on the one that found it by name - two
 * strings for one file, and so two positions that never meet once they are carried between boxes.
 * For a share or a WebDAV server the host, port and credentials are dropped and what is left is the
 * share and the path, {@code smb:///video/Film.mkv}; WebDAV over https and over http is one key too.
 * Everything else keeps its address whole: a torrent server, a media server and an online stream are
 * where they say they are, and a {@code content://} uri is only ever this device's.
 *
 * <p>ponytail: two different servers each with a share of the same name and the same path are one
 * key. Rare at home, and the cost is a position shown on the wrong one of two copies.
 *
 * <p>Strings in, strings out, with no {@link android.net.Uri}: the browser and the player both hold
 * addresses as strings, and the unit tests run where {@code Uri} is a stub.
 */
final class SyncKey {

    private SyncKey() {
    }

    @Nullable
    static String of(@Nullable final String uri) {
        if (uri == null) {
            return null;
        }
        final int colon = uri.indexOf("://");
        if (colon <= 0) {
            return uri;
        }
        final String scheme = uri.substring(0, colon).toLowerCase(Locale.ROOT);
        final String torrent = torrentKey(scheme, uri);
        if (torrent != null) {
            return torrent;
        }
        final boolean dav = DavFiles.SCHEME.equals(scheme) || DavFiles.SCHEME_SECURE.equals(scheme);
        if (!dav && !SmbSessions.SCHEME.equals(scheme)) {
            return uri;
        }
        final int path = uri.indexOf('/', colon + 3);
        return (dav ? DavFiles.SCHEME : scheme) + "://" + (path < 0 ? "" : uri.substring(path));
    }

    /**
     * One file of a torrent on a TorrServer, by its infohash and index, whichever way the address was
     * written: {@code /stream/Name.mkv?link=<hash>&index=2&play} as its own M3U lists it,
     * {@code /play/<hash>/2}, or our {@code torr://…?h=<hash>&i=2}. The server, its port and a
     * reverse proxy's prefix drop out, as they do for a share. Null when the address is not one of
     * these or the link is a magnet rather than a bare hash.
     */
    @Nullable
    static String torrentKey(final String scheme, final String uri) {
        String hash;
        String index;
        if (TorrFiles.SCHEME.equals(scheme) || TorrFiles.SCHEME_SECURE.equals(scheme)) {
            hash = query(uri, "h");
            index = query(uri, "i");
        } else if ("http".equals(scheme) || "https".equals(scheme)) {
            final int q = uri.indexOf('?');
            final String path = (q < 0 ? uri : uri.substring(0, q));
            final int play = path.indexOf("/play/");
            if (path.contains("/stream")) {
                hash = query(uri, "link");
                index = query(uri, "index");
            } else if (play >= 0) {
                final String[] parts = path.substring(play + 6).split("/");
                hash = parts.length >= 2 ? parts[0] : null;
                index = parts.length >= 2 ? parts[1] : null;
            } else {
                return null;
            }
        } else {
            return null;
        }
        if (hash == null || index == null || !hash.matches("[0-9a-fA-F]{40}") || !index.matches("\\d+")) {
            return null;
        }
        return "torr:///" + hash.toLowerCase(Locale.ROOT) + "/" + Integer.parseInt(index);
    }

    @Nullable
    private static String query(final String uri, final String name) {
        final int q = uri.indexOf('?');
        if (q < 0) {
            return null;
        }
        int end = uri.indexOf('#', q);
        for (final String pair : uri.substring(q + 1, end < 0 ? uri.length() : end).split("&")) {
            final int eq = pair.indexOf('=');
            if ((eq < 0 ? pair : pair.substring(0, eq)).equals(name)) {
                return eq < 0 ? "" : pair.substring(eq + 1);
            }
        }
        return null;
    }

    /** Whether a key means anything on another device: a {@code content://} grant does not. */
    static boolean portable(final String key) {
        return !key.startsWith("content:");
    }
}
