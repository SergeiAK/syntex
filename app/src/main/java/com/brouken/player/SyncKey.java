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
        final boolean dav = DavFiles.SCHEME.equals(scheme) || DavFiles.SCHEME_SECURE.equals(scheme);
        if (!dav && !SmbSessions.SCHEME.equals(scheme)) {
            return uri;
        }
        final int path = uri.indexOf('/', colon + 3);
        return (dav ? DavFiles.SCHEME : scheme) + "://" + (path < 0 ? "" : uri.substring(path));
    }

    /** Whether a key means anything on another device: a {@code content://} grant does not. */
    static boolean portable(final String key) {
        return !key.startsWith("content:");
    }
}
