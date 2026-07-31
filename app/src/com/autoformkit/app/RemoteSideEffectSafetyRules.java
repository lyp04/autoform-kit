package com.autoformkit.app;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/** Pure fail-closed capability gates for optional profile-owned remote operations. */
final class RemoteSideEffectSafetyRules {
    private RemoteSideEffectSafetyRules() {}

    interface RemoteCall<T> {
        T run() throws Exception;
    }

    /** A normal profile may use remote OCR only after policy review and full OCR capability. */
    static List<String> ocrCapabilityErrors(
            ProfileWorkflow workflow, BackendAdapter adapter) {
        List<String> errors = new ArrayList<>();
        if (workflow == null || !workflow.operationalPoliciesExplicit) {
            errors.add("profile.workflow.compatibilityReviewed");
        }
        if (adapter == null) {
            errors.add("backendAdapter");
        } else {
            if (!adapter.isSupported()) errors.add("backendAdapter");
            errors.addAll(adapter.missingForOcr());
        }
        return immutableDistinct(errors);
    }

    /**
     * Keep the capability decision adjacent to the callback that may open the OCR socket. This
     * second check complements the UI preflight and makes a skipped/partial preflight fail closed.
     */
    static <T> T executeOcr(ProfileWorkflow workflow, BackendAdapter adapter,
                            RemoteCall<T> remoteCall) throws Exception {
        return execute(ocrCapabilityErrors(workflow, adapter), remoteCall);
    }

    /**
     * An alternate entry is jointly owned by its visible source and hidden target profiles.
     * Neither profile may authorize a remote lookup, upload, or submit until its complete
     * operational policy has been reviewed, and the frozen adapter must support the exact basic
     * submit contract used by the alternate-entry flow.
     */
    static List<String> alternateEntryCapabilityErrors(
            JSONObject sourceProfile, JSONObject targetProfile, BackendAdapter adapter) {
        List<String> errors = new ArrayList<>();
        if (!operationalPoliciesExplicit(sourceProfile)) {
            errors.add("sourceProfile.workflow.compatibilityReviewed");
        }
        if (!operationalPoliciesExplicit(targetProfile)) {
            errors.add("targetProfile.workflow.compatibilityReviewed");
        }
        if (adapter == null) {
            errors.add("backendAdapter");
        } else {
            if (!adapter.isSupported()) errors.add("backendAdapter");
            errors.addAll(adapter.missingForSubmit(false, false, false, false));
        }
        return immutableDistinct(errors);
    }

    /** OCR is optional, but when invoked it must add its own complete adapter capability. */
    static List<String> alternateEntryOcrCapabilityErrors(
            JSONObject sourceProfile, JSONObject targetProfile, BackendAdapter adapter) {
        List<String> errors = new ArrayList<>(alternateEntryCapabilityErrors(
            sourceProfile, targetProfile, adapter));
        if (adapter == null) {
            errors.add("backendAdapter");
        } else {
            errors.addAll(adapter.missingForOcr());
        }
        return immutableDistinct(errors);
    }

    /** Alternate-entry OCR retains its joint source/target authorization at the socket boundary. */
    static <T> T executeAlternateEntryOcr(
            JSONObject sourceProfile, JSONObject targetProfile, BackendAdapter adapter,
            RemoteCall<T> remoteCall) throws Exception {
        return execute(alternateEntryOcrCapabilityErrors(
            sourceProfile, targetProfile, adapter), remoteCall);
    }

    /** Printing/reprint requires an explicitly reviewed enabled policy and full print adapter. */
    static List<String> printingCapabilityErrors(
            ProfileWorkflow workflow, BackendAdapter adapter) {
        List<String> errors = new ArrayList<>();
        if (workflow == null || !workflow.operationalPoliciesExplicit) {
            errors.add("profile.workflow.compatibilityReviewed");
        }
        if (workflow == null || !workflow.printingEnabled) {
            errors.add("profile.workflow.printing.enabled");
        }
        if (adapter == null) {
            errors.add("backendAdapter");
        } else {
            if (!adapter.isSupported()) errors.add("backendAdapter");
            errors.addAll(adapter.missingForSubmit(false, false, false, true));
        }
        return immutableDistinct(errors);
    }

    private static boolean operationalPoliciesExplicit(JSONObject profile) {
        return profile != null && ProfileWorkflow.from(profile).operationalPoliciesExplicit;
    }

    private static <T> T execute(List<String> errors, RemoteCall<T> remoteCall)
            throws Exception {
        if (errors != null && !errors.isEmpty()) {
            throw new BackendAdapter.ConfigurationException(errors.get(0));
        }
        if (remoteCall == null) throw new IllegalArgumentException("remoteCall");
        return remoteCall.run();
    }

    private static List<String> immutableDistinct(List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>(values);
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }
}
