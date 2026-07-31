package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class LegacyPanelCacheMigrationRulesTest {
    private static final String PANEL = "https://panel.example.invalid";
    private static final String KEY = "fictional-read-key";

    private static String catalog(int version) throws Exception {
        return new JSONObject()
            .put("schemaVersion", 2)
            .put("version", version)
            .put("profiles", new JSONArray().put(new JSONObject().put("id", "sample")))
            .toString();
    }

    private static JSONObject config(String catalog, int version) throws Exception {
        return new JSONObject()
            .put("catalogVersion", version)
            .put(LegacyPanelCacheMigrationRules.PROOF_FIELD, new JSONObject()
                .put("version", 1)
                .put("panelBase", PANEL)
                .put("keySha256", LegacyPanelCacheMigrationRules.sha256(KEY))
                .put("catalogSha256", LegacyPanelCacheMigrationRules.sha256(catalog))
                .put("catalogVersion", version));
    }

    @Test
    public void exactPrewarmedPairCanMigrateOffline() throws Exception {
        String catalog = catalog(12);
        assertTrue(LegacyPanelCacheMigrationRules.canMigrate(
            PANEL + "/", KEY, config(catalog, 12), catalog));
    }

    @Test
    public void wrongPanelOrKeyCannotClaimLegacyCache() throws Exception {
        String catalog = catalog(12);
        JSONObject config = config(catalog, 12);
        assertFalse(LegacyPanelCacheMigrationRules.canMigrate(
            "https://other.example.invalid", KEY, config, catalog));
        assertFalse(LegacyPanelCacheMigrationRules.canMigrate(
            PANEL, "different-key", config, catalog));
    }

    @Test
    public void alteredCatalogCannotUseCachedProof() throws Exception {
        String catalog = catalog(12);
        JSONObject config = config(catalog, 12);
        String altered = new JSONObject(catalog).put("version", 13).toString();
        assertFalse(LegacyPanelCacheMigrationRules.canMigrate(
            PANEL, KEY, config, altered));
    }

    @Test
    public void incompleteOrRevisionMismatchedCatalogFailsClosed() throws Exception {
        String empty = new JSONObject()
            .put("schemaVersion", 2)
            .put("version", 12)
            .put("profiles", new JSONArray())
            .toString();
        assertFalse(LegacyPanelCacheMigrationRules.canMigrate(
            PANEL, KEY, config(empty, 12), empty));

        String catalog = catalog(12);
        assertFalse(LegacyPanelCacheMigrationRules.canMigrate(
            PANEL, KEY, config(catalog, 11), catalog));
    }
}
