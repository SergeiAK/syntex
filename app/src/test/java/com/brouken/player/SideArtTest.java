package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SideArtTest {

    @Test
    public void ownPictureLandscapeFirst() {
        final SideArt side = new SideArt();
        side.picture("Film-poster.jpg", "p");
        side.picture("Film-thumb.jpg", "t");
        side.picture("Film.jpg", "plain");
        side.video();
        assertEquals("t", side.forVideo("Film.mkv"));
    }

    @Test
    public void caseOfNamesDoesNotMatter() {
        final SideArt side = new SideArt();
        side.picture("FOLDER.JPG", "f");
        side.picture("film-POSTER.Png", "p");
        side.video();
        assertEquals("p", side.forVideo("Film.mkv"));
        assertEquals("f", side.forFolder());
    }

    @Test
    public void folderPictureForTheOnlyVideo() {
        final SideArt side = new SideArt();
        side.picture("poster.jpg", "poster");
        side.picture("folder.jpg", "folder");
        side.video();
        assertEquals("poster", side.forVideo("Film.2024.mkv"));
    }

    @Test
    public void folderPictureNotForEpisodes() {
        final SideArt side = new SideArt();
        side.picture("folder.jpg", "folder");
        side.picture("S01E02-thumb.jpg", "e2");
        side.video();
        side.video();
        side.video();
        assertNull(side.forVideo("S01E01.mkv"));
        assertEquals("e2", side.forVideo("S01E02.mkv"));
        assertEquals("folder", side.forFolder());
    }

    @Test
    public void anotherFilmsPictureIsNotThisOnes() {
        final SideArt side = new SideArt();
        side.picture("Other.jpg", "other");
        side.video();
        assertNull(side.forVideo("Film.mkv"));
        assertNull(side.forFolder());
    }

    @Test
    public void picturesByExtension() {
        assertTrue(SideArt.isPicture("poster.JPEG"));
        assertTrue(SideArt.isPicture("Film.tbn"));
        assertTrue(SideArt.isPicture("fanart.webp"));
        assertFalse(SideArt.isPicture("Film.nfo"));
        assertFalse(SideArt.isPicture(null));
    }

    /** «8 женщин», «Аватар»: poster.jpg and fanart.jpg in the film's folder - the fanart fits a 16:9 row. */
    @Test
    public void fanartBeforePoster() {
        final SideArt side = new SideArt();
        side.picture("poster.jpg", "poster");
        side.picture("fanart.jpg", "fanart");
        side.picture("clearlogo.png", "logo");
        side.picture("banner.jpg", "banner");
        side.video();
        assertEquals("fanart", side.forVideo("8 femmes.mkv"));
        assertEquals("fanart", side.forFolder());
    }

    @Test
    public void landscapeBeforeFanart() {
        final SideArt side = new SideArt();
        side.picture("fanart.jpg", "fanart");
        side.picture("landscape.jpg", "landscape");
        side.picture("Film-poster.jpg", "own poster");
        side.video();
        assertEquals("landscape", side.forVideo("Film.mkv"));
    }

    @Test
    public void ownOfAKindBeforeSharedOfIt() {
        final SideArt side = new SideArt();
        side.picture("fanart.jpg", "shared");
        side.picture("Film-fanart.jpg", "own");
        side.video();
        assertEquals("own", side.forVideo("Film.mkv"));
    }

    @Test
    public void numberedBackdrop() {
        final SideArt side = new SideArt();
        side.picture("poster.jpg", "poster");
        side.picture("backdrop1.jpg", "b1");
        assertEquals("b1", side.forFolder());
    }

    @Test
    public void seasonPicturesFromTheSeriesFolder() {
        final SideArt series = new SideArt();
        series.picture("poster.jpg", "show poster");
        series.picture("fanart.jpg", "show fanart");
        series.picture("season01-poster.jpg", "s1 poster");
        series.picture("season01-thumb.jpg", "s1 thumb");
        series.picture("season02-poster.jpg", "s2 poster");
        series.picture("season-specials-fanart.jpg", "specials");
        series.picture("Season03.jpg", "plex s3");
        assertEquals("s1 thumb", series.forSeason(1).address);
        assertEquals("s2 poster", series.forSeason(2).address);
        assertEquals("specials", series.forSeason(0).address);
        assertEquals("plex s3", series.forSeason(3).address);
        assertNull(series.forSeason(4));
        assertEquals("show fanart", series.forFolder());
    }

    @Test
    public void seasonAllIsTheLastResort() {
        final SideArt series = new SideArt();
        series.picture("season-all-poster.jpg", "all");
        series.picture("season02-landscape.jpg", "s2");
        assertEquals("all", series.forSeason(5).address);
        assertEquals("s2", series.forSeason(2).address);
    }

    @Test
    public void seasonInsideBeatsOutsideOnlyByKind() {
        final SideArt series = new SideArt();
        series.picture("season01-poster.jpg", "outside poster");
        final SideArt season = new SideArt();
        season.picture("fanart.jpg", "inside fanart");
        assertEquals("inside fanart", series.forSeason(1).or(season.forFolderPick()).address);
        season.picture("fanart.jpg", "x");
        final SideArt posterOnly = new SideArt();
        posterOnly.picture("folder.jpg", "inside folder");
        // A tie of kinds: the season's own picture from the series' folder stays.
        assertEquals("outside poster", series.forSeason(1).or(posterOnly.forFolderPick()).address);
    }

    @Test
    public void seasonNumberFromTheFolderName() {
        assertEquals(1, SideArt.seasonOf("Season 1"));
        assertEquals(1, SideArt.seasonOf("Сезон 01"));
        assertEquals(2, SideArt.seasonOf("S02"));
        assertEquals(10, SideArt.seasonOf("Season 10"));
        assertEquals(1, SideArt.seasonOf("1 сезон"));
        assertEquals(3, SideArt.seasonOf("3"));
        assertEquals(2, SideArt.seasonOf("Темные начала 2 сезон"));
        assertEquals(4, SideArt.seasonOf("Season.04"));
        assertEquals(0, SideArt.seasonOf("Specials"));
        assertEquals(0, SideArt.seasonOf("Спецвыпуски"));
        assertEquals(-1, SideArt.seasonOf("Sherlock"));
        assertEquals(-1, SideArt.seasonOf("Avatar (2009)"));
        assertEquals(-1, SideArt.seasonOf("Doctor Who 2005"));
        assertEquals(-1, SideArt.seasonOf("Extras"));
    }

    @Test
    public void bareNameIsAThumbAmongEpisodes() {
        final SideArt season = new SideArt();
        season.picture("S01E01.jpg", "e1");
        season.picture("folder.jpg", "folder");
        season.video();
        season.video();
        assertEquals("e1", season.forVideo("S01E01.mkv"));
        assertNull(season.forVideo("S01E02.mkv"));
    }

    /**
     * tinyMediaManager's layout on the owner's library (J:/Media2/TVshow, 21.10): the season's
     * pictures inside its own folder, named for the season, beside every episode's -thumb.
     */
    @Test
    public void seasonPicturesInsideTheSeasonFolder() {
        final SideArt season = new SideArt();
        season.picture("season01-banner.jpg", "banner");
        season.picture("season01-poster.jpg", "poster");
        season.picture("season01-thumb.jpg", "thumb");
        for (int e = 1; e <= 10; e++) {
            season.picture(String.format("Человек в высоком замке - S01E%02d - x [1080p]-thumb.jpg", e), "e" + e);
            season.video();
        }
        assertNull(season.forFolderPick());
        assertEquals("thumb", season.forSeason(SideArt.seasonOf("Season 01")).address);
        assertEquals("e3", season.forVideo("Человек в высоком замке - S01E03 - x [1080p].mkv"));

        final SideArt chernobyl = new SideArt();
        chernobyl.picture("season01-poster.jpg", "poster");
        chernobyl.video();
        chernobyl.video();
        assertEquals("poster", chernobyl.forSeason(SideArt.seasonOf("Season 01")).address);
    }

    /** Sherlock's Specials: one episode with its own thumb, and the season's own pictures - the season's win. */
    @Test
    public void specialsFolderShowsTheSeasonNotTheEpisode() {
        final SideArt specials = new SideArt();
        specials.picture("season-specials-thumb.jpg", "season");
        specials.picture("Шерлок - S00E09 - Безобразная невеста [720p]-thumb.jpg", "episode");
        specials.video();
        final SideArt.Pick general = specials.pickForVideo("Шерлок - S00E09 - Безобразная невеста [720p].mkv");
        assertEquals("episode", general.address);
        assertEquals("season", specials.forSeason(SideArt.seasonOf("Specials")).or(general).address);
    }
}
