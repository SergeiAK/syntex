package com.brouken.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import org.junit.Test;

public class NamedAudioCodecTest {

    private static Format declared(String mime, String label) {
        return new Format.Builder().setSampleMimeType(mime).setLabel(label).build();
    }

    @Test
    public void kinoPubDubNamedAc3() {
        assertEquals(MimeTypes.AUDIO_AC3,
                NamedAudioCodec.of(declared(MimeTypes.AUDIO_AAC, "07. Дубляж (RUS) AC3")));
    }

    @Test
    public void eac3IsNotReadAsAc3() {
        assertEquals(MimeTypes.AUDIO_E_AC3, NamedAudioCodec.of("03. Original (ENG) E-AC3"));
        assertEquals(MimeTypes.AUDIO_E_AC3, NamedAudioCodec.of("Dolby Digital Plus 5.1"));
        assertEquals(MimeTypes.AUDIO_E_AC3, NamedAudioCodec.of("Многоголосый, DD+"));
        assertEquals(MimeTypes.AUDIO_AC3, NamedAudioCodec.of("Dolby Digital 5.1"));
        assertEquals(MimeTypes.AUDIO_AC3, NamedAudioCodec.of("ac-3"));
    }

    @Test
    public void plainNamesSayNothing() {
        assertNull(NamedAudioCodec.of("01. Оригинал (ENG)"));
        assertNull(NamedAudioCodec.of("Track AC35"));
        assertNull(NamedAudioCodec.of("BAC3 commentary"));
    }

    @Test
    public void onlyADeclaredAacIsRead() {
        assertNull(NamedAudioCodec.of(declared(MimeTypes.AUDIO_AC3, "07. Дубляж (RUS) AC3")));
        assertNull(NamedAudioCodec.of(declared(MimeTypes.AUDIO_OPUS, "AC3")));
        assertNull(NamedAudioCodec.of(declared(MimeTypes.AUDIO_AAC, null)));
    }
}
