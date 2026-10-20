package com.brouken.player;

import com.hierynomus.security.AEADBlockCipher;
import com.hierynomus.security.Cipher;
import com.hierynomus.security.DerivationFunction;
import com.hierynomus.security.Mac;
import com.hierynomus.security.MessageDigest;
import com.hierynomus.security.SecurityException;
import com.hierynomus.security.SecurityProvider;
import com.hierynomus.security.bc.BCSecurityProvider;
import com.hierynomus.security.jce.JceSecurityProvider;

/**
 * smbj's cryptography, split by cost. What is done once a session - MD4 and MD5 of the NTLM sign-in,
 * RC4, the key derivation - stays with BouncyCastle, which has all of it; Android's providers have
 * no MD4, so the JCE provider alone cannot sign in with a password. What is done on every request -
 * the AES-CMAC or HMAC-SHA256 signature, the AES-GCM or AES-CCM of an encrypted session - goes to
 * the platform's provider (Conscrypt, BoringSSL underneath, the CPU's AES instructions on ARMv8) where
 * it has the algorithm, and falls back to BouncyCastle where it does not: AESCMAC came to Conscrypt
 * in 2021, after Android 11.
 */
final class SmbCrypto implements SecurityProvider {

    private static final BCSecurityProvider BC = new BCSecurityProvider();
    private static final JceSecurityProvider JCE = new JceSecurityProvider();
    private static final boolean JCE_CMAC = has("AESCMAC", true);
    private static final boolean JCE_HMAC = has("HmacSHA256", true);
    private static final boolean JCE_GCM = has("AES/GCM/NoPadding", false);
    private static final boolean JCE_CCM = has("AES/CCM/NoPadding", false);

    private SmbCrypto() {
    }

    static SecurityProvider create() {
        return new SmbCrypto();
    }

    /** Whether an SMB 3 signature is the platform's: AESCMAC is in Conscrypt from Android 12. */
    static boolean platformCmac() {
        return JCE_CMAC;
    }

    /** Whether an SMB 2 signature is the platform's. */
    static boolean platformHmac() {
        return JCE_HMAC;
    }

    /** Whether an encrypted SMB 3 session is the platform's: GCM is the cipher smbj offers first. */
    static boolean platformGcm() {
        return JCE_GCM;
    }

    static String describe() {
        return "platform cmac " + JCE_CMAC + ", hmac " + JCE_HMAC + ", gcm " + JCE_GCM + ", ccm " + JCE_CCM;
    }

    private static boolean has(final String name, final boolean mac) {
        try {
            final String provider = mac
                    ? javax.crypto.Mac.getInstance(name).getProvider().getName()
                    : javax.crypto.Cipher.getInstance(name).getProvider().getName();
            Utils.log("smb: " + name + " from " + provider);
            return true;
        } catch (final Exception e) {
            Utils.log("smb: no " + name + " in the platform: " + e);
            return false;
        }
    }

    @Override
    public MessageDigest getDigest(final String name) throws SecurityException {
        return BC.getDigest(name);
    }

    @Override
    public Mac getMac(final String name) throws SecurityException {
        if ("AesCmac".equalsIgnoreCase(name) ? JCE_CMAC : "HmacSHA256".equalsIgnoreCase(name) && JCE_HMAC) {
            return JCE.getMac(name);
        }
        return BC.getMac(name);
    }

    @Override
    public Cipher getCipher(final String name) throws SecurityException {
        return BC.getCipher(name);
    }

    @Override
    public AEADBlockCipher getAEADBlockCipher(final String name) throws SecurityException {
        if ("AES/GCM/NoPadding".equals(name) ? JCE_GCM : "AES/CCM/NoPadding".equals(name) && JCE_CCM) {
            return JCE.getAEADBlockCipher(name);
        }
        return BC.getAEADBlockCipher(name);
    }

    @Override
    public DerivationFunction getDerivationFunction(final String name) throws SecurityException {
        return BC.getDerivationFunction(name);
    }
}
