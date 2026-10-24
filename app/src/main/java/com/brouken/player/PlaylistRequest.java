package com.brouken.player;

import android.os.Bundle;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The {@code playlist} extra of a launch, read into plain values: the nested contract Just+ Player 2.1.1
 * introduced and LAMPA's shell sends from 1.13.3 on ({@code docs/PLAYLIST_API.md} of just-plus-player,
 * 2.3.0 edition, 09.10.2026).
 *
 * <p>Only what the shell sends is read: the playlist's title, start index, headers, {@code ask_resume}
 * and callback, and per item the stream, its names, ids, season and episode, segments, start position,
 * qualities and subtitles. Voices and the track keys of section 5 are not — an item with nothing but
 * voices is refused, the track keys are left alone. The values stay plain Java (no Uri, no MediaItem) so
 * the rules are tested without a device; the activity turns them into media items.
 *
 * <p>Reading follows section 6.2: a string is any value's text, trimmed, blank as absent; a whole number
 * is any number or numeric string, rounded down; a time comes as {@code _ms} or {@code _sec}, the first
 * one readable winning; arrays of Bundles may be {@code Parcelable[]} or {@code ArrayList}. What section
 * 6.3 calls bad input leaves {@link #error} set and nothing to play.
 */
final class PlaylistRequest {

    static final String EXTRA = "playlist";

    static final class Subtitle {
        String uri;
        String label;
        String language;
        String mime;
        boolean selected;
    }

    static final class Item {
        /** What plays first: the item's uri, else the first quality marked selected, else the first. */
        String uri;
        /** The item's own title (a film's name), beating the playlist's. */
        String title;
        String episodeTitle;
        /** A logo by URL, shown in place of the title; beats the playlist's. */
        String logo;
        String thumbnail;
        String imdbId;
        String tmdbId;
        Integer season;
        Integer episode;
        String segments;
        /** Where the item starts, in milliseconds; null when none was sent. */
        Long positionMs;
        /** Label to uri, in the order sent. Empty when the item has none. */
        final LinkedHashMap<String, String> qualities = new LinkedHashMap<>();
        final List<Subtitle> subtitles = new ArrayList<>();
    }

    String title;
    String logo;
    String background;
    int startIndex;
    String[] headers;
    boolean askResume;
    /** The caller's {@code result_callback}, a PendingIntent; kept as it came. */
    Parcelable resultCallback;
    final List<Item> items = new ArrayList<>();
    /** One of section 6.3's messages, or null for a request that plays. */
    String error;
    /** What was dropped while reading, as {@code <path>: <problem>}. */
    final List<String> warnings = new ArrayList<>();

    private PlaylistRequest() {
    }

    static PlaylistRequest read(final Bundle playlist) {
        final PlaylistRequest request = new PlaylistRequest();
        request.title = string(playlist, "title");
        request.logo = string(playlist, "logo");
        request.background = string(playlist, "background");
        request.headers = strings(playlist.get("headers"));
        request.askResume = bool(playlist.get("ask_resume"));
        final Object callback = playlist.get("result_callback");
        request.resultCallback = callback instanceof Parcelable ? (Parcelable) callback : null;

        final List<Object> items = bundles(playlist.get("items"));
        if (items == null || items.isEmpty()) {
            request.error = "playlist has no items";
            return request;
        }
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof Bundle)) {
                request.fail("items[" + i + "] is not a Bundle");
                return request;
            }
            final Item item = request.readItem((Bundle) items.get(i), i);
            if (item == null) {
                return request;
            }
            request.items.add(item);
        }
        final Long start = whole(playlist.get("start_index"));
        request.startIndex = start == null ? 0 : start.intValue();
        if (start != null && (start < 0 || start >= items.size())) {
            request.fail("start_index " + start + " is out of range 0.." + (items.size() - 1));
        }
        return request;
    }

    private Item readItem(final Bundle bundle, final int index) {
        final String path = "items[" + index + "]";
        final Item item = new Item();
        item.title = string(bundle, "title");
        item.episodeTitle = string(bundle, "episode_title");
        item.logo = string(bundle, "logo");
        item.thumbnail = string(bundle, "thumbnail");
        item.imdbId = string(bundle, "imdb_id");
        item.tmdbId = string(bundle, "tmdb_id");
        item.season = integer(bundle.get("season"));
        item.episode = integer(bundle.get("episode"));
        item.segments = string(bundle, "segments");
        item.positionMs = time(bundle, "position");

        String firstSelected = null;
        final List<Object> qualities = bundles(bundle.get("qualities"));
        if (qualities != null) {
            for (Object entry : qualities) {
                if (!(entry instanceof Bundle)) {
                    continue;
                }
                final Bundle quality = (Bundle) entry;
                final String label = string(quality, "label");
                final String uri = string(quality, "uri");
                if (label == null || uri == null) {
                    continue;
                }
                item.qualities.put(label, uri);
                if (firstSelected == null && bool(quality.get("selected"))) {
                    firstSelected = uri;
                }
            }
        }
        final String uri = string(bundle, "uri");
        if (firstSelected != null) {
            item.uri = firstSelected;
        } else if (uri != null) {
            item.uri = uri;
        } else if (!item.qualities.isEmpty()) {
            item.uri = item.qualities.values().iterator().next();
        }
        if (item.uri == null) {
            fail(bundle.get("voices") != null
                    ? path + " has only voices, which this player does not read"
                    : path + " has neither uri nor qualities");
            return null;
        }

        final List<Object> subtitles = bundles(bundle.get("subtitles"));
        if (subtitles != null) {
            boolean selectedSeen = false;
            for (int k = 0; k < subtitles.size(); k++) {
                final Object entry = subtitles.get(k);
                final String subUri = entry instanceof Bundle ? string((Bundle) entry, "uri") : null;
                if (subUri == null) {
                    warn(path + ": subtitles[" + k + "] "
                            + (entry instanceof Bundle ? "has no uri" : "is not a Bundle") + "; skipped");
                    continue;
                }
                final Bundle sub = (Bundle) entry;
                final Subtitle subtitle = new Subtitle();
                subtitle.uri = subUri;
                subtitle.label = string(sub, "label");
                subtitle.language = string(sub, "language");
                subtitle.mime = string(sub, "mime");
                // Only the first entry marked is tried.
                subtitle.selected = !selectedSeen && bool(sub.get("selected"));
                selectedSeen |= subtitle.selected;
                item.subtitles.add(subtitle);
            }
        }
        return item;
    }

    private void fail(final String message) {
        error = message;
        items.clear();
        warnings.clear();
    }

    private void warn(final String warning) {
        warnings.add(warning);
    }

    /** The title the item shows: its episode's name, else its own title, else the playlist's. */
    String displayTitle(final int index) {
        final Item item = items.get(index);
        if (item.episodeTitle != null) {
            return item.episodeTitle;
        }
        return item.title != null ? item.title : title;
    }

    /** The header's bold line (section 3): the series or the film - the item's, else the playlist's. */
    String seriesTitle(final int index) {
        final Item item = items.get(index);
        return item.title != null ? item.title : title;
    }

    /** The logo shown in place of that line: the item's, else the playlist's; null when neither sent one. */
    String logo(final int index) {
        final Item item = items.get(index);
        return item.logo != null ? item.logo : logo;
    }

    /**
     * The episode's own name for the line under the header's bold one, or null when it says nothing the
     * numbers do not: "Episode 3", "Серия 3", "Серія 3", "E03", a bare "3" (section 3, episode_title).
     */
    static String episodeName(final String name, final Integer episode) {
        if (name == null) {
            return null;
        }
        final java.util.regex.Matcher m = REPEATED_NUMBER.matcher(name.trim());
        if (m.matches() && (episode == null || Integer.parseInt(m.group(1)) == episode)) {
            return null;
        }
        return name;
    }

    private static final java.util.regex.Pattern REPEATED_NUMBER = java.util.regex.Pattern.compile(
            "(?iu)(?:episode|ep\\.?|e|серия|серія|эпизод|епізод)?\\s*0*(\\d{1,4})");

    // --- Section 6.2 -------------------------------------------------------------------------------------

    static String string(final Bundle bundle, final String key) {
        final Object value = bundle.get(key);
        if (value == null) {
            return null;
        }
        final String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    static boolean bool(final Object value) {
        return Boolean.TRUE.equals(value) || "true".equals(value);
    }

    /** A number or numeric string, rounded down; null for anything else. */
    static Long whole(final Object value) {
        final Double number = number(value);
        return number == null ? null : (long) Math.floor(number);
    }

    static Integer integer(final Object value) {
        final Long number = whole(value);
        return number == null || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE
                ? null : number.intValue();
    }

    /** {@code <name>_ms}, else {@code <name>_sec}, in whole milliseconds; null when neither reads. */
    static Long time(final Bundle bundle, final String name) {
        final Double ms = number(bundle.get(name + "_ms"));
        if (ms != null) {
            return (long) Math.floor(ms);
        }
        final Double sec = number(bundle.get(name + "_sec"));
        return sec == null ? null : (long) Math.floor(sec * 1000);
    }

    private static Double number(final Object value) {
        double number;
        if (value instanceof Number) {
            number = ((Number) value).doubleValue();
        } else if (value instanceof String) {
            try {
                number = Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        return Double.isNaN(number) || Double.isInfinite(number) ? null : number;
    }

    /** An array of Bundles as {@code Parcelable[]} or a list; null for anything else. */
    static List<Object> bundles(final Object value) {
        if (value instanceof Parcelable[]) {
            final List<Object> list = new ArrayList<>();
            Collections.addAll(list, (Object[]) value);
            return list;
        }
        if (value instanceof List) {
            return new ArrayList<>((List<?>) value);
        }
        return null;
    }

    /** {@code String[]} or a list of strings; null for anything else. */
    static String[] strings(final Object value) {
        if (value instanceof String[]) {
            return (String[]) value;
        }
        if (value instanceof List) {
            final List<?> list = (List<?>) value;
            final String[] array = new String[list.size()];
            for (int i = 0; i < array.length; i++) {
                final Object entry = list.get(i);
                array[i] = entry == null ? null : entry.toString();
            }
            return array;
        }
        return null;
    }
}
