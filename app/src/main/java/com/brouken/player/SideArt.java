package com.brouken.player;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pictures lying next to the videos, as a library manager leaves them. The names are the ones Kodi,
 * Jellyfin, Emby and tinyMediaManager all read (checked 21.10.2026 against Kodi's wiki, Jellyfin's
 * LocalImageProvider, Emby's Movie and TV naming pages and tinyMediaManager's settings pages):
 * {@code Film-thumb.jpg} beside {@code Film.mkv}, {@code fanart.jpg} or {@code poster.jpg} in the film's
 * own folder, {@code season01-poster.jpg} in a series' folder for its first season.
 *
 * <p>Read off the listing the folder was shown with, so a picture costs nothing to find; only the one
 * chosen is ever fetched.
 *
 * <p>Which one, when there are several: by kind first, since a row's still is 16:9. A landscape - a
 * still with the title on it - fits the frame and says what the film is; a fanart fits the frame
 * without the title; a poster is 2:3 and stands in the middle of its own blur. Within a kind, the
 * picture named for this video, or for this season, comes before the folder's general one.
 *
 * <p>A folder's general picture stands for a video only when that video is the folder's only one: a
 * film's folder, where fanart.jpg is the film. In a season's folder it would make every episode the
 * same picture, and a frame of each is what tells them apart.
 */
final class SideArt {

    private static final String[] EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".tbn", ".gif"};
    /** The kinds, best first; each kind's names in the order the managers check them. */
    private static final String[][] KINDS = {
            {"landscape", "thumb"},
            {"fanart", "backdrop", "background", "art"},
            {"poster", "folder", "cover", "default", "movie", "show", "keyart", "jacket"},
    };
    /** Numbered backdrops - fanart1, fanart-1, backdrop2 - when the plain name is not there. */
    private static final Pattern NUMBERED = Pattern.compile("(fanart|backdrop|background|art)-?\\d+");
    private static final Pattern SEASON = Pattern.compile(
            "^(?:season|сезон|series|serie|saison|staffel|temporada|s)\\s*[._-]?\\s*0*(\\d{1,3})(?!\\d).*"
                    + "|^0*(\\d{1,3})\\s*[._-]?\\s*(?:season|сезон)?$"
                    // Not \b: on the JVM it is a boundary of ASCII words only, on Android of any letters.
                    + "|^.*?(?<![\\p{L}\\p{N}])0*(\\d{1,3})\\s*[._-]?\\s*(?:season|сезон)(?![\\p{L}\\p{N}]).*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SPECIALS = Pattern.compile(
            "^(?:specials?|спецвыпуски?|спешлы|спецэпизоды|особые)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Lower-case name to the address of every picture in one folder. */
    private final Map<String, String> pictures = new HashMap<>();
    private int videos;

    /** A picture and how good it is for a 16:9 still: lower is better. */
    static final class Pick {
        final String address;
        final int rank;

        Pick(final String address, final int rank) {
            this.address = address;
            this.rank = rank;
        }

        /** The better of two, this one on a tie. */
        Pick or(@Nullable final Pick other) {
            return other != null && other.rank < rank ? other : this;
        }
    }

    /** Whether a name in a listing is one of the pictures looked for at all. */
    static boolean isPicture(@Nullable final String name) {
        if (name == null) {
            return false;
        }
        final String lower = name.toLowerCase(Locale.ROOT);
        for (final String extension : EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The season a folder's name says it holds: "Season 1", "Сезон 01", "S01", "1 сезон", a bare "2";
     * 0 for the specials; -1 when the name says no season at all.
     */
    static int seasonOf(@Nullable final String folderName) {
        if (folderName == null) {
            return -1;
        }
        final String name = folderName.trim();
        if (SPECIALS.matcher(name).matches()) {
            return 0;
        }
        final Matcher m = SEASON.matcher(name);
        if (!m.matches()) {
            return -1;
        }
        for (int group = 1; group <= m.groupCount(); group++) {
            if (m.group(group) != null) {
                return Integer.parseInt(m.group(group));
            }
        }
        return -1;
    }

    void picture(final String name, final String address) {
        final int dot = name.lastIndexOf('.');
        pictures.put((dot > 0 ? name.substring(0, dot) : name).toLowerCase(Locale.ROOT), address);
    }

    void video() {
        videos++;
    }

    int videos() {
        return videos;
    }

    /** The picture for one video in this folder, or null to take a frame of it. */
    @Nullable
    String forVideo(final String name) {
        final Pick pick = pickForVideo(name);
        return pick == null ? null : pick.address;
    }

    @Nullable
    Pick pickForVideo(final String name) {
        if (pictures.isEmpty()) {
            return null;
        }
        final String lower = name.toLowerCase(Locale.ROOT);
        final int dot = lower.lastIndexOf('.');
        final String base = dot > 0 ? lower.substring(0, dot) : lower;
        // The video's own, a kind at a time; the bare name is the poster of a film in tinyMediaManager
        // and Emby, and the thumb of an episode in Plex and tinyMediaManager - so a poster's rank when
        // the video is alone and a thumb's among episodes.
        Pick own = best(base + "-");
        final String bare = pictures.get(base);
        if (bare != null) {
            final Pick plain = new Pick(bare, videos == 1 ? 2 * 2 : 0);
            own = own == null ? plain : own.or(plain);
        }
        if (videos != 1) {
            return own;
        }
        // Alone in its folder: the folder's pictures are this video's too, after its own of a kind.
        final Pick shared = forFolderPick();
        if (shared == null) {
            return own;
        }
        final Pick sharedRanked = new Pick(shared.address, shared.rank + 1);
        return own == null ? sharedRanked : own.or(sharedRanked);
    }

    /** The folder's own picture, or null. */
    @Nullable
    String forFolder() {
        final Pick pick = forFolderPick();
        return pick == null ? null : pick.address;
    }

    @Nullable
    Pick forFolderPick() {
        return best("");
    }

    /**
     * The picture this folder - a series' - has for one of its seasons: season01-landscape.jpg and the
     * rest, season-specials-* or season00-* for season 0, Plex's bare season01.jpg as a poster, and
     * season-all-* for any season as the last resort.
     */
    @Nullable
    Pick forSeason(final int season) {
        if (season < 0 || pictures.isEmpty()) {
            return null;
        }
        Pick pick = null;
        final String[] prefixes = season == 0
                ? new String[]{"season-specials-", "season00-", "season0-", "specials-"}
                : new String[]{String.format(Locale.ROOT, "season%02d-", season), "season" + season + "-"};
        for (final String prefix : prefixes) {
            final Pick found = best(prefix);
            pick = pick == null ? found : found == null ? pick : pick.or(found);
            final String bare = pictures.get(prefix.substring(0, prefix.length() - 1));
            if (bare != null) {
                final Pick plain = new Pick(bare, 2 * 2);
                pick = pick == null ? plain : pick.or(plain);
            }
        }
        if (pick == null) {
            final Pick all = best("season-all-");
            if (all != null) {
                pick = new Pick(all.address, all.rank + 1);
            }
        }
        return pick;
    }

    /**
     * The best picture named {@code prefix + kind}, ranked twice the kind: the odd ranks between are
     * for a folder's general picture standing in for a video's own of the same kind.
     */
    @Nullable
    private Pick best(final String prefix) {
        for (int kind = 0; kind < KINDS.length; kind++) {
            for (final String name : KINDS[kind]) {
                final String found = pictures.get(prefix + name);
                if (found != null) {
                    return new Pick(found, 2 * kind);
                }
            }
            if (kind == 1) {
                for (final Map.Entry<String, String> entry : pictures.entrySet()) {
                    final String key = entry.getKey();
                    if (key.startsWith(prefix) && NUMBERED.matcher(key.substring(prefix.length())).matches()) {
                        return new Pick(entry.getValue(), 2 * kind);
                    }
                }
            }
        }
        return null;
    }
}
