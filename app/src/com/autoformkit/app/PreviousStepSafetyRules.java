package com.autoformkit.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Pure fail-closed rules shared by previous-step lookup and side-effect call sites. */
final class PreviousStepSafetyRules {
    static final class CandidateLookupTimeoutException extends IOException {
        CandidateLookupTimeoutException() {
            super("previous-step candidate lookup timed out");
        }
    }

    static final class CandidateOutcome {
        final boolean found;
        final String target;

        private CandidateOutcome(boolean found, String target) {
            this.found = found;
            this.target = target == null ? "" : target;
        }

        static CandidateOutcome found(String target) {
            if (target == null || target.isEmpty()) {
                throw new IllegalArgumentException("found target is required");
            }
            return new CandidateOutcome(true, target);
        }

        static CandidateOutcome missing() {
            return new CandidateOutcome(false, "");
        }
    }

    private PreviousStepSafetyRules() {}

    /**
     * Futures may execute concurrently, but their decisions are consumed strictly in Panel order.
     * A timeout is distinct from all candidates being explicitly missing, so callers cannot
     * accidentally apply a missing-record policy to an indeterminate lookup.
     */
    static CandidateOutcome awaitFirstFoundInPanelOrder(
            List<? extends Future<CandidateOutcome>> futures, long timeoutMs)
            throws Exception {
        if (futures == null || futures.isEmpty()) return null;
        if (timeoutMs <= 0L) throw new CandidateLookupTimeoutException();
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long started = System.nanoTime();
        for (Future<CandidateOutcome> future : futures) {
            long elapsed = System.nanoTime() - started;
            long remaining = budgetNanos - elapsed;
            if (remaining <= 0L) throw new CandidateLookupTimeoutException();
            final CandidateOutcome outcome;
            try {
                outcome = future.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException timeout) {
                throw new CandidateLookupTimeoutException();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new IOException("previous-step candidate lookup failed", cause);
            }
            if (outcome == null) {
                throw new IOException("previous-step candidate lookup returned no decision");
            }
            if (outcome.found) return outcome;
        }
        return null;
    }

    static List<String> sideEffectCapabilityErrors(
            ProfileWorkflow workflow, BackendAdapter adapter) {
        List<String> errors = new ArrayList<>();
        if (workflow == null || !workflow.previousStepsEnabled
                || !workflow.operationalPoliciesExplicit) {
            errors.add("profile.workflow.compatibilityReviewed");
        }
        if (adapter == null || !adapter.isSupported()) {
            errors.add("backendAdapter");
        } else {
            errors.addAll(adapter.missingForSubmit(true, false, false, false));
            if (workflow != null) {
                errors.addAll(adapter.missingForDynamicPreviousSteps(workflow));
            }
            if (adapter.baseUrl.isEmpty()) errors.add("backendAdapter.baseUrl");
        }
        return immutableDistinct(errors);
    }

    static List<String> lookupCapabilityErrors(
            ProfileWorkflow workflow, BackendAdapter adapter) {
        List<String> errors = new ArrayList<>();
        if (workflow == null || !workflow.previousStepsEnabled
                || !workflow.operationalPoliciesExplicit) {
            errors.add("profile.workflow.compatibilityReviewed");
        }
        if (adapter == null || !adapter.isSupported()) {
            errors.add("backendAdapter");
        } else {
            errors.addAll(adapter.missingForPreviousStepLookup());
            if (adapter.baseUrl.isEmpty()) errors.add("backendAdapter.baseUrl");
        }
        return immutableDistinct(errors);
    }

    private static List<String> immutableDistinct(List<String> values) {
        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>(values);
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }
}
