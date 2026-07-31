package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executable cross-profile proof over the same JSON contract emitted by Panel and consumed by
 * Android. Both profiles are fictional and non-operational.
 */
public class PanelProfileIsolationContractTest {
    private static final String CONNECTION = "0123456789abcdef0123";
    private static final String PAIR = repeat('a', 64);
    private static final String WEB = "sample-browser-fingerprint";
    private static final String TOKEN = "sample-session";
    private static final String OPERATION = "0123456789abcdef0123456789abcdef";

    private static JSONObject seed() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path path : new Path[]{
                cwd.resolve("app/assets/form-profiles.seed.json"),
                cwd.resolve("assets/form-profiles.seed.json")}) {
            if (Files.isRegularFile(path)) {
                return new JSONObject(new String(
                    Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
        }
        throw new AssertionError("bundled seed not found from " + cwd);
    }

    private static JSONObject config(int version) throws Exception {
        return new JSONObject()
            .put("catalogVersion", version)
            .put("backendAdapter", new JSONObject().put("version", 1));
    }

    @Test
    public void panelProfilesKeepIndependentSelectionPhotoAndDraftIdentities()
            throws Exception {
        JSONObject root = seed();
        assertTrue(CatalogPromotionValidator.isStructurallyValid(root));
        int version = root.getInt("version");
        JSONObject first = root.getJSONArray("profiles").getJSONObject(0);
        JSONObject second = root.getJSONArray("profiles").getJSONObject(1);

        assertFalse(first.getString("id").equals(second.getString("id")));
        assertFalse(ProfileFieldRules.primaryIdentifierField(first).equals(
            ProfileFieldRules.primaryIdentifierField(second)));
        assertFalse(PhotoOrderRules.profileDefault(first).equals(
            PhotoOrderRules.profileDefault(second)));

        List<String> firstPhotos = ProfileFieldRules.activePhotoSlotFields(first, false);
        List<String> secondPhotos = ProfileFieldRules.activePhotoSlotFields(second, false);
        assertFalse(firstPhotos.equals(secondPhotos));
        Map<String, List<String>> staleSecondPhoto = new LinkedHashMap<>();
        staleSecondPhoto.put(secondPhotos.get(0),
            Collections.singletonList("/sample/second-profile.jpg"));
        assertEquals(secondPhotos,
            ProfileFieldRules.unexpectedPhotoSlotFields(
                first, false, staleSecondPhoto));

        MainDraftSnapshotRules.Binding firstBinding =
            MainDraftSnapshotRules.currentBinding(CONNECTION, version,
                first.getString("id"), first, config(version),
                root.getJSONObject("settings"));
        MainDraftSnapshotRules.Binding secondBinding =
            MainDraftSnapshotRules.currentBinding(CONNECTION, version,
                second.getString("id"), second, config(version),
                root.getJSONObject("settings"));
        assertFalse(firstBinding.sameAs(secondBinding));
        assertFalse(MainDraftSnapshotRules.runtimeProfileMatchesCatalog(first, second));

        OperationBindingRules.Binding operation = OperationBindingRules.capture(
            CONNECTION, version, PAIR, WEB, TOKEN, OPERATION,
            PendingFormOperationRules.PHOTO);
        PendingFormOperationRules.Target target = PendingFormOperationRules.create(
            PendingFormOperationRules.PHOTO, OPERATION, firstBinding, PAIR, 1,
            PendingFormOperationRules.ROLE_PHOTO, "slot", firstPhotos.get(0),
            "/sample/first-profile.jpg", "", operation);
        assertTrue(target.matches(firstBinding, PAIR, WEB, TOKEN));
        assertFalse(target.matches(secondBinding, PAIR, WEB, TOKEN));
    }

    @Test
    public void equalVisibleResultKeysAndStaleExtraFieldsStillResolvePerProfile()
            throws Exception {
        JSONObject first = seed().getJSONArray("profiles").getJSONObject(0);
        JSONObject second = seed().getJSONArray("profiles").getJSONObject(1);
        first.getJSONObject("gradeMap").put("shared-result", new JSONObject()
            .put("field", "first_result").put("value", "FIRST"));
        second.getJSONObject("gradeMap").put("shared-result", new JSONObject()
            .put("field", "second_result").put("value", "SECOND"));
        first.getJSONArray("snPlugins").put(new JSONObject()
            .put("key", "sample-first-extra").put("field", "first_extra")
            .put("visible", true));
        second.getJSONArray("snPlugins").put(new JSONObject()
            .put("key", "sample-second-extra").put("field", "second_extra")
            .put("visible", true));

        assertEquals("first_result",
            ProfileFieldRules.resultMapping(first, "shared-result").getString("field"));
        assertEquals("FIRST",
            ProfileFieldRules.resultMapping(first, "shared-result").getString("value"));
        assertEquals("second_result",
            ProfileFieldRules.resultMapping(second, "shared-result").getString("field"));
        assertEquals("SECOND",
            ProfileFieldRules.resultMapping(second, "shared-result").getString("value"));

        Map<String, String> staleValues = new LinkedHashMap<>();
        staleValues.put("first_extra", "FIRST-VALUE");
        staleValues.put("second_extra", "SECOND-VALUE");
        staleValues.put(ProfileFieldRules.primaryIdentifierField(first),
            "MUST-NOT-OVERRIDE");
        assertEquals(Collections.singletonMap("first_extra", "FIRST-VALUE"),
            ProfileFieldRules.boundVisibleExtraIdentifierValues(first, staleValues));
        assertEquals(2,
            ProfileFieldRules.unexpectedExtraIdentifierFields(
                first, staleValues).size());
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
