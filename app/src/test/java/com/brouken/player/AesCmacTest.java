package com.brouken.player;

import static org.junit.Assert.assertArrayEquals;

import com.hierynomus.security.Mac;
import com.hierynomus.security.bc.BCSecurityProvider;

import org.junit.Test;

import java.util.Random;

/** {@link AesCmac} against RFC 4493 and against BouncyCastle's CMAC, which smbj used before it. */
public class AesCmacTest {

    private static final byte[] KEY = hex("2b7e151628aed2a6abf7158809cf4f3c");
    private static final byte[] MESSAGE = hex("6bc1bee22e409f96e93d7e117393172a"
            + "ae2d8a571e03ac9c9eb76fac45af8e51" + "30c81c46a35ce411e5fbc1191a0a52ef"
            + "f69f2445df4f9b17ad2b417be66c3710");

    private static byte[] hex(final String s) {
        final byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static byte[] cmac(final byte[] key, final byte[] message, final int length) throws Exception {
        final AesCmac mac = new AesCmac();
        mac.init(key);
        mac.update(message, 0, length);
        return mac.doFinal();
    }

    @Test
    public void rfc4493Examples() throws Exception {
        assertArrayEquals(hex("bb1d6929e95937287fa37d129b756746"), cmac(KEY, MESSAGE, 0));
        assertArrayEquals(hex("070a16b46b4d4144f79bdd9dd04a287c"), cmac(KEY, MESSAGE, 16));
        assertArrayEquals(hex("dfa66747de9ae63030ca32611497c827"), cmac(KEY, MESSAGE, 40));
        assertArrayEquals(hex("51f0bebf7e3b9d92fc49741779363cfe"), cmac(KEY, MESSAGE, 64));
    }

    @Test
    public void sameTagWhateverThePieces() throws Exception {
        final Random random = new Random(4493);
        final AesCmac mac = new AesCmac();
        for (int round = 0; round < 300; round++) {
            final byte[] key = new byte[16];
            random.nextBytes(key);
            // Lengths around block edges and a few the size of an SMB read, 1 MiB and a header.
            final int length = round < 100 ? round : round < 290 ? random.nextInt(200_000)
                    : (1 << 20) + 64 + random.nextInt(3) - 1;
            final byte[] message = new byte[length];
            random.nextBytes(message);

            final Mac reference = new BCSecurityProvider().getMac("AesCmac");
            reference.init(key);
            reference.update(message, 0, length);
            final byte[] expected = reference.doFinal();

            mac.init(key);
            int at = 0;
            while (at < length) {
                final int piece = random.nextInt(4) == 0 ? 1 : Math.min(length - at, 1 + random.nextInt(70_000));
                if (piece == 1 && random.nextBoolean()) {
                    mac.update(message[at]);
                } else {
                    mac.update(message, at, piece);
                }
                at += piece;
            }
            assertArrayEquals("length " + length, expected, mac.doFinal());
            // doFinal leaves it ready for the next message under the same key.
            mac.update(message, 0, length);
            assertArrayEquals("again, length " + length, expected, mac.doFinal());
        }
    }
}
