package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class FrameChoiceTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";

    @Test
    public void roundTrips() {
        final FrameChoice back = FrameChoice.decode(new FrameChoice(4, 4f / 3f, 0f).encode());
        assertEquals(4, back.resizeMode);
        assertEquals(4f / 3f, back.scale, 0.001f);
        assertEquals(0f, back.aspectRatio, 0f);
    }

    @Test
    public void junkIsNoChoice() {
        assertNull(FrameChoice.decode(null));
        assertNull(FrameChoice.decode("4|x|0"));
        assertNull(FrameChoice.decode("4|0|0"));
        assertNull(FrameChoice.decode("4|1.0"));
    }

    @Test
    public void aTorrentIsItsOwnSeries_notTheWholeServer() {
        assertEquals("torr:" + HASH, TrackMemory.seriesKey(null,
                "http://ts:8090/stream/S01E02.mkv?link=" + HASH + "&index=2&play"));
        assertEquals("torr:" + HASH, TrackMemory.seriesKey(null, "http://ts:8090/play/" + HASH + "/5"));
        assertEquals("tmdb:1399", TrackMemory.seriesKey("1399",
                "http://ts:8090/stream/S01E02.mkv?link=" + HASH + "&index=2&play"));
        assertEquals("dir:smb://nas/TV/Show", TrackMemory.seriesKey(null, "smb://nas/TV/Show/E1.mkv"));
    }
}
