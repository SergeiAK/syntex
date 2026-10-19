package com.brouken.player;

import androidx.annotation.Nullable;
import androidx.media3.common.util.ParsableBitArray;

import java.util.Arrays;
import java.util.List;

/**
 * The one fact about an MPEG-4 Part 2 stream that decides who may decode it: whether its video object
 * layer turns on global motion compensation (sprite_enable = GMC, ISO/IEC 14496-2 6.2.3). XviD writes
 * it with "GMC" ticked, and the device decoders part ways on it: Nvidia's and the Android software one
 * refuse the stream with an error - which the player answers by moving to FFmpeg - while Amlogic's
 * (Dune HD Homatics) accepts it and decodes the warped macroblocks as plain ones, a picture of squares
 * with no error to react to. So a GMC stream is sent to FFmpeg from the start, wherever it plays.
 * <p>
 * Matroska and MP4 carry the VOL as the track's initialization data; the fork's AVI extractor reads it
 * from the first video chunk and puts it there when it finds GMC (see AviExtractor).
 */
public final class Mpeg4VideoObjectLayer {

    private static final int SPRITE_GMC = 2;
    private static final int SHAPE_RECTANGULAR = 0;
    private static final int SHAPE_BINARY_ONLY = 2;
    private static final int SHAPE_GRAYSCALE = 3;
    private static final int ASPECT_EXTENDED_PAR = 0xF;

    private Mpeg4VideoObjectLayer() {
    }

    /** True when one of the pieces holds a VOL with global motion compensation on. */
    public static boolean usesGmc(@Nullable List<byte[]> initializationData) {
        if (initializationData == null) {
            return false;
        }
        for (byte[] piece : initializationData) {
            if (findGmcVol(piece, 0, piece.length) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first VOL in {@code data[from, to)}, start code to the next start code, if it turns on GMC;
     * null when there is no VOL, or the first one does not, or it is cut short.
     */
    @Nullable
    public static byte[] findGmcVol(byte[] data, int from, int to) {
        final int start = findVolStart(data, from, to);
        if (start < 0) {
            return null;
        }
        int end = to;
        for (int i = start + 4; i + 3 <= to; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                end = i;
                break;
            }
        }
        final byte[] vol = Arrays.copyOfRange(data, start, end);
        return spriteEnable(vol) == SPRITE_GMC ? vol : null;
    }

    /** video_object_layer_start_code: 00 00 01 20..2F. */
    private static int findVolStart(byte[] data, int from, int to) {
        for (int i = from; i + 4 <= to; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 && (data[i + 3] & 0xF0) == 0x20) {
                return i;
            }
        }
        return -1;
    }

    /** sprite_enable of a VOL beginning with its start code, or -1 when it ends before the field. */
    static int spriteEnable(byte[] vol) {
        final ParsableBitArray bits = new ParsableBitArray(vol);
        try {
            bits.skipBits(32); // start code
            bits.skipBits(1); // random_accessible_vol
            bits.skipBits(8); // video_object_type_indication
            int verid = 1;
            if (bits.readBit()) { // is_object_layer_identifier
                verid = bits.readBits(4);
                bits.skipBits(3); // video_object_layer_priority
            }
            if (bits.readBits(4) == ASPECT_EXTENDED_PAR) {
                bits.skipBits(16); // par_width, par_height
            }
            if (bits.readBit()) { // vol_control_parameters
                bits.skipBits(2); // chroma_format
                bits.skipBits(1); // low_delay
                if (bits.readBit()) { // vbv_parameters
                    bits.skipBits(15 + 1 + 15 + 1 + 15 + 1 + 3 + 11 + 1 + 15 + 1);
                }
            }
            final int shape = bits.readBits(2);
            if (shape == SHAPE_GRAYSCALE && verid != 1) {
                bits.skipBits(4); // video_object_layer_shape_extension
            }
            bits.skipBits(1); // marker
            final int resolution = bits.readBits(16); // vop_time_increment_resolution
            bits.skipBits(1); // marker
            if (bits.readBit()) { // fixed_vop_rate
                int length = 1;
                while ((1 << length) < resolution) {
                    length++;
                }
                bits.skipBits(length); // fixed_vop_time_increment
            }
            if (shape == SHAPE_BINARY_ONLY) {
                return 0;
            }
            if (shape == SHAPE_RECTANGULAR) {
                bits.skipBits(1 + 13 + 1 + 13 + 1); // marker, width, marker, height, marker
            }
            bits.skipBits(1); // interlaced
            bits.skipBits(1); // obmc_disable
            final int value = bits.readBits(verid == 1 ? 1 : 2);
            return bits.getPosition() <= vol.length * 8 ? value : -1;
        } catch (IllegalStateException | IndexOutOfBoundsException e) {
            return -1;
        }
    }
}
