package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class LegacyDraftArtifactRulesTest {
    private static ProfileWorkflow workflow(String legacyKey, String... keys) throws Exception {
        JSONArray artifacts = new JSONArray();
        for (String key : keys) {
            artifacts.put(new JSONObject()
                .put("key", key)
                .put("title", "Example " + key)
                .put("required", true)
                .put("uploadNameTemplate", "{identifier}-sample-evidence.jpg"));
        }
        return ProfileWorkflow.from(new JSONObject().put("workflow", new JSONObject()
            .put("previousSteps", new JSONObject()
                .put("legacyDraftArtifactKey", legacyKey)
                .put("artifacts", artifacts))));
    }

    @Test
    public void explicitPanelTargetReceivesLegacyPhoto() throws Exception {
        Map<String, String> mapped = new LinkedHashMap<>();
        String unmapped = LegacyDraftArtifactRules.restore(
            new JSONObject().put("aStepPhotoPath", "/fictional/evidence.jpg"),
            mapped, workflow("example-evidence", "example-evidence"));

        assertEquals("/fictional/evidence.jpg", unmapped);
        assertEquals("/fictional/evidence.jpg", mapped.get("example-evidence"));

        JSONObject saved = new JSONObject();
        LegacyDraftArtifactRules.write(saved, unmapped);
        assertEquals("/fictional/evidence.jpg", saved.getString("aStepPhotoPath"));
    }

    @Test
    public void missingOrWrongExplicitTargetRoundTripsOldKey() throws Exception {
        JSONObject oldUnit = new JSONObject()
            .put("aStepPhotoPath", "/fictional/evidence.jpg");
        Map<String, String> mapped = new LinkedHashMap<>();

        String unmapped = LegacyDraftArtifactRules.restore(
            oldUnit, mapped, workflow("", "example-one"));
        assertTrue(mapped.isEmpty());
        assertEquals("/fictional/evidence.jpg", unmapped);

        LegacyDraftArtifactRules.restore(oldUnit, mapped,
            workflow("missing-example", "example-one", "example-two"));
        assertTrue(mapped.isEmpty());

        JSONObject saved = new JSONObject();
        LegacyDraftArtifactRules.write(saved, unmapped);
        assertEquals("/fictional/evidence.jpg", saved.getString("aStepPhotoPath"));

        JSONObject empty = new JSONObject();
        LegacyDraftArtifactRules.write(empty, "");
        assertFalse(empty.has("aStepPhotoPath"));
    }

    @Test
    public void rollbackViewRetainsLegacyRequiredDecision() throws Exception {
        JSONObject required = new JSONObject();
        LegacyDraftArtifactRules.write(
            required, "/fictional/evidence.jpg", true);
        assertTrue(required.getBoolean("stepPhotoRequired"));
        assertEquals("/fictional/evidence.jpg", required.getString("aStepPhotoPath"));

        JSONObject optional = new JSONObject();
        LegacyDraftArtifactRules.write(optional, "", false);
        assertFalse(optional.getBoolean("stepPhotoRequired"));
        assertFalse(optional.has("aStepPhotoPath"));
    }

    @Test
    public void captureReplaceAndDeleteMirrorOnlyExplicitLegacyTarget() throws Exception {
        ProfileWorkflow workflow = workflow(
            "legacy-example", "legacy-example", "other-example");
        assertEquals("/fictional/new.jpg", LegacyDraftArtifactRules.afterArtifactChange(
            workflow, "legacy-example", "/fictional/new.jpg", "/fictional/old.jpg"));
        assertEquals("", LegacyDraftArtifactRules.afterArtifactChange(
            workflow, "legacy-example", "", "/fictional/new.jpg"));
        assertEquals("/fictional/old.jpg", LegacyDraftArtifactRules.afterArtifactChange(
            workflow, "other-example", "/fictional/other.jpg", "/fictional/old.jpg"));
        assertEquals("/fictional/old.jpg", LegacyDraftArtifactRules.afterArtifactChange(
            workflow("", "legacy-example"), "legacy-example", "/fictional/new.jpg",
            "/fictional/old.jpg"));
    }
}
