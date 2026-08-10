package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PanelPairCacheCoordinatorTest {
    private static final String PANEL = "https://panel.example.invalid";
    private static final String KEY = "fictional-key";

    @Test
    public void acceptsOnlyOneExactBoundMatchingRevisionPair() throws Exception {
        JSONObject config = config(7);
        JSONObject catalog = catalog(7);

        PanelPairCacheCoordinator.ActivePair pair =
            PanelPairCacheCoordinator.parsePair(
                config.toString(), catalog.toString(), PANEL, KEY);

        assertEquals(7, pair.version);
        assertEquals(7, pair.catalog.version);
        assertTrue(pair.pairSha256.matches("[0-9a-f]{64}"));
        assertEquals("fictional-profile",
            pair.catalog.profiles.getJSONObject(0).getString("id"));
    }

    @Test
    public void unboundPrewarmedCacheBecomesTheSamePairAcrossColdRestart()
            throws Exception {
        // This is the neutral public equivalent of the signed-v1 cache layout: the exact Panel
        // response roots are stored without local binding stamps, while /api/config carries the
        // prewarm proof for the catalog bytes written beside it.
        JSONObject legacyConfig = config(7);
        legacyConfig.remove(AppConfig.CACHE_BINDING_FIELD);
        JSONObject legacyCatalog = rawCatalog(7);
        String legacyCatalogText = legacyCatalog.toString();
        assertTrue(LegacyPanelCacheMigrationRules.canMigrate(
            PANEL, KEY, legacyConfig, legacyCatalogText));
        String logicalPairBefore = MainDraftSnapshotRules.panelPairSha256(
            legacyConfig, legacyCatalog);

        JSONObject boundConfig = new JSONObject(legacyConfig.toString());
        JSONObject boundCatalog = new JSONObject(legacyCatalogText);
        AppConfig.stampConnection(boundConfig, PANEL, KEY);
        AppConfig.stampConnection(boundCatalog, PANEL, KEY);
        AppConfig.stampCatalogSource(boundCatalog,
            LegacyPanelCacheMigrationRules.sha256(legacyCatalogText));

        PanelPairCacheCoordinator.ActivePair firstProcess =
            PanelPairCacheCoordinator.parsePair(boundConfig.toString(),
                boundCatalog.toString(), PANEL, KEY);
        PanelPairCacheCoordinator.ActivePair restartedProcess =
            PanelPairCacheCoordinator.parsePair(firstProcess.config.toString(),
                firstProcess.catalogRoot.toString(), PANEL, KEY);

        assertEquals(logicalPairBefore, firstProcess.pairSha256);
        assertEquals(firstProcess.pairSha256, restartedProcess.pairSha256);
        assertEquals(firstProcess.version, restartedProcess.version);
        assertEquals(firstProcess.catalog.profiles.toString(),
            restartedProcess.catalog.profiles.toString());
        assertEquals(firstProcess.catalog.settings.toString(),
            restartedProcess.catalog.settings.toString());
    }

    @Test
    public void rejectsDifferentRevisionsEvenWhenBothHalvesAreIndividuallyValid()
            throws Exception {
        assertThrows(IOException.class, () -> PanelPairCacheCoordinator.parsePair(
            config(8).toString(), catalog(7).toString(), PANEL, KEY));
    }

    @Test
    public void rejectsSameRevisionHalvesFromDifferentPublishedBytes() throws Exception {
        JSONObject configFromPublishA = config(7);
        JSONObject catalogFromPublishB = rawCatalog(7);
        catalogFromPublishB.getJSONArray("profiles").getJSONObject(0)
            .put("displayName", "Different fictional publish");
        String publishBSha = LegacyPanelCacheMigrationRules.sha256(
            catalogFromPublishB.toString());
        AppConfig.stampConnection(catalogFromPublishB, PANEL, KEY);
        AppConfig.stampCatalogSource(catalogFromPublishB, publishBSha);

        assertThrows(IOException.class, () -> PanelPairCacheCoordinator.parsePair(
            configFromPublishA.toString(), catalogFromPublishB.toString(), PANEL, KEY));
    }

    @Test
    public void rejectsEitherHalfFromAnotherPanelOrKey() throws Exception {
        JSONObject wrongPanelCatalog = catalog(7);
        AppConfig.stampConnection(wrongPanelCatalog,
            "https://other.example.invalid", KEY);
        assertThrows(IOException.class, () -> PanelPairCacheCoordinator.parsePair(
            config(7).toString(), wrongPanelCatalog.toString(), PANEL, KEY));

        JSONObject wrongKeyConfig = config(7);
        AppConfig.stampConnection(wrongKeyConfig, PANEL, "other-key");
        assertThrows(IOException.class, () -> PanelPairCacheCoordinator.parsePair(
            wrongKeyConfig.toString(), catalog(7).toString(), PANEL, KEY));
    }

    @Test
    public void rejectsUnsupportedSchemaEmptyProfilesAndCoercedVersions() throws Exception {
        JSONObject tooNew = catalog(7).put("schemaVersion",
            FormCatalog.SUPPORTED_SCHEMA_VERSION + 1);
        assertThrows(IOException.class, () -> PanelPairCacheCoordinator.parsePair(
            config(7).toString(), tooNew.toString(), PANEL, KEY));

        JSONObject empty = catalog(7).put("profiles", new JSONArray());
        assertThrows(IOException.class, () -> PanelPairCacheCoordinator.parsePair(
            config(7).toString(), empty.toString(), PANEL, KEY));

        JSONObject stringVersion = catalog(7).put("version", "7");
        assertThrows(IOException.class, () -> PanelPairCacheCoordinator.parsePair(
            config(7).toString(), stringVersion.toString(), PANEL, KEY));
    }

    @Test
    public void rejectsConfigWithoutCompleteUploadAndSubmitContract() throws Exception {
        JSONObject incomplete = config(7);
        incomplete.getJSONObject("backendAdapter")
            .getJSONObject("operations").remove("submit");

        assertFalse(AppConfig.hasUsablePayload(incomplete));
        assertThrows(IOException.class, () -> PanelPairCacheCoordinator.parsePair(
            incomplete.toString(), catalog(7).toString(), PANEL, KEY));
    }

    @Test
    public void legacyUnversionedConfigUpgradeUsesOnlyAnExactCatalogMatch()
            throws Exception {
        JSONObject legacyCatalog = rawCatalog(7);

        PanelPairCacheCoordinator.ActivePair matched =
            PanelPairCacheCoordinator.candidatePairMatchingLegacyCatalog(
                legacyCatalog.toString(), config(7).toString(),
                catalog(7).toString(), PANEL, KEY);
        assertEquals(7, matched.version);

        JSONObject changedSameRevision = rawCatalog(7);
        changedSameRevision.getJSONArray("profiles").getJSONObject(0)
            .put("displayName", "Different fictional publish");
        assertEquals(null,
            PanelPairCacheCoordinator.candidatePairMatchingLegacyCatalog(
                legacyCatalog.toString(), config(7).toString(),
                boundCatalog(changedSameRevision).toString(), PANEL, KEY));

        assertEquals(null,
            PanelPairCacheCoordinator.candidatePairMatchingLegacyCatalog(
                legacyCatalog.toString(), config(8).toString(),
                catalog(8).toString(), PANEL, KEY));
    }

    @Test
    public void strictlyNewerValidCandidateHalvesPermitExactOldActiveFallback()
            throws Exception {
        PanelPairCacheCoordinator.ActivePair active =
            PanelPairCacheCoordinator.parsePair(
                config(7).toString(), catalog(7).toString(), PANEL, KEY);

        assertTrue(PanelPairCacheCoordinator.newerCandidatesPermitActiveUse(
            active, config(8).toString(), null, PANEL, KEY));
        assertTrue(PanelPairCacheCoordinator.newerCandidatesPermitActiveUse(
            active, null, catalog(8).toString(), PANEL, KEY));
        // Two independently valid future halves can be in a publish-race mismatch. They still
        // cannot alter the immutable v7 active pair while the bounded whole-pair retry converges.
        assertTrue(PanelPairCacheCoordinator.newerCandidatesPermitActiveUse(
            active, config(8).toString(), catalog(9).toString(), PANEL, KEY));
    }

    @Test
    public void sameOlderMalformedOrCrossPanelCandidatesBlockActiveFallback()
            throws Exception {
        PanelPairCacheCoordinator.ActivePair active =
            PanelPairCacheCoordinator.parsePair(
                config(7).toString(), catalog(7).toString(), PANEL, KEY);
        JSONObject wrongPanel = config(8);
        AppConfig.stampConnection(wrongPanel, "https://other.example.invalid", KEY);

        assertFalse(PanelPairCacheCoordinator.newerCandidatesPermitActiveUse(
            active, config(7).toString(), null, PANEL, KEY));
        assertFalse(PanelPairCacheCoordinator.newerCandidatesPermitActiveUse(
            active, null, catalog(6).toString(), PANEL, KEY));
        assertFalse(PanelPairCacheCoordinator.newerCandidatesPermitActiveUse(
            active, "{", null, PANEL, KEY));
        assertFalse(PanelPairCacheCoordinator.newerCandidatesPermitActiveUse(
            active, wrongPanel.toString(), null, PANEL, KEY));
        assertFalse(PanelPairCacheCoordinator.newerCandidatesPermitActiveUse(
            active, null, null, PANEL, KEY));
        assertFalse(PanelPairCacheCoordinator.newerCandidatesPermitActiveUse(
            null, config(8).toString(), null, PANEL, KEY));
    }

    @Test
    public void atomicViewClassifiesCandidateInsertedAfterActiveRead()
            throws Exception {
        PanelPairCacheCoordinator.ActivePair active =
            PanelPairCacheCoordinator.parsePair(
                config(7).toString(), catalog(7).toString(), PANEL, KEY);
        Object handoffLock = new Object();
        RaceSource source = new RaceSource(handoffLock, active);
        source.insertMalformedConfigWhenActiveIsRead = true;

        assertEquals(null, PanelPairCacheCoordinator.atomicActivePairView(
            handoffLock,
            AppConfig.connectionNamespaceId(PANEL, KEY),
            PanelPairCacheCoordinator.CandidatePolicy.PERMIT_STRICTLY_NEWER,
            source));
        assertTrue(source.allCallbacksHeldLock);
        assertTrue(source.activeRead);
        assertTrue(source.configCandidateReadAfterActive);
    }

    @Test
    public void atomicViewsSeparateWorkflowAndUpdateCandidatePolicies()
            throws Exception {
        PanelPairCacheCoordinator.ActivePair active =
            PanelPairCacheCoordinator.parsePair(
                config(7).toString(), catalog(7).toString(), PANEL, KEY);
        Object handoffLock = new Object();
        RaceSource noCandidates = new RaceSource(handoffLock, active);
        String connection = AppConfig.connectionNamespaceId(PANEL, KEY);

        assertTrue(active == PanelPairCacheCoordinator.atomicActivePairView(
            handoffLock, connection,
            PanelPairCacheCoordinator.CandidatePolicy.PERMIT_STRICTLY_NEWER,
            noCandidates));
        assertTrue(active == PanelPairCacheCoordinator.atomicActivePairView(
            handoffLock, connection,
            PanelPairCacheCoordinator.CandidatePolicy.REQUIRE_NONE,
            noCandidates));

        RaceSource newer = new RaceSource(handoffLock, active);
        newer.catalogCandidate = catalog(8).toString();
        assertTrue(active == PanelPairCacheCoordinator.atomicActivePairView(
            handoffLock, connection,
            PanelPairCacheCoordinator.CandidatePolicy.PERMIT_STRICTLY_NEWER,
            newer));
        assertEquals(null, PanelPairCacheCoordinator.atomicActivePairView(
            handoffLock, connection,
            PanelPairCacheCoordinator.CandidatePolicy.REQUIRE_NONE,
            newer));
    }

    private static JSONObject config(int version) throws Exception {
        JSONObject value = new JSONObject()
            .put("catalogVersion", version)
            .put("backendAdapter", sharedAdapter())
            .put(LegacyPanelCacheMigrationRules.PROOF_FIELD, new JSONObject()
                .put("version", 1)
                .put("panelBase", PANEL)
                .put("keySha256", LegacyPanelCacheMigrationRules.sha256(KEY))
                .put("catalogSha256", catalogSourceSha(version))
                .put("catalogVersion", version));
        AppConfig.stampConnection(value, PANEL, KEY);
        return value;
    }

    private static JSONObject catalog(int version) throws Exception {
        return boundCatalog(rawCatalog(version));
    }

    private static JSONObject boundCatalog(JSONObject raw) throws Exception {
        JSONObject value = new JSONObject(raw.toString());
        String sourceSha = LegacyPanelCacheMigrationRules.sha256(value.toString());
        AppConfig.stampConnection(value, PANEL, KEY);
        AppConfig.stampCatalogSource(value, sourceSha);
        return value;
    }

    private static JSONObject rawCatalog(int version) throws Exception {
        return new JSONObject()
            .put("schemaVersion", FormCatalog.SUPPORTED_SCHEMA_VERSION)
            .put("version", version)
            .put("profiles", new JSONArray().put(publicProfile()))
            .put("settings", new JSONObject());
    }

    private static JSONObject publicProfile() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path path : new Path[]{
                cwd.resolve("app/assets/form-profiles.seed.json"),
                cwd.resolve("assets/form-profiles.seed.json")}) {
            if (Files.isRegularFile(path)) {
                JSONObject root = new JSONObject(new String(
                    Files.readAllBytes(path), StandardCharsets.UTF_8));
                JSONObject profile = root.getJSONArray("profiles").getJSONObject(0);
                return new JSONObject(profile.toString())
                    .put("id", "fictional-profile")
                    .put("displayName", "Fictional Form");
            }
        }
        throw new AssertionError("public profile fixture not found from " + cwd);
    }

    private static String catalogSourceSha(int version) throws Exception {
        return LegacyPanelCacheMigrationRules.sha256(rawCatalog(version).toString());
    }

    private static JSONObject sharedAdapter() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path path : new Path[]{
                cwd.resolve("panel/backend-adapter.example.json"),
                cwd.resolve("../panel/backend-adapter.example.json")}) {
            if (Files.isRegularFile(path)) {
                return new JSONObject(new String(
                    Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
        }
        throw new AssertionError("shared Panel adapter fixture not found from " + cwd);
    }

    private static final class RaceSource
            implements PanelPairCacheCoordinator.AtomicActiveUseSource {
        final Object expectedLock;
        final PanelPairCacheCoordinator.ActivePair active;
        boolean insertMalformedConfigWhenActiveIsRead;
        boolean allCallbacksHeldLock = true;
        boolean activeRead;
        boolean configCandidateReadAfterActive;
        String configCandidate;
        String catalogCandidate;

        RaceSource(Object expectedLock,
                   PanelPairCacheCoordinator.ActivePair active) {
            this.expectedLock = expectedLock;
            this.active = active;
        }

        private void observeLock() {
            allCallbacksHeldLock &= Thread.holdsLock(expectedLock);
        }

        @Override public void recover() {
            observeLock();
        }

        @Override public String currentConnection() {
            observeLock();
            return AppConfig.connectionNamespaceId(PANEL, KEY);
        }

        @Override public PanelPairCacheCoordinator.ActivePair activePair() {
            observeLock();
            activeRead = true;
            if (insertMalformedConfigWhenActiveIsRead) configCandidate = "{";
            return active;
        }

        @Override public String configCandidateTextOrNull() {
            observeLock();
            configCandidateReadAfterActive = activeRead;
            return configCandidate;
        }

        @Override public String catalogCandidateTextOrNull() {
            observeLock();
            return catalogCandidate;
        }

        @Override public String panelBase() {
            observeLock();
            return PANEL;
        }

        @Override public String catalogKey() {
            observeLock();
            return KEY;
        }
    }
}
