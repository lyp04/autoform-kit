package com.autoformkit.app;

/** Exact, side-effect-free linkage rules for retiring a completed upload replay barrier. */
final class UploadReplayRecoveryRules {
    private UploadReplayRecoveryRules() {}

    static boolean completedMain(
            UploadReplayBarrier.Identity barrier,
            AlternateSubmissionAttempt attempt) {
        return barrier != null
            && barrier.flow == UploadReplayBarrier.Flow.MAIN
            && attempt != null
            && attempt.state == AlternateSubmissionAttempt.State.COMPLETED
            && barrier.connectionNamespace.equals(attempt.key.connectionNamespace)
            && barrier.profileId.equals(attempt.key.target.profileId)
            && barrier.bindingFingerprintSha256.equals(attempt.key.bindingFingerprint)
            && barrier.operationId.equals(attempt.key.operationId);
    }

    static boolean completedAlternate(
            UploadReplayBarrier.Identity barrier,
            AlternateSubmissionAttempt attempt,
            AlternateEntryDraftState durableDraft,
            String durableSourceSnapshotSha256) {
        return barrier != null
            && barrier.flow == UploadReplayBarrier.Flow.ALTERNATE
            && attempt != null
            && attempt.state == AlternateSubmissionAttempt.State.COMPLETED
            && durableDraft != null
            && durableSourceSnapshotSha256 != null
            && barrier.connectionNamespace.equals(attempt.key.connectionNamespace)
            && barrier.connectionNamespace.equals(durableDraft.connectionNamespace)
            && barrier.bindingFingerprintSha256.equals(attempt.key.bindingFingerprint)
            && barrier.bindingFingerprintSha256.equals(durableDraft.bindingFingerprint)
            && barrier.backendFingerprintSha256.equals(durableDraft.backendFingerprint)
            && barrier.sourceProfileId.equals(durableDraft.sourceProfileId)
            && barrier.targetProfileId.equals(attempt.key.target.profileId)
            && barrier.sourceSnapshotSha256.equals(attempt.key.sourceSnapshotSha256)
            && barrier.sourceSnapshotSha256.equals(durableSourceSnapshotSha256)
            && attempt.key.serial.equals(durableDraft.serial)
            && barrier.operationId.equals(attempt.key.operationId);
    }
}
