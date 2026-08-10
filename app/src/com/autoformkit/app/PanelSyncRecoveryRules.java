package com.autoformkit.app;

/** Pure fail-closed policy for the hidden obsolete-draft Panel recovery action. */
final class PanelSyncRecoveryRules {
    private PanelSyncRecoveryRules() {}

    static boolean canDiscardObsoleteLocalDraft(
            boolean settingsPageOpen,
            boolean exactConnection,
            boolean recoveryBlocked,
            boolean completeActivePairBytes,
            boolean activePairValid,
            int activeConfigVersion,
            int activeCatalogVersion,
            boolean completeValidCandidatePair,
            int candidateConfigVersion,
            int candidateCatalogVersion,
            int unsubmittedDraftCount,
            boolean otherSafetyBlocker) {
        int activeVersion = Math.max(activeConfigVersion, activeCatalogVersion);
        return settingsPageOpen
            && exactConnection
            && !recoveryBlocked
            && completeActivePairBytes
            && !activePairValid
            && completeValidCandidatePair
            && candidateConfigVersion > activeVersion
            && candidateConfigVersion == candidateCatalogVersion
            && unsubmittedDraftCount > 0
            && !otherSafetyBlocker;
    }
}
