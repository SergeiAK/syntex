package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class TrackMemoryTest {

    private static TrackMemory.Track t(String lang, int kind, String name, int index) {
        return new TrackMemory.Track(lang, kind, name, index);
    }

    @Test
    public void languageAndKindDecideAlone() {
        final List<TrackMemory.Track> tracks = Arrays.asList(
                t("eng", TrackMemory.KIND_MAIN, "Original", 0),
                t("rus", TrackMemory.KIND_MAIN, "Дубляж", 1),
                t("rus", TrackMemory.KIND_COMMENTARY, "Комментарии", 2));
        assertEquals(1, TrackMemory.match(tracks, t("rus", TrackMemory.KIND_MAIN, "LostFilm", 3)));
        assertEquals(2, TrackMemory.match(tracks, t("rus", TrackMemory.KIND_COMMENTARY, null, 0)));
    }

    @Test
    public void nameThenPlaceAmongSameLanguage() {
        final List<TrackMemory.Track> tracks = Arrays.asList(
                t("rus", TrackMemory.KIND_MAIN, "Дубляж", 0),
                t("rus", TrackMemory.KIND_MAIN, "MVO LostFilm", 1),
                t("rus", TrackMemory.KIND_MAIN, "MVO NewStudio", 2));
        assertEquals(1, TrackMemory.match(tracks, t("rus", TrackMemory.KIND_MAIN, "mvo lostfilm", 2)));
        assertEquals(2, TrackMemory.match(tracks, t("rus", TrackMemory.KIND_MAIN, "Кураж-Бамбей", 2)));
        assertEquals(0, TrackMemory.match(tracks, t("rus", TrackMemory.KIND_MAIN, "Кураж-Бамбей", 7)));
    }

    @Test
    public void untaggedFoundByNameButNeverByPlaceAcrossLanguages() {
        final List<TrackMemory.Track> tracks = Arrays.asList(
                t(null, TrackMemory.KIND_MAIN, "Original", 0),
                t(null, TrackMemory.KIND_MAIN, "Дубляж", 1));
        assertEquals(1, TrackMemory.match(tracks, t("rus", TrackMemory.KIND_MAIN, "Дубляж", 0)));
        assertEquals(-1, TrackMemory.match(tracks, t("rus", TrackMemory.KIND_MAIN, "LostFilm", 1)));
        // Kind is never crossed, even by name.
        assertEquals(-1, TrackMemory.match(tracks, t("rus", TrackMemory.KIND_FORCED, "Дубляж", 1)));
    }

    @Test
    public void encodeRoundTripsAndKeepsBars() {
        final TrackMemory.Track back = TrackMemory.decode(
                TrackMemory.encode(t("rus", TrackMemory.KIND_SDH, "a|b", 4)));
        assertEquals("rus", back.language);
        assertEquals(TrackMemory.KIND_SDH, back.kind);
        assertEquals("a|b", back.name);
        assertEquals(4, back.index);
        final TrackMemory.Track bare = TrackMemory.decode(TrackMemory.encode(t(null, 0, null, 0)));
        assertNull(bare.language);
        assertNull(bare.name);
        assertNull(TrackMemory.decode("junk"));
    }

    @Test
    public void seriesIsCardThenFolder() {
        assertEquals("tmdb:1399", TrackMemory.seriesKey(" 1399 ", "smb://nas/a/b.mkv"));
        assertEquals("dir:smb://nas/TV/Show/Season 1",
                TrackMemory.seriesKey(null, "smb://nas/TV/Show/Season 1/e01.mkv"));
        assertEquals("dir:http://h/stream",
                TrackMemory.seriesKey("", "http://h/stream/e01.mkv?token=a/b"));
        assertNull(TrackMemory.seriesKey(null, null));
    }
}
