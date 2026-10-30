package com.brouken.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * What one device carries to another: where the viewer stopped in each file, which files are
 * watched, and the settings that mean the same on any box. One document for every way it travels -
 * a file exported by hand, a folder on the NAS, the hub - so the three are one format and one merge.
 *
 * <p>Every entry carries the moment it was written, and a merge takes an entry only when it is newer
 * than the one already here. Per entry, not per document: the film stopped on the Shield and the
 * series stopped on the Dune both survive, whichever box wrote its document last. A watched mark
 * that was taken off is kept as such, with its own time - dropped, it would come straight back from
 * the first box that still had it.
 *
 * <p>Plain maps and org.json, nothing of Android's, so the merge is tested on the JVM.
 */
final class SyncData {

    static final String FORMAT = "syntex-sync";
    static final int VERSION = 1;

    static final class Position {
        final long at;
        final long position;
        /** How long the file plays for, or 0 when the device never had it open to know. */
        final long length;

        Position(final long at, final long position, final long length) {
            this.at = at;
            this.position = position;
            this.length = length;
        }
    }

    static final class Mark {
        final long at;
        final boolean watched;

        Mark(final long at, final boolean watched) {
            this.at = at;
            this.watched = watched;
        }
    }

    static final class Setting {
        final long at;
        /** Boolean, Integer, Long, Float, String or Set&lt;String&gt; - what SharedPreferences holds. */
        final Object value;

        Setting(final long at, final Object value) {
            this.at = at;
            this.value = value;
        }
    }

    /** What a merge took, per section, by key. */
    static final class Taken {
        final Set<String> positions = new LinkedHashSet<>();
        final Set<String> watched = new LinkedHashSet<>();
        final Set<String> settings = new LinkedHashSet<>();

        int total() {
            return positions.size() + watched.size() + settings.size();
        }
    }

    final Map<String, Position> positions = new LinkedHashMap<>();
    final Map<String, Mark> watched = new LinkedHashMap<>();
    final Map<String, Setting> settings = new LinkedHashMap<>();
    /** Which box wrote it, for the person reading the file; nothing decides on it. */
    String device = "";
    long written;

    /**
     * Takes from {@code other} every entry newer than the one here, or missing here. An entry of the
     * same age stays as it is: that is the same write seen twice, and taking it would count a change
     * that is none.
     */
    Taken merge(final SyncData other) {
        final Taken taken = new Taken();
        for (final Map.Entry<String, Position> entry : other.positions.entrySet()) {
            final Position had = positions.get(entry.getKey());
            if (had == null || entry.getValue().at > had.at) {
                positions.put(entry.getKey(), entry.getValue());
                taken.positions.add(entry.getKey());
            }
        }
        for (final Map.Entry<String, Mark> entry : other.watched.entrySet()) {
            final Mark had = watched.get(entry.getKey());
            if (had == null || entry.getValue().at > had.at) {
                watched.put(entry.getKey(), entry.getValue());
                taken.watched.add(entry.getKey());
            }
        }
        for (final Map.Entry<String, Setting> entry : other.settings.entrySet()) {
            final Setting had = settings.get(entry.getKey());
            if (had == null || entry.getValue().at > had.at) {
                settings.put(entry.getKey(), entry.getValue());
                taken.settings.add(entry.getKey());
            }
        }
        return taken;
    }

    String toJson() throws JSONException {
        final JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        root.put("device", device);
        root.put("written", written);
        final JSONObject places = new JSONObject();
        for (final Map.Entry<String, Position> entry : positions.entrySet()) {
            final Position p = entry.getValue();
            final JSONObject one = new JSONObject().put("at", p.at).put("position", p.position);
            if (p.length > 0) {
                one.put("length", p.length);
            }
            places.put(entry.getKey(), one);
        }
        root.put("positions", places);
        final JSONObject marks = new JSONObject();
        for (final Map.Entry<String, Mark> entry : watched.entrySet()) {
            marks.put(entry.getKey(), new JSONObject()
                    .put("at", entry.getValue().at).put("watched", entry.getValue().watched));
        }
        root.put("watched", marks);
        final JSONObject values = new JSONObject();
        for (final Map.Entry<String, Setting> entry : settings.entrySet()) {
            final JSONObject one = typed(entry.getValue().value);
            if (one != null) {
                values.put(entry.getKey(), one.put("at", entry.getValue().at));
            }
        }
        root.put("settings", values);
        return root.toString(1);
    }

    /**
     * Reads a document back.
     *
     * @throws JSONException when it is not one of ours, or was written by a newer format than this
     *                       build reads - taking half of a newer document would be worse than none
     */
    static SyncData fromJson(final String text) throws JSONException {
        final JSONObject root = new JSONObject(text);
        if (!FORMAT.equals(root.optString("format"))) {
            throw new JSONException("not a " + FORMAT + " document");
        }
        if (root.optInt("version") > VERSION) {
            throw new JSONException("version " + root.optInt("version") + " is newer than " + VERSION);
        }
        final SyncData data = new SyncData();
        data.device = root.optString("device");
        data.written = root.optLong("written");
        final JSONObject places = root.optJSONObject("positions");
        if (places != null) {
            for (final Iterator<String> keys = places.keys(); keys.hasNext(); ) {
                final String key = keys.next();
                final JSONObject one = places.getJSONObject(key);
                data.positions.put(key, new Position(one.getLong("at"), one.getLong("position"),
                        one.optLong("length")));
            }
        }
        final JSONObject marks = root.optJSONObject("watched");
        if (marks != null) {
            for (final Iterator<String> keys = marks.keys(); keys.hasNext(); ) {
                final String key = keys.next();
                final JSONObject one = marks.getJSONObject(key);
                data.watched.put(key, new Mark(one.getLong("at"), one.getBoolean("watched")));
            }
        }
        final JSONObject values = root.optJSONObject("settings");
        if (values != null) {
            for (final Iterator<String> keys = values.keys(); keys.hasNext(); ) {
                final String key = keys.next();
                final JSONObject one = values.getJSONObject(key);
                final Object value = untyped(one);
                // A type this build does not know is skipped rather than failing the whole import.
                if (value != null) {
                    data.settings.put(key, new Setting(one.getLong("at"), value));
                }
            }
        }
        return data;
    }

    /**
     * A preference value with its type beside it. JSON alone would read an int back as whichever
     * number type it liked, and SharedPreferences throws on a getInt of a stored Long.
     */
    private static JSONObject typed(final Object value) throws JSONException {
        if (value instanceof Boolean) {
            return new JSONObject().put("type", "bool").put("value", value);
        }
        if (value instanceof Integer) {
            return new JSONObject().put("type", "int").put("value", value);
        }
        if (value instanceof Long) {
            return new JSONObject().put("type", "long").put("value", value);
        }
        if (value instanceof Float) {
            return new JSONObject().put("type", "float").put("value", ((Float) value).doubleValue());
        }
        if (value instanceof String) {
            return new JSONObject().put("type", "string").put("value", value);
        }
        if (value instanceof Set) {
            final JSONArray items = new JSONArray();
            for (final Object item : (Set<?>) value) {
                items.put(String.valueOf(item));
            }
            return new JSONObject().put("type", "set").put("value", items);
        }
        return null;
    }

    private static Object untyped(final JSONObject one) throws JSONException {
        switch (one.optString("type")) {
            case "bool":
                return one.getBoolean("value");
            case "int":
                return one.getInt("value");
            case "long":
                return one.getLong("value");
            case "float":
                return (float) one.getDouble("value");
            case "string":
                return one.getString("value");
            case "set":
                final JSONArray items = one.getJSONArray("value");
                final Set<String> set = new LinkedHashSet<>();
                for (int i = 0; i < items.length(); i++) {
                    set.add(items.getString(i));
                }
                return set;
            default:
                return null;
        }
    }
}
