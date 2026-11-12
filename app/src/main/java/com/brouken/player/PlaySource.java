package com.brouken.player;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

import androidx.annotation.Nullable;

/**
 * Where what is playing comes from, said in the header (one short line) and on the statistics card (in
 * full): "Kinopub · Syntex HUB", "Torrent · LAMPA", "SMB · Syntex". The user asked to see it, because the
 * same film reaches the player along very different roads (12.11.2026).
 *
 * <p>Two parts. The <b>kind</b> is what the caller says ({@code source} in a playlist - which provider
 * Syntex HUB took it from, say), else what the address gives away: a torrent through TorrServer, SMB,
 * WebDAV, DLNA, a file on the device, an online stream. The <b>app</b> is who launched the player, by its
 * label; Syntex's own browser is "Syntex".
 */
final class PlaySource {

    /** A playlist's (or a plain launch's) name for where it comes from: "Kinopub", "Jellyfin". */
    static final String EXTRA_SOURCE = "syntex.source";

    /** What the header shows; null when nothing is known. */
    @Nullable
    final String kind;
    @Nullable
    final String app;
    /** The launching app's package, for the statistics card; null for Syntex's own launches. */
    @Nullable
    final String appPackage;

    private PlaySource(@Nullable final String kind, @Nullable final String app,
            @Nullable final String appPackage) {
        this.kind = kind;
        this.app = app;
        this.appPackage = appPackage;
    }

    /** "Kinopub · Syntex HUB"; null when neither part is known. */
    @Nullable
    String line() {
        if (kind == null) {
            return app;
        }
        return app == null ? kind : kind + " · " + app;
    }

    /**
     * @param declared the caller's own name for the source, or null
     * @param uri      what is playing
     */
    static PlaySource of(final Activity activity, @Nullable final Intent launch, @Nullable final String declared,
            @Nullable final Uri uri) {
        final String kind = declared != null && !declared.trim().isEmpty() ? declared.trim()
                : kindOf(activity, uri);
        if (launch != null && launch.getBooleanExtra(PlayerActivity.EXTRA_FROM_BROWSER, false)) {
            return new PlaySource(kind, activity.getString(R.string.app_name), null);
        }
        final String pkg = caller(activity);
        if (pkg == null || pkg.equals(activity.getPackageName())) {
            return new PlaySource(kind, null, null);
        }
        return new PlaySource(kind, label(activity, pkg), pkg);
    }

    @Nullable
    private static String kindOf(final Context context, @Nullable final Uri uri) {
        if (uri == null) {
            return null;
        }
        final String scheme = uri.getScheme();
        if (TorrFiles.speaks(uri) || torrServerStream(uri)) {
            return context.getString(R.string.source_torrent);
        }
        if (SmbSessions.SCHEME.equals(scheme)) {
            return "SMB";
        }
        if (DavFiles.SCHEME.equals(scheme) || DavFiles.SCHEME_SECURE.equals(scheme)) {
            return "WebDAV";
        }
        if (DlnaFiles.SCHEME.equals(scheme)) {
            return "DLNA";
        }
        if (Utils.isSupportedNetworkUri(uri)) {
            return context.getString(R.string.source_online);
        }
        if ("file".equals(scheme) || "content".equals(scheme)) {
            return context.getString(R.string.source_local);
        }
        return null;
    }

    /**
     * A TorrServer address as LAMPA and the server's own M3U write it - {@code /stream/Name.mkv?link=...}
     * or {@code /play/<hash>/<index>} - which is plain http and would otherwise read as "Online".
     */
    static boolean torrServerStream(@Nullable final Uri uri) {
        if (uri == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            return false;
        }
        final String path = uri.getPath();
        if (path == null) {
            return false;
        }
        if (path.contains("/stream/") || path.endsWith("/stream")) {
            return uri.getQueryParameter("link") != null;
        }
        final int play = path.indexOf("/play/");
        return play >= 0 && path.substring(play + 6).split("/").length >= 2;
    }

    /** The launching app's package: the referrer Android records for the start, when it records one. */
    @Nullable
    private static String caller(final Activity activity) {
        final String calling = activity.getCallingPackage();
        if (calling != null) {
            return calling;
        }
        final Uri referrer = activity.getReferrer();
        if (referrer != null && "android-app".equals(referrer.getScheme())) {
            return referrer.getHost();
        }
        return null;
    }

    private static String label(final Context context, final String pkg) {
        // Named here as well: from Android 11 another app's label is visible only to an app that declares
        // it, and these are the two that launch Syntex every evening.
        if (pkg.startsWith("top.rootu.lampa")) {
            return "LAMPA";
        }
        if (pkg.startsWith("com.syntex.hub")) {
            return "Syntex HUB";
        }
        try {
            final PackageManager pm = context.getPackageManager();
            final ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            final CharSequence label = pm.getApplicationLabel(info);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            // Not visible to this app (package visibility) or gone: the package name says enough.
        }
        return pkg;
    }
}
