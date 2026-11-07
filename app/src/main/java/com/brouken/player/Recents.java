package com.brouken.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * The files last opened from the browser, newest first: what the Recent destination lists. Like a
 * favorite, a recent file is a name and an address and nothing else; the row is read again from the
 * address when it is drawn. Only what the browser itself opened - a film started from LAMPA is for
 * that app to remember.
 */
final class Recents {

    static final String PREF_ENABLED = "browseRecent";
    static final int MAX = 20;

    private static final String PREFS = "recents";
    private static final String KEY_LIST = "list";

    static final class Recent {
        final String name;
        final String uri;

        Recent(String name, String uri) {
            this.name = name;
            this.uri = uri;
        }
    }

    private Recents() {
    }

    static boolean enabled(Context context) {
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_ENABLED, false);
    }

    static List<Recent> all(Context context) {
        return parse(prefs(context).getString(KEY_LIST, ""));
    }

    /** Puts the file on top; opened again, it moves up rather than being listed twice. Off: nothing kept. */
    static void record(Context context, String name, Uri uri) {
        if (!enabled(context)) {
            return;
        }
        final SharedPreferences prefs = prefs(context);
        prefs.edit().putString(KEY_LIST, push(prefs.getString(KEY_LIST, ""), name, uri.toString())).apply();
    }

    /** The stored list with {@code name, uri} on top, the same address dropped below, cut to {@link #MAX}. */
    static String push(String stored, String name, String uri) {
        final StringBuilder out = new StringBuilder();
        out.append(clean(name)).append('\t').append(uri);
        int n = 1;
        for (String line : stored.split("\n")) {
            final String[] parts = line.split("\t", -1);
            if (parts.length < 2 || parts[1].isEmpty() || parts[1].equals(uri)) continue;
            if (n >= MAX) break;
            out.append('\n').append(line);
            n++;
        }
        return out.toString();
    }

    static List<Recent> parse(String stored) {
        final List<Recent> found = new ArrayList<>();
        for (String line : stored.split("\n")) {
            final String[] parts = line.split("\t", -1);
            if (parts.length < 2 || parts[1].isEmpty()) continue;
            found.add(new Recent(parts[0], parts[1]));
        }
        return found;
    }

    private static String clean(String name) {
        return name == null ? "" : name.replace('\t', ' ').replace('\n', ' ');
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
