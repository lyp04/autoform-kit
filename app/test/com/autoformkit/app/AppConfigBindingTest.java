package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class AppConfigBindingTest {
    private static String mainActivitySource() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path[] candidates = new Path[]{
            cwd.resolve("app/src/com/autoformkit/app/MainActivity.java"),
            cwd.resolve("src/com/autoformkit/app/MainActivity.java")
        };
        for (Path path : candidates) {
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("MainActivity source not found from " + cwd);
    }

    private static JSONObject sharedFixture() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path[] candidates = new Path[]{
            cwd.resolve("panel/backend-adapter.example.json"),
            cwd.resolve("../panel/backend-adapter.example.json")
        };
        for (Path path : candidates) {
            if (Files.isRegularFile(path)) {
                return new JSONObject(new String(
                    Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
        }
        throw new AssertionError("shared panel fixture not found from " + cwd);
    }

    @Test
    public void unboundLegacyCatalogIsFetchedEvenWhenItsVersionWasPreviouslyApplied() {
        assertTrue(FormCatalogManager.shouldFetchVersion(7, 7, false));
        assertFalse(FormCatalogManager.shouldFetchVersion(7, 7, true));
        assertTrue(FormCatalogManager.shouldFetchVersion(8, 7, true));
        assertFalse(FormCatalogManager.shouldFetchVersion(0, 7, false));
        assertTrue(FormCatalogManager.shouldFetchVersion(7, 7, 0));
        assertTrue(FormCatalogManager.shouldFetchVersion(7, 7, 8));
    }

    @Test
    public void catalogSkipRequiresExactPublishedSourceDigest() {
        String first = repeat('a', 64);
        String second = repeat('b', 64);

        assertFalse(FormCatalogManager.shouldFetchPublishedCatalog(
            7, "sha256:" + first, 7, first));
        assertTrue(FormCatalogManager.shouldFetchPublishedCatalog(
            7, second, 7, first));
        assertTrue(FormCatalogManager.shouldFetchPublishedCatalog(
            7, "", 7, first));
        assertTrue(FormCatalogManager.shouldFetchPublishedCatalog(
            8, second, 7, second));
        assertFalse(FormCatalogManager.shouldFetchPublishedCatalog(
            0, second, 0, ""));
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }

    @Test
    public void catalogReadKeyIsScopedToTheWorkerCapturedPanelOrigin() {
        assertTrue(FormCatalogManager.tokenAllowedForUrl(
            "https://old-panel.example.invalid/catalog/form-profiles.json",
            "https://old-panel.example.invalid"));
        assertFalse(FormCatalogManager.tokenAllowedForUrl(
            "https://new-panel.example.invalid/catalog/form-profiles.json",
            "https://old-panel.example.invalid"));
        assertFalse(FormCatalogManager.tokenAllowedForUrl(
            "http://old-panel.example.invalid/catalog/form-profiles.json",
            "https://old-panel.example.invalid"));
        assertFalse(FormCatalogManager.tokenAllowedForUrl(
            "https://old-panel.example.invalid:8443/catalog/form-profiles.json",
            "https://old-panel.example.invalid"));
    }

    @Test
    public void cacheIsBoundToBothPanelAddressAndAccessKey() throws Exception {
        JSONObject cached = new JSONObject().put("backendAdapter", new JSONObject());
        AppConfig.stampConnection(cached, "https://panel.example.invalid/", "key-one");

        assertTrue(AppConfig.isBoundToConnection(
            cached, "https://panel.example.invalid", "key-one"));
        assertFalse(AppConfig.isBoundToConnection(
            cached, "https://other.example.invalid", "key-one"));
        assertFalse(AppConfig.isBoundToConnection(
            cached, "https://panel.example.invalid", "key-two"));
        assertFalse(AppConfig.isBoundToConnection(
            new JSONObject(), "https://panel.example.invalid", "key-one"));
        assertTrue(AppConfig.connectionNamespaceId(
            "https://panel.example.invalid/", "key-one").equals(
                AppConfig.connectionNamespaceId(
                    "https://panel.example.invalid", "key-one")));
        assertNotEquals(AppConfig.connectionNamespaceId(
            "https://panel.example.invalid", "key-one"),
            AppConfig.connectionNamespaceId(
                "https://panel.example.invalid", "key-two"));
    }

    @Test
    public void bootstrapRejectsLoginOnlyOrOldShapeConfig() throws Exception {
        JSONObject complete = new JSONObject()
            .put("catalogVersion", 7)
            .put("backendAdapter", sharedFixture());
        assertTrue(AppConfig.hasUsablePayload(complete));

        JSONObject noSubmit = new JSONObject(complete.toString());
        noSubmit.getJSONObject("backendAdapter").getJSONObject("operations")
            .remove("submit");
        assertFalse(AppConfig.hasUsablePayload(noSubmit));

        JSONObject legacyTopLevelOnly = new JSONObject()
            .put("backendApiBase", "https://backend.example.invalid/api");
        assertFalse(AppConfig.hasUsablePayload(legacyTopLevelOnly));

        JSONObject missingRevision = new JSONObject(complete.toString());
        missingRevision.remove("catalogVersion");
        assertFalse(AppConfig.hasUsablePayload(missingRevision));
        JSONObject stringRevision = new JSONObject(complete.toString())
            .put("catalogVersion", "7");
        assertFalse(AppConfig.hasUsablePayload(stringRevision));
    }

    @Test
    public void submitPayloadUsesOneCapturedAdapterForEnvelopeAndMaterialItems()
            throws Exception {
        String source = mainActivitySource();
        int start = source.indexOf(
            "private JSONObject buildPayload(BackendAdapter adapter");
        int end = source.indexOf("private JSONObject submitEnvelope", start);
        assertTrue("captured-adapter buildPayload overload is missing", start >= 0);
        assertTrue("submitEnvelope boundary is missing", end > start);
        String method = source.substring(start, end);
        assertTrue(method.contains(
            "adapter.operations.submit.materialItemMapping.item("));
        assertFalse(method.contains(
            "endpoints().operations.submit.materialItemMapping.item("));

        int envelopeStart = source.indexOf(
            "private JSONObject submitEnvelope(BackendAdapter adapter", end);
        int envelopeEnd = source.indexOf(
            "private Map<String, List<String>> uploadSlotPhotos", envelopeStart);
        assertTrue("captured-adapter submitEnvelope overload is missing",
            envelopeStart >= 0);
        assertTrue("submitEnvelope method boundary is missing",
            envelopeEnd > envelopeStart);
        String envelope = source.substring(envelopeStart, envelopeEnd);
        assertTrue(envelope.contains("adapter.operations.submit.wrap("));
        assertFalse(envelope.contains("endpoints()"));
    }
}
