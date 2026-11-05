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
    public void noMatchingApk_noUpdate() throws Exception {
        final JSONArray assets = new JSONArray().put(asset("Syntex.v3.1.1.apk")).put(asset("other.apk"));
        assertNull(Updater.releaseApkAsset(assets, "3.1.2"));
        assertNull(Updater.releaseApkAsset(null, "3.1.2"));
    }
}
