package com.brouken.player;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;

import java.util.Objects;

/**
 * The selected audio track's format as the header and the audio menu show it: the track's own entry, with
 * what only the playing format knows filled in.
 *
 * <p>An HLS rendition's format is the master playlist's word. A variant that lists a single audio codec has
 * it written over every audio rendition it carries (HlsSampleStreamWrapper.deriveFormat), whatever the
 * segments hold: KinoPub declares mp4a.40.2 on every variant, so its AC3 dub was headed "AAC" while the
 * decoder was c2.amlogic.audio.decoder.ac3 (27.10.2026). And a playlist without {@code CHANNELS} leaves the
 * count unknown, so the header said "AAC" where it could say "AAC 2.0" (user, 11.11.2026). Both are known
 * once the decoder is fed; a track that is not playing keeps what the playlist says.
 */
final class PlayingAudio {

    private PlayingAudio() {
    }

    /**
     * The two are matched by id where the playing format has one (Format.withManifestFormatInfo). On
     * KinoPub it does not — the report on the Dune read {@code Audio: id=null} with the rendition's
     * language and label (29.10.2026), and 0117, which matched by id only, kept saying "AAC" — so a
     * selected track with the same language and label is taken as the one playing.
     *
     * @param selected whether the track is the selected one: only that one can be what plays, and it
     *                 keeps two renditions that share a label from both being renamed
     */
    @Nullable
    static Format of(@Nullable final Format track, @Nullable final Format playing, final boolean selected) {
        if (track == null || playing == null || !selected) {
            return track;
        }
        final boolean same = playing.id != null
                ? playing.id.equals(track.id)
                : track.label != null && track.label.equals(playing.label)
                        && Objects.equals(track.language, playing.language);
        if (!same) {
            return track;
        }
        final boolean otherCodec = playing.sampleMimeType != null
                && !playing.sampleMimeType.equals(track.sampleMimeType);
        final boolean countUnknown = track.channelCount <= 0 && playing.channelCount > 0;
        if (!otherCodec && !countUnknown) {
            return track;
        }
        final Format.Builder corrected = track.buildUpon();
        if (otherCodec) {
            corrected.setSampleMimeType(playing.sampleMimeType)
                    // Not the playing format's codecs: Media3 fills those in from the same playlist entry.
                    .setCodecs(null);
            if (track.averageBitrate == Format.NO_VALUE && track.peakBitrate == Format.NO_VALUE) {
                corrected.setAverageBitrate(playing.averageBitrate).setPeakBitrate(playing.peakBitrate);
            }
        }
        // A playlist that named the wrong codec may have named the wrong count with it.
        if (playing.channelCount > 0 && (otherCodec || countUnknown)) {
            corrected.setChannelCount(playing.channelCount);
        }
        return corrected.build();
    }
}
