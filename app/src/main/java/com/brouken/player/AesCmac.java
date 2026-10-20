package com.brouken.player;

import com.hierynomus.security.Mac;
import com.hierynomus.security.SecurityException;

import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-CMAC (RFC 4493) built on the platform's AES-CBC, for the SMB 3 signature where the platform has
 * AES but no AESCMAC - Android 11 and older, the Shield among them. A CMAC is a CBC-MAC whose last
 * block is first XORed with a subkey, so everything but that last block goes through the provider's
 * CBC as it is - in Conscrypt, BoringSSL with the CPU's AES instructions - and only the subkeys and
 * the last block are worked out here.
 *
 * <p>smbj makes one per packet and feeds it in pieces: a header, the signature field zeroed, the
 * rest, up to 1 MiB of a read. The last block is held back until {@link #doFinal()}, since only then
 * is it known to be the last.
 */
final class AesCmac implements Mac {

    private static final int BLOCK = 16;
    /** How much of a long update goes to the cipher at once: the size of the reused output buffer. */
    private static final int CHUNK = 64 * 1024;

    private final Cipher cbc;
    private final byte[] pending = new byte[BLOCK];
    private final byte[] sink = new byte[CHUNK];
    private SecretKeySpec key;
    private byte[] k1;
    private byte[] k2;
    private int pendingLength;

    AesCmac() throws SecurityException {
        try {
            cbc = Cipher.getInstance("AES/CBC/NoPadding");
        } catch (final GeneralSecurityException e) {
            throw new SecurityException(e);
        }
    }

    /** The provider the blocks go through, for the log. */
    String provider() {
        return cbc.getProvider().getName();
    }

    @Override
    public void init(final byte[] keyBytes) throws SecurityException {
        try {
            key = new SecretKeySpec(keyBytes, "AES");
            final Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding", cbc.getProvider());
            ecb.init(Cipher.ENCRYPT_MODE, key);
            k1 = doubled(ecb.doFinal(new byte[BLOCK]));
            k2 = doubled(k1);
        } catch (final GeneralSecurityException e) {
            throw new SecurityException(e);
        }
        reset();
    }

    /** Multiplication by x in GF(2^128): a shift left by one bit, 0x87 folded in on a carry. */
    private static byte[] doubled(final byte[] block) {
        final byte[] out = new byte[BLOCK];
        for (int i = 0; i < BLOCK - 1; i++) {
            out[i] = (byte) (block[i] << 1 | (block[i + 1] & 0xff) >>> 7);
        }
        out[BLOCK - 1] = (byte) (block[BLOCK - 1] << 1);
        if ((block[0] & 0x80) != 0) {
            out[BLOCK - 1] ^= (byte) 0x87;
        }
        return out;
    }

    @Override
    public void update(final byte b) {
        update(new byte[] {b}, 0, 1);
    }

    @Override
    public void update(final byte[] array) {
        update(array, 0, array.length);
    }

    @Override
    public void update(final byte[] array, int offset, int length) {
        while (length > 0) {
            if (pendingLength == BLOCK) {
                // More is coming, so the held block was not the last.
                chain(pending, 0, BLOCK);
                pendingLength = 0;
            }
            if (pendingLength == 0 && length > BLOCK) {
                // Whole blocks straight to the cipher, leaving at least one byte to hold back.
                final int whole = Math.min((length - 1) / BLOCK * BLOCK, CHUNK);
                chain(array, offset, whole);
                offset += whole;
                length -= whole;
                continue;
            }
            final int take = Math.min(BLOCK - pendingLength, length);
            System.arraycopy(array, offset, pending, pendingLength, take);
            pendingLength += take;
            offset += take;
            length -= take;
        }
    }

    private void chain(final byte[] array, final int offset, final int length) {
        try {
            cbc.update(array, offset, length, sink, 0);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public byte[] doFinal() {
        final byte[] last = new byte[BLOCK];
        System.arraycopy(pending, 0, last, 0, pendingLength);
        final byte[] subkey;
        if (pendingLength == BLOCK) {
            subkey = k1;
        } else {
            last[pendingLength] = (byte) 0x80;
            subkey = k2;
        }
        for (int i = 0; i < BLOCK; i++) {
            last[i] ^= subkey[i];
        }
        final byte[] tag;
        try {
            tag = cbc.doFinal(last);
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        reset();
        return tag;
    }

    @Override
    public void reset() {
        try {
            cbc.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(new byte[BLOCK]));
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        pendingLength = 0;
    }
}
