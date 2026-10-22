package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.media3.common.MimeTypes;

import org.junit.Test;

public class StreamMimeTest {

    @Test
    public void manifestExtensionBeatsLauncherMime() {
        assertEquals(MimeTypes.APPLICATION_M3U8, StreamMime.of("/hls/abc/index.m3u8", "video/*"));
        assertEquals(MimeTypes.APPLICATION_M3U8, StreamMime.of("/live/Index.M3U8", "video/mp4"));
        assertEquals(MimeTypes.APPLICATION_MPD, StreamMime.of("/dash/stream.mpd", "video/*"));
        assertEquals(MimeTypes.APPLICATION_SS, StreamMime.of("/ss/Movie.ism/Manifest", "video/*"));
    }

    @Test
    public void otherPathsKeepLauncherMime() {
        assertEquals("video/*", StreamMime.of("/stream/film.mkv", "video/*"));
        assertEquals("video/*", StreamMime.of("/ts/stream", "video/*"));
        assertNull(StreamMime.of("/stream/film.mkv", null));
        assertEquals("video/*", StreamMime.of(null, "video/*"));
    }
}
