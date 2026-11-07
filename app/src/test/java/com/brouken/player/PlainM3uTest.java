package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class PlainM3uTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";

    @Test
    public void torrServerLinksAreCandidates_hlsIsNot() {
        assertTrue(PlainM3u.isCandidate("http://ts:8090/playlist?hash=" + HASH + "&fromlast"));
        assertTrue(PlainM3u.isCandidate("http://ts:8090/playlistall/all.m3u"));
        assertTrue(PlainM3u.isCandidate("http://ts:8090/stream/Show.m3u?link=" + HASH + "&m3u"));
        assertFalse(PlainM3u.isCandidate("https://cdn/x/master.m3u8"));
        assertFalse(PlainM3u.isCandidate("http://ts:8090/stream/Film.mkv?link=" + HASH + "&index=1&play"));
        assertFalse(PlainM3u.isCandidate("smb://nas/list.m3u"));
    }

    @Test
    public void readsTitlesAndResolvesAddresses() {
        final String text = "﻿#EXTM3U\n#EXTINF:0,S01E01.mkv\n"
                + "http://ts:8090/stream/S01E01.mkv?link=" + HASH + "&index=1&play\n"
                + "#EXTINF:0,S01E02.mkv\r\n/stream/S01E02.mkv?link=" + HASH + "&index=2&play\r\n";
        final List<PlainM3u.Entry> list = PlainM3u.parse(text, "http://ts:8090/playlist?hash=" + HASH);
        assertEquals(2, list.size());
        assertEquals("S01E01.mkv", list.get(0).title);
        assertEquals("http://ts:8090/stream/S01E02.mkv?link=" + HASH + "&index=2&play", list.get(1).uri);
    }

    @Test
    public void hlsAndJunkAreNotPlainLists() {
        assertNull(PlainM3u.parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n", "http://a/"));
        assertNull(PlainM3u.parse("<html>busy</html>", "http://a/"));
        assertNull(PlainM3u.parse("#EXTM3U\n", "http://a/"));
    }

    @Test
    public void oneTorrentFileIsOneKeyWhicheverWayItIsWritten() {
        final String key = "torr:///" + HASH + "/2";
        assertEquals(key, SyncKey.of("http://192.168.1.5:8090/stream/Ep2.mkv?link=" + HASH + "&index=2&play"));
        assertEquals(key, SyncKey.of("http://ts:8090/play/" + HASH.toUpperCase() + "/2"));
        assertEquals(key, SyncKey.of("https://home/ts/stream/x?link=" + HASH + "&index=02&play"));
        assertEquals(key, SyncKey.of("torr://ts:8090/Show/Ep2.mkv?b=x&h=" + HASH + "&i=2"));
        // A magnet link, or an address that only looks alike, keeps its whole address.
        final String magnet = "http://ts:8090/stream/x?link=magnet:?xt=urn&index=1&play";
        assertEquals(magnet, SyncKey.of(magnet));
        assertEquals("https://cdn/stream/a.mp4", SyncKey.of("https://cdn/stream/a.mp4"));
    }
}
