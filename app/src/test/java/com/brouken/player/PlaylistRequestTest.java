package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Bundle;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Test;

/** The {@code playlist} extra as LAMPA's shell builds it (see {@link PlaylistRequest}). */
public class PlaylistRequestTest {

    private static Bundle episode(String uri, int season, int episode, String name) {
        return new Bundle().put("uri", uri).put("season", season).put("episode", episode)
                .put("episode_title", name).put("imdb_id", "tt0944947").put("tmdb_id", "1399");
    }

    private static Bundle playlist(Bundle... items) {
        return new Bundle().put("title", "Game of Thrones").put("items", items);
    }

    @Test
    public void headerLineIsTheSeriesAndTheLogoFallsBackToThePlaylists() {
        final Bundle own = episode("https://host/s1e2.mkv", 1, 2, "The Kingsroad")
                .put("title", "GoT").put("logo", "https://host/own.png");
        final PlaylistRequest read = PlaylistRequest.read(
                playlist(episode("https://host/s1e1.mkv", 1, 1, "Winter Is Coming"), own)
                        .put("logo", "https://host/logo.png"));

        assertEquals("Game of Thrones", read.seriesTitle(0));
        assertEquals("GoT", read.seriesTitle(1));
        assertEquals("https://host/logo.png", read.logo(0));
        assertEquals("https://host/own.png", read.logo(1));
    }

    @Test
    public void anEpisodeNameThatOnlyRepeatsTheNumberIsLeftOut() {
        assertNull(PlaylistRequest.episodeName("Episode 3", 3));
        assertNull(PlaylistRequest.episodeName("Серия 3", 3));
        assertNull(PlaylistRequest.episodeName("Серія 3", 3));
        assertNull(PlaylistRequest.episodeName("E03", 3));
        assertNull(PlaylistRequest.episodeName("3", 3));
        assertNull(PlaylistRequest.episodeName(null, 3));
        assertEquals("Серия 4", PlaylistRequest.episodeName("Серия 4", 3));
        assertEquals("The Kingsroad", PlaylistRequest.episodeName("The Kingsroad", 2));
        assertEquals("1984", PlaylistRequest.episodeName("1984", 2));
    }

    @Test
    public void readsWhatTheShellSends() {
        final Bundle second = episode("https://host/s1e2.mkv", 1, 2, "The Kingsroad")
                .put("position_sec", 754)
                .put("thumbnail", "https://host/s1e2.jpg")
                .put("segments", "{\"skip\":[{\"start\":62,\"end\":152}]}");
        final Bundle request = playlist(episode("https://host/s1e1.mkv", 1, 1, "Winter Is Coming"), second)
                .put("start_index", 1)
                .put("ask_resume", true)
                .put("headers", new String[]{"User-Agent", "Lampa"})
                .put("logo", "https://host/logo.png");

        final PlaylistRequest read = PlaylistRequest.read(request);

        assertNull(read.error);
        assertEquals("Game of Thrones", read.title);
        assertEquals("https://host/logo.png", read.logo);
        assertEquals(1, read.startIndex);
        assertTrue(read.askResume);
        assertEquals(Arrays.asList("User-Agent", "Lampa"), Arrays.asList(read.headers));
        assertEquals(2, read.items.size());
        final PlaylistRequest.Item item = read.items.get(1);
        assertEquals("https://host/s1e2.mkv", item.uri);
        assertEquals(Integer.valueOf(1), item.season);
        assertEquals(Integer.valueOf(2), item.episode);
        assertEquals("tt0944947", item.imdbId);
        assertEquals("1399", item.tmdbId);
        assertEquals("https://host/s1e2.jpg", item.thumbnail);
        assertEquals(Long.valueOf(754_000), item.positionMs);
        assertTrue(item.segments.contains("skip"));
        assertNull(read.items.get(0).positionMs);
        assertEquals("The Kingsroad", read.displayTitle(1));
    }

    @Test
    public void aFilmIsTitledByThePlaylist() {
        final PlaylistRequest read = PlaylistRequest.read(
                new Bundle().put("title", "Heat").put("items", new Parcelable[]{new Bundle().put("uri", "u")}));
        assertEquals("Heat", read.displayTitle(0));
        assertFalse(read.askResume);
        assertEquals(0, read.startIndex);
    }

    @Test
    public void millisecondsWinOverSeconds() {
        final PlaylistRequest.Item item = PlaylistRequest.read(playlist(
                new Bundle().put("uri", "u").put("position_ms", 1500L).put("position_sec", 9))).items.get(0);
        assertEquals(Long.valueOf(1500), item.positionMs);
    }

    @Test
    public void anUnreadableTimeFallsBackToTheOtherUnit() {
        final PlaylistRequest.Item item = PlaylistRequest.read(playlist(
                new Bundle().put("uri", "u").put("position_ms", "soon").put("position_sec", "12.9"))).items.get(0);
        assertEquals(Long.valueOf(12_900), item.positionMs);
    }

    @Test
    public void numbersAsStringsAndFractionsRoundDown() {
        final PlaylistRequest read = PlaylistRequest.read(playlist(
                new Bundle().put("uri", "a"),
                new Bundle().put("uri", "b").put("season", "3").put("episode", 4.7))
                .put("start_index", "1"));
        assertNull(read.error);
        assertEquals(1, read.startIndex);
        assertEquals(Integer.valueOf(3), read.items.get(1).season);
        assertEquals(Integer.valueOf(4), read.items.get(1).episode);
    }

    @Test
    public void blankStringsAreAbsent() {
        final PlaylistRequest.Item item = PlaylistRequest.read(playlist(
                new Bundle().put("uri", " u ").put("episode_title", "  ").put("imdb_id", ""))).items.get(0);
        assertEquals("u", item.uri);
        assertNull(item.episodeTitle);
        assertNull(item.imdbId);
    }

    @Test
    public void itemsMayComeAsAList() {
        final ArrayList<Bundle> items = new ArrayList<>();
        items.add(new Bundle().put("uri", "u"));
        assertEquals(1, PlaylistRequest.read(new Bundle().put("items", items)).items.size());
    }

    @Test
    public void qualitiesWithoutASelectionPlayTheItemsUri() {
        final Bundle item = new Bundle().put("uri", "https://host/720").put("qualities", new Parcelable[]{
                new Bundle().put("label", "1080p").put("uri", "https://host/1080"),
                new Bundle().put("label", "720p").put("uri", "https://host/720"),
                new Bundle().put("label", "480p"),
        });
        final PlaylistRequest.Item read = PlaylistRequest.read(playlist(item)).items.get(0);
        assertEquals("https://host/720", read.uri);
        assertEquals(Arrays.asList("1080p", "720p"), new ArrayList<>(read.qualities.keySet()));
    }

    @Test
    public void aSelectedQualityPlaysFirst() {
        final Bundle item = new Bundle().put("uri", "https://host/720").put("qualities", new Parcelable[]{
                new Bundle().put("label", "1080p").put("uri", "https://host/1080").put("selected", "true"),
        });
        assertEquals("https://host/1080", PlaylistRequest.read(playlist(item)).items.get(0).uri);
    }

    @Test
    public void qualitiesAloneArePlayable() {
        final Bundle item = new Bundle().put("qualities", new Parcelable[]{
                new Bundle().put("label", "1080p").put("uri", "https://host/1080"),
        });
        assertEquals("https://host/1080", PlaylistRequest.read(playlist(item)).items.get(0).uri);
    }

    @Test
    public void subtitlesWithoutUriAreSkippedWithAWarning() {
        final Bundle item = new Bundle().put("uri", "u").put("subtitles", new Parcelable[]{
                new Bundle().put("uri", "https://host/uk.srt").put("label", "Full").put("language", "uk"),
                new Bundle().put("label", "Sub"),
                new Bundle().put("uri", "https://host/en.srt").put("selected", true),
                new Bundle().put("uri", "https://host/ru.srt").put("selected", true),
        });
        final PlaylistRequest read = PlaylistRequest.read(playlist(item));
        assertNull(read.error);
        final PlaylistRequest.Item subs = read.items.get(0);
        assertEquals(3, subs.subtitles.size());
        assertEquals("uk", subs.subtitles.get(0).language);
        assertEquals("Full", subs.subtitles.get(0).label);
        assertTrue(subs.subtitles.get(1).selected);
        assertFalse("only the first marked counts", subs.subtitles.get(2).selected);
        assertEquals(Arrays.asList("items[0]: subtitles[1] has no uri; skipped"), read.warnings);
    }

    @Test
    public void noItemsIsRefused() {
        assertEquals("playlist has no items", PlaylistRequest.read(new Bundle()).error);
        assertEquals("playlist has no items",
                PlaylistRequest.read(new Bundle().put("items", new Parcelable[0])).error);
        assertEquals("playlist has no items", PlaylistRequest.read(new Bundle().put("items", "x")).error);
    }

    @Test
    public void anItemThatIsNotABundleIsRefused() {
        final PlaylistRequest read = PlaylistRequest.read(new Bundle().put("items", Arrays.asList(
                new Bundle().put("uri", "u"), "x")));
        assertEquals("items[1] is not a Bundle", read.error);
        assertTrue(read.items.isEmpty());
    }

    @Test
    public void anItemWithNothingToPlayIsRefused() {
        assertEquals("items[0] has neither uri nor qualities",
                PlaylistRequest.read(playlist(new Bundle().put("title", "x"))).error);
        assertEquals("items[0] has only voices, which this player does not read",
                PlaylistRequest.read(playlist(new Bundle().put("voices", new Parcelable[0]))).error);
    }

    @Test
    public void aStartIndexOutOfRangeIsRefused() {
        final PlaylistRequest read = PlaylistRequest.read(
                playlist(new Bundle().put("uri", "a"), new Bundle().put("uri", "b")).put("start_index", 2));
        assertEquals("start_index 2 is out of range 0..1", read.error);
        assertTrue(read.items.isEmpty());
    }
}
