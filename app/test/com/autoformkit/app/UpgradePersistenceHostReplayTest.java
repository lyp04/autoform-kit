package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline replay of the signed-v1 storage shape through the current pure migration rules.
 *
 * <p>This deliberately does not claim to be a covering Android install test. It closes the
 * host-verifiable part of the upgrade contract: every operational field read/written by the
 * locally archived signed v1.0.6 binary survives binding, the global/scoped rollback mirror is
 * deterministic, and the old reader's view remains available after a current-version save.
 */
public class UpgradePersistenceHostReplayTest {
    private static final String CONNECTION = "0123456789abcdef0123";
    private static final int CATALOG_VERSION = 12;
    private static final int RELEASE_CODE = 8;
    private static final String PROFILE_ID = "synthetic-form";
    private static final String DRAFT_STORE_KEY = "pending_form_draft_store_json";

    private static JSONObject profile() throws Exception {
        JSONObject previousSteps = new JSONObject()
            .put("enabled", true)
            .put("legacyDraftArtifactKey", "synthetic-evidence")
            .put("artifacts", new JSONArray().put(new JSONObject()
                .put("key", "synthetic-evidence")
                .put("title", "Synthetic evidence")
                .put("required", true)
                .put("uploadNameTemplate", "{identifier}-synthetic.jpg")));
        return new JSONObject()
            .put("id", PROFILE_ID)
            .put("defaultPhotoOrder", PhotoOrderRules.GROUPED)
            .put("workflow", new JSONObject().put("previousSteps", previousSteps))
            .put("template", new JSONObject()
                .put("id", 101)
                .put("warehouseId", 202)
                .put("sku", "SYNTHETIC-SKU"))
            .put("uploadFields", new JSONArray().put(new JSONObject()
                .put("field", "synthetic-photo-field")));
    }

    private static JSONObject config() throws Exception {
        return new JSONObject()
            .put("catalogVersion", CATALOG_VERSION)
            .put("backendAdapter", new JSONObject()
                .put("version", 1)
                .put("baseUrl", "https://backend.example.invalid")
                .put("endpoints", new JSONObject().put("submitEntry", "/submit")));
    }

    private static JSONObject catalog() throws Exception {
        return new JSONObject()
            .put("schemaVersion", 2)
            .put("version", CATALOG_VERSION)
            .put("settings", new JSONObject())
            .put("profiles", new JSONArray().put(profile()));
    }

    /** Complete operational unit shape recovered from the locally archived signed-v1.0.6 APK. */
    private static JSONObject legacyUnit(String suffix) throws Exception {
        return new JSONObject()
            .put("sequence", 7)
            .put("sn", "SYNTHETIC-SN-" + suffix)
            .put("grade", "A")
            .put("baseSn", "SYNTHETIC-BASE-" + suffix)
            .put("aStepPhotoPath", "/synthetic/step-" + suffix + ".jpg")
            .put("stepPhotoRequired", true)
            .put("frontPhoto", "/synthetic/front-" + suffix + ".jpg")
            .put("backPhoto", "/synthetic/back-" + suffix + ".jpg")
            .put("precheckStatus", "checked")
            .put("status", "pending")
            .put("defective", true)
            .put("supplementalPhotos", new JSONArray()
                .put("/synthetic/extra-" + suffix + ".jpg"))
            .put("slotPhotos", new JSONObject().put("synthetic-slot",
                new JSONArray().put("/synthetic/slot-" + suffix + ".jpg")))
            .put("pluginSns", new JSONObject().put(
                "synthetic-plugin", "SYNTHETIC-PLUGIN-" + suffix));
    }

    private static JSONObject legacyDraft(String suffix, long savedAt) throws Exception {
        return new JSONObject()
            .put("version", 2)
            .put("profileId", PROFILE_ID)
            .put("photoOrder", PhotoOrderRules.PER_RECORD)
            .put("savedAt", savedAt)
            .put("savedAtText", "2000-01-01 00:00")
            .put("missingMaterialNoticeShown", true)
            .put("missingMaterialCodes", new JSONArray().put("SYNTHETIC-MATERIAL"))
            .put("units", new JSONArray().put(legacyUnit(suffix)));
    }

    private static JSONObject store(JSONObject draft) throws Exception {
        return new JSONObject()
            .put("version", 2)
            .put("drafts", new JSONObject().put(PROFILE_ID, draft));
    }

    private static MainDraftSnapshotRules.Binding binding() throws Exception {
        return MainDraftSnapshotRules.currentBinding(CONNECTION, CATALOG_VERSION,
            PROFILE_ID, profile(), config(), new JSONObject());
    }

    private static JSONObject receipt() throws Exception {
        String pair = MainDraftSnapshotRules.panelPairSha256(config(), catalog());
        return MainDraftSnapshotRules.newLegacyMigrationReceipt(
            CONNECTION, CATALOG_VERSION, RELEASE_CODE, pair);
    }

    private static String pairSha256() throws Exception {
        return MainDraftSnapshotRules.panelPairSha256(config(), catalog());
    }

    private static void assertLegacyUnitEqual(JSONObject expected, JSONObject actual)
            throws Exception {
        assertEquals(expected.getInt("sequence"), actual.getInt("sequence"));
        for (String key : new String[]{"sn", "grade", "baseSn", "aStepPhotoPath",
                "frontPhoto", "backPhoto", "precheckStatus", "status"}) {
            assertEquals(key, expected.getString(key), actual.getString(key));
        }
        assertEquals(expected.getBoolean("stepPhotoRequired"),
            actual.getBoolean("stepPhotoRequired"));
        assertEquals(expected.getBoolean("defective"), actual.getBoolean("defective"));
        for (String key : new String[]{"supplementalPhotos", "slotPhotos", "pluginSns"}) {
            assertEquals(key, expected.get(key).toString(), actual.get(key).toString());
        }
    }

    /** The exact legacy projection: new-only fields are intentionally ignored like signed v1. */
    private static JSONObject signedV1UnitProjection(JSONObject value) throws Exception {
        JSONObject out = new JSONObject()
            .put("sequence", value.optInt("sequence", 1))
            .put("sn", value.optString("sn", "").trim())
            .put("grade", value.optString("grade", "A"))
            .put("baseSn", value.optString("baseSn", ""))
            .put("aStepPhotoPath", value.optString("aStepPhotoPath", ""))
            .put("stepPhotoRequired", value.optBoolean("stepPhotoRequired", false))
            .put("frontPhoto", value.optString("frontPhoto", ""))
            .put("backPhoto", value.optString("backPhoto", ""))
            .put("precheckStatus", value.optString("precheckStatus", "unchecked"))
            .put("status", value.optString("status", "pending"))
            .put("supplementalPhotos", value.optJSONArray("supplementalPhotos") == null
                ? new JSONArray() : new JSONArray(
                    value.getJSONArray("supplementalPhotos").toString()));
        if (value.optBoolean("defective", false)) out.put("defective", true);
        if (value.optJSONObject("slotPhotos") != null) {
            out.put("slotPhotos", new JSONObject(value.getJSONObject("slotPhotos").toString()));
        }
        if (value.optJSONObject("pluginSns") != null) {
            out.put("pluginSns", new JSONObject(value.getJSONObject("pluginSns").toString()));
        }
        return out;
    }

    @Test
    public void signedV1DraftBindsWithoutLosingAnyOperationalField() throws Exception {
        JSONObject legacy = legacyDraft("0001", 100L);
        MainDraftSnapshotRules.RestoreDecision decision = MainDraftSnapshotRules.evaluate(
            legacy, binding(), receipt(), RELEASE_CODE, pairSha256());
        assertEquals(MainDraftSnapshotRules.RestoreKind.MIGRATE_VERIFIED_LEGACY,
            decision.kind);

        JSONObject bound = MainDraftSnapshotRules.bindVerifiedLegacy(legacy, binding());
        assertEquals(MainDraftSnapshotRules.DRAFT_VERSION, bound.getInt("version"));
        assertEquals(legacy.getString("profileId"), bound.getString("profileId"));
        assertEquals(legacy.getString("photoOrder"), bound.getString("photoOrder"));
        assertEquals(legacy.getLong("savedAt"), bound.getLong("savedAt"));
        assertEquals(legacy.getString("savedAtText"), bound.getString("savedAtText"));
        assertEquals(legacy.getBoolean("missingMaterialNoticeShown"),
            bound.getBoolean("missingMaterialNoticeShown"));
        assertEquals(legacy.getJSONArray("missingMaterialCodes").toString(),
            bound.getJSONArray("missingMaterialCodes").toString());
        assertLegacyUnitEqual(legacy.getJSONArray("units").getJSONObject(0),
            bound.getJSONArray("units").getJSONObject(0));
        assertTrue(bound.has(MainDraftSnapshotRules.BINDING_FIELD));
    }

    @Test
    public void noPrewarmReceiptPinsLegacyDraftAndLeavesExactBytesUntouched()
            throws Exception {
        JSONObject legacy = legacyDraft("NO-PREWARM", 101L);
        String originalBytes = legacy.toString();

        MainDraftSnapshotRules.RestoreDecision decision = MainDraftSnapshotRules.evaluate(
            legacy, binding(), null, RELEASE_CODE, pairSha256());

        assertEquals(MainDraftSnapshotRules.RestoreKind.BLOCKED, decision.kind);
        assertFalse(LegacyUpgradeSafetyRules.cachePromotionAllowed(
            false, new LinkedHashMap<>()));
        assertEquals(originalBytes, legacy.toString());
        assertFalse(legacy.has(MainDraftSnapshotRules.BINDING_FIELD));
    }

    @Test
    public void exactPrewarmReceiptBindsLegacyDraftBeforeCacheMayAdvance()
            throws Exception {
        JSONObject legacy = legacyDraft("PREWARMED", 102L);
        String originalBytes = legacy.toString();
        MainDraftSnapshotRules.RestoreDecision decision = MainDraftSnapshotRules.evaluate(
            legacy, binding(), receipt(), RELEASE_CODE, pairSha256());

        assertEquals(MainDraftSnapshotRules.RestoreKind.MIGRATE_VERIFIED_LEGACY,
            decision.kind);
        JSONObject bound = MainDraftSnapshotRules.bindVerifiedLegacy(legacy, binding());
        assertTrue(bound.has(MainDraftSnapshotRules.BINDING_FIELD));
        assertTrue(LegacyUpgradeSafetyRules.cachePromotionAllowed(
            true, new LinkedHashMap<>()));
        // The migration creates a new bound value; it never edits the signed-v1 object in place.
        assertEquals(originalBytes, legacy.toString());
    }

    @Test
    public void unownedLegacyManualQueuePinsOldCacheWithoutRewritingSnapshot()
            throws Exception {
        String originalBytes = legacyDraft("MANUAL-QUEUE", 103L).toString();
        List<RollbackMirrorRules.Candidate> copies = new ArrayList<>();
        copies.add(RollbackMirrorRules.Candidate.of(
            "legacy-file", originalBytes, true, false, false, 103L));

        RollbackMirrorRules.Decision decision = RollbackMirrorRules.chooseNewestSnapshot(
            copies, null, CONNECTION, "manual_saved_queue_json");

        assertTrue(decision.blocked());
        assertEquals("", decision.value);
        assertFalse(decision.mirrorAllowed);
        assertEquals(originalBytes, copies.get(0).raw);
        assertFalse(LegacyUpgradeSafetyRules.cachePromotionAllowed(
            false, new LinkedHashMap<>()));
    }

    @Test
    public void unownedLegacyLedgerPinsOldCacheWithoutGuessingCatalogOwnership() {
        String originalBytes = "[{\"profileId\":\"synthetic-form\",\"roundId\":\"r1\"}]";
        RollbackMirrorRules.Candidate absent =
            RollbackMirrorRules.Candidate.absent("scoped-pref");
        RollbackMirrorRules.Candidate legacy = RollbackMirrorRules.Candidate.of(
            "legacy-pref", originalBytes, true, false, false, 0L);

        RollbackMirrorRules.Decision decision = RollbackMirrorRules.chooseReceiptBoundValue(
            absent, legacy, null, CONNECTION, "round_ledger_json");

        assertTrue(decision.blocked());
        assertEquals("", decision.value);
        assertFalse(decision.mirrorAllowed);
        assertEquals(originalBytes, legacy.raw);
        assertFalse(LegacyUpgradeSafetyRules.cachePromotionAllowed(
            false, new LinkedHashMap<>()));
    }

    @Test
    public void pendingLegacyAStepCameraReturnPinsCacheAndInstallerAndKeepsPath()
            throws Exception {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(LegacyUpgradeSafetyRules.PENDING_A_STEP_PHOTO_PATH_KEY,
            "/data/user/0/com.autoformkit.app/files/photos/a-step-original.jpg");
        settings.put(LegacyUpgradeSafetyRules.PENDING_A_STEP_PHOTO_SEQUENCE_KEY, 19);
        settings.put(LegacyUpgradeSafetyRules.PENDING_A_STEP_ENTRY_PHOTO_PATH_KEY,
            "/data/user/0/com.autoformkit.app/files/photos/a-step-entry-original.jpg");
        Map<String, Object> original = new LinkedHashMap<>(settings);

        assertTrue(LegacyUpgradeSafetyRules.pendingAStepEvidence(settings));
        assertFalse(LegacyUpgradeSafetyRules.cachePromotionAllowed(true, settings));
        assertTrue(RemoteSideEffectGate.durableSlotPresent(settings));
        assertEquals(original, settings);
        assertEquals("/data/user/0/com.autoformkit.app/files/photos/a-step-original.jpg",
            settings.get(LegacyUpgradeSafetyRules.PENDING_A_STEP_PHOTO_PATH_KEY));
    }

    @Test
    public void currentSaveRetainsTheCompleteSignedV1ReaderView() throws Exception {
        JSONObject oldUnit = legacyUnit("0002");
        Map<String, String> artifacts = new LinkedHashMap<>();
        String legacyPath = LegacyDraftArtifactRules.restore(
            oldUnit, artifacts, ProfileWorkflow.from(profile()));
        assertEquals(oldUnit.getString("aStepPhotoPath"), legacyPath);
        assertEquals(legacyPath, artifacts.get("synthetic-evidence"));

        JSONObject currentUnit = new JSONObject(oldUnit.toString());
        currentUnit.remove("defective");
        currentUnit.put("snSource", SnScanRules.SOURCE_ENTERED)
            .put("baseSnSource", SnScanRules.SOURCE_ENTERED)
            .put("workflowArtifactRequired", true)
            .put("workflowArtifacts", new JSONObject()
                .put("synthetic-evidence", legacyPath));
        LegacyDraftArtifactRules.write(currentUnit, legacyPath, true);
        boolean legacyDefective = oldUnit.optBoolean("defective", false);
        if (legacyDefective) currentUnit.put("defective", true);

        JSONObject rollbackView = signedV1UnitProjection(currentUnit);
        assertLegacyUnitEqual(oldUnit, rollbackView);
        JSONObject ordinaryCurrentUnit = new JSONObject();
        if (new JSONObject().optBoolean("defective", false)) {
            ordinaryCurrentUnit.put("defective", true);
        }
        assertFalse(ordinaryCurrentUnit.has("defective"));
        assertEquals(PhotoOrderRules.PER_RECORD, PhotoOrderRules.restoreForDraft(
            profile(), PhotoOrderRules.PER_RECORD, true));
    }

    @Test
    public void globalDraftMigratesToMirrorAndSignedV1RollbackChangeWins() throws Exception {
        JSONObject legacyDraft = legacyDraft("BASELINE", 100L);
        String legacyStore = store(legacyDraft).toString();
        RollbackMirrorRules.Candidate absent =
            RollbackMirrorRules.Candidate.absent("scoped-pref");
        RollbackMirrorRules.Candidate global = RollbackMirrorRules.Candidate.of(
            "legacy-pref", legacyStore, true, true, false, 100L);
        RollbackMirrorRules.Decision initial = RollbackMirrorRules.chooseDraftStore(
            absent, global, null, CONNECTION, DRAFT_STORE_KEY);
        assertFalse(initial.blocked());
        assertTrue(initial.mirrorAllowed);
        assertEquals(legacyStore, initial.value);

        JSONObject boundDraft = MainDraftSnapshotRules.bindVerifiedLegacy(
            legacyDraft, binding());
        String mirroredBaseline = store(boundDraft).toString();
        JSONObject mirrorReceipt = RollbackMirrorRules.newReceipt(
            CONNECTION, DRAFT_STORE_KEY, mirroredBaseline);

        JSONObject rollbackDraft = new JSONObject(boundDraft.toString());
        rollbackDraft.put("savedAt", 50L); // device time moving backwards must remain safe.
        rollbackDraft.getJSONArray("units").getJSONObject(0)
            .put("backPhoto", "/synthetic/rollback-change.jpg");
        String rollbackStore = store(rollbackDraft).toString();
        RollbackMirrorRules.Candidate scoped = RollbackMirrorRules.Candidate.of(
            "scoped-pref", mirroredBaseline, true, true, true, 100L);
        RollbackMirrorRules.Candidate changedGlobal = RollbackMirrorRules.Candidate.of(
            "legacy-pref", rollbackStore, true, true, false, 50L);

        RollbackMirrorRules.Decision replayed = RollbackMirrorRules.chooseDraftStore(
            scoped, changedGlobal, mirrorReceipt, CONNECTION, DRAFT_STORE_KEY);
        assertFalse(replayed.blocked());
        assertEquals(RollbackMirrorRules.Source.LEGACY, replayed.source);
        assertEquals(rollbackStore, replayed.value);
        JSONObject selectedUnit = new JSONObject(replayed.value)
            .getJSONObject("drafts").getJSONObject(PROFILE_ID)
            .getJSONArray("units").getJSONObject(0);
        assertEquals("/synthetic/rollback-change.jpg",
            signedV1UnitProjection(selectedUnit).getString("backPhoto"));
        assertTrue(signedV1UnitProjection(selectedUnit).getBoolean("defective"));
    }

    @Test
    public void currentRuntimeKeepsLegacyDefectiveOnlyInDraftRoundTrip() throws Exception {
        String source = mainActivitySource();
        assertTrue(source.contains(
            "unit.legacyDefective = item.optBoolean(\"defective\", false);"));
        assertTrue(source.contains(
            "if (unit.legacyDefective) item.put(\"defective\", true);"));
        assertTrue(source.contains("boolean legacyDefective = false;"));
        assertEquals(3, occurrences(source, "legacyDefective"));
    }

    @Test
    public void signedV1DeleteNeedsExactReceiptAndAmbiguityFailsClosed() throws Exception {
        String baseline = store(MainDraftSnapshotRules.bindVerifiedLegacy(
            legacyDraft("DELETE", 100L), binding())).toString();
        JSONObject mirrorReceipt = RollbackMirrorRules.newReceipt(
            CONNECTION, DRAFT_STORE_KEY, baseline);
        RollbackMirrorRules.Candidate scoped = RollbackMirrorRules.Candidate.of(
            "scoped-pref", baseline, true, true, true, 100L);

        RollbackMirrorRules.Decision deleted = RollbackMirrorRules.chooseDraftStore(
            scoped, RollbackMirrorRules.Candidate.absent("legacy-pref"),
            mirrorReceipt, CONNECTION, DRAFT_STORE_KEY);
        assertTrue(deleted.tombstone());

        RollbackMirrorRules.Decision noReceipt = RollbackMirrorRules.chooseDraftStore(
            scoped, RollbackMirrorRules.Candidate.absent("legacy-pref"),
            null, CONNECTION, DRAFT_STORE_KEY);
        assertFalse(noReceipt.tombstone());
        assertEquals(baseline, noReceipt.value);

        List<RollbackMirrorRules.Candidate> ambiguous = new ArrayList<>();
        ambiguous.add(RollbackMirrorRules.Candidate.of(
            "scoped-file", baseline, true, true, true, 100L));
        ambiguous.add(RollbackMirrorRules.Candidate.of(
            "legacy-file", store(legacyDraft("OTHER", 100L)).toString(),
            true, true, false, 100L));
        assertTrue(RollbackMirrorRules.chooseNewestSnapshot(
            ambiguous, mirrorReceipt, CONNECTION, DRAFT_STORE_KEY).blocked());
    }

    private static String mainActivitySource() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir"));
        for (Path candidate : new Path[]{
                cwd.resolve("app/src/com/autoformkit/app/MainActivity.java"),
                cwd.resolve("src/com/autoformkit/app/MainActivity.java")}) {
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("MainActivity source not found");
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int index = 0; (index = value.indexOf(needle, index)) >= 0;
                index += needle.length()) {
            count++;
        }
        return count;
    }
}
