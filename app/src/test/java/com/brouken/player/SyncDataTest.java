package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** The sync document and its merge (see {@link SyncData}), and the key it files things under. */
public class SyncDataTest {

    @Test
    public void keyDropsTheMachineOfAShare() {
        assertEquals("smb:///video/Film.mkv", SyncKey.of("smb://192.168.1.40/video/Film.mkv"));
        assertEquals("smb:///video/Film.mkv", SyncKey.of("smb://nas:445/video/Film.mkv"));
        assertEquals("smb:///video/Film.mkv", SyncKey.of("smb://user@nas/video/Film.mkv"));
        assertEquals("dav:///dav/Film.mkv", SyncKey.of("davs://nas:5006/dav/Film.mkv"));
        assertEquals("dav:///dav/Film.mkv", SyncKey.of("dav://nas/dav/Film.mkv"));
    }

    @Test
    public void keyIsItsOwnKey() {
        final String key = SyncKey.of("smb://nas/video/Film.mkv");
        assertEquals(key, SyncKey.of(key));
    }

    @Test
    public void keyKeepsEveryOtherAddressWhole() {
        final String[] whole = {
                "http://cdn.example/hls/master.m3u8",
                "torr://192.168.1.40:8090/stream/x?link=abc&index=1",
                "content://com.android.externalstorage.documents/document/primary%3AMovies%2F1.mkv",
                "file:///storage/emulated/0/Movies/1.mkv",
        };
        for (final String uri : whole) {
            assertEquals(uri, SyncKey.of(uri));
        }
        assertFalse(SyncKey.portable(whole[2]));
        assertTrue(SyncKey.portable(whole[3]));
    }

    @Test
    public void newerEntryWinsPerEntry() {
        final SyncData shield = new SyncData();
        shield.positions.put("film", new SyncData.Position(200, 50_000, 7_200_000));
        shield.positions.put("series", new SyncData.Position(100, 10_000, 0));
        final SyncData dune = new SyncData();
        dune.positions.put("film", new SyncData.Position(150, 90_000, 7_200_000));
        dune.positions.put("series", new SyncData.Position(300, 20_000, 2_400_000));

        final SyncData.Taken taken = shield.merge(dune);

        assertEquals(1, taken.positions.size());
        assertEquals(50_000, shield.positions.get("film").position);
        assertEquals(20_000, shield.positions.get("series").position);
    }

    @Test
    public void sameAgeIsNotAChange() {
        final SyncData one = new SyncData();
        one.watched.put("film", new SyncData.Mark(100, true));
        final SyncData two = new SyncData();
        two.watched.put("film", new SyncData.Mark(100, true));
        assertEquals(0, one.merge(two).total());
    }

    @Test
    public void anUnmarkThatIsNewerStaysUnmarked() {
        final SyncData here = new SyncData();
        here.watched.put("film", new SyncData.Mark(500, false));
        final SyncData stale = new SyncData();
        stale.watched.put("film", new SyncData.Mark(400, true));
        here.merge(stale);
        assertFalse(here.watched.get("film").watched);

        final SyncData later = new SyncData();
        later.watched.put("film", new SyncData.Mark(600, true));
        here.merge(later);
        assertTrue(here.watched.get("film").watched);
    }

    @Test
    public void roundTripKeepsEveryTypeItsOwn() throws JSONException {
        final SyncData data = new SyncData();
        data.device = "Shield";
        data.written = 1234;
        data.positions.put("smb:///video/Film.mkv", new SyncData.Position(10, 20, 30));
        data.positions.put("smb:///video/Clip.mkv", new SyncData.Position(11, 21, 0));
        data.watched.put("smb:///video/Old.mkv", new SyncData.Mark(12, false));
        final Set<String> set = new LinkedHashSet<>(Arrays.asList("a", "b"));
        data.settings.put("b", new SyncData.Setting(1, true));
        data.settings.put("i", new SyncData.Setting(2, 42));
        data.settings.put("l", new SyncData.Setting(3, 1L << 40));
        data.settings.put("f", new SyncData.Setting(4, 1.25f));
        data.settings.put("s", new SyncData.Setting(5, "ru"));
        data.settings.put("set", new SyncData.Setting(6, set));

        final SyncData back = SyncData.fromJson(data.toJson());

        assertEquals("Shield", back.device);
        assertEquals(1234, back.written);
        assertEquals(20, back.positions.get("smb:///video/Film.mkv").position);
        assertEquals(30, back.positions.get("smb:///video/Film.mkv").length);
        assertEquals(0, back.positions.get("smb:///video/Clip.mkv").length);
        assertFalse(back.watched.get("smb:///video/Old.mkv").watched);
        assertEquals(12, back.watched.get("smb:///video/Old.mkv").at);
        assertEquals(Boolean.TRUE, back.settings.get("b").value);
        assertEquals(Integer.valueOf(42), back.settings.get("i").value);
        assertEquals(Long.valueOf(1L << 40), back.settings.get("l").value);
        assertEquals(Float.valueOf(1.25f), back.settings.get("f").value);
        assertEquals("ru", back.settings.get("s").value);
        assertEquals(set, back.settings.get("set").value);
        assertEquals(6, back.settings.get("set").at);
    }

    @Test
    public void somethingElseIsRefused() {
        try {
            SyncData.fromJson("{\"positions\":{}}");
            fail();
        } catch (final JSONException expected) {
            // Not ours: no format line.
        }
        try {
            SyncData.fromJson("{\"format\":\"syntex-sync\",\"version\":99}");
            fail();
        } catch (final JSONException expected) {
            // Ours, from a build that writes more than this one reads.
        }
    }

    @Test
    public void anUnknownSettingTypeIsSkipped() throws JSONException {
        final SyncData back = SyncData.fromJson("{\"format\":\"syntex-sync\",\"version\":1,"
                + "\"settings\":{\"x\":{\"at\":1,\"type\":\"blob\",\"value\":1},"
                + "\"y\":{\"at\":1,\"type\":\"bool\",\"value\":true}}}");
        assertFalse(back.settings.containsKey("x"));
        assertTrue(back.settings.containsKey("y"));
    }
}
