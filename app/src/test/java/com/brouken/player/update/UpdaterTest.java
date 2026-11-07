package com.brouken.player.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class UpdaterTest {

    private static JSONObject asset(String name) throws Exception {
        return new JSONObject().put("name", name).put("browser_download_url", "https://example.invalid/" + name);
    }

    @Test
    public void picksTheReleaseApkByName_notTheFirstOne() throws Exception {
        final JSONArray assets = new JSONArray()
                .put(asset("Syntex.v3.1.2-legacy.apk"))
                .put(asset("THIRD_PARTY_NOTICES.md"))
                .put(asset("Syntex.v3.1.2.apk"));
        assertEquals("Syntex.v3.1.2.apk", Updater.releaseApkAsset(assets, "3.1.2").getString("name"));
    }

    @Test
    public void digestIsReadAsSha256Hex() {
        final String hex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertEquals(hex, Updater.sha256Of("sha256:" + hex.toUpperCase(java.util.Locale.ROOT)));
        assertEquals("", Updater.sha256Of(""));
        assertEquals("", Updater.sha256Of("md5:abc"));
        assertEquals("", Updater.sha256Of("sha256:123"));
    }

    @Test
    public void hexOfADigest() throws Exception {
        // SHA-256 of "abc", FIPS 180-2.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Updater.hex(java.security.MessageDigest.getInstance("SHA-256").digest("abc".getBytes("US-ASCII"))));
    }

    @Test
    public void noMatchingApk_noUpdate() throws Exception {
        final JSONArray assets = new JSONArray().put(asset("Syntex.v3.1.1.apk")).put(asset("other.apk"));
        assertNull(Updater.releaseApkAsset(assets, "3.1.2"));
        assertNull(Updater.releaseApkAsset(null, "3.1.2"));
    }
}
