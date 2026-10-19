package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.media3.common.MimeTypes;

import org.junit.Test;

public class ContainerNameTest {

    @Test
    public void theExtractorNamesTheFamily() {
        assertEquals("MKV", ContainerName.of(MimeTypes.VIDEO_MATROSKA, "Sherlock.S01E01.mkv"));
        assertEquals("AVI", ContainerName.of(MimeTypes.VIDEO_AVI, "A Disney Christmas Gift.avi"));
        assertEquals("FLV", ContainerName.of(MimeTypes.VIDEO_FLV, "Queen - Bohemian Rhapsody [360p].flv"));
        assertEquals("MPEG-PS", ContainerName.of(MimeTypes.VIDEO_PS, "clip.dat"));
    }

    @Test
    public void theExtensionSharpensAMemberOfTheSameFamily() {
        assertEquals("MOV", ContainerName.of(MimeTypes.VIDEO_MP4, "Сплин - Романс.mov"));
        assertEquals("M2TS", ContainerName.of(MimeTypes.VIDEO_MP2T, "dolby_truehd_channel_check_lossless.m2ts"));
        assertEquals("MPG", ContainerName.of(MimeTypes.VIDEO_PS, "Земфира - Мы разбиваемся.mpg"));
    }

    @Test
    public void theExtensionNeverOverridesWhatTheExtractorRead() {
        assertEquals("MKV", ContainerName.of(MimeTypes.VIDEO_MATROSKA, "misnamed.avi"));
        assertEquals("TS", ContainerName.of(MimeTypes.VIDEO_MP2T, "stream"));
    }

    @Test
    public void withoutTheExtractorTheExtensionIsAllThereIs() {
        assertEquals("WMV", ContainerName.of(null, "Король и Шут - Марионетки.wmv"));
        assertNull(ContainerName.of(null, "1234567"));
        assertNull(ContainerName.of(null, null));
    }

    @Test
    public void adaptiveStreamsByTheirManifest() {
        assertEquals("HLS", ContainerName.of(MimeTypes.APPLICATION_M3U8, "index.m3u8"));
        assertEquals("DASH", ContainerName.of(MimeTypes.APPLICATION_MPD, "manifest.mpd"));
    }
}
