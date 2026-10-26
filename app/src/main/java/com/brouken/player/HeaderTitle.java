package com.brouken.player;

import java.util.regex.Pattern;

/**
 * A file name as a title for the header and the questions asked over it: without the container's
 * extension and without the tags a library manager writes into names for itself -
 * {@code {tmdb-14160}}, {@code [1080p]}, {@code [imdbid-tt…]}. "Вверх (2009) {tmdb-14160} - [1080p].mkv"
 * reads "Вверх (2009)". The year in round brackets stays: it tells two films of one name apart.
 *
 * <p>Pure text, tested on the JVM. A name that would be cut to nothing is returned as it was.
 */
final class HeaderTitle {

    private HeaderTitle() {
    }

    /** Only containers: a dot followed by anything else is part of the name ("Vol. 2", "Mr. Robot"). */
    private static final Pattern EXTENSION = Pattern.compile(
            "(?i)\\.(mkv|mk3d|mp4|m4v|avi|ts|m2ts|mts|mpg|mpeg|vob|wmv|mov|webm|flv|3gp|ogv|divx|rmvb|iso)$");
    // The closing brackets escaped: Android's ICU regex rejects a bare ] or } where the JVM lets it pass.
    private static final Pattern CURLY_TAG = Pattern.compile("\\s*\\{[^\\}]*\\}");
    private static final Pattern SQUARE_TAG = Pattern.compile("\\s*\\[[^\\]]*\\]");
    /** What the tags leave hanging at the ends: " - ", "_", "." and the like. */
    private static final Pattern LOOSE_ENDS = Pattern.compile("^[\\s\\-_.·]+|[\\s\\-_.·]+$");
    private static final Pattern SPACES = Pattern.compile("\\s{2,}");

    static String clean(final String name) {
        return clean(name, false);
    }

    /**
     * @param fileName the name is a file's, whether or not it still has its extension: the player's
     *                 own file name comes with the extension already cut (Utils.getFileName), and
     *                 "… [1080p]" stayed in the header on Dune (26.10.2026).
     */
    static String clean(final String name, final boolean fileName) {
        if (name == null) {
            return null;
        }
        final String bare = EXTENSION.matcher(name.trim()).replaceFirst("");
        String title = CURLY_TAG.matcher(bare).replaceAll("");
        // Square brackets only on a file's name. A title a launcher sent may be "[REC] 2" - a film.
        if (fileName || bare.length() != name.trim().length()) {
            title = SQUARE_TAG.matcher(title).replaceAll("");
        }
        title = LOOSE_ENDS.matcher(title).replaceAll("");
        title = SPACES.matcher(title).replaceAll(" ");
        return title.isEmpty() ? bare : title;
    }
}
