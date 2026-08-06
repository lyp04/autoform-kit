package com.autoformkit.app;

import java.util.Collection;

/** Pure fail-closed rules for abandoning a side-effect-free independent-entry scan. */
final class AlternateEntryScanRecoveryRules {
    private AlternateEntryScanRecoveryRules() {}

    static final class Binding {
        final String sourceProfileId;
        final String entryId;
        final String bindingFingerprint;

        Binding(String sourceProfileId, String entryId, String bindingFingerprint) {
            this.sourceProfileId = safe(sourceProfileId);
            this.entryId = safe(entryId);
            this.bindingFingerprint = safe(bindingFingerprint);
        }
    }

    /**
     * Permits the cold-start fallback only when no form data could be lost and the reservation's
     * immutable binding still resolves exactly once in the active catalog for the open entry.
     *
     * <p>The base-state hash cannot be reconstructed after a cold start when the old page contained
     * only a source selection or toggle changes: those controls are not a submit-ready draft. The
     * scan itself has no remote or file side effect, so the exact stored reservation may still be
     * abandoned without restoring those ephemeral controls. A durable draft/proof, any pending form
     * data, any photo evidence, or a continuation token disables this fallback.</p>
     */
    static boolean canCancelSideEffectFreeScan(
            AlternateEntryAsyncReservation reservation,
            String accountFingerprint, String connectionNamespace, int catalogVersion,
            String panelPairSha256, String backendFingerprint, String storedGuard,
            String currentEntryId, Collection<Binding> activeBindings,
            boolean durableDraftOrProofPresent, boolean pendingDataPresent,
            boolean photoEvidencePresent, boolean continuationTokenPresent) {
        if (reservation == null || durableDraftOrProofPresent || pendingDataPresent
                || photoEvidencePresent || continuationTokenPresent) return false;
        String entryId = safe(currentEntryId);
        if (entryId.isEmpty() || activeBindings == null) return false;

        // Reuse the strict reservation matcher for every persisted/global identity field. Its own
        // binding/base hashes are supplied here because catalog uniqueness below proves the former,
        // while this fallback deliberately applies only when the latter was ephemeral and empty.
        if (!reservation.matches(AlternateEntryAsyncReservation.KIND_SCAN,
                accountFingerprint, connectionNamespace, catalogVersion,
                panelPairSha256, reservation.bindingFingerprint, backendFingerprint,
                storedGuard, reservation.baseStateSha256, "")) {
            return false;
        }

        int matches = 0;
        for (Binding binding : activeBindings) {
            if (binding == null || binding.sourceProfileId.isEmpty()
                    || !entryId.equals(binding.entryId)
                    || !reservation.bindingFingerprint.equals(
                        binding.bindingFingerprint)) continue;
            matches++;
        }
        return matches == 1;
    }

    /** Compare both independently allocated tokens before deleting the re-read stored pair. */
    static boolean sameReservation(AlternateEntryAsyncReservation expected,
                                   AlternateEntryAsyncReservation exact) {
        return expected != null && exact != null
            && exact.reservationToken.equals(expected.reservationToken)
            && exact.resultContinuationToken.equals(expected.resultContinuationToken);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
