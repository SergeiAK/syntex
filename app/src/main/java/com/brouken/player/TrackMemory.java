package com.brouken.player;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The track picked by hand, kept for the series rather than the file: the next episode opens on the
 * same dub and the same subtitles. A series is the launcher's card where there is one (its TMDB id),
 * and the folder for files that came with none — a season sits in one.
 *
 * <p>An episode's tracks are rarely the same list twice, so the pick is found again rather than
 * replayed: by language and kind first, then by the track's name among those, then by its place among
 * the tracks of its type. Kind is what tells two tracks in one language apart for what they are — a
 * commentary, forced subtitles, subtitles for the deaf — and is never crossed: a dub is not swapped for
 * a commentary because the commentary happens to share the name.
 *
 * <p>Pure Java, so it is tested on the JVM; the player turns its tracks into {@link Track}s.
 */
final class TrackMemory {

    static final int KIND_MAIN = 0;
    static final int KIND_COMMENTARY = 1;
    static final int KIND_FORCED = 2;
    static final int KIND_SDH = 3;

    /** One track as the matching sees it, or the pick as remembered. */
    static final class Track {
        @Nullable final String language; // ISO-639-2/T, null when the track declares none
        final int kind;
        @Nullable final String name;
        final int index; // place among the tracks of its type, from 0

        Track(@Nullable String language, int kind, @Nullable String name, int index) {
            this.language = language;
            this.kind = kind;
            this.name = name == null || name.trim().isEmpty() ? null : name.trim();
            this.index = index;
        }
    }

    /** What is written for subtitles turned off: off is a pick like any other, and carried the same way. */
    static final String OFF = "off";

    /** Optional bundle in a caller's single-title playlist and in its terminal activity result. */
    static final String EXTRA = "syntex_track_memory_v1";
    static final String AUDIO = "audio";
    static final String SUBTITLES = "subtitles";

    private TrackMemory() {
    }

    /** The series a file belongs to: the card's id, else the folder; null when neither is known. */
    @Nullable
    static String seriesKey(@Nullable String tmdbId, @Nullable String uri) {
        if (tmdbId != null && !tmdbId.trim().isEmpty()) {
            return "tmdb:" + tmdbId.trim();
        }
        if (uri == null) {
            return null;
        }
        // A torrent is the series: every file of every torrent on a TorrServer sits under one /stream
        // "folder", which would make the whole server a single series.
        final String torrent = SyncKey.of(uri);
        if (torrent != null && torrent.startsWith("torr:///")) {
            final int slash = torrent.indexOf('/', "torr:///".length());
            return "torr:" + torrent.substring("torr:///".length(), slash);
        }
        // The query of a link is the link's, not the folder's: a token there would make every episode
        // a series of its own.
        String path = uri;
        final int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        final int slash = path.lastIndexOf('/');
        return slash > 0 ? "dir:" + path.substring(0, slash) : null;
    }

    /** {@code language|kind|index|name}; the name last, since it is the one part that may hold a bar. */
    static String encode(Track pick) {
        return (pick.language == null ? "" : pick.language) + "|" + pick.kind + "|" + pick.index + "|"
                + (pick.name == null ? "" : pick.name);
    }

    @Nullable
    static Track decode(@Nullable String value) {
        if (value == null) {
            return null;
        }
        final String[] parts = value.split("\\|", 4);
        if (parts.length < 4) {
            return null;
        }
        try {
            return new Track(parts[0].isEmpty() ? null : parts[0], Integer.parseInt(parts[1]),
                    parts[3], Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Which of {@code tracks} is the remembered pick; -1 when none answers to it.
     *
     * <p>Language and kind narrow the list. One left is the answer. Several — two Russian dubs — and
     * the name chooses, then the place. None — the episode has no track in that language — and the
     * name is tried over all of them before giving up, for a track whose language was never tagged.
     * The place alone never picks across languages: in a different list it is a different track.
     */
    static int match(List<Track> tracks, @Nullable Track pick) {
        if (pick == null || tracks.isEmpty()) {
            return -1;
        }
        final List<Integer> same = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) {
            final Track track = tracks.get(i);
            if (track.kind == pick.kind && sameLanguage(track.language, pick.language)) {
                same.add(i);
            }
        }
        if (same.size() == 1) {
            return same.get(0);
        }
        if (same.isEmpty()) {
            final List<Integer> all = new ArrayList<>();
            for (int i = 0; i < tracks.size(); i++) {
                if (tracks.get(i).kind == pick.kind) {
                    all.add(i);
                }
            }
            return byName(tracks, all, pick.name);
        }
        final int byName = byName(tracks, same, pick.name);
        if (byName >= 0) {
            return byName;
        }
        for (final int i : same) {
            if (tracks.get(i).index == pick.index) {
                return i;
            }
        }
        return same.get(0);
    }

    @Nullable
    static String portableValue(@Nullable String value, boolean subtitles) {
        if (value == null || value.length() > 1024) {
            return null;
        }
        if (OFF.equals(value)) {
            return subtitles ? value : null;
        }
        final Track track = decode(value);
        return track != null && track.index >= 0 && track.kind >= KIND_MAIN
                && track.kind <= (subtitles ? KIND_SDH : KIND_COMMENTARY)
                && (!subtitles || track.kind != KIND_COMMENTARY)
                && (track.name != null || track.language != null)
                ? value : null;
    }

    /** Another device can receive another edition. Never guess by ordinal or resolve an ambiguity. */
    static int matchPortable(List<Track> tracks, @Nullable Track pick) {
        if (pick == null) {
            return -1;
        }
        int match = -1;
        for (int i = 0; i < tracks.size(); i++) {
            final Track candidate = tracks.get(i);
            if (candidate.kind != pick.kind) {
                continue;
            }
            if (pick.name != null) {
                if (!pick.name.equalsIgnoreCase(candidate.name)
                        || (pick.language != null && candidate.language != null
                        && !sameLanguage(pick.language, candidate.language))) {
                    continue;
                }
            } else if (pick.language == null || !sameLanguage(pick.language, candidate.language)) {
                continue;
            }
            if (match >= 0) {
                return -1;
            }
            match = i;
        }
        return match;
    }

    private static int byName(List<Track> tracks, List<Integer> among, @Nullable String name) {
        if (name == null) {
            return -1;
        }
        final String wanted = name.toLowerCase(Locale.ROOT);
        for (final int i : among) {
            final String own = tracks.get(i).name;
            if (own != null && own.toLowerCase(Locale.ROOT).equals(wanted)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean sameLanguage(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }
}
