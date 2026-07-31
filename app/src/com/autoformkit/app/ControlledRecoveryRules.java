package com.autoformkit.app;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Pure contract for applying Panel-authorized evidence to an uncertain remote side effect.
 *
 * <p>The public App never decides a production outcome from an operator button, a serial number,
 * a backend message, or an editable JSON field. A trusted reconciliation authority must sign an
 * evidence envelope. The signature is bound to a one-time device challenge, the exact private
 * reconciliation contract, a SHA-256 subject derived from every immutable local journal field,
 * and separate digests of the server correlation, authoritative remote receipt, and exact
 * journal-to-Panel-pair cross-proof. The request/evidence envelope contains only hashes and
 * non-sensitive reason codes.
 *
 * <p>This class is not an operator recovery tool. It performs no network call, does not persist or
 * consume a challenge/evidence id, does not prove that separately supplied Panel context belongs to
 * a journal, and clears no storage. A caller must first restore the authoritative journal/barrier,
 * prove its exact original Panel/catalog/backend context, build the exact {@link Subject}, obtain an
 * authority result correlated to the original server-side request/receipt, verify the signed
 * evidence, then synchronously persist the resulting journal transition before it consumes the
 * challenge/evidence id or removes any recovery request. A failed commit or process restart must
 * retain the original lock.
 */
final class ControlledRecoveryRules {
    static final int CAPABILITY_VERSION = 1;
    static final int SUBJECT_VERSION = 1;
    static final int EVIDENCE_VERSION = 1;
    static final String SIGNATURE_ALGORITHM = "RS256";

    private static final long MAX_EVIDENCE_AGE_SECONDS = 3600L;
    private static final long MAX_FUTURE_CLOCK_SKEW_SECONDS = 300L;
    private static final int MIN_RSA_SPKI_BYTES = 256;
    private static final int MAX_RSA_SPKI_BYTES = 4096;

    private static final Set<String> EVIDENCE_KEYS = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(
            "schemaVersion", "subjectVersion", "operation", "subjectSha256",
            "capabilitySha256", "challengeNonce", "outcome", "evidenceId", "keyId",
            "issuedAtEpochSeconds", "expiresAtEpochSeconds", "observationSha256",
            "serverCorrelationSha256", "remoteReceiptSha256",
            "journalPairCrossProofSha256", "reviewerSha256", "signatureHex")));

    enum Operation {
        FINAL_SUBMISSION,
        PREVIOUS_STEP_RECIPE,
        MULTIPART_UPLOAD
    }

    enum Outcome {
        WRITTEN,
        NOT_WRITTEN,
        PARTIAL,
        COMPLETE
    }

    enum Decision {
        FINAL_CONFIRM_WRITTEN,
        FINAL_CONFIRM_NOT_WRITTEN,
        PREVIOUS_STEP_CONFIRM_WRITTEN,
        PREVIOUS_STEP_CONFIRM_NOT_WRITTEN,
        UPLOAD_CONFIRMED_EMPTY_CLEAR_FOR_RETRY,
        UPLOAD_PARTIAL_KEEP_LOCKED,
        UPLOAD_COMPLETE_KEEP_LOCKED
    }

    /** App-facing, non-secret verification half of the private Panel recovery capability. */
    static final class Capability {
        final String keyId;
        final String publicKeySpkiHex;
        final int maxEvidenceAgeSeconds;
        final String reconciliationContractSha256;
        final Set<Operation> enabledOperations;
        final String sha256;

        private Capability(String keyId, String publicKeySpkiHex,
                           int maxEvidenceAgeSeconds,
                           String reconciliationContractSha256,
                           Set<Operation> enabledOperations) {
            this.keyId = requiredId(keyId, "keyId", 128);
            this.publicKeySpkiHex = requiredHex(
                publicKeySpkiHex, "publicKeySpkiHex",
                MIN_RSA_SPKI_BYTES * 2, MAX_RSA_SPKI_BYTES * 2);
            if (maxEvidenceAgeSeconds <= 0
                    || maxEvidenceAgeSeconds > MAX_EVIDENCE_AGE_SECONDS) {
                throw invalid("maxEvidenceAgeSeconds is out of range");
            }
            this.maxEvidenceAgeSeconds = maxEvidenceAgeSeconds;
            this.reconciliationContractSha256 = requiredSha256(
                reconciliationContractSha256, "reconciliationContractSha256");
            if (enabledOperations == null || enabledOperations.isEmpty()) {
                throw invalid("enabledOperations is required");
            }
            this.enabledOperations = Collections.unmodifiableSet(
                EnumSet.copyOf(enabledOperations));
            // Reject random DER-like bytes at capability load time. Only an RSA public key can
            // authorize evidence; no caller-provided boolean can replace this check.
            decodeRsaPublicKey(this.publicKeySpkiHex);
            this.sha256 = digest(new Canonical("capability")
                .add("version", CAPABILITY_VERSION)
                .add("algorithm", SIGNATURE_ALGORITHM)
                .add("keyId", this.keyId)
                .add("publicKeySpkiHex", this.publicKeySpkiHex)
                .add("maxEvidenceAgeSeconds", this.maxEvidenceAgeSeconds)
                .add("reconciliationContractSha256", this.reconciliationContractSha256)
                .add("enabledOperations", canonicalOperations(this.enabledOperations))
                .bytes());
        }

        static Capability of(String keyId, String publicKeySpkiHex,
                             int maxEvidenceAgeSeconds,
                             String reconciliationContractSha256,
                             Set<Operation> enabledOperations) {
            return new Capability(keyId, publicKeySpkiHex, maxEvidenceAgeSeconds,
                reconciliationContractSha256, enabledOperations);
        }

        boolean supports(Operation operation) {
            return operation != null && enabledOperations.contains(operation);
        }
    }

    /** Opaque digest of one exact local uncertain journal/barrier and its active Panel pair. */
    static final class Subject {
        final Operation operation;
        final String subjectSha256;
        final String capabilitySha256;

        private Subject(Operation operation, Capability capability, Canonical identity) {
            if (operation == null || capability == null || identity == null) {
                throw invalid("operation, capability and identity are required");
            }
            if (!capability.supports(operation)) {
                throw invalid("recovery operation is not enabled by the capability");
            }
            this.operation = operation;
            this.capabilitySha256 = capability.sha256;
            this.subjectSha256 = digest(new Canonical("subject")
                .add("version", SUBJECT_VERSION)
                .add("operation", operation.name())
                .add("capabilitySha256", capability.sha256)
                .addBytes("identity", identity.bytes())
                .bytes());
        }

        /**
         * Binds a final-submit recovery to connection, catalog/pair, profile/target, draft source,
         * backend semantics, operation id and exact POST body digest.
         */
        static Subject finalSubmission(AlternateSubmissionAttempt attempt,
                                       int catalogVersion, String panelPairSha256,
                                       String backendFingerprintSha256,
                                       Capability capability) {
            if (attempt == null || attempt.state != AlternateSubmissionAttempt.State.UNCERTAIN) {
                throw invalid("final submission must be an exact UNCERTAIN journal");
            }
            if (catalogVersion <= 0) throw invalid("catalogVersion must be positive");
            AlternateSubmissionAttempt.Key key = attempt.key;
            AlternateSubmissionAttempt.TargetIdentity target = key.target;
            Canonical identity = new Canonical("final-submission")
                .add("connectionNamespace", key.connectionNamespace)
                .add("catalogVersion", catalogVersion)
                .add("panelPairSha256", requiredSha256(panelPairSha256, "panelPairSha256"))
                .add("bindingFingerprint", requiredSha256(
                    key.bindingFingerprint, "bindingFingerprint"))
                .add("backendFingerprintSha256", requiredSha256(
                    backendFingerprintSha256, "backendFingerprintSha256"))
                .add("profileId", target.profileId)
                .add("templateId", target.templateId)
                .add("warehouseId", target.warehouseId)
                .add("sku", target.sku)
                .add("serial", key.serial)
                .add("sourceSnapshotSha256", key.sourceSnapshotSha256)
                .add("payloadSha256", key.payloadSha256)
                .add("operationId", key.operationId);
            return new Subject(Operation.FINAL_SUBMISSION, capability, identity);
        }

        /**
         * Binds one previous-step recovery to the complete chain, exact recipe position/identity,
         * attempt number, dynamic semantics and exact POST body digest.
         */
        static Subject previousStep(PreviousStepSubmissionAttempt attempt,
                                    String panelPairSha256,
                                    String backendFingerprintSha256,
                                    Capability capability) {
            if (attempt == null
                    || attempt.state != PreviousStepSubmissionAttempt.State.UNCERTAIN) {
                throw invalid("previous-step submission must be an exact UNCERTAIN journal");
            }
            PreviousStepSubmissionAttempt.Key key = attempt.key;
            PreviousStepSubmissionAttempt.ChainIdentity chain = key.chain;
            PreviousStepSubmissionAttempt.RecipeIdentity recipe = key.recipe;
            Canonical identity = new Canonical("previous-step")
                .add("connectionNamespace", chain.connectionNamespace)
                .add("catalogVersion", chain.catalogVersion)
                .add("panelPairSha256", requiredSha256(panelPairSha256, "panelPairSha256"))
                .add("backendFingerprintSha256", requiredSha256(
                    backendFingerprintSha256, "backendFingerprintSha256"))
                .add("profileId", chain.profileId)
                .add("draftSemanticsSha256", chain.draftSemanticsSha256)
                .add("unitSequence", chain.unitSequence)
                .add("serial", chain.serial)
                .add("unitSnapshotSha256", chain.unitSnapshotSha256)
                .add("recipeChainSha256", chain.recipeChainSha256)
                .add("dynamicResolvedSemanticsSha256",
                    chain.dynamicResolvedSemanticsSha256)
                .add("recipeCount", chain.recipeCount)
                .add("completedRecipeCount", key.completedRecipeCount)
                .add("recipeOrder", recipe.order)
                .add("recipeSourceIndex", recipe.sourceIndex)
                .add("recipeKind", recipe.kind.name())
                .add("recipeIdentitySha256", recipe.identitySha256)
                .add("payloadSha256", key.payloadSha256)
                .add("attemptNumber", key.attemptNumber)
                .add("operationId", key.operationId);
            return new Subject(Operation.PREVIOUS_STEP_RECIPE, capability, identity);
        }

        /**
         * Binds recovery to every v1 upload-barrier identity field. This does not invent a part plan:
         * the current barrier cannot safely resume a COMPLETE or PARTIAL batch without exact URLs.
         */
        static Subject multipartUpload(UploadReplayBarrier barrier, Capability capability) {
            if (barrier == null || barrier.state != UploadReplayBarrier.State.STARTED) {
                throw invalid("multipart upload must be an exact STARTED barrier");
            }
            UploadReplayBarrier.Identity value = barrier.identity;
            Canonical identity = new Canonical("multipart-upload")
                .add("barrierSchemaVersion", UploadReplayBarrier.SCHEMA_VERSION)
                .add("flow", value.flow.name())
                .add("connectionNamespace", value.connectionNamespace)
                .add("catalogVersion", value.catalogVersion)
                .add("profileId", nullToEmpty(value.profileId))
                .add("sourceProfileId", nullToEmpty(value.sourceProfileId))
                .add("targetProfileId", nullToEmpty(value.targetProfileId))
                .add("panelPairSha256", value.panelPairSha256)
                .add("bindingFingerprintSha256", value.bindingFingerprintSha256)
                .add("backendFingerprintSha256", value.backendFingerprintSha256)
                .add("sessionFingerprintSha256", value.sessionFingerprintSha256)
                .add("sourceSnapshotSha256", value.sourceSnapshotSha256)
                .add("operationId", value.operationId);
            return new Subject(Operation.MULTIPART_UPLOAD, capability, identity);
        }

        JSONObject request(String challengeNonce) {
            try {
                return new JSONObject()
                    .put("schemaVersion", EVIDENCE_VERSION)
                    .put("subjectVersion", SUBJECT_VERSION)
                    .put("operation", operation.name())
                    .put("subjectSha256", subjectSha256)
                    .put("capabilitySha256", capabilitySha256)
                    .put("challengeNonce", requiredNonce(challengeNonce));
            } catch (Exception impossible) {
                throw invalid("cannot serialize recovery request");
            }
        }
    }

    /** Signed, hash-only evidence returned by the Panel reconciliation authority. */
    static final class Evidence {
        final Operation operation;
        final String subjectSha256;
        final String capabilitySha256;
        final String challengeNonce;
        final Outcome outcome;
        final String evidenceId;
        final String keyId;
        final long issuedAtEpochSeconds;
        final long expiresAtEpochSeconds;
        final String observationSha256;
        final String serverCorrelationSha256;
        final String remoteReceiptSha256;
        final String journalPairCrossProofSha256;
        final String reviewerSha256;
        final String signatureHex;

        private Evidence(Operation operation, String subjectSha256,
                         String capabilitySha256, String challengeNonce,
                         Outcome outcome, String evidenceId, String keyId,
                         long issuedAtEpochSeconds, long expiresAtEpochSeconds,
                         String observationSha256, String serverCorrelationSha256,
                         String remoteReceiptSha256, String journalPairCrossProofSha256,
                         String reviewerSha256,
                         String signatureHex) {
            if (operation == null || outcome == null) {
                throw invalid("evidence operation and outcome are required");
            }
            this.operation = operation;
            this.subjectSha256 = requiredSha256(subjectSha256, "subjectSha256");
            this.capabilitySha256 = requiredSha256(
                capabilitySha256, "capabilitySha256");
            this.challengeNonce = requiredNonce(challengeNonce);
            this.outcome = outcome;
            this.evidenceId = requiredId(evidenceId, "evidenceId", 128);
            this.keyId = requiredId(keyId, "keyId", 128);
            if (issuedAtEpochSeconds <= 0L || expiresAtEpochSeconds <= issuedAtEpochSeconds) {
                throw invalid("evidence time window is invalid");
            }
            this.issuedAtEpochSeconds = issuedAtEpochSeconds;
            this.expiresAtEpochSeconds = expiresAtEpochSeconds;
            this.observationSha256 = requiredSha256(
                observationSha256, "observationSha256");
            this.serverCorrelationSha256 = requiredSha256(
                serverCorrelationSha256, "serverCorrelationSha256");
            this.remoteReceiptSha256 = requiredSha256(
                remoteReceiptSha256, "remoteReceiptSha256");
            this.journalPairCrossProofSha256 = requiredSha256(
                journalPairCrossProofSha256, "journalPairCrossProofSha256");
            this.reviewerSha256 = requiredSha256(reviewerSha256, "reviewerSha256");
            this.signatureHex = requiredHex(signatureHex, "signatureHex", 2, 16384);
            requireOutcomeShape(operation, outcome);
        }

        static Evidence of(Operation operation, String subjectSha256,
                           String capabilitySha256, String challengeNonce,
                           Outcome outcome, String evidenceId, String keyId,
                           long issuedAtEpochSeconds, long expiresAtEpochSeconds,
                           String observationSha256, String serverCorrelationSha256,
                           String remoteReceiptSha256, String journalPairCrossProofSha256,
                           String reviewerSha256,
                           String signatureHex) {
            return new Evidence(operation, subjectSha256, capabilitySha256,
                challengeNonce, outcome, evidenceId, keyId, issuedAtEpochSeconds,
                expiresAtEpochSeconds, observationSha256, serverCorrelationSha256,
                remoteReceiptSha256, journalPairCrossProofSha256, reviewerSha256,
                signatureHex);
        }

        static Evidence parse(String raw) {
            try {
                JSONObject value = new JSONObject(requiredText(raw, "evidence", 65536));
                requireExactKeys(value, EVIDENCE_KEYS);
                if (exactLong(value.opt("schemaVersion")) != EVIDENCE_VERSION
                        || exactLong(value.opt("subjectVersion")) != SUBJECT_VERSION) {
                    throw invalid("evidence version is not supported");
                }
                return new Evidence(
                    Operation.valueOf(requiredJsonString(value, "operation")),
                    requiredJsonString(value, "subjectSha256"),
                    requiredJsonString(value, "capabilitySha256"),
                    requiredJsonString(value, "challengeNonce"),
                    Outcome.valueOf(requiredJsonString(value, "outcome")),
                    requiredJsonString(value, "evidenceId"),
                    requiredJsonString(value, "keyId"),
                    exactPositiveLong(value.opt("issuedAtEpochSeconds"),
                        "issuedAtEpochSeconds"),
                    exactPositiveLong(value.opt("expiresAtEpochSeconds"),
                        "expiresAtEpochSeconds"),
                    requiredJsonString(value, "observationSha256"),
                    requiredJsonString(value, "serverCorrelationSha256"),
                    requiredJsonString(value, "remoteReceiptSha256"),
                    requiredJsonString(value, "journalPairCrossProofSha256"),
                    requiredJsonString(value, "reviewerSha256"),
                    requiredJsonString(value, "signatureHex"));
            } catch (IllegalArgumentException error) {
                throw error;
            } catch (Exception error) {
                throw invalid("evidence is malformed");
            }
        }

        byte[] signedBytes() {
            return new Canonical("evidence")
                .add("schemaVersion", EVIDENCE_VERSION)
                .add("subjectVersion", SUBJECT_VERSION)
                .add("operation", operation.name())
                .add("subjectSha256", subjectSha256)
                .add("capabilitySha256", capabilitySha256)
                .add("challengeNonce", challengeNonce)
                .add("outcome", outcome.name())
                .add("evidenceId", evidenceId)
                .add("keyId", keyId)
                .add("issuedAtEpochSeconds", issuedAtEpochSeconds)
                .add("expiresAtEpochSeconds", expiresAtEpochSeconds)
                .add("observationSha256", observationSha256)
                .add("serverCorrelationSha256", serverCorrelationSha256)
                .add("remoteReceiptSha256", remoteReceiptSha256)
                .add("journalPairCrossProofSha256", journalPairCrossProofSha256)
                .add("reviewerSha256", reviewerSha256)
                .bytes();
        }

        JSONObject toJson() {
            try {
                return new JSONObject()
                    .put("schemaVersion", EVIDENCE_VERSION)
                    .put("subjectVersion", SUBJECT_VERSION)
                    .put("operation", operation.name())
                    .put("subjectSha256", subjectSha256)
                    .put("capabilitySha256", capabilitySha256)
                    .put("challengeNonce", challengeNonce)
                    .put("outcome", outcome.name())
                    .put("evidenceId", evidenceId)
                    .put("keyId", keyId)
                    .put("issuedAtEpochSeconds", issuedAtEpochSeconds)
                    .put("expiresAtEpochSeconds", expiresAtEpochSeconds)
                    .put("observationSha256", observationSha256)
                    .put("serverCorrelationSha256", serverCorrelationSha256)
                    .put("remoteReceiptSha256", remoteReceiptSha256)
                    .put("journalPairCrossProofSha256", journalPairCrossProofSha256)
                    .put("reviewerSha256", reviewerSha256)
                    .put("signatureHex", signatureHex);
            } catch (Exception impossible) {
                throw invalid("cannot serialize evidence");
            }
        }
    }

    static final class Verification {
        final Operation operation;
        final Outcome outcome;
        final Decision decision;
        final String subjectSha256;
        final String evidenceId;

        private Verification(Operation operation, Outcome outcome, Decision decision,
                             String subjectSha256, String evidenceId) {
            this.operation = operation;
            this.outcome = outcome;
            this.decision = decision;
            this.subjectSha256 = subjectSha256;
            this.evidenceId = evidenceId;
        }
    }

    private ControlledRecoveryRules() {}

    /** Verifies exact subject/challenge/capability/time/signature before returning a transition. */
    static Verification verify(Subject subject, Capability capability,
                               String expectedChallengeNonce, Evidence evidence,
                               long nowEpochSeconds) {
        if (subject == null || capability == null || evidence == null) {
            throw invalid("subject, capability and evidence are required");
        }
        String challenge = requiredNonce(expectedChallengeNonce);
        if (!capability.supports(subject.operation)
                || !subject.capabilitySha256.equals(capability.sha256)
                || evidence.operation != subject.operation
                || !evidence.subjectSha256.equals(subject.subjectSha256)
                || !evidence.capabilitySha256.equals(capability.sha256)
                || !evidence.challengeNonce.equals(challenge)
                || !evidence.keyId.equals(capability.keyId)) {
            throw invalid("evidence binding does not match the exact recovery subject");
        }
        long lifetime = evidence.expiresAtEpochSeconds - evidence.issuedAtEpochSeconds;
        if (lifetime <= 0L || lifetime > capability.maxEvidenceAgeSeconds
                || evidence.issuedAtEpochSeconds > nowEpochSeconds
                    + MAX_FUTURE_CLOCK_SKEW_SECONDS
                || nowEpochSeconds > evidence.expiresAtEpochSeconds) {
            throw invalid("evidence is outside its trusted time window");
        }
        if (!verifySignature(capability.publicKeySpkiHex,
                evidence.signedBytes(), evidence.signatureHex)) {
            throw invalid("evidence signature is invalid");
        }
        return new Verification(subject.operation, evidence.outcome,
            decision(subject.operation, evidence.outcome),
            subject.subjectSha256, evidence.evidenceId);
    }

    static AlternateSubmissionAttempt applyFinalConfirmation(
            AlternateSubmissionAttempt uncertain, Subject subject,
            Verification verification) {
        requireVerifiedSubject(Operation.FINAL_SUBMISSION, subject, verification);
        AlternateSubmissionAttempt.ConfirmationResult result;
        if (verification.decision == Decision.FINAL_CONFIRM_WRITTEN) {
            result = AlternateSubmissionAttempt.ConfirmationResult.WRITTEN;
        } else if (verification.decision == Decision.FINAL_CONFIRM_NOT_WRITTEN) {
            result = AlternateSubmissionAttempt.ConfirmationResult.NOT_WRITTEN;
        } else {
            throw invalid("verification cannot transition a final submission");
        }
        return uncertain.applyConfirmation(
            AlternateSubmissionAttempt.Confirmation.of(uncertain.key, result));
    }

    static PreviousStepSubmissionAttempt applyPreviousStepConfirmation(
            PreviousStepSubmissionAttempt uncertain, Subject subject,
            Verification verification) {
        requireVerifiedSubject(Operation.PREVIOUS_STEP_RECIPE, subject, verification);
        PreviousStepSubmissionAttempt.ConfirmationResult result;
        if (verification.decision == Decision.PREVIOUS_STEP_CONFIRM_WRITTEN) {
            result = PreviousStepSubmissionAttempt.ConfirmationResult.WRITTEN;
        } else if (verification.decision
                == Decision.PREVIOUS_STEP_CONFIRM_NOT_WRITTEN) {
            result = PreviousStepSubmissionAttempt.ConfirmationResult.NOT_WRITTEN;
        } else {
            throw invalid("verification cannot transition a previous-step submission");
        }
        return uncertain.applyConfirmation(
            PreviousStepSubmissionAttempt.Confirmation.of(uncertain.key, result));
    }

    /**
     * Only NOT_WRITTEN authorizes barrier retirement for retry. PARTIAL and COMPLETE remain locked:
     * barrier v1 has neither an exact upload-part plan nor the returned URL receipts needed to
     * rebuild the final payload without sending the wrong data.
     */
    static boolean uploadBarrierMayBeClearedForRetry(
            UploadReplayBarrier barrier, Subject subject, Verification verification) {
        if (barrier == null || barrier.state != UploadReplayBarrier.State.STARTED) {
            throw invalid("upload barrier is not STARTED");
        }
        requireVerifiedSubject(Operation.MULTIPART_UPLOAD, subject, verification);
        return verification.decision == Decision.UPLOAD_CONFIRMED_EMPTY_CLEAR_FOR_RETRY;
    }

    private static void requireVerifiedSubject(Operation expected, Subject subject,
                                               Verification verification) {
        if (subject == null || verification == null
                || subject.operation != expected
                || verification.operation != expected
                || !subject.subjectSha256.equals(verification.subjectSha256)) {
            throw invalid("verified recovery subject does not match");
        }
    }

    private static Decision decision(Operation operation, Outcome outcome) {
        requireOutcomeShape(operation, outcome);
        if (operation == Operation.FINAL_SUBMISSION) {
            return outcome == Outcome.WRITTEN
                ? Decision.FINAL_CONFIRM_WRITTEN
                : Decision.FINAL_CONFIRM_NOT_WRITTEN;
        }
        if (operation == Operation.PREVIOUS_STEP_RECIPE) {
            return outcome == Outcome.WRITTEN
                ? Decision.PREVIOUS_STEP_CONFIRM_WRITTEN
                : Decision.PREVIOUS_STEP_CONFIRM_NOT_WRITTEN;
        }
        if (outcome == Outcome.NOT_WRITTEN) {
            return Decision.UPLOAD_CONFIRMED_EMPTY_CLEAR_FOR_RETRY;
        }
        return outcome == Outcome.PARTIAL
            ? Decision.UPLOAD_PARTIAL_KEEP_LOCKED
            : Decision.UPLOAD_COMPLETE_KEEP_LOCKED;
    }

    private static void requireOutcomeShape(Operation operation, Outcome outcome) {
        boolean valid = operation == Operation.MULTIPART_UPLOAD
            ? outcome == Outcome.NOT_WRITTEN || outcome == Outcome.PARTIAL
                || outcome == Outcome.COMPLETE
            : outcome == Outcome.WRITTEN || outcome == Outcome.NOT_WRITTEN;
        if (!valid) throw invalid("outcome is invalid for operation");
    }

    private static String canonicalOperations(Set<Operation> operations) {
        StringBuilder value = new StringBuilder();
        for (Operation operation : Operation.values()) {
            if (!operations.contains(operation)) continue;
            if (value.length() > 0) value.append(',');
            value.append(operation.name());
        }
        return value.toString();
    }

    private static boolean verifySignature(String publicKeySpkiHex,
                                           byte[] signedBytes,
                                           String signatureHex) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(decodeRsaPublicKey(publicKeySpkiHex));
            verifier.update(signedBytes);
            return verifier.verify(decodeHex(signatureHex));
        } catch (Exception invalid) {
            return false;
        }
    }

    private static PublicKey decodeRsaPublicKey(String publicKeySpkiHex) {
        try {
            byte[] encoded = decodeHex(publicKeySpkiHex);
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(encoded));
            if (!"RSA".equalsIgnoreCase(key.getAlgorithm())) {
                throw invalid("recovery public key is not RSA");
            }
            return key;
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("recovery public key is invalid");
        }
    }

    static String hex(byte[] value) {
        if (value == null) throw invalid("bytes are required");
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte part : value) out.append(String.format(Locale.US, "%02x", part & 0xff));
        return out.toString();
    }

    private static byte[] decodeHex(String value) {
        if (value == null || (value.length() & 1) != 0
                || !value.matches("[0-9a-f]+")) {
            throw invalid("hex value is invalid");
        }
        byte[] out = new byte[value.length() / 2];
        for (int index = 0; index < out.length; index++) {
            out[index] = (byte) Integer.parseInt(
                value.substring(index * 2, index * 2 + 2), 16);
        }
        return out;
    }

    private static String digest(byte[] value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String requiredSha256(String value, String label) {
        String exact = requiredText(value, label, 64);
        if (!exact.matches("[0-9a-f]{64}")) {
            throw invalid(label + " must be lowercase SHA-256");
        }
        return exact;
    }

    private static String requiredHex(String value, String label,
                                      int minimumLength, int maximumLength) {
        String exact = requiredText(value, label, maximumLength);
        if ((exact.length() & 1) != 0 || exact.length() < minimumLength
                || !exact.matches("[0-9a-f]+")) {
            throw invalid(label + " must be bounded lowercase hex");
        }
        return exact;
    }

    private static String requiredId(String value, String label, int maximumLength) {
        String exact = requiredText(value, label, maximumLength);
        if (!exact.matches("[A-Za-z0-9_.-]+")) throw invalid(label + " is invalid");
        return exact;
    }

    private static String requiredNonce(String value) {
        String exact = requiredText(value, "challengeNonce", 128);
        if (exact.length() < 32 || !exact.matches("[A-Za-z0-9_-]+")) {
            throw invalid("challengeNonce is invalid");
        }
        return exact;
    }

    private static String requiredText(String value, String label, int maximumLength) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())
                || value.length() > maximumLength) {
            throw invalid(label + " is required");
        }
        return value;
    }

    private static String requiredJsonString(JSONObject value, String key) {
        Object raw = value == null ? null : value.opt(key);
        if (!(raw instanceof String)) throw invalid(key + " must be a string");
        return (String) raw;
    }

    private static long exactPositiveLong(Object value, String label) {
        long number = exactLong(value);
        if (number <= 0L) throw invalid(label + " must be a positive integer");
        return number;
    }

    private static long exactLong(Object value) {
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) return Long.MIN_VALUE;
        return ((Number) value).longValue();
    }

    private static void requireExactKeys(JSONObject value, Set<String> expected) {
        if (value == null || value.length() != expected.size()) {
            throw invalid("evidence fields are invalid");
        }
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            if (!expected.contains(keys.next())) throw invalid("evidence fields are invalid");
        }
        for (String key : expected) {
            if (!value.has(key)) throw invalid("evidence fields are invalid");
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("controlled recovery rejected: " + detail);
    }

    /** Length-framed UTF-8 encoding; values can never become ambiguous by delimiter injection. */
    private static final class Canonical {
        private final StringBuilder value = new StringBuilder();

        Canonical(String domain) {
            add("domain", domain);
        }

        Canonical add(String key, Object raw) {
            String text = raw == null ? "" : String.valueOf(raw);
            value.append(key.length()).append(':').append(key)
                .append(':').append(text.getBytes(StandardCharsets.UTF_8).length)
                .append(':').append(text).append(';');
            return this;
        }

        Canonical addBytes(String key, byte[] raw) {
            return add(key, hex(raw));
        }

        byte[] bytes() {
            return value.toString().getBytes(StandardCharsets.UTF_8);
        }
    }
}
