package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class MainDraftSnapshotRulesTest {
    private static final String CONNECTION = "0123456789abcdef0123";
    private static final int CATALOG_VERSION = 12;
    private static final int RELEASE_CODE = 8;

    private static JSONObject profile(String templateSku) throws Exception {
        return new JSONObject()
            .put("id", "sample-form")
            .put("workflow", new JSONObject()
                .put("previousSteps", new JSONObject().put("enabled", true)))
            .put("template", new JSONObject()
                .put("id", 101)
                .put("warehouseId", 202)
                .put("sku", templateSku))
            .put("uploadFields", new JSONArray().put(new JSONObject()
                .put("field", "sample-photo-field")));
    }

    private static JSONObject config(String submitPath) throws Exception {
        return new JSONObject()
            .put("catalogVersion", CATALOG_VERSION)
            .put("webOrigin", "https://client.example.invalid")
            .put("backendAdapter", new JSONObject()
                .put("version", 1)
                .put("baseUrl", "https://backend.example.invalid")
                .put("endpoints", new JSONObject().put("submitEntry", submitPath)));
    }

    private static JSONObject catalog(JSONObject profile) throws Exception {
        return new JSONObject()
            .put("schemaVersion", 3)
            .put("version", CATALOG_VERSION)
            .put("settings", new JSONObject())
            .put("profiles", new JSONArray().put(profile));
    }

    private static MainDraftSnapshotRules.Binding binding() throws Exception {
        JSONObject profile = profile("SAMPLE-SKU");
        return MainDraftSnapshotRules.currentBinding(CONNECTION, CATALOG_VERSION,
            "sample-form", profile, config("/submit"), new JSONObject());
    }

    private static JSONObject legacyDraft() throws Exception {
        return new JSONObject()
            .put("version", 2)
            .put("profileId", "sample-form")
            .put("units", new JSONArray().put(new JSONObject()
                .put("sequence", 1)
                .put("sn", "SAMPLE-0001")));
    }

    @Test
    public void canonicalDigestIgnoresObjectInsertionOrderButPreservesArrayOrder()
            throws Exception {
        JSONObject first = new JSONObject()
            .put("beta", new JSONObject().put("two", 2).put("one", 1))
            .put("alpha", new JSONArray().put("a").put("b"));
        JSONObject reordered = new JSONObject()
            .put("alpha", new JSONArray().put("a").put("b"))
            .put("beta", new JSONObject().put("one", 1.0).put("two", 2));
        JSONObject changedArray = new JSONObject(reordered.toString())
            .put("alpha", new JSONArray().put("b").put("a"));

        assertEquals(MainDraftSnapshotRules.semanticSha256(first),
            MainDraftSnapshotRules.semanticSha256(reordered));
        assertFalse(MainDraftSnapshotRules.semanticSha256(first).equals(
            MainDraftSnapshotRules.semanticSha256(changedArray)));
    }

    @Test
    public void exactNewDraftBindingRestoresAndSemanticChangesBlock() throws Exception {
        MainDraftSnapshotRules.Binding exact = binding();
        JSONObject draft = MainDraftSnapshotRules.bindVerifiedLegacy(legacyDraft(), exact);

        assertEquals(MainDraftSnapshotRules.RestoreKind.EXACT,
            MainDraftSnapshotRules.evaluate(draft, exact, null, RELEASE_CODE, "").kind);

        MainDraftSnapshotRules.Binding changedTarget =
            MainDraftSnapshotRules.currentBinding(CONNECTION, CATALOG_VERSION,
                "sample-form", profile("CHANGED-SKU"), config("/submit"),
                new JSONObject());
        assertEquals(MainDraftSnapshotRules.RestoreKind.BLOCKED,
            MainDraftSnapshotRules.evaluate(
                draft, changedTarget, null, RELEASE_CODE, "").kind);

        MainDraftSnapshotRules.Binding changedBackend =
            MainDraftSnapshotRules.currentBinding(CONNECTION, CATALOG_VERSION,
                "sample-form", profile("SAMPLE-SKU"), config("/other-submit"),
                new JSONObject());
        assertEquals(MainDraftSnapshotRules.RestoreKind.BLOCKED,
            MainDraftSnapshotRules.evaluate(
                draft, changedBackend, null, RELEASE_CODE, "").kind);
    }

    @Test
    public void connectionCatalogAndProfileAreAllPartOfBinding() throws Exception {
        MainDraftSnapshotRules.Binding exact = binding();
        JSONObject draft = MainDraftSnapshotRules.bindVerifiedLegacy(legacyDraft(), exact);
        JSONObject sameProfile = profile("SAMPLE-SKU");

        MainDraftSnapshotRules.Binding otherConnection =
            MainDraftSnapshotRules.currentBinding("fedcba9876543210fedc", CATALOG_VERSION,
                "sample-form", sameProfile, config("/submit"), new JSONObject());
        MainDraftSnapshotRules.Binding otherVersion =
            MainDraftSnapshotRules.currentBinding(CONNECTION, CATALOG_VERSION + 1,
                "sample-form", sameProfile, config("/submit"), new JSONObject());

        assertEquals(MainDraftSnapshotRules.RestoreKind.BLOCKED,
            MainDraftSnapshotRules.evaluate(
                draft, otherConnection, null, RELEASE_CODE, "").kind);
        assertEquals(MainDraftSnapshotRules.RestoreKind.BLOCKED,
            MainDraftSnapshotRules.evaluate(
                draft, otherVersion, null, RELEASE_CODE, "").kind);
        assertThrows(IllegalArgumentException.class, () ->
            MainDraftSnapshotRules.currentBinding(CONNECTION, CATALOG_VERSION,
                "other-form", sameProfile, config("/submit"), new JSONObject()));
    }

    @Test
    public void unfinishedDraftCanRebindOnlyToNewerSamePanelAndProfile() throws Exception {
        MainDraftSnapshotRules.Binding original = binding();
        JSONObject draft = MainDraftSnapshotRules.bindVerifiedLegacy(
            legacyDraft(), original);
        MainDraftSnapshotRules.Binding newer =
            MainDraftSnapshotRules.currentBinding(CONNECTION, CATALOG_VERSION + 1,
                "sample-form", profile("CHANGED-SKU"), config("/other-submit"),
                new JSONObject());

        assertTrue(MainDraftSnapshotRules.canRebindToNewerSameConnection(draft, newer));
        JSONObject rebound = MainDraftSnapshotRules.rebindToNewerSameConnection(
            draft, newer);
        assertEquals(MainDraftSnapshotRules.RestoreKind.EXACT,
            MainDraftSnapshotRules.evaluate(
                rebound, newer, null, RELEASE_CODE, "").kind);
        assertEquals("SAMPLE-0001", rebound.getJSONArray("units")
            .getJSONObject(0).getString("sn"));
        assertEquals("SAMPLE-0001", draft.getJSONArray("units")
            .getJSONObject(0).getString("sn"));

        MainDraftSnapshotRules.Binding otherPanel =
            MainDraftSnapshotRules.currentBinding("fedcba9876543210fedc",
                CATALOG_VERSION + 1, "sample-form", profile("CHANGED-SKU"),
                config("/other-submit"), new JSONObject());
        MainDraftSnapshotRules.Binding otherProfile =
            MainDraftSnapshotRules.currentBinding(CONNECTION, CATALOG_VERSION + 1,
                "other-form", new JSONObject(profile("CHANGED-SKU").toString())
                    .put("id", "other-form"), config("/other-submit"),
                new JSONObject());
        assertFalse(MainDraftSnapshotRules.canRebindToNewerSameConnection(
            draft, original));
        assertFalse(MainDraftSnapshotRules.canRebindToNewerSameConnection(
            draft, otherPanel));
        assertFalse(MainDraftSnapshotRules.canRebindToNewerSameConnection(
            draft, otherProfile));
        assertFalse(MainDraftSnapshotRules.canRebindToNewerSameConnection(
            new JSONObject(draft.toString()).put("version", 99), newer));
        assertThrows(IllegalArgumentException.class, () ->
            MainDraftSnapshotRules.rebindToNewerSameConnection(draft, otherPanel));
    }

    @Test
    public void ownershipKeepsSameConnectionMismatchButRejectsAnotherPanel() throws Exception {
        MainDraftSnapshotRules.Binding exact = binding();
        JSONObject draft = MainDraftSnapshotRules.bindVerifiedLegacy(legacyDraft(), exact);
        assertTrue(MainDraftSnapshotRules.belongsToConnection(
            draft, CONNECTION, CATALOG_VERSION, null, RELEASE_CODE, ""));
        assertTrue(MainDraftSnapshotRules.hasSelfBindingForConnection(draft, CONNECTION));

        String other = "fedcba9876543210fedc";
        assertFalse(MainDraftSnapshotRules.belongsToConnection(
            draft, other, CATALOG_VERSION, null, RELEASE_CODE, ""));
        assertFalse(MainDraftSnapshotRules.hasSelfBindingForConnection(draft, other));
    }

    @Test
    public void legacyDraftNeedsHashBoundPrewarmReceipt() throws Exception {
        MainDraftSnapshotRules.Binding exact = binding();
        JSONObject config = config("/submit");
        JSONObject catalog = catalog(profile("SAMPLE-SKU"));
        String pairSha = MainDraftSnapshotRules.panelPairSha256(config, catalog);
        JSONObject receipt = MainDraftSnapshotRules.newLegacyMigrationReceipt(
            CONNECTION, CATALOG_VERSION, RELEASE_CODE, pairSha);

        assertEquals(MainDraftSnapshotRules.RestoreKind.BLOCKED,
            MainDraftSnapshotRules.evaluate(
                legacyDraft(), exact, null, RELEASE_CODE, pairSha).kind);
        assertEquals(MainDraftSnapshotRules.RestoreKind.MIGRATE_VERIFIED_LEGACY,
            MainDraftSnapshotRules.evaluate(
                legacyDraft(), exact, new JSONObject(receipt.toString()),
                RELEASE_CODE, pairSha).kind);
        assertTrue(MainDraftSnapshotRules.belongsToConnection(
            legacyDraft(), CONNECTION, CATALOG_VERSION, receipt,
            RELEASE_CODE, pairSha));
        assertFalse(MainDraftSnapshotRules.hasSelfBindingForConnection(
            legacyDraft(), CONNECTION));
        assertEquals(MainDraftSnapshotRules.RestoreKind.BLOCKED,
            MainDraftSnapshotRules.evaluate(legacyDraft(), exact, receipt, RELEASE_CODE,
                repeat('f', 64)).kind);
        assertEquals(MainDraftSnapshotRules.RestoreKind.BLOCKED,
            MainDraftSnapshotRules.evaluate(
                legacyDraft(), exact, receipt, RELEASE_CODE - 1, pairSha).kind);
        JSONObject otherConnectionReceipt =
            MainDraftSnapshotRules.newLegacyMigrationReceipt(
                "fedcba9876543210fedc", CATALOG_VERSION, RELEASE_CODE, pairSha);
        JSONObject otherVersionReceipt = MainDraftSnapshotRules.newLegacyMigrationReceipt(
            CONNECTION, CATALOG_VERSION + 1, RELEASE_CODE, pairSha);
        assertEquals(MainDraftSnapshotRules.RestoreKind.BLOCKED,
            MainDraftSnapshotRules.evaluate(legacyDraft(), exact,
                otherConnectionReceipt, RELEASE_CODE, pairSha).kind);
        assertEquals(MainDraftSnapshotRules.RestoreKind.BLOCKED,
            MainDraftSnapshotRules.evaluate(legacyDraft(), exact,
                otherVersionReceipt, RELEASE_CODE, pairSha).kind);
    }

    @Test
    public void exactPrewarmReceiptCanBeReusedWithoutRelaxingItsPairBinding()
            throws Exception {
        JSONObject config = config("/submit");
        JSONObject catalog = catalog(profile("SAMPLE-SKU"));
        String pairSha = MainDraftSnapshotRules.panelPairSha256(config, catalog);
        JSONObject receipt = MainDraftSnapshotRules.newLegacyMigrationReceipt(
            CONNECTION, CATALOG_VERSION, RELEASE_CODE, pairSha);

        assertTrue(MainDraftSnapshotRules.verifiedLegacyMigrationReceipt(
            receipt, CONNECTION, CATALOG_VERSION, RELEASE_CODE, pairSha));
        assertFalse(MainDraftSnapshotRules.verifiedLegacyMigrationReceipt(
            null, CONNECTION, CATALOG_VERSION, RELEASE_CODE, pairSha));
        assertFalse(MainDraftSnapshotRules.verifiedLegacyMigrationReceipt(
            receipt, "fedcba9876543210fedc", CATALOG_VERSION,
            RELEASE_CODE, pairSha));
        assertFalse(MainDraftSnapshotRules.verifiedLegacyMigrationReceipt(
            receipt, CONNECTION, CATALOG_VERSION + 1, RELEASE_CODE, pairSha));
        assertFalse(MainDraftSnapshotRules.verifiedLegacyMigrationReceipt(
            receipt, CONNECTION, CATALOG_VERSION, RELEASE_CODE - 1, pairSha));
        assertFalse(MainDraftSnapshotRules.verifiedLegacyMigrationReceipt(
            receipt, CONNECTION, CATALOG_VERSION, RELEASE_CODE, repeat('e', 64)));

        JSONObject unknownField = new JSONObject(receipt.toString())
            .put("future", true);
        assertFalse(MainDraftSnapshotRules.verifiedLegacyMigrationReceipt(
            unknownField, CONNECTION, CATALOG_VERSION, RELEASE_CODE, pairSha));
    }

    @Test
    public void localCacheStampsDoNotChangeLogicalPairHash() throws Exception {
        JSONObject config = config("/submit");
        JSONObject catalog = catalog(profile("SAMPLE-SKU"));
        String original = MainDraftSnapshotRules.panelPairSha256(config, catalog);
        config.put(AppConfig.CACHE_BINDING_FIELD, new JSONObject().put("local", true));
        catalog.put(AppConfig.CACHE_BINDING_FIELD, new JSONObject().put("local", true));
        assertEquals(original, MainDraftSnapshotRules.panelPairSha256(config, catalog));
    }

    @Test
    public void runtimeProfileMayRefreshOnlyMaterialItemsAndCannotCrossPayloadMappings()
            throws Exception {
        JSONObject catalogProfile = profile("SAMPLE-SKU")
            .put("defaultPhotoOrder", "fronts_then_backs")
            .put("gradeMap", new JSONObject().put("sample-ready", new JSONObject()
                .put("field", "sample_result").put("value", "READY")))
            .put("operationFields", new JSONArray().put(new JSONObject()
                .put("field", "sample_operation").put("value", "INTAKE")))
            .put("materialGroups", new JSONArray().put(new JSONObject()
                .put("field", "sample_materials")
                .put("title", "Sample materials")
                .put("materials", new JSONArray().put(new JSONObject()
                    .put("code", "SAMPLE-A").put("name", "Sample A")
                    .put("defaultQty", 1)))));
        JSONObject runtime = new JSONObject(catalogProfile.toString());
        runtime.getJSONArray("materialGroups").getJSONObject(0)
            .put("materials", new JSONArray()
                .put(new JSONObject().put("code", "SAMPLE-B")
                    .put("name", "Live sample B").put("defaultQty", 2)));

        assertTrue(MainDraftSnapshotRules.runtimeProfileMatchesCatalog(
            runtime, catalogProfile));

        for (JSONObject changed : new JSONObject[]{
            new JSONObject(runtime.toString()).put(
                "defaultPhotoOrder", "front_back_per_unit"),
            new JSONObject(runtime.toString()).put(
                "uploadFields", new JSONArray().put(new JSONObject()
                    .put("field", "other_profile_photo"))),
            new JSONObject(runtime.toString()).put(
                "gradeMap", new JSONObject().put("sample-ready", new JSONObject()
                    .put("field", "other_result").put("value", "OTHER"))),
            new JSONObject(runtime.toString()).put(
                "operationFields", new JSONArray().put(new JSONObject()
                    .put("field", "sample_operation").put("value", "OTHER"))),
            new JSONObject(runtime.toString()).put(
                "template", new JSONObject().put("id", 999)
                    .put("warehouseId", 202).put("sku", "OTHER-SKU"))
        }) {
            assertFalse(MainDraftSnapshotRules.runtimeProfileMatchesCatalog(
                changed, catalogProfile));
        }

        JSONObject changedMaterialField = new JSONObject(runtime.toString());
        changedMaterialField.getJSONArray("materialGroups").getJSONObject(0)
            .put("field", "other_profile_materials");
        assertFalse(MainDraftSnapshotRules.runtimeProfileMatchesCatalog(
            changedMaterialField, catalogProfile));
        assertFalse(MainDraftSnapshotRules.runtimeProfileMatchesCatalog(
            new JSONObject(runtime.toString()).put("id", "other-profile"),
            catalogProfile));
    }

    @Test
    public void malformedOrForwardBindingNeverFallsBackToLegacy() throws Exception {
        MainDraftSnapshotRules.Binding exact = binding();
        JSONObject config = config("/submit");
        JSONObject catalog = catalog(profile("SAMPLE-SKU"));
        String pairSha = MainDraftSnapshotRules.panelPairSha256(config, catalog);
        JSONObject receipt = MainDraftSnapshotRules.newLegacyMigrationReceipt(
            CONNECTION, CATALOG_VERSION, RELEASE_CODE, pairSha);

        JSONObject malformed = legacyDraft()
            .put("version", MainDraftSnapshotRules.DRAFT_VERSION)
            .put(MainDraftSnapshotRules.BINDING_FIELD, new JSONObject()
                .put("version", 1));
        JSONObject future = legacyDraft().put("version", 99);

        assertEquals(MainDraftSnapshotRules.RestoreKind.BLOCKED,
            MainDraftSnapshotRules.evaluate(
                malformed, exact, receipt, RELEASE_CODE, pairSha).kind);
        assertEquals(MainDraftSnapshotRules.RestoreKind.BLOCKED,
            MainDraftSnapshotRules.evaluate(
                future, exact, receipt, RELEASE_CODE, pairSha).kind);
    }

    @Test
    public void verifiedLegacyBindingPreservesQueueAndBecomesStrictV3() throws Exception {
        JSONObject legacy = legacyDraft();
        MainDraftSnapshotRules.Binding exact = binding();
        JSONObject bound = MainDraftSnapshotRules.bindVerifiedLegacy(legacy, exact);

        assertEquals(2, legacy.getInt("version"));
        assertFalse(legacy.has(MainDraftSnapshotRules.BINDING_FIELD));
        assertEquals(MainDraftSnapshotRules.DRAFT_VERSION, bound.getInt("version"));
        assertEquals("SAMPLE-0001", bound.getJSONArray("units")
            .getJSONObject(0).getString("sn"));
        assertTrue(bound.has(MainDraftSnapshotRules.BINDING_FIELD));
    }

    private static String repeat(char value, int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) out.append(value);
        return out.toString();
    }
}
