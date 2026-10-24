package com.brouken.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class NameIdTest {

    private static List<String> path(String... parts) {
        return Arrays.asList(parts);
    }

    /** tinyMediaManager, as on the owner's share: the id on the series folder over a season folder. */
    @Test
    public void tmdbTagOnSeriesFolder() {
        final NameId id = NameId.read(path("Data2", "Media2", "TVshow",
                "Аббатство Даунтон (2010) {tmdb-33907}", "Season 01",
                "Аббатство Даунтон - S01E05 - Эпизод 5.mkv"), null);
        assertEquals("33907", id.tmdb);
        assertEquals(1, id.season);
        assertEquals(5, id.episode);
    }

    @Test
    public void tmdbTagOnMovieFolder() {
        final NameId id = NameId.read(path("Movies", "12 обезьян (1995) {tmdb-63}", "12.Monkeys.1995.1080p.mkv"), null);
        assertEquals("63", id.tmdb);
        assertTrue(id.isMovie());
    }

    @Test
    public void jellyfinTagAndImdb() {
        assertEquals("1399", NameId.read(path("Shows", "Game of Thrones [tmdbid-1399]", "Season 2", "S02E03.mkv"), null).tmdb);
        final NameId imdb = NameId.read(path("Movies", "Alien (1979) [imdbid-tt0078748]", "Alien.mkv"), null);
        assertEquals("tt0078748", imdb.imdb);
    }

    /** A tag above the series folder names a library or a collection, not this file. */
    @Test
    public void tagTooFarUpIsIgnored() {
        final NameId id = NameId.read(path("Collection {tmdb-10}", "Films", "Alien (1979)", "Alien.1979.mkv"), null);
        assertNull(id.tmdb);
        assertEquals("Alien", id.title);
        assertEquals("1979", id.year);
    }

    @Test
    public void episodeFromSeasonFolderAndBareNumber() {
        final NameId id = NameId.read(path("Show (2015)", "Season 03", "07 - Name of it.mkv"), null);
        assertEquals(3, id.season);
        assertEquals(7, id.episode);
        assertEquals("Show", id.title);
        assertEquals("2015", id.year);
    }

    @Test
    public void russianSeasonFolderAndSeriya() {
        final NameId id = NameId.read(path("Друзья", "Сезон 2", "Серия 4.mkv"), null);
        assertEquals(2, id.season);
        assertEquals(4, id.episode);
        assertEquals("Друзья", id.title);
    }

    /** No segment source numbers specials: nothing to look up. */
    @Test
    public void specialsAreSkipped() {
        assertNull(NameId.read(path("Show", "Specials", "S00E01.mkv"), null));
    }

    @Test
    public void releaseNameOfAFilm() {
        final NameId id = NameId.read(path("Downloads", "The.Matrix.1999.2160p.UHD.BluRay.x265-GROUP.mkv"), null);
        assertNull(id.tmdb);
        assertEquals("The Matrix", id.title);
        assertEquals("1999", id.year);
        assertTrue(id.isMovie());
    }

    @Test
    public void releaseNameOfAnEpisode() {
        final NameId id = NameId.read(path("Downloads", "Severance.S02E01.1080p.WEB-DL.mkv"), null);
        assertEquals("Severance", id.title);
        assertEquals(2, id.season);
        assertEquals(1, id.episode);
    }

    /** "1917 (2019)" is the film 1917; a leading year is a title, not a year. */
    @Test
    public void yearAtTheStartIsTheTitle() {
        assertArrayEquals(new String[] { "1917", "2019" }, NameId.clean("1917 (2019)"));
        assertArrayEquals(new String[] { "2012", null }, NameId.clean("2012.mkv".replace(".mkv", "")));
    }

    @Test
    public void numbersWithDotsStayInTheTitle() {
        assertEquals("11.22.63", NameId.clean("11.22.63 (2016)")[0]);
    }

    /** A track number in front goes; a title that starts with a number keeps it. */
    @Test
    public void leadingTrackNumber() {
        assertArrayEquals(new String[] { "The Hockey Champ", "1939" }, NameId.clean("01. The Hockey Champ (1939)"));
        assertEquals("Name", NameId.clean("07 - Name")[0]);
        assertEquals("12 обезьян", NameId.clean("12 обезьян (1995)")[0]);
        assertEquals("300 спартанцев", NameId.clean("300 спартанцев 2006")[0]);
    }

    /** A generic file name sends the reading to the folder around it. */
    @Test
    public void genericFileNameReadsTheFolder() {
        final NameId id = NameId.read(path("Films", "Брат (1997)", "movie.mkv"), null);
        assertEquals("Брат", id.title);
        assertEquals("1997", id.year);
    }

    /** LAMPA: a URL that says nothing and a title with both names and the episode in it. */
    @Test
    public void launcherLabel() {
        final NameId id = NameId.read(Collections.emptyList(), "Ведьмак / The Witcher (2019) S01E03");
        assertEquals("Ведьмак", id.title);
        assertEquals("2019", id.year);
        assertEquals(1, id.season);
        assertEquals(3, id.episode);
    }

    @Test
    public void nothingToGoOn() {
        assertNull(NameId.read(Collections.emptyList(), null));
        assertNull(NameId.read(path("12345.mkv"), null));
    }

    @Test
    public void matchingNames() {
        assertTrue(NameId.matches("Аббатство Даунтон", "2010", "Аббатство Даунтон", "Downton Abbey", "2010"));
        assertTrue(NameId.matches("Downton Abbey", null, "Аббатство Даунтон", "Downton Abbey", "2010"));
        assertTrue(NameId.matches("Елки", "2010", "Ёлки", "Ёлки", "2010"));
        assertTrue(NameId.matches("11 22 63", "2016", "11.22.63", "11.22.63", "2016"));
        assertTrue(NameId.matches("Amelie", "2001", "Le Fabuleux Destin d'Amélie Poulain", "Amélie", "2001"));
        // A year apart is allowed, two is not; a resembling name is not the name.
        assertTrue(NameId.matches("Brat", "1997", "Brat", null, "1998"));
        assertFalse(NameId.matches("Brat", "1997", "Brat", null, "2000"));
        assertFalse(NameId.matches("Matrix", "1999", "The Matrix", "The Matrix", "1999"));
    }
}
