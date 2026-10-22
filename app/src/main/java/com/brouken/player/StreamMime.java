package com.brouken.player;

import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;

import java.util.Locale;

/**
 * The mime type a launched stream goes to the player with. A launcher's mime only wins where the path
 * says nothing: Lampa sends video/* for everything, and a .m3u8 under that mime went down the progressive
 * path first, failed there and was recovered as HLS — a second request, and the last one a one-time link
 * would answer. The manifest extensions are what the path can tell for certain; anything else keeps the
 * launcher's word.
 */
final class StreamMime {

    private StreamMime() {
    }

    @Nullable
    static String of(@Nullable String path, @Nullable String launcherMime) {
        if (path != null) {
            final String lower = path.toLowerCase(Locale.US);
            if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")) {
                return MimeTypes.APPLICATION_M3U8;
            }
            if (lower.endsWith(".mpd")) {
                return MimeTypes.APPLICATION_MPD;
            }
            if (lower.endsWith(".ism") || lower.endsWith(".isml")
                    || lower.endsWith(".ism/manifest") || lower.endsWith(".isml/manifest")) {
                return MimeTypes.APPLICATION_SS;
            }
        }
        return launcherMime;
    }
}
