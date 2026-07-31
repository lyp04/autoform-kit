package com.autoformkit.app;

import org.json.JSONObject;
import org.json.JSONException;

import java.util.Map;

/** Lossless bridge for the unnamed previous-step photo stored by v1.0.4-v1.0.6 drafts. */
final class LegacyDraftArtifactRules {
    private LegacyDraftArtifactRules() {}

    /**
     * Maps the old photo only when the Panel exposes one unambiguous artifact target. The path is
     * always returned for verbatim round-trip under the old key, so a canary downgrade can reopen
     * the same draft. Callers submit only the configured map, never this compatibility field.
     */
    static String restore(JSONObject draftUnit, Map<String, String> configuredArtifacts,
                          ProfileWorkflow workflow) {
        String path = draftUnit == null ? "" : draftUnit.optString("aStepPhotoPath", "");
        if (path.isEmpty()) return "";
        ProfileWorkflow.WorkflowArtifact target = workflow == null
            ? null : workflow.legacyArtifactTarget();
        if (target != null && configuredArtifacts != null
                && !configuredArtifacts.containsKey(target.key)) {
            configuredArtifacts.put(target.key, path);
        }
        return path;
    }

    /** Mirrors only the Panel-declared compatibility artifact into the signed-v1 draft field. */
    static String afterArtifactChange(ProfileWorkflow workflow, String artifactKey,
                                      String newPath, String currentLegacyPath) {
        ProfileWorkflow.WorkflowArtifact target = workflow == null
            ? null : workflow.legacyArtifactTarget();
        if (target == null || artifactKey == null || !target.key.equals(artifactKey)) {
            return currentLegacyPath == null ? "" : currentLegacyPath;
        }
        return newPath == null ? "" : newPath;
    }

    static void write(JSONObject draftUnit, String legacyPath) throws JSONException {
        if (draftUnit != null && legacyPath != null && !legacyPath.isEmpty()) {
            draftUnit.put("aStepPhotoPath", legacyPath);
        }
    }

    /** Writes the complete signed-v1 rollback view without changing current-runtime ownership. */
    static void write(JSONObject draftUnit, String legacyPath, boolean legacyRequired)
            throws JSONException {
        if (draftUnit == null) return;
        draftUnit.put("stepPhotoRequired", legacyRequired);
        write(draftUnit, legacyPath);
    }
}
