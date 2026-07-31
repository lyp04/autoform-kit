package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class OcrSafetyRulesTest {
    private static JSONObject fixture(String relative) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path[] candidates = new Path[]{cwd.resolve(relative), cwd.resolve("..").resolve(relative)};
        for (Path path : candidates) {
            if (Files.isRegularFile(path)) {
                return new JSONObject(new String(
                    Files.readAllBytes(path), StandardCharsets.UTF_8));
            }
        }
        throw new AssertionError("fixture not found: " + relative + " from " + cwd);
    }

    private static JSONObject reviewedProfile() throws Exception {
        JSONObject seed = fixture("app/assets/form-profiles.seed.json");
        return new JSONObject(seed.getJSONArray("profiles").getJSONObject(0).toString());
    }

    private static JSONObject completeAdapterJson() throws Exception {
        return fixture("panel/backend-adapter.example.json");
    }

    private static BackendAdapter adapter(JSONObject adapterJson) throws Exception {
        return BackendAdapter.from(new JSONObject().put("backendAdapter", adapterJson));
    }

    private static void assertOcrBlocked(ProfileWorkflow workflow, BackendAdapter adapter)
            throws Exception {
        AtomicInteger socketCalls = new AtomicInteger();
        try {
            RemoteSideEffectSafetyRules.executeOcr(workflow, adapter, () -> {
                socketCalls.incrementAndGet();
                return "unexpected";
            });
            fail("OCR remote callback must be blocked");
        } catch (BackendAdapter.ConfigurationException expected) {
            assertTrue(expected.getMessage().contains("Missing panel configuration"));
        }
        assertEquals(0, socketCalls.get());
    }

    private static void assertAlternateOcrBlocked(
            JSONObject source, JSONObject target, BackendAdapter adapter) throws Exception {
        AtomicInteger socketCalls = new AtomicInteger();
        try {
            RemoteSideEffectSafetyRules.executeAlternateEntryOcr(
                source, target, adapter, () -> {
                    socketCalls.incrementAndGet();
                    return "unexpected";
                });
            fail("alternate-entry OCR remote callback must be blocked");
        } catch (BackendAdapter.ConfigurationException expected) {
            assertTrue(expected.getMessage().contains("Missing panel configuration"));
        }
        assertEquals(0, socketCalls.get());
    }

    @Test
    public void unreviewedNormalProfileExecutesZeroOcrSockets() throws Exception {
        JSONObject unreviewed = reviewedProfile();
        unreviewed.getJSONObject("workflow").put("compatibilityReviewed", false);

        assertOcrBlocked(ProfileWorkflow.from(unreviewed), adapter(completeAdapterJson()));
        assertTrue(RemoteSideEffectSafetyRules.ocrCapabilityErrors(
            ProfileWorkflow.from(unreviewed), adapter(completeAdapterJson()))
            .contains("profile.workflow.compatibilityReviewed"));
    }

    @Test
    public void everyRequiredOcrAdapterCapabilityFailsClosedBeforeSocket() throws Exception {
        List<JSONObject> incompleteAdapters = new ArrayList<>();

        JSONObject unsupportedVersion = completeAdapterJson();
        unsupportedVersion.put("version", BackendAdapter.SUPPORTED_VERSION + 1);
        incompleteAdapters.add(unsupportedVersion);

        JSONObject missingBaseUrl = completeAdapterJson();
        missingBaseUrl.put("baseUrl", "");
        incompleteAdapters.add(missingBaseUrl);

        JSONObject missingUserInfo = completeAdapterJson();
        missingUserInfo.getJSONObject("endpoints").remove("userInfo");
        incompleteAdapters.add(missingUserInfo);

        JSONObject missingOcrOperation = completeAdapterJson();
        missingOcrOperation.getJSONObject("operations").remove("ocr");
        incompleteAdapters.add(missingOcrOperation);

        JSONObject missingMultipartField = completeAdapterJson();
        missingMultipartField.getJSONObject("operations").getJSONObject("ocr")
            .remove("multipartField");
        incompleteAdapters.add(missingMultipartField);

        JSONObject missingUserInfoUrlFields = completeAdapterJson();
        missingUserInfoUrlFields.getJSONObject("operations").getJSONObject("ocr")
            .put("userInfoUrlFields", new JSONArray());
        incompleteAdapters.add(missingUserInfoUrlFields);

        JSONObject missingResultPaths = completeAdapterJson();
        missingResultPaths.getJSONObject("operations").getJSONObject("ocr")
            .put("resultPaths", new JSONArray());
        incompleteAdapters.add(missingResultPaths);

        ProfileWorkflow workflow = ProfileWorkflow.from(reviewedProfile());
        for (JSONObject incomplete : incompleteAdapters) {
            BackendAdapter candidate = adapter(incomplete);
            assertTrue(candidate.missingForOcr().toString(),
                !candidate.missingForOcr().isEmpty());
            assertOcrBlocked(workflow, candidate);
        }
        assertOcrBlocked(workflow, null);
    }

    @Test
    public void completeNormalOcrConfigurationPreservesExactlyOneRemoteExecution()
            throws Exception {
        ProfileWorkflow workflow = ProfileWorkflow.from(reviewedProfile());
        BackendAdapter complete = adapter(completeAdapterJson());
        AtomicInteger socketCalls = new AtomicInteger();

        assertTrue(RemoteSideEffectSafetyRules.ocrCapabilityErrors(
            workflow, complete).toString(),
            RemoteSideEffectSafetyRules.ocrCapabilityErrors(workflow, complete).isEmpty());
        String result = RemoteSideEffectSafetyRules.executeOcr(workflow, complete, () -> {
            socketCalls.incrementAndGet();
            return "original-result";
        });

        assertEquals("original-result", result);
        assertEquals(1, socketCalls.get());
    }

    @Test
    public void alternateOcrRequiresBothReviewedProfilesAndOcrCapabilityAtSocket()
            throws Exception {
        JSONObject source = reviewedProfile();
        JSONObject target = reviewedProfile();
        BackendAdapter complete = adapter(completeAdapterJson());

        JSONObject unreviewedSource = new JSONObject(source.toString());
        unreviewedSource.getJSONObject("workflow").put("compatibilityReviewed", false);
        assertAlternateOcrBlocked(unreviewedSource, target, complete);

        JSONObject unreviewedTarget = new JSONObject(target.toString());
        unreviewedTarget.getJSONObject("workflow").put("compatibilityReviewed", false);
        assertAlternateOcrBlocked(source, unreviewedTarget, complete);

        JSONObject incompleteJson = completeAdapterJson();
        incompleteJson.getJSONObject("operations").getJSONObject("ocr")
            .put("resultPaths", new JSONArray());
        assertAlternateOcrBlocked(source, target, adapter(incompleteJson));

        AtomicInteger socketCalls = new AtomicInteger();
        String result = RemoteSideEffectSafetyRules.executeAlternateEntryOcr(
            source, target, complete, () -> {
                socketCalls.incrementAndGet();
                return "alternate-result";
            });
        assertEquals("alternate-result", result);
        assertEquals(1, socketCalls.get());
    }
}
