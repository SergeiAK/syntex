package com.brouken.player;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * VOL headers as they stand in the library's AVI files (first video chunk, start code to the next start
 * code). Whether each turns on GMC is FFmpeg's word, not this parser's: {@code ffmpeg -debug 1} prints
 * the number of sprite warping points as {@code w:}, 3 and 2 for the two GMC files, 0 for the rest.
 */
public class Mpeg4VideoObjectLayerTest {

    /** "A Disney Christmas Gift.avi": XviD, GMC with 3 points, qpel - the Shield/Dune field case. */
    private static final String GMC_3_POINTS = "0000012008ca0d0800ce18b42220b078e0";
    /** "Дональд Дак/09": GMC with 2 points, with VBV parameters before the shape. */
    private static final String GMC_2_POINTS = "0000012008c80d40006616400c4001900097530a2608702c16183f";

    private static final String[] NO_GMC = {
            "0000012008c80d400057b3c00c4001900097530a2b088028307f",
            "0000012008c80d0ba98516044214183f",
            "0000012008c80d0ba98515843a14183f",
            "0000012008868400670c5a11105263",
            "00000120088687fffb0aad8b42220a4c7f",
    };

    @Test
    public void gmcStreamsAreFound() {
        assertEquals(2, Mpeg4VideoObjectLayer.spriteEnable(bytes(GMC_3_POINTS)));
        assertEquals(2, Mpeg4VideoObjectLayer.spriteEnable(bytes(GMC_2_POINTS)));
        assertTrue(Mpeg4VideoObjectLayer.usesGmc(Collections.singletonList(bytes(GMC_3_POINTS))));
        assertTrue(Mpeg4VideoObjectLayer.usesGmc(Collections.singletonList(bytes(GMC_2_POINTS))));
    }

    @Test
    public void plainStreamsAreLeftAlone() {
        for (String vol : NO_GMC) {
            assertEquals(vol, 0, Mpeg4VideoObjectLayer.spriteEnable(bytes(vol)));
            assertFalse(vol, Mpeg4VideoObjectLayer.usesGmc(Collections.singletonList(bytes(vol))));
        }
    }

    @Test
    public void volIsCutOutOfAChunk() {
        // As in an AVI chunk or Matroska CodecPrivate: VOS and VO before the VOL, user data after it.
        final byte[] chunk = concat(bytes("000001b0f5000001b509000001000000"), bytes(GMC_3_POINTS),
                bytes("000001b258766964"));
        final byte[] vol = Mpeg4VideoObjectLayer.findGmcVol(chunk, 0, chunk.length);
        assertNotNull(vol);
        assertArrayEquals(bytes(GMC_3_POINTS), vol);
    }

    @Test
    public void nothingIsClaimedWithoutAWholeVol() {
        final byte[] gmc = bytes(GMC_3_POINTS);
        assertNull(Mpeg4VideoObjectLayer.findGmcVol(gmc, 0, 8));
        assertNull(Mpeg4VideoObjectLayer.findGmcVol(bytes("000001b6512345"), 0, 7));
        assertFalse(Mpeg4VideoObjectLayer.usesGmc(null));
        assertFalse(Mpeg4VideoObjectLayer.usesGmc(Collections.emptyList()));
    }

    private static byte[] concat(byte[]... pieces) {
        int length = 0;
        for (byte[] piece : pieces) {
            length += piece.length;
        }
        final byte[] out = new byte[length];
        int at = 0;
        for (byte[] piece : pieces) {
            System.arraycopy(piece, 0, out, at, piece.length);
            at += piece.length;
        }
        return Arrays.copyOf(out, at);
    }

    private static byte[] bytes(String hex) {
        final byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
