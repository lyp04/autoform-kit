package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.EnumSet;

public class ControlledRecoveryRulesTest {
    private static final String CONNECTION = "0123456789abcdefabcd";
    private static final String A = repeat('a', 64);
    private static final String B = repeat('b', 64);
    private static final String C = repeat('c', 64);
    private static final String D = repeat('d', 64);
    private static final String E = repeat('e', 64);
    private static final String F = repeat('f', 64);
    private static final String CHALLENGE =
        "sample_device_challenge_000000000001";
    private static final long NOW = 2_000_000_000L;

    private static KeyPair signingKey;
    private static ControlledRecoveryRules.Capability capability;

    @BeforeClass
    public static void createSigningCapability() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        signingKey = generator.generateKeyPair();
        capability = ControlledRecoveryRules.Capability.of(
            "sample-recovery-key-1",
            ControlledRecoveryRules.hex(signingKey.getPublic().getEncoded()),
            300, A, EnumSet.allOf(ControlledRecoveryRules.Operation.class));
    }

    @Test
    public void finalEvidenceIsSignedAndBoundToEveryExactJournalContext() throws Exception {
        AlternateSubmissionAttempt uncertain = finalUncertain("operation-final-0001", B);
        ControlledRecoveryRules.Subject subject =
            ControlledRecoveryRules.Subject.finalSubmission(
                uncertain, 17, C, D, capability);
        ControlledRecoveryRules.Evidence evidence = signedEvidence(
            subject, ControlledRecoveryRules.Outcome.WRITTEN, NOW - 5L, NOW + 60L);

        ControlledRecoveryRules.Verification verified = ControlledRecoveryRules.verify(
            subject, capability, CHALLENGE, evidence, NOW);
        assertEquals(ControlledRecoveryRules.Decision.FINAL_CONFIRM_WRITTEN,
            verified.decision);
        AlternateSubmissionAttempt completed =
            ControlledRecoveryRules.applyFinalConfirmation(
                uncertain, subject, verified);
        assertEquals(AlternateSubmissionAttempt.State.COMPLETED, completed.state);

        ControlledRecoveryRules.Subject changedPayload =
            ControlledRecoveryRules.Subject.finalSubmission(
                finalUncertain("operation-final-0001", E), 17, C, D, capability);
        ControlledRecoveryRules.Subject changedPair =
            ControlledRecoveryRules.Subject.finalSubmission(
                uncertain, 17, E, D, capability);
        ControlledRecoveryRules.Subject changedBackend =
            ControlledRecoveryRules.Subject.finalSubmission(
                uncertain, 17, C, E, capability);
        assertNotEquals(subject.subjectSha256, changedPayload.subjectSha256);
        assertNotEquals(subject.subjectSha256, changedPair.subjectSha256);
        assertNotEquals(subject.subjectSha256, changedBackend.subjectSha256);
        assertRejected("binding does not match", () ->
            ControlledRecoveryRules.verify(
                changedPayload, capability, CHALLENGE, evidence, NOW));
    }

    @Test
    public void signedAbsenceAllowsOneNormalJournalRetryTransition() throws Exception {
        AlternateSubmissionAttempt uncertain = finalUncertain("operation-final-0002", B);
        ControlledRecoveryRules.Subject subject =
            ControlledRecoveryRules.Subject.finalSubmission(
                uncertain, 17, C, D, capability);
        ControlledRecoveryRules.Verification verified = ControlledRecoveryRules.verify(
            subject, capability, CHALLENGE,
            signedEvidence(subject, ControlledRecoveryRules.Outcome.NOT_WRITTEN,
                NOW - 5L, NOW + 60L), NOW);

        AlternateSubmissionAttempt absent =
            ControlledRecoveryRules.applyFinalConfirmation(
                uncertain, subject, verified);
        assertEquals(AlternateSubmissionAttempt.State.CONFIRMED_NOT_WRITTEN,
            absent.state);
        assertEquals(AlternateSubmissionAttempt.State.POSTING,
            absent.beginPosting(absent.key).state);
    }

    @Test
    public void previousStepEvidenceBindsChainRecipeAttemptAndPayload() throws Exception {
        PreviousStepSubmissionAttempt uncertain = previousUncertain(B, 1);
        ControlledRecoveryRules.Subject subject =
            ControlledRecoveryRules.Subject.previousStep(
                uncertain, C, D, capability);
        ControlledRecoveryRules.Verification written = ControlledRecoveryRules.verify(
            subject, capability, CHALLENGE,
            signedEvidence(subject, ControlledRecoveryRules.Outcome.WRITTEN,
                NOW - 5L, NOW + 60L), NOW);

        PreviousStepSubmissionAttempt completed =
            ControlledRecoveryRules.applyPreviousStepConfirmation(
                uncertain, subject, written);
        assertEquals(PreviousStepSubmissionAttempt.State.COMPLETED, completed.state);
        assertEquals(1, completed.completedRecipeCount());

        ControlledRecoveryRules.Subject changedPayload =
            ControlledRecoveryRules.Subject.previousStep(
                previousUncertain(E, 1), C, D, capability);
        ControlledRecoveryRules.Subject changedAttempt =
            ControlledRecoveryRules.Subject.previousStep(
                previousUncertain(B, 2), C, D, capability);
        assertNotEquals(subject.subjectSha256, changedPayload.subjectSha256);
        assertNotEquals(subject.subjectSha256, changedAttempt.subjectSha256);
    }

    @Test
    public void previousStepSignedAbsenceUsesMonotonicRetryState() throws Exception {
        PreviousStepSubmissionAttempt uncertain = previousUncertain(B, 1);
        ControlledRecoveryRules.Subject subject =
            ControlledRecoveryRules.Subject.previousStep(
                uncertain, C, D, capability);
        ControlledRecoveryRules.Verification absent = ControlledRecoveryRules.verify(
            subject, capability, CHALLENGE,
            signedEvidence(subject, ControlledRecoveryRules.Outcome.NOT_WRITTEN,
                NOW - 5L, NOW + 60L), NOW);

        PreviousStepSubmissionAttempt rejected =
            ControlledRecoveryRules.applyPreviousStepConfirmation(
                uncertain, subject, absent);
        assertEquals(PreviousStepSubmissionAttempt.State.EXPLICITLY_REJECTED,
            rejected.state);
    }

    @Test
    public void uploadOnlyClearsWhenTheWholeBatchIsProvenEmpty() throws Exception {
        UploadReplayBarrier barrier = uploadBarrier();
        ControlledRecoveryRules.Subject subject =
            ControlledRecoveryRules.Subject.multipartUpload(barrier, capability);

        ControlledRecoveryRules.Verification empty = ControlledRecoveryRules.verify(
            subject, capability, CHALLENGE,
            signedEvidence(subject, ControlledRecoveryRules.Outcome.NOT_WRITTEN,
                NOW - 5L, NOW + 60L), NOW);
        assertTrue(ControlledRecoveryRules.uploadBarrierMayBeClearedForRetry(
            barrier, subject, empty));

        ControlledRecoveryRules.Verification partial = ControlledRecoveryRules.verify(
            subject, capability, CHALLENGE,
            signedEvidence(subject, ControlledRecoveryRules.Outcome.PARTIAL,
                NOW - 5L, NOW + 60L), NOW);
        assertEquals(ControlledRecoveryRules.Decision.UPLOAD_PARTIAL_KEEP_LOCKED,
            partial.decision);
        assertFalse(ControlledRecoveryRules.uploadBarrierMayBeClearedForRetry(
            barrier, subject, partial));

        ControlledRecoveryRules.Verification complete = ControlledRecoveryRules.verify(
            subject, capability, CHALLENGE,
            signedEvidence(subject, ControlledRecoveryRules.Outcome.COMPLETE,
                NOW - 5L, NOW + 60L), NOW);
        assertEquals(ControlledRecoveryRules.Decision.UPLOAD_COMPLETE_KEEP_LOCKED,
            complete.decision);
        assertFalse(ControlledRecoveryRules.uploadBarrierMayBeClearedForRetry(
            barrier, subject, complete));
    }

    @Test
    public void requestAndEvidenceExposeOnlyOpaqueHashesAndReasonCodes() throws Exception {
        AlternateSubmissionAttempt uncertain = finalUncertain("operation-final-0003", B);
        ControlledRecoveryRules.Subject subject =
            ControlledRecoveryRules.Subject.finalSubmission(
                uncertain, 17, C, D, capability);
        String request = subject.request(CHALLENGE).toString();
        String evidence = signedEvidence(
            subject, ControlledRecoveryRules.Outcome.WRITTEN,
            NOW - 5L, NOW + 60L).toJson().toString();
        String exposed = (request + evidence).toLowerCase(java.util.Locale.US);

        assertFalse(exposed.contains("sample-profile"));
        assertFalse(exposed.contains("sample-serial"));
        assertFalse(exposed.contains("sample-sku"));
        assertFalse(exposed.contains("templateid"));
        assertFalse(exposed.contains("warehouseid"));
        assertFalse(exposed.contains("payload"));
        assertFalse(exposed.contains("token"));
        assertTrue(exposed.contains("subjectsha256"));
        assertTrue(exposed.contains("observationsha256"));
        assertTrue(exposed.contains("servercorrelationsha256"));
        assertTrue(exposed.contains("remotereceiptsha256"));
        assertTrue(exposed.contains("journalpaircrossproofsha256"));
    }

    @Test
    public void unsignedEditedStaleOrWrongChallengeEvidenceNeverUnlocks() throws Exception {
        AlternateSubmissionAttempt uncertain = finalUncertain("operation-final-0004", B);
        ControlledRecoveryRules.Subject subject =
            ControlledRecoveryRules.Subject.finalSubmission(
                uncertain, 17, C, D, capability);
        ControlledRecoveryRules.Evidence valid = signedEvidence(
            subject, ControlledRecoveryRules.Outcome.WRITTEN,
            NOW - 5L, NOW + 60L);

        assertRejected("binding does not match", () ->
            ControlledRecoveryRules.verify(subject, capability,
                "different_device_challenge_00000001", valid, NOW));
        ControlledRecoveryRules.Evidence expired = signedEvidence(
            subject, ControlledRecoveryRules.Outcome.WRITTEN,
            NOW - 200L, NOW - 1L);
        assertRejected("time window", () ->
            ControlledRecoveryRules.verify(
                subject, capability, CHALLENGE, expired, NOW));
        ControlledRecoveryRules.Evidence tooLong = signedEvidence(
            subject, ControlledRecoveryRules.Outcome.WRITTEN,
            NOW - 1L, NOW + 301L);
        assertRejected("time window", () ->
            ControlledRecoveryRules.verify(
                subject, capability, CHALLENGE, tooLong, NOW));

        String changed = valid.signatureHex.substring(0, valid.signatureHex.length() - 2)
            + (valid.signatureHex.endsWith("00") ? "01" : "00");
        ControlledRecoveryRules.Evidence forged = copyWithSignature(valid, changed);
        assertRejected("signature is invalid", () ->
            ControlledRecoveryRules.verify(
                subject, capability, CHALLENGE, forged, NOW));

        ControlledRecoveryRules.Evidence editedReceipt =
            ControlledRecoveryRules.Evidence.of(
                valid.operation, valid.subjectSha256, valid.capabilitySha256,
                valid.challengeNonce, valid.outcome, valid.evidenceId, valid.keyId,
                valid.issuedAtEpochSeconds, valid.expiresAtEpochSeconds,
                valid.observationSha256, valid.serverCorrelationSha256, E,
                valid.journalPairCrossProofSha256, valid.reviewerSha256,
                valid.signatureHex);
        assertRejected("signature is invalid", () ->
            ControlledRecoveryRules.verify(
                subject, capability, CHALLENGE, editedReceipt, NOW));
    }

    @Test
    public void aDifferentPanelKeyOrReconciliationContractCannotReuseEvidence()
            throws Exception {
        AlternateSubmissionAttempt uncertain = finalUncertain("operation-final-0005", B);
        ControlledRecoveryRules.Subject subject =
            ControlledRecoveryRules.Subject.finalSubmission(
                uncertain, 17, C, D, capability);
        ControlledRecoveryRules.Evidence evidence = signedEvidence(
            subject, ControlledRecoveryRules.Outcome.WRITTEN,
            NOW - 5L, NOW + 60L);

        ControlledRecoveryRules.Capability changedContract =
            ControlledRecoveryRules.Capability.of(
                capability.keyId, capability.publicKeySpkiHex,
                capability.maxEvidenceAgeSeconds, E,
                capability.enabledOperations);
        assertNotEquals(capability.sha256, changedContract.sha256);
        assertRejected("binding does not match", () ->
            ControlledRecoveryRules.verify(
                subject, changedContract, CHALLENGE, evidence, NOW));

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair other = generator.generateKeyPair();
        ControlledRecoveryRules.Capability changedKey =
            ControlledRecoveryRules.Capability.of(
                "sample-recovery-key-2",
                ControlledRecoveryRules.hex(other.getPublic().getEncoded()),
                300, A, capability.enabledOperations);
        assertRejected("binding does not match", () ->
            ControlledRecoveryRules.verify(
                subject, changedKey, CHALLENGE, evidence, NOW));
    }

    @Test
    public void legacyOrNonUncertainStateCannotMintARecoverySubject() {
        AlternateSubmissionAttempt prepared = AlternateSubmissionAttempt.prepare(
            finalKey("operation-legacy-0001", B),
            AlternateSubmissionAttempt.restore(null));
        assertRejected("exact UNCERTAIN", () ->
            ControlledRecoveryRules.Subject.finalSubmission(
                prepared, 17, C, D, capability));

        PreviousStepSubmissionAttempt previous = PreviousStepSubmissionAttempt.prepare(
            previousKey(B, 1), PreviousStepSubmissionAttempt.restore(null));
        assertRejected("exact UNCERTAIN", () ->
            ControlledRecoveryRules.Subject.previousStep(
                previous, C, D, capability));

        UploadReplayBarrier.RestoreResult legacyUnknown =
            UploadReplayBarrier.restore("{\"schemaVersion\":0}");
        assertEquals(UploadReplayBarrier.RestoreKind.LOCKED, legacyUnknown.kind);
        assertRejected("exact STARTED", () ->
            ControlledRecoveryRules.Subject.multipartUpload(
                legacyUnknown.barrier, capability));

        AlternateSubmissionAttempt.RestoreResult legacyFinal =
            AlternateSubmissionAttempt.restore("{\"schemaVersion\":0}");
        assertEquals(AlternateSubmissionAttempt.RestoreKind.LOCKED, legacyFinal.kind);
        assertRejected("exact UNCERTAIN", () ->
            ControlledRecoveryRules.Subject.finalSubmission(
                legacyFinal.attempt, 17, C, D, capability));

        PreviousStepSubmissionAttempt.RestoreResult legacyPrevious =
            PreviousStepSubmissionAttempt.restore("{\"schemaVersion\":0}");
        assertEquals(PreviousStepSubmissionAttempt.RestoreKind.LOCKED,
            legacyPrevious.kind);
        assertRejected("exact UNCERTAIN", () ->
            ControlledRecoveryRules.Subject.previousStep(
                legacyPrevious.attempt, C, D, capability));
    }

    @Test
    public void evidenceSchemaIsExactAndOutcomeCannotCrossOperationKinds() throws Exception {
        ControlledRecoveryRules.Subject subject =
            ControlledRecoveryRules.Subject.finalSubmission(
                finalUncertain("operation-final-0006", B), 17, C, D, capability);
        ControlledRecoveryRules.Evidence valid = signedEvidence(
            subject, ControlledRecoveryRules.Outcome.WRITTEN,
            NOW - 5L, NOW + 60L);
        assertEquals(valid.evidenceId,
            ControlledRecoveryRules.Evidence.parse(valid.toJson().toString()).evidenceId);

        JSONObject extra = valid.toJson().put("operatorResult", "NOT_WRITTEN");
        assertRejected("evidence fields", () ->
            ControlledRecoveryRules.Evidence.parse(extra.toString()));
        assertRejected("outcome is invalid", () ->
            ControlledRecoveryRules.Evidence.of(
                ControlledRecoveryRules.Operation.FINAL_SUBMISSION,
                subject.subjectSha256, subject.capabilitySha256, CHALLENGE,
                ControlledRecoveryRules.Outcome.PARTIAL, "sample-evidence-invalid",
                capability.keyId, NOW - 5L, NOW + 60L,
                E, A, B, C, F, "00"));
    }

    @Test
    public void capabilityRejectsMissingModesRandomBytesAndWeakLifetimes() {
        assertRejected("enabledOperations is required", () ->
            ControlledRecoveryRules.Capability.of(
                "sample-key", capability.publicKeySpkiHex, 300, A,
                EnumSet.noneOf(ControlledRecoveryRules.Operation.class)));
        assertRejected("public key is invalid", () ->
            ControlledRecoveryRules.Capability.of(
                "sample-key", repeat('a', 600), 300, A,
                EnumSet.of(ControlledRecoveryRules.Operation.FINAL_SUBMISSION)));
        assertRejected("out of range", () ->
            ControlledRecoveryRules.Capability.of(
                "sample-key", capability.publicKeySpkiHex, 3601, A,
                EnumSet.of(ControlledRecoveryRules.Operation.FINAL_SUBMISSION)));
    }

    private static AlternateSubmissionAttempt finalUncertain(
            String operation, String payloadSha256) {
        AlternateSubmissionAttempt.Key key = finalKey(operation, payloadSha256);
        return AlternateSubmissionAttempt.prepare(
            key, AlternateSubmissionAttempt.restore(null))
            .beginPosting(key).markUncertain(key);
    }

    private static AlternateSubmissionAttempt.Key finalKey(
            String operation, String payloadSha256) {
        return AlternateSubmissionAttempt.Key.of(
            CONNECTION, A,
            AlternateSubmissionAttempt.TargetIdentity.of(
                "sample-profile", 41, 7, "SAMPLE-SKU"),
            "SAMPLE-SERIAL", E, payloadSha256, operation);
    }

    private static PreviousStepSubmissionAttempt previousUncertain(
            String payloadSha256, int attemptNumber) {
        PreviousStepSubmissionAttempt.Key key = previousKey(payloadSha256, attemptNumber);
        return PreviousStepSubmissionAttempt.prepare(
            key, PreviousStepSubmissionAttempt.restore(null))
            .beginPosting(key).markUncertain(key);
    }

    private static PreviousStepSubmissionAttempt.Key previousKey(
            String payloadSha256, int attemptNumber) {
        PreviousStepSubmissionAttempt.ChainIdentity chain =
            PreviousStepSubmissionAttempt.ChainIdentity.of(
                CONNECTION, 17, "sample-profile", A, 3, "SAMPLE-SERIAL",
                B, C, D, 2);
        return PreviousStepSubmissionAttempt.Key.of(chain, 0,
            PreviousStepSubmissionAttempt.RecipeIdentity.of(
                1, 0, PreviousStepSubmissionAttempt.RecipeKind.STATIC, E),
            payloadSha256, attemptNumber, "operation-previous-0001");
    }

    private static UploadReplayBarrier uploadBarrier() {
        UploadReplayBarrier.Identity identity = UploadReplayBarrier.Identity.main(
            CONNECTION, 17, "sample-profile", A, B, C, D, E,
            "operation-upload-0001");
        return UploadReplayBarrier.prepare(
            identity, UploadReplayBarrier.restore(null));
    }

    private static ControlledRecoveryRules.Evidence signedEvidence(
            ControlledRecoveryRules.Subject subject,
            ControlledRecoveryRules.Outcome outcome,
            long issuedAt, long expiresAt) throws Exception {
        ControlledRecoveryRules.Evidence unsigned = ControlledRecoveryRules.Evidence.of(
            subject.operation, subject.subjectSha256, subject.capabilitySha256,
            CHALLENGE, outcome, "sample-evidence-0001", capability.keyId,
            issuedAt, expiresAt, E, A, B, C, F, "00");
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(signingKey.getPrivate());
        signer.update(unsigned.signedBytes());
        return copyWithSignature(unsigned,
            ControlledRecoveryRules.hex(signer.sign()));
    }

    private static ControlledRecoveryRules.Evidence copyWithSignature(
            ControlledRecoveryRules.Evidence source, String signatureHex) {
        return ControlledRecoveryRules.Evidence.of(
            source.operation, source.subjectSha256, source.capabilitySha256,
            source.challengeNonce, source.outcome, source.evidenceId, source.keyId,
            source.issuedAtEpochSeconds, source.expiresAtEpochSeconds,
            source.observationSha256, source.serverCorrelationSha256,
            source.remoteReceiptSha256, source.journalPairCrossProofSha256,
            source.reviewerSha256, signatureHex);
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int index = 0; index < count; index++) out.append(value);
        return out.toString();
    }

    private static void assertRejected(String expected, ThrowingRunnable action) {
        try {
            action.run();
            throw new AssertionError("operation must fail closed");
        } catch (IllegalArgumentException error) {
            assertTrue(error.getMessage(), error.getMessage().contains(expected));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
