package com.brouken.player;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Tokens in a URL's host and path, as KinoPub writes them (logs of 0154, 12.11.2026). */
public class UrlTokensTest {

    @Test
    public void kinoPubPathTokensAreHidden() {
        assertEquals("media=https://<token>.msk-static-05.cdntogo.net/hls/<token>.mp4/master-v1a1.m3u8",
                UrlTokens.hide("media=https://1a5af214-d010-4ccc-b666-eba61130c0db.msk-static-05.cdntogo.net"
                        + "/hls/0f3a9c1b7e2d4a5f8b6c9d0e1f2a3b4c.mp4/master-v1a1.m3u8"));
        assertEquals("https://api.service-kp.com/manifest/hls4/<token>/8224.m3u8",
                UrlTokens.hide("https://api.service-kp.com/manifest/hls4/a1B2c3D4e5F6g7H8i9J0k1L2m3N4/8224.m3u8"));
        assertEquals("Subtitle: id=2:https://cdn.example/pd/<token>/subtitles/9/59/481189.srt, mime",
                UrlTokens.hide("Subtitle: id=2:https://cdn.example/pd/aWQ9MTA4ODIyOzE1NjU4MTA3MTg7NDY3MTc1/"
                        + "subtitles/9/59/481189.srt, mime"));
    }

    @Test
    public void namesAndPlainTextStay() {
        final String smb = "smb://192.168.1.40/Data2/Media2/TVshow/%D0%90%D0%B1%D0%B1%D0%B0%D1%82%D1%81%D1%82/Season%2001";
        assertEquals(smb, UrlTokens.hide(smb));
        final String file = "http://192.168.1.40:5665/stream/04.%20The%20Incident.mkv";
        assertEquals(file, UrlTokens.hide(file));
        final String words = "http://host/Abcdefghijklmnopqrstuvwxyzabc/x.mkv";
        assertEquals(words, UrlTokens.hide(words));
        assertEquals("segments: tt1442437 S5E7 picked INTRO 48-60",
                UrlTokens.hide("segments: tt1442437 S5E7 picked INTRO 48-60"));
    }
}
