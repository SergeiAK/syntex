package com.brouken.player;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The codec a track's name gives away when its declared one says otherwise. HLS takes a track's mime from
 * the variant's CODECS, and KinoPub writes mp4a.40.2 on every variant, so its "07. Дубляж (RUS) AC3" is
 * listed as AAC; the name is the only place the real codec shows before a decoder opens on it. Only a
 * declared AAC is read this way - any other declared codec is taken at its word.
 */
final class NamedAudioCodec {

    // E-AC3 before AC3, so "E-AC3" is not read as AC3. Word edges keep "AC3" out of longer tokens; "DD+" and
    // "Dolby Digital Plus" are the names E-AC3 goes by in release titles, "DD" and "Dolby Digital" AC3's.
    private static final Pattern EAC3 = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])(e-?ac-?3|dd\\+|ddp|dolby digital plus)(?![\\p{L}\\p{N}])");
    private static final Pattern AC3 = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}-])(ac-?3|dolby digital)(?![\\p{L}\\p{N}])");

    private NamedAudioCodec() {
    }

    /** {@code audio/eac3} or {@code audio/ac3} for a declared AAC whose name says so; null otherwise. */
    @Nullable
    static String of(@Nullable Format format) {
        if (format == null || !MimeTypes.AUDIO_AAC.equals(format.sampleMimeType) || format.label == null) {
            return null;
        }
        return of(format.label);
    }

    @Nullable
    static String of(String label) {
        final Matcher eac3 = EAC3.matcher(label);
        if (eac3.find()) {
            return MimeTypes.AUDIO_E_AC3;
        }
        return AC3.matcher(label).find() ? MimeTypes.AUDIO_AC3 : null;
    }
}
