package com.brouken.player;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This device's side of {@link SyncData}: gathering it out of where the player keeps things, putting
 * a merged document back, and the file it travels in when it is carried by hand.
 *
 * <p>Three switches decide what goes, each on its own: positions, watched marks, settings. They are
 * read on both ends - an export leaves out what is off here, and an import takes nothing into a
 * section that is off here, whatever the file holds.
 */
final class Sync {

    static final String PREF_POSITIONS = "syncPositions";
    static final String PREF_WATCHED = "syncWatched";
    static final String PREF_SETTINGS = "syncSettings";

    /**
     * Settings that describe this box and its screen rather than the viewer, so they stay where
     * they are: what the Shield and the Dune need for frame rate, audio and decoding is not the same
     * (decided 30.10.2026). The nickname is a person in a room, and two boxes under one name are
     * one person twice; the room password is a secret and has no business in a file on a share.
     * The sync switches themselves stay too, or an import could switch off what it was asked to do.
     */
    private static final Set<String> STAYS_HERE = new HashSet<>(Arrays.asList(
            "frameRateMatching", "refreshRateMultiple", "skip24p", "displayResolutionMatching",
            "modeSwitchPauseMs", "allowSystemFrameRate",
            "audioPassthrough", "audioPassthroughForce", "audioSyncMs", "audioPassthroughSyncMs",
            "decoderPriority", "tunneling", "mapDV7ToHevc", "refuseDolbyVision", "removeHdr10Plus",
            "backBufferMs", "allFilesAccess",
            "togetherNick", "togetherPassword",
            PREF_POSITIONS, PREF_WATCHED, PREF_SETTINGS));

    /** When each setting was last changed by hand here - kept apart from the settings themselves. */
    private static final String TIMES = "sync_setting_times";

    /**
     * Values being written by an import, with the time they carry. The change listener runs after
     * the write, on the main thread, so it is told the time this way rather than by a flag that the
     * write would have long cleared.
     */
    private static final Map<String, Long> ARRIVING = new ConcurrentHashMap<>();

    // Held here: SharedPreferences keeps its listeners weakly, and a lambda nobody holds is collected.
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;
    private static Set<String> settingKeys;

    private Sync() {
    }

    /**
     * Starts writing down when each setting is changed, which is what a merge decides by. A value
     * that was never changed has no time and loses to any that has one - it is a default, or a
     * choice made before this was kept.
     */
    static void watchSettings(final Context context) {
        final Context app = context.getApplicationContext();
        final Set<String> keys = settingKeys(app);
        final SharedPreferences times = app.getSharedPreferences(TIMES, Context.MODE_PRIVATE);
        listener = (prefs, key) -> {
            if (key == null || !keys.contains(key)) {
                return;
            }
            final Long carried = ARRIVING.remove(key);
            times.edit().putLong(key, carried != null ? carried : System.currentTimeMillis()).apply();
        };
        PreferenceManager.getDefaultSharedPreferences(app).registerOnSharedPreferenceChangeListener(listener);
    }

    /**
     * The settings that travel: every key on the settings screen, less those that stay here. Read
     * off the screen's own XML, so a setting added there travels without being listed twice.
     */
    static synchronized Set<String> settingKeys(final Context context) {
        if (settingKeys != null) {
            return settingKeys;
        }
        final Set<String> keys = new LinkedHashSet<>();
        try (XmlResourceParser xml = context.getResources().getXml(R.xml.root_preferences)) {
            for (int event = xml.next(); event != XmlPullParser.END_DOCUMENT; event = xml.next()) {
                if (event != XmlPullParser.START_TAG) {
                    continue;
                }
                for (int i = 0; i < xml.getAttributeCount(); i++) {
                    if ("key".equals(xml.getAttributeName(i))) {
                        keys.add(xml.getAttributeValue(i));
                    }
                }
            }
        } catch (final Exception e) {
            Utils.log("sync: settings keys unreadable: " + e);
        }
        keys.removeAll(STAYS_HERE);
        settingKeys = Collections.unmodifiableSet(keys);
        return settingKeys;
    }

    /** What this device has, in the sections switched on here. */
    static SyncData collect(final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final SyncData data = new SyncData();
        data.device = deviceName(context);
        data.written = System.currentTimeMillis();
        if (prefs.getBoolean(PREF_POSITIONS, true)) {
            final Map<String, Long> lengths = Prefs.readLengths(context);
            final Map<String, Long> times = Prefs.readPositionTimes(context);
            for (final Map.Entry<String, Long> entry : Prefs.readPositions(context).entrySet()) {
                final String key = entry.getKey();
                if (!SyncKey.portable(key)) {
                    continue;
                }
                final Long length = lengths.get(key);
                final Long at = times.get(key);
                data.positions.put(key, new SyncData.Position(at == null ? 0 : at, entry.getValue(),
                        length == null ? 0 : length));
            }
        }
        if (prefs.getBoolean(PREF_WATCHED, true)) {
            for (final Map.Entry<String, Long> entry : Prefs.readWatched(context).entrySet()) {
                if (SyncKey.portable(entry.getKey())) {
                    final long at = entry.getValue();
                    data.watched.put(entry.getKey(), new SyncData.Mark(Math.abs(at), at > 0));
                }
            }
        }
        if (prefs.getBoolean(PREF_SETTINGS, true)) {
            final SharedPreferences times = context.getSharedPreferences(TIMES, Context.MODE_PRIVATE);
            final Map<String, ?> all = prefs.getAll();
            for (final String key : settingKeys(context)) {
                final Object value = all.get(key);
                if (value != null) {
                    data.settings.put(key, new SyncData.Setting(times.getLong(key, 0), value));
                }
            }
        }
        return data;
    }

    /**
     * Takes from {@code incoming} what is newer than what is here, in the sections switched on here,
     * and writes it where the player reads it. Off the main thread: it is files.
     */
    static SyncData.Taken apply(final Context context, final SyncData incoming) {
        final SyncData here = collect(context);
        final SyncData offered = new SyncData();
        // Only what this device would have sent itself: a section switched off here stays as it is,
        // and a setting that stays here, or that this build does not have, is not taken.
        if (sectionOn(context, PREF_POSITIONS)) {
            offered.positions.putAll(incoming.positions);
        }
        if (sectionOn(context, PREF_WATCHED)) {
            offered.watched.putAll(incoming.watched);
        }
        if (sectionOn(context, PREF_SETTINGS)) {
            for (final Map.Entry<String, SyncData.Setting> entry : incoming.settings.entrySet()) {
                if (settingKeys(context).contains(entry.getKey())) {
                    offered.settings.put(entry.getKey(), entry.getValue());
                }
            }
        }
        final SyncData.Taken taken = here.merge(offered);
        for (final String key : taken.positions) {
            final SyncData.Position p = here.positions.get(key);
            Prefs.rememberSynced(context, key, p.position, p.length, p.at);
        }
        for (final String key : taken.watched) {
            final SyncData.Mark mark = here.watched.get(key);
            Prefs.markWatched(context, key, mark.watched, mark.at);
        }
        if (!taken.settings.isEmpty()) {
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            final Map<String, ?> all = prefs.getAll();
            final SharedPreferences.Editor editor = prefs.edit();
            for (final String key : new LinkedHashSet<>(taken.settings)) {
                final SyncData.Setting setting = here.settings.get(key);
                final Object had = all.get(key);
                // A value of another type than the one stored here is from a build that kept the
                // key differently; written, it would throw on the next read of it.
                if (had != null && had.getClass() != setting.value.getClass()
                        && !(had instanceof Set && setting.value instanceof Set)) {
                    taken.settings.remove(key);
                    continue;
                }
                ARRIVING.put(key, setting.at);
                put(editor, key, setting.value);
            }
            editor.commit();
        }
        return taken;
    }

    private static boolean sectionOn(final Context context, final String key) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(key, true);
    }

    @SuppressWarnings("unchecked")
    private static void put(final SharedPreferences.Editor editor, final String key, final Object value) {
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Set) {
            editor.putStringSet(key, (Set<String>) value);
        }
    }

    /** What the box is called in its own settings, for the file name and the reader of the file. */
    static String deviceName(final Context context) {
        String name = null;
        try {
            name = Settings.Global.getString(context.getContentResolver(), "device_name");
        } catch (final Exception ignored) {
            // A box that keeps no name of its own is named by its model below.
        }
        return name == null || name.trim().isEmpty() ? Build.MODEL : name.trim();
    }

    /** Whether an export can write into this folder: one on the device, on a share, or a granted tree. */
    static boolean canWriteInto(final Uri folder) {
        final String scheme = folder.getScheme();
        // A share is a folder inside one: the host itself, listing its shares, holds no files.
        return "file".equals(scheme) || DavFiles.speaks(folder)
                || SmbSessions.SCHEME.equals(scheme) && !folder.getPathSegments().isEmpty()
                || ContentResolver.SCHEME_CONTENT.equals(scheme);
    }

    /**
     * Writes this device's document into a folder as a new file, never over one already there -
     * {@code syntex-<box>-<date>.json}, with a number after it when the minute is taken.
     *
     * @return the name it was written under
     */
    static String exportTo(final Context context, final Uri folder) throws IOException, JSONException {
        final byte[] content = collect(context).toJson().getBytes(StandardCharsets.UTF_8);
        final String base = "syntex-" + deviceName(context).replaceAll("[^A-Za-z0-9._-]+", "_") + "-"
                + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date());
        for (int n = 1; n <= 9; n++) {
            final String name = base + (n == 1 ? "" : "-" + n) + ".json";
            if (create(context, folder, name, content)) {
                return name;
            }
        }
        throw new IOException("Every name for " + base + " is taken");
    }

    /** Reads a document and takes from it what is newer (see {@link #apply}). */
    static SyncData.Taken importFrom(final Context context, final Uri file) throws IOException, JSONException {
        final String text = new String(SubtitleSave.read(context, file), StandardCharsets.UTF_8);
        return apply(context, SyncData.fromJson(text));
    }

    /** @return false when the name is taken */
    private static boolean create(final Context context, final Uri folder, final String name,
                                  final byte[] content) throws IOException {
        final String scheme = folder.getScheme();
        if ("file".equals(scheme)) {
            final File target = new File(folder.getPath(), name);
            if (!target.createNewFile()) {
                return false;
            }
            try (OutputStream out = new FileOutputStream(target)) {
                out.write(content);
            }
            return true;
        }
        if (SmbSessions.SCHEME.equals(scheme)) {
            return SmbSessions.create(context, folder.buildUpon().appendPath(name).build(), content);
        }
        if (DavFiles.speaks(folder)) {
            return DavFiles.create(context, folder.buildUpon().appendPath(name).build(), content,
                    "application/json");
        }
        final DocumentFile dir = DocumentFile.fromTreeUri(context, folder);
        if (dir == null) {
            throw new IOException("Not a folder: " + folder);
        }
        if (dir.findFile(name) != null) {
            return false;
        }
        final DocumentFile target = dir.createFile("application/json", name);
        if (target == null) {
            throw new IOException("Folder refused " + name);
        }
        try (OutputStream out = context.getContentResolver().openOutputStream(target.getUri())) {
            if (out == null) {
                throw new IOException("No stream for " + target.getUri());
            }
            out.write(content);
        }
        return true;
    }
}
