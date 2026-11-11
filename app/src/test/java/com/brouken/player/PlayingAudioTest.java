package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import org.junit.Test;

public class PlayingAudioTest {

    /** A KinoPub rendition as the master playlist describes it: AAC, no channel count, no id on playback. */
    private static Format rendition(final String label) {
        return new Format.Builder().setId("audio:" + label).setLabel(label).setLanguage("ru")
                .setSampleMimeType(MimeTypes.AUDIO_AAC).setCodecs("mp4a.40.2").build();
    }

    private static Format playing(final String label, final String mime, final int channels) {
        return new Format.Builder().setLabel(label).setLanguage("ru")
                .setSampleMimeType(mime).setChannelCount(channels).build();
    }

    @Test
    public void aacFromAPlaylistWithoutChannelsGetsTheCount() {
        final Format shown = PlayingAudio.of(rendition("01. Дубляж"),
                playing("01. Дубляж", MimeTypes.AUDIO_AAC, 2), true);
        assertEquals(MimeTypes.AUDIO_AAC, shown.sampleMimeType);
        assertEquals(2, shown.channelCount);
        assertEquals("AAC 2.0", CustomDefaultTrackNameProvider.techInfo(shown));
    }

    @Test
    public void ac3DeclaredAsAacGetsCodecAndCount() {
        final Format shown = PlayingAudio.of(rendition("07. Дубляж (RUS) AC3"),
                playing("07. Дубляж (RUS) AC3", MimeTypes.AUDIO_AC3, 6), true);
        assertEquals(MimeTypes.AUDIO_AC3, shown.sampleMimeType);
        assertEquals(6, shown.channelCount);
        assertEquals("AC-3 5.1", CustomDefaultTrackNameProvider.techInfo(shown));
    }

    @Test
    public void wrongCodecBringsTheWrongCountWithIt() {
        final Format track = rendition("07. Дубляж (RUS) AC3").buildUpon().setChannelCount(2).build();
        final Format shown = PlayingAudio.of(track,
                playing("07. Дубляж (RUS) AC3", MimeTypes.AUDIO_AC3, 6), true);
        assertEquals(6, shown.channelCount);
    }

    @Test
    public void aKnownCountOfTheSameCodecStands() {
        final Format track = rendition("01. Дубляж").buildUpon().setChannelCount(6).build();
        assertSame(track, PlayingAudio.of(track, playing("01. Дубляж", MimeTypes.AUDIO_AAC, 2), true));
    }

    @Test
    public void anUnselectedOrOtherTrackIsLeftAlone() {
        final Format track = rendition("02. Оригинал");
        assertSame(track, PlayingAudio.of(track, playing("02. Оригинал", MimeTypes.AUDIO_AAC, 2), false));
        assertSame(track, PlayingAudio.of(track, playing("01. Дубляж", MimeTypes.AUDIO_AAC, 2), true));
    }

    @Test
    public void anIdOnThePlayingFormatDecides() {
        final Format track = rendition("01. Дубляж");
        final Format other = playing("01. Дубляж", MimeTypes.AUDIO_AAC, 2).buildUpon().setId("audio:x").build();
        assertSame(track, PlayingAudio.of(track, other, true));
        final Format same = other.buildUpon().setId(track.id).build();
        assertEquals(2, PlayingAudio.of(track, same, true).channelCount);
    }
}
