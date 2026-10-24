package com.brouken.player;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a file is, read off its name and the folders around it, for the times nothing else says: a
 * file opened from a share, or a launcher that sends a URL and a title and no ids at all. The online
 * segment lookup is keyed by imdb/tmdb, so without this it never runs for any of them.
 *
 * <p>Two ways in, in order of trust. An id written into the path - {@code {tmdb-33907}}, the way
 * tinyMediaManager and Jellyfin name folders, or an imdb {@code tt} number - is taken as it is. Failing
 * that, a cleaned title and year that {@link TitleSearch#match} may look up; that answer is accepted
 * only when the names agree, because a wrong title means skipping a piece of a film that was never an
 * intro.
 *
 * <p>Pure text: no Android, no network, so the reading is tested on the JVM.
 */
final class NameId {

    /** {@code {tmdb-33907}}, {@code [tmdbid-33907]}, {@code (tmdb=33907)}: always inside brackets. */
    private static final Pattern TMDB_TAG =
            Pattern.compile("(?i)[\\[\\{(]\\s*tmdb(?:id)?\\s*[-=:_ ]\\s*(\\d{1,9})\\s*[\\]\\})]");
    /** An imdb number anywhere: {@code tt0903747}, {@code [imdbid-tt0903747]}. */
    private static final Pattern IMDB_TAG = Pattern.compile("(?i)(?<![a-z0-9])(tt\\d{7,9})(?!\\d)");

    // The Cyrillic "х" is in the second one on purpose: a localized episode list writes 1х05 with it,
    // and it is indistinguishable from the Latin letter on screen.
    private static final Pattern[] SEASON_EPISODE = {
            Pattern.compile("(?i)s\\s*(\\d{1,2})\\s*[.\\-_ ]?\\s*e\\s*(\\d{1,3})"),
            Pattern.compile("(?<!\\d)(\\d{1,2})\\s*[xх]\\s*(\\d{1,3})(?!\\d)"),
    };
    /** A season folder: {@code Season 01}, {@code Сезон 1}, {@code S01}. */
    private static final Pattern SEASON_FOLDER =
            Pattern.compile("(?iu)^\\s*(?:season|сезон|s)\\s*[.\\-_ ]?\\s*(\\d{1,2})\\s*$");
    private static final Pattern SPECIALS_FOLDER = Pattern.compile("(?iu)^\\s*(?:specials|спецвыпуски)\\s*$");
    /** The episode of a file inside a season folder: {@code E05}, {@code Episode 5}, {@code Серия 5}, {@code 05 - ...}. */
    private static final Pattern EPISODE_IN_SEASON = Pattern.compile(
            "(?iu)(?:^|[^\\p{L}])(?:e|ep|episode|серия)\\s*[.\\-_ ]?\\s*(\\d{1,3})(?!\\d)|^\\s*(\\d{1,3})(?!\\d)(?![xх])");
    /** A year, not part of a longer number. */
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)");
    /** Where a release name stops being a title. */
    private static final Pattern RELEASE = Pattern.compile("(?i)(?<![\\p{L}\\d])(?:2160p|1080[pi]|720p|576[pi]|480p"
            + "|4k|uhd|web-?dl|web-?rip|webrip|blu-?ray|bdrip|bdremux|hdrip|dvdrip|remux|hdtv|x26[45]|h\\.?26[45]"
            + "|hevc|avc|hdr10?\\+?|dovi|proper|repack|extended|unrated)(?![\\p{L}\\d])");
    // Every bracket escaped, even where Java reads it as a literal: Android's ICU engine does not, and
    // an unescaped closing brace here took the player down on its first file (Shield, 25.10).
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]|\\{[^\\}]*\\}");
    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*\\d{1,3}\\s*[.\\-]\\s+(?=\\S)");
    private static final Pattern EXTENSION =Pattern.compile("\\.[A-Za-z0-9]{2,4}$");
    /** Names that say nothing about the film, so the folder around the file is read instead. */
    private static final Pattern GENERIC = Pattern.compile("(?i)^(?:movie|film|video|фильм|видео|\\d+)$");

    final String tmdb;
    final String imdb;
    /** Below 1 for a film. */
    final int season;
    final int episode;
    /** What to look up when there is no id: the cleaned title, or null. */
    final String title;
    final String year;

    private NameId(String tmdb, String imdb, int season, int episode, String title, String year) {
        this.tmdb = tmdb;
        this.imdb = imdb;
        this.season = season;
        this.episode = episode;
        this.title = title;
        this.year = year;
    }

    boolean hasId() {
        return tmdb != null || imdb != null;
    }

    boolean isMovie() {
        return season < 1;
    }

    @Override
    public String toString() {
        return (tmdb != null ? "tmdb " + tmdb : imdb != null ? imdb : "\"" + title + "\" " + year)
                + (isMovie() ? " film" : " S" + season + "E" + episode);
    }

    /**
     * Reads a playing item.
     *
     * @param path  the path the file was opened by, outermost folder first and the file's own name
     *              last; empty when the address has no path worth reading (a stream URL)
     * @param label what the launcher called the item, or null
     * @return null when there is nothing to go on - no id, no title, or a special, which no segment
     *         source numbers
     */
    static NameId read(List<String> path, String label) {
        final int last = path.size() - 1;
        final String file = last >= 0 ? path.get(last) : null;

        // Season and episode: the launcher's own label first, then the file name, then a season
        // folder around a file that only carries its episode number.
        int season = -1;
        int episode = -1;
        int seasonFolder = -1;
        int[] found = seasonEpisodeIn(label);
        if (found == null) {
            found = seasonEpisodeIn(file);
        }
        if (found != null) {
            season = found[0];
            episode = found[1];
        }
        if (last >= 1) {
            final String parent = path.get(last - 1);
            final Matcher folder = SEASON_FOLDER.matcher(parent);
            if (folder.find() || SPECIALS_FOLDER.matcher(parent).find()) {
                seasonFolder = last - 1;
                if (found == null) {
                    final int number = folder.reset().find() ? Integer.parseInt(folder.group(1)) : 0;
                    final int inFile = episodeInSeason(file);
                    if (inFile > 0) {
                        season = number;
                        episode = inFile;
                    } else {
                        // The season is known and the episode is not: still a series, asked by season.
                        season = number;
                    }
                }
            }
        }
        if (season == 0) {
            return null;
        }

        // An id in the path, nearest first: the file, then the folders around it. Three levels reach the
        // series folder over a season folder; past that a tag names a collection or a library, not this.
        String tmdb = null;
        String imdb = null;
        for (int i = last; i >= 0 && i >= last - 2 && tmdb == null && imdb == null; i--) {
            final String part = path.get(i);
            final Matcher t = TMDB_TAG.matcher(part);
            if (t.find()) {
                tmdb = t.group(1);
            }
            final Matcher m = IMDB_TAG.matcher(part);
            if (m.find()) {
                imdb = m.group(1).toLowerCase(Locale.ROOT);
            }
        }
        if (tmdb == null && imdb == null && label != null) {
            final Matcher m = IMDB_TAG.matcher(label);
            if (m.find()) {
                imdb = m.group(1).toLowerCase(Locale.ROOT);
            }
        }
        if (tmdb != null || imdb != null) {
            return new NameId(tmdb, imdb, season, episode, null, null);
        }

        // No id: a title to look up. What the launcher called it beats the file's name, which is a
        // release name more often than not; a series is named by its folder over the season folder.
        String source = label;
        if (source == null || clean(source)[0].isEmpty()) {
            if (seasonFolder >= 1) {
                source = path.get(seasonFolder - 1);
            } else if (file != null) {
                source = EXTENSION.matcher(file).replaceFirst("");
                if ((clean(source)[0].isEmpty() || GENERIC.matcher(clean(source)[0]).matches()) && last >= 1) {
                    source = path.get(last - 1);
                }
            }
        }
        if (source == null) {
            return null;
        }
        final String[] cleaned = clean(source);
        if (cleaned[0].isEmpty() || GENERIC.matcher(cleaned[0]).matches()) {
            return null;
        }
        return new NameId(null, null, season, episode, cleaned[0], cleaned[1]);
    }

    /** {@code S01E05} or {@code 1x05} anywhere in the text, as {season, episode}; null if absent. */
    static int[] seasonEpisodeIn(String text) {
        if (text == null) {
            return null;
        }
        for (Pattern pattern : SEASON_EPISODE) {
            final Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                try {
                    return new int[] { Integer.parseInt(matcher.group(1)),
                            Integer.parseInt(matcher.group(2)) };
                } catch (NumberFormatException ignored) {
                    // Try the next shape.
                }
            }
        }
        return null;
    }

    private static int episodeInSeason(String file) {
        if (file == null) {
            return -1;
        }
        final Matcher matcher = EPISODE_IN_SEASON.matcher(EXTENSION.matcher(file).replaceFirst(""));
        if (!matcher.find()) {
            return -1;
        }
        final String digits = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return Integer.parseInt(digits);
    }

    /**
     * A release name as {title, year}: tags in brackets gone, dots and underscores read as spaces, and
     * cut where the year or the first technical word starts. The year is the last one not at the very
     * start - "1917 (2019)" is the film 1917 of 2019, and "2012" alone is a title. Year null if none.
     */
    static String[] clean(String name) {
        // A track number in front - "01. The Hockey Champ (1939)", "07 - Name" - goes first; only with a
        // dot or a dash and a space after it, so "12 обезьян", "1917" and "11.22.63" keep their digits.
        String text = LEADING_NUMBER.matcher(name).replaceFirst("");
        text = BRACKETS.matcher(text).replaceAll(" ");
        // Dots between letters are word breaks in a release name; a dot inside a number ("11.22.63")
        // is part of the title and comparing names ignores it anyway.
        text = text.replace('_', ' ').replaceAll("(?<=\\D)\\.|\\.(?=\\D)", " ");
        int cut = text.length();
        final Matcher se = SEASON_EPISODE[0].matcher(text);
        if (se.find()) {
            cut = Math.min(cut, se.start());
        }
        final Matcher release = RELEASE.matcher(text);
        if (release.find()) {
            cut = Math.min(cut, release.start());
        }
        String year = null;
        final Matcher years = YEAR.matcher(text);
        int yearAt = -1;
        while (years.find()) {
            if (years.start() >= cut) {
                break;
            }
            if (!text.substring(0, years.start()).replaceAll("[\\s(\\-.,]", "").isEmpty()) {
                year = years.group(1);
                yearAt = years.start();
            }
        }
        if (yearAt >= 0) {
            cut = Math.min(cut, yearAt);
        }
        String title = text.substring(0, cut)
                .replaceAll("[(\\[]\\s*$", "")
                .replaceAll("\\s+", " ")
                .replaceAll("^[\\s\\-.,:]+|[\\s\\-.,:(]+$", "")
                .trim();
        // "Название / Original Title": the launcher writes both; the first is the one in this language.
        final int slash = title.indexOf(" / ");
        if (slash > 0) {
            title = title.substring(0, slash).trim();
        }
        return new String[] { title, year };
    }

    /**
     * Whether a catalogue entry is the title asked for. The name has to be the same once case,
     * punctuation and accents are set aside ("ё" counts as "е"), as the translated or the original
     * name; and where both sides have a year, the years may differ by one - a festival premiere and a
     * release are routinely a year apart.
     */
    static boolean matches(String wantedTitle, String wantedYear, String name, String originalName,
                           String year) {
        final String wanted = normal(wantedTitle);
        if (wanted.isEmpty() || !(wanted.equals(normal(name)) || wanted.equals(normal(originalName)))) {
            return false;
        }
        if (wantedYear == null || year == null) {
            return true;
        }
        try {
            return Math.abs(Integer.parseInt(wantedYear) - Integer.parseInt(year)) <= 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String normal(String text) {
        if (text == null) {
            return "";
        }
        final String bare = Normalizer.normalize(text.toLowerCase(Locale.ROOT).replace('ё', 'е')
                .replace("&", "and"), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return bare.replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
