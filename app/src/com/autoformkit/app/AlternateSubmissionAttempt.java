package com.autoformkit.app;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Pure state machine for one exact submit attempt.
 *
 * <p>The historical class name remains during the staged upgrade, but both the main form and an
 * alternate entry use this same transport-neutral journal. It contains no profile-specific rules.
 *
 * <p>The caller must persist the {@link #toJsonString()} result synchronously before starting a
 * POST and after every transition. A restored {@link State#POSTING} attempt is deliberately
 * converted to {@link State#UNCERTAIN}: after process death the client cannot know whether the
 * remote side committed the request.
 *
 * <p>An uncertain attempt cannot be posted again. It first needs a target-scoped confirmation
 * whose complete {@link Key} matches the original attempt. The backend-specific confirmation
 * query is intentionally outside this class.
 */
final class AlternateSubmissionAttempt {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_TEXT_LENGTH = 4096;
    private static final int MAX_OPERATION_ID_LENGTH = 512;
    private static final Set<String> ROOT_KEYS = setOf("schemaVersion", "state", "key");
    private static final Set<String> KEY_KEYS = setOf(
        "connectionNamespace", "bindingFingerprint", "target", "serial",
        "sourceSnapshotSha256", "payloadSha256", "operationId");
    private static final Set<String> TARGET_KEYS = setOf(
        "profileId", "templateId", "warehouseId", "sku");

    enum State {
        PREPARED,
        POSTING,
        UNCERTAIN,
        CONFIRMED_NOT_WRITTEN,
        COMPLETED
    }

    enum ConfirmationResult {
        WRITTEN,
        NOT_WRITTEN
    }

    enum RestoreKind {
        NONE,
        RESTORED,
        LOCKED
    }

    /** Non-sensitive reason codes suitable for logging without copying persisted content. */
    enum LockReason {
        EMPTY_RECORD,
        INVALID_STORAGE_TYPE,
        MALFORMED_JSON,
        UNKNOWN_VERSION,
        UNKNOWN_STATE,
        INVALID_SCHEMA
    }

    static final class TargetIdentity {
        final String profileId;
        final String templateId;
        final String warehouseId;
        final String sku;

        private TargetIdentity(String profileId, String templateId,
                               String warehouseId, String sku) {
            this.profileId = requiredText(profileId, "target.profileId", MAX_TEXT_LENGTH);
            this.templateId = requiredText(templateId, "target.templateId", MAX_TEXT_LENGTH);
            this.warehouseId = requiredText(
                warehouseId, "target.warehouseId", MAX_TEXT_LENGTH);
            this.sku = requiredText(sku, "target.sku", MAX_TEXT_LENGTH);
        }

        static TargetIdentity of(String profileId, Object templateId,
                                 Object warehouseId, String sku) {
            return new TargetIdentity(profileId,
                identityScalar(templateId, "target.templateId"),
                identityScalar(warehouseId, "target.warehouseId"), sku);
        }

        private JSONObject toJson() {
            try {
                return new JSONObject()
                    .put("profileId", profileId)
                    .put("templateId", templateId)
                    .put("warehouseId", warehouseId)
                    .put("sku", sku);
            } catch (Exception impossible) {
                throw new IllegalStateException("cannot serialize target identity", impossible);
            }
        }

        private static TargetIdentity fromJson(JSONObject json) throws ParseFailure {
            requireExactKeys(json, TARGET_KEYS, "key.target");
            return new TargetIdentity(
                persistedText(json, "profileId", "key.target.profileId", MAX_TEXT_LENGTH),
                persistedText(json, "templateId", "key.target.templateId", MAX_TEXT_LENGTH),
                persistedText(json, "warehouseId", "key.target.warehouseId", MAX_TEXT_LENGTH),
                persistedText(json, "sku", "key.target.sku", MAX_TEXT_LENGTH));
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof TargetIdentity)) return false;
            TargetIdentity that = (TargetIdentity) other;
            return profileId.equals(that.profileId)
                && templateId.equals(that.templateId)
                && warehouseId.equals(that.warehouseId)
                && sku.equals(that.sku);
        }

        @Override
        public int hashCode() {
            return Objects.hash(profileId, templateId, warehouseId, sku);
        }
    }

    /** Immutable identity of the exact request whose outcome is being tracked. */
    static final class Key {
        final String connectionNamespace;
        final String bindingFingerprint;
        final TargetIdentity target;
        final String serial;
        final String sourceSnapshotSha256;
        final String payloadSha256;
        final String operationId;

        private Key(String connectionNamespace, String bindingFingerprint,
                    TargetIdentity target, String serial, String sourceSnapshotSha256,
                    String payloadSha256, String operationId) {
            this.connectionNamespace = requiredText(
                connectionNamespace, "connectionNamespace", MAX_TEXT_LENGTH);
            this.bindingFingerprint = requiredText(
                bindingFingerprint, "bindingFingerprint", MAX_TEXT_LENGTH);
            if (target == null) throw invalid("target is required");
            this.target = target;
            this.serial = requiredText(serial, "serial", MAX_TEXT_LENGTH);
            this.sourceSnapshotSha256 = requiredSha256(
                sourceSnapshotSha256, "sourceSnapshotSha256");
            this.payloadSha256 = requiredSha256(payloadSha256);
            this.operationId = requiredText(
                operationId, "operationId", MAX_OPERATION_ID_LENGTH);
        }

        static Key of(String connectionNamespace, String bindingFingerprint,
                      TargetIdentity target, String serial, String sourceSnapshotSha256,
                      String payloadSha256, String operationId) {
            return new Key(connectionNamespace, bindingFingerprint, target, serial,
                sourceSnapshotSha256, payloadSha256, operationId);
        }

        private JSONObject toJson() {
            try {
                return new JSONObject()
                    .put("connectionNamespace", connectionNamespace)
                    .put("bindingFingerprint", bindingFingerprint)
                    .put("target", target.toJson())
                    .put("serial", serial)
                    .put("sourceSnapshotSha256", sourceSnapshotSha256)
                    .put("payloadSha256", payloadSha256)
                    .put("operationId", operationId);
            } catch (Exception impossible) {
                throw new IllegalStateException("cannot serialize attempt key", impossible);
            }
        }

        private static Key fromJson(JSONObject json) throws ParseFailure {
            requireExactKeys(json, KEY_KEYS, "key");
            Object targetValue = json.opt("target");
            if (!(targetValue instanceof JSONObject)) {
                throw parseFailure(LockReason.INVALID_SCHEMA);
            }
            try {
                return new Key(
                    persistedText(json, "connectionNamespace",
                        "key.connectionNamespace", MAX_TEXT_LENGTH),
                    persistedText(json, "bindingFingerprint",
                        "key.bindingFingerprint", MAX_TEXT_LENGTH),
                    TargetIdentity.fromJson((JSONObject) targetValue),
                    persistedText(json, "serial", "key.serial", MAX_TEXT_LENGTH),
                    persistedText(json, "sourceSnapshotSha256",
                        "key.sourceSnapshotSha256", MAX_TEXT_LENGTH),
                    persistedText(json, "payloadSha256",
                        "key.payloadSha256", MAX_TEXT_LENGTH),
                    persistedText(json, "operationId",
                        "key.operationId", MAX_OPERATION_ID_LENGTH));
            } catch (IllegalArgumentException error) {
                throw parseFailure(LockReason.INVALID_SCHEMA);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key that = (Key) other;
            return connectionNamespace.equals(that.connectionNamespace)
                && bindingFingerprint.equals(that.bindingFingerprint)
                && target.equals(that.target)
                && serial.equals(that.serial)
                && sourceSnapshotSha256.equals(that.sourceSnapshotSha256)
                && payloadSha256.equals(that.payloadSha256)
                && operationId.equals(that.operationId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(connectionNamespace, bindingFingerprint, target, serial,
                sourceSnapshotSha256, payloadSha256, operationId);
        }
    }

    /** A confirmation is useful only when its complete request identity matches the attempt. */
    static final class Confirmation {
        final Key key;
        final ConfirmationResult result;

        private Confirmation(Key key, ConfirmationResult result) {
            if (key == null) throw invalid("confirmation key is required");
            if (result == null) throw invalid("confirmation result is required");
            this.key = key;
            this.result = result;
        }

        static Confirmation of(Key key, ConfirmationResult result) {
            return new Confirmation(key, result);
        }
    }

    /** Result of loading a single persisted slot. Invalid content never becomes an empty slot. */
    static final class RestoreResult {
        final RestoreKind kind;
        final AlternateSubmissionAttempt attempt;
        final LockReason lockReason;
        final boolean requiresWriteBack;

        private RestoreResult(RestoreKind kind, AlternateSubmissionAttempt attempt,
                              LockReason lockReason, boolean requiresWriteBack) {
            this.kind = kind;
            this.attempt = attempt;
            this.lockReason = lockReason;
            this.requiresWriteBack = requiresWriteBack;
        }

        private static RestoreResult none() {
            return new RestoreResult(RestoreKind.NONE, null, null, false);
        }

        private static RestoreResult restored(AlternateSubmissionAttempt attempt,
                                              boolean requiresWriteBack) {
            return new RestoreResult(
                RestoreKind.RESTORED, attempt, null, requiresWriteBack);
        }

        private static RestoreResult locked(LockReason reason) {
            return new RestoreResult(RestoreKind.LOCKED, null, reason, false);
        }
    }

    final State state;
    final Key key;

    private AlternateSubmissionAttempt(State state, Key key) {
        if (state == null) throw invalid("state is required");
        if (key == null) throw invalid("key is required");
        this.state = state;
        this.key = key;
    }

    /**
     * Prepares a new attempt using the authoritative restored slot.
     *
     * <p>A locked slot cannot be bypassed. An unresolved slot cannot be replaced. A completed slot
     * may be replaced only with a new operation id; reusing an operation id for another payload or
     * target is explicitly rejected.
     */
    static AlternateSubmissionAttempt prepare(Key requested, RestoreResult slot) {
        if (requested == null) throw invalid("requested key is required");
        if (slot == null) throw invalid("restored slot is required");
        if (slot.kind == RestoreKind.LOCKED) {
            throw invalid("persisted attempt is locked");
        }
        if (slot.kind == RestoreKind.NONE) {
            return new AlternateSubmissionAttempt(State.PREPARED, requested);
        }
        AlternateSubmissionAttempt retained = slot.attempt;
        if (retained == null) throw invalid("restored attempt is required");
        if (retained.key.operationId.equals(requested.operationId)) {
            if (!retained.key.payloadSha256.equals(requested.payloadSha256)) {
                throw invalid("operationId is already bound to a different payload");
            }
            if (!retained.key.equals(requested)) {
                throw invalid("operationId is already bound to a different submission identity");
            }
            throw invalid("operationId has already been used");
        }
        if (retained.state != State.COMPLETED) {
            throw invalid("another submission attempt is unresolved");
        }
        return new AlternateSubmissionAttempt(State.PREPARED, requested);
    }

    /** PREPARED, or an exactly confirmed absence, may enter the one allowed POST attempt. */
    AlternateSubmissionAttempt beginPosting(Key expected) {
        requireExactKey(expected);
        if (state != State.PREPARED && state != State.CONFIRMED_NOT_WRITTEN) {
            throw invalid("posting is not allowed from " + state.name());
        }
        return new AlternateSubmissionAttempt(State.POSTING, key);
    }

    /** Any POST result without a positive application acknowledgement is ambiguous. */
    AlternateSubmissionAttempt markUncertain(Key expected) {
        requireExactKey(expected);
        requireState(State.POSTING, "uncertain");
        return new AlternateSubmissionAttempt(State.UNCERTAIN, key);
    }

    /** The backend returned an explicit Panel-classified rejection before accepting the record. */
    AlternateSubmissionAttempt markServerRejected(Key expected) {
        requireExactKey(expected);
        requireState(State.POSTING, "server rejection");
        return new AlternateSubmissionAttempt(State.CONFIRMED_NOT_WRITTEN, key);
    }

    /** A positive response to the original POST completes the attempt. */
    AlternateSubmissionAttempt markPostAcknowledged(Key expected) {
        requireExactKey(expected);
        requireState(State.POSTING, "completed");
        return new AlternateSubmissionAttempt(State.COMPLETED, key);
    }

    /**
     * Applies an exact backend confirmation to an ambiguous attempt.
     *
     * <p>{@link ConfirmationResult#NOT_WRITTEN} enables one retry transition; it never starts the
     * retry itself. A caller must persist CONFIRMED_NOT_WRITTEN before calling {@link
     * #beginPosting(Key)} and persist POSTING before network I/O.
     */
    AlternateSubmissionAttempt applyConfirmation(Confirmation confirmation) {
        if (confirmation == null) throw invalid("confirmation is required");
        requireExactKey(confirmation.key);
        requireState(State.UNCERTAIN, "confirmation");
        State next = confirmation.result == ConfirmationResult.WRITTEN
            ? State.COMPLETED : State.CONFIRMED_NOT_WRITTEN;
        return new AlternateSubmissionAttempt(next, key);
    }

    JSONObject toJson() {
        try {
            return new JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("state", state.name())
                .put("key", key.toJson());
        } catch (Exception impossible) {
            throw new IllegalStateException("cannot serialize submission attempt", impossible);
        }
    }

    String toJsonString() {
        return toJson().toString();
    }

    /**
     * Restores a persisted slot. Pass {@code null} only when storage proves the key is absent.
     * Empty, unknown, and damaged records are locked rather than treated as no prior attempt.
     */
    static RestoreResult restore(String persisted) {
        if (persisted == null) return RestoreResult.none();
        if (persisted.trim().isEmpty()) {
            return RestoreResult.locked(LockReason.EMPTY_RECORD);
        }
        try {
            JSONObject json = new JSONObject(persisted);
            requireExactKeys(json, ROOT_KEYS, "attempt");
            Object version = json.opt("schemaVersion");
            if (!((version instanceof Integer || version instanceof Long)
                    && ((Number) version).longValue() == SCHEMA_VERSION)) {
                throw parseFailure(LockReason.UNKNOWN_VERSION);
            }
            Object rawState = json.opt("state");
            if (!(rawState instanceof String)) {
                throw parseFailure(LockReason.INVALID_SCHEMA);
            }
            State restoredState;
            try {
                restoredState = State.valueOf((String) rawState);
            } catch (IllegalArgumentException error) {
                throw parseFailure(LockReason.UNKNOWN_STATE);
            }
            Object rawKey = json.opt("key");
            if (!(rawKey instanceof JSONObject)) {
                throw parseFailure(LockReason.INVALID_SCHEMA);
            }
            Key key = Key.fromJson((JSONObject) rawKey);
            boolean recoveredPosting = restoredState == State.POSTING;
            State safeState = recoveredPosting ? State.UNCERTAIN : restoredState;
            return RestoreResult.restored(
                new AlternateSubmissionAttempt(safeState, key), recoveredPosting);
        } catch (ParseFailure error) {
            return RestoreResult.locked(error.reason);
        } catch (Exception error) {
            return RestoreResult.locked(LockReason.MALFORMED_JSON);
        }
    }

    /**
     * Restores the raw value returned by a key/value store without trusting its runtime type.
     *
     * <p>{@code SharedPreferences#getString} throws when an older or damaged installation has a
     * value of another type under the same key. Callers must instead pass the value from
     * {@code getAll()}; a present non-string value is an unreadable safety record and therefore
     * locks submission rather than masquerading as an empty slot.
     */
    static RestoreResult restoreStoredValue(boolean present, Object persisted) {
        if (!present) return restore(null);
        if (!(persisted instanceof String)) {
            return RestoreResult.locked(LockReason.INVALID_STORAGE_TYPE);
        }
        return restore((String) persisted);
    }

    /** Only states which prove that no ambiguous POST remains may be removed locally. */
    boolean canClearLocallyWithoutRemoteConfirmation() {
        return state == State.PREPARED || state == State.CONFIRMED_NOT_WRITTEN
            || state == State.COMPLETED;
    }

    /** SHA-256 of the exact immutable bytes that will be sent as the POST body. */
    static String payloadSha256(byte[] requestBody) {
        if (requestBody == null) throw invalid("request body is required");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(requestBody);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte part : digest) {
                value.append(String.format(Locale.US, "%02x", part & 0xff));
            }
            return value.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static String payloadSha256(String exactRequestBody) {
        if (exactRequestBody == null) throw invalid("request body is required");
        return payloadSha256(exactRequestBody.getBytes(StandardCharsets.UTF_8));
    }

    private static String identityScalar(Object value, String path) {
        if (value == null || JSONObject.NULL.equals(value)) {
            throw invalid(path + " is required");
        }
        if (!(value instanceof String) && !(value instanceof Number)) {
            throw invalid(path + " must be a string or number");
        }
        return requiredText(String.valueOf(value), path, MAX_TEXT_LENGTH);
    }

    private void requireExactKey(Key expected) {
        if (expected == null || !key.equals(expected)) {
            throw invalid("submission attempt key does not match");
        }
    }

    private void requireState(State expected, String action) {
        if (state != expected) {
            throw invalid(action + " is not allowed from " + state.name());
        }
    }

    private static String requiredSha256(String value) {
        return requiredSha256(value, "payloadSha256");
    }

    private static String requiredSha256(String value, String path) {
        String required = requiredText(value, path, 64);
        if (!required.matches("[0-9a-f]{64}")) {
            throw invalid(path + " must be a lowercase SHA-256 digest");
        }
        return required;
    }

    private static String requiredText(String value, String path, int maxLength) {
        if (value == null || value.trim().isEmpty()) {
            throw invalid(path + " is required");
        }
        if (value.length() > maxLength) {
            throw invalid(path + " is too long");
        }
        return value;
    }

    private static String persistedText(JSONObject json, String name, String path,
                                        int maxLength) throws ParseFailure {
        Object value = json.opt(name);
        if (!(value instanceof String)) {
            throw parseFailure(LockReason.INVALID_SCHEMA);
        }
        try {
            return requiredText((String) value, path, maxLength);
        } catch (IllegalArgumentException error) {
            throw parseFailure(LockReason.INVALID_SCHEMA);
        }
    }

    private static void requireExactKeys(JSONObject json, Set<String> expected,
                                         String path) throws ParseFailure {
        if (json == null || json.length() != expected.size()) {
            throw parseFailure(LockReason.INVALID_SCHEMA);
        }
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            if (!expected.contains(keys.next())) {
                throw parseFailure(LockReason.INVALID_SCHEMA);
            }
        }
        for (String key : expected) {
            if (!json.has(key)) throw parseFailure(LockReason.INVALID_SCHEMA);
        }
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static ParseFailure parseFailure(LockReason reason) {
        return new ParseFailure(reason);
    }

    private static final class ParseFailure extends Exception {
        final LockReason reason;

        private ParseFailure(LockReason reason) {
            this.reason = reason;
        }
    }
}
