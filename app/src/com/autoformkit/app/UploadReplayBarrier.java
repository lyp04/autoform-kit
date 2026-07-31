package com.autoformkit.app;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * Pure durable barrier which prevents an upload sequence from being replayed after its start.
 *
 * <p>The record contains only immutable, non-sensitive binding identities. It deliberately has no
 * URL, serial number, credential, request header, uploaded value, or payload field. The caller
 * must persist {@link #toJsonString()} synchronously before the first upload side effect. A valid
 * restored STARTED record remains blocking after process restart; damaged storage is also blocking
 * and can never masquerade as an empty slot.
 */
final class UploadReplayBarrier {
    static final int SCHEMA_VERSION = 1;
    static final int MAX_PROFILE_ID_LENGTH = 256;
    static final int MIN_OPERATION_ID_LENGTH = 16;
    static final int MAX_OPERATION_ID_LENGTH = 96;

    private static final Set<String> ROOT_KEYS = setOf(
        "schemaVersion", "state", "identity");
    private static final Set<String> MAIN_IDENTITY_KEYS = setOf(
        "flow", "connectionNamespace", "catalogVersion", "profileId",
        "panelPairSha256", "bindingFingerprintSha256", "backendFingerprintSha256",
        "sessionFingerprintSha256", "sourceSnapshotSha256", "operationId");
    private static final Set<String> ALTERNATE_IDENTITY_KEYS = setOf(
        "flow", "connectionNamespace", "catalogVersion", "sourceProfileId",
        "targetProfileId", "panelPairSha256", "bindingFingerprintSha256",
        "backendFingerprintSha256", "sessionFingerprintSha256",
        "sourceSnapshotSha256", "operationId");

    enum Flow {
        MAIN,
        ALTERNATE
    }

    enum State {
        STARTED
    }

    enum RestoreKind {
        NONE,
        RESTORED,
        LOCKED
    }

    /** Non-sensitive reason codes suitable for local diagnostics. */
    enum LockReason {
        EMPTY_RECORD,
        INVALID_STORAGE_TYPE,
        MALFORMED_JSON,
        UNKNOWN_VERSION,
        UNKNOWN_STATE,
        UNKNOWN_FLOW,
        INVALID_SCHEMA
    }

    /** Complete immutable identity of the upload sequence guarded by one storage slot. */
    static final class Identity {
        final Flow flow;
        final String connectionNamespace;
        final int catalogVersion;
        final String profileId;
        final String sourceProfileId;
        final String targetProfileId;
        final String panelPairSha256;
        final String bindingFingerprintSha256;
        final String backendFingerprintSha256;
        final String sessionFingerprintSha256;
        final String sourceSnapshotSha256;
        final String operationId;

        private Identity(Flow flow, String connectionNamespace, int catalogVersion,
                         String profileId, String sourceProfileId, String targetProfileId,
                         String panelPairSha256, String bindingFingerprintSha256,
                         String backendFingerprintSha256, String sessionFingerprintSha256,
                         String sourceSnapshotSha256, String operationId) {
            if (flow == null) throw invalid("flow is required");
            this.flow = flow;
            this.connectionNamespace = requiredNamespace(connectionNamespace);
            this.catalogVersion = positiveCatalogVersion(catalogVersion);
            if (flow == Flow.MAIN) {
                this.profileId = requiredProfileId(profileId, "profileId");
                if (sourceProfileId != null || targetProfileId != null) {
                    throw invalid("main identity must not contain alternate profile ids");
                }
                this.sourceProfileId = null;
                this.targetProfileId = null;
            } else {
                if (profileId != null) {
                    throw invalid("alternate identity must not contain main profileId");
                }
                this.profileId = null;
                this.sourceProfileId = requiredProfileId(
                    sourceProfileId, "sourceProfileId");
                this.targetProfileId = requiredProfileId(
                    targetProfileId, "targetProfileId");
                if (this.sourceProfileId.equals(this.targetProfileId)) {
                    throw invalid("alternate source and target profile ids must differ");
                }
            }
            this.panelPairSha256 = requiredSha256(panelPairSha256, "panelPairSha256");
            this.bindingFingerprintSha256 = requiredSha256(
                bindingFingerprintSha256, "bindingFingerprintSha256");
            this.backendFingerprintSha256 = requiredSha256(
                backendFingerprintSha256, "backendFingerprintSha256");
            this.sessionFingerprintSha256 = requiredSha256(
                sessionFingerprintSha256, "sessionFingerprintSha256");
            this.sourceSnapshotSha256 = requiredSha256(
                sourceSnapshotSha256, "sourceSnapshotSha256");
            this.operationId = requiredOperationId(operationId);
        }

        static Identity main(String connectionNamespace, int catalogVersion,
                             String profileId, String panelPairSha256,
                             String bindingFingerprintSha256,
                             String backendFingerprintSha256,
                             String sessionFingerprintSha256,
                             String sourceSnapshotSha256, String operationId) {
            return new Identity(Flow.MAIN, connectionNamespace, catalogVersion,
                profileId, null, null, panelPairSha256, bindingFingerprintSha256,
                backendFingerprintSha256, sessionFingerprintSha256,
                sourceSnapshotSha256, operationId);
        }

        static Identity alternate(String connectionNamespace, int catalogVersion,
                                  String sourceProfileId, String targetProfileId,
                                  String panelPairSha256,
                                  String bindingFingerprintSha256,
                                  String backendFingerprintSha256,
                                  String sessionFingerprintSha256,
                                  String sourceSnapshotSha256, String operationId) {
            return new Identity(Flow.ALTERNATE, connectionNamespace, catalogVersion,
                null, sourceProfileId, targetProfileId, panelPairSha256,
                bindingFingerprintSha256, backendFingerprintSha256,
                sessionFingerprintSha256, sourceSnapshotSha256, operationId);
        }

        private JSONObject toJson() {
            try {
                JSONObject value = new JSONObject()
                    .put("flow", flow.name())
                    .put("connectionNamespace", connectionNamespace)
                    .put("catalogVersion", catalogVersion);
                if (flow == Flow.MAIN) {
                    value.put("profileId", profileId);
                } else {
                    value.put("sourceProfileId", sourceProfileId)
                        .put("targetProfileId", targetProfileId);
                }
                return value
                    .put("panelPairSha256", panelPairSha256)
                    .put("bindingFingerprintSha256", bindingFingerprintSha256)
                    .put("backendFingerprintSha256", backendFingerprintSha256)
                    .put("sessionFingerprintSha256", sessionFingerprintSha256)
                    .put("sourceSnapshotSha256", sourceSnapshotSha256)
                    .put("operationId", operationId);
            } catch (Exception impossible) {
                throw new IllegalStateException(
                    "cannot serialize upload barrier identity", impossible);
            }
        }

        private static Identity fromJson(JSONObject value) throws ParseFailure {
            Object rawFlow = value == null ? null : value.opt("flow");
            if (!(rawFlow instanceof String)) {
                throw parseFailure(LockReason.INVALID_SCHEMA);
            }
            final Flow flow;
            try {
                flow = Flow.valueOf((String) rawFlow);
            } catch (IllegalArgumentException unknown) {
                throw parseFailure(LockReason.UNKNOWN_FLOW);
            }
            requireExactKeys(value,
                flow == Flow.MAIN ? MAIN_IDENTITY_KEYS : ALTERNATE_IDENTITY_KEYS);
            try {
                if (flow == Flow.MAIN) {
                    return main(
                        persistedText(value, "connectionNamespace"),
                        persistedInteger(value, "catalogVersion"),
                        persistedText(value, "profileId"),
                        persistedText(value, "panelPairSha256"),
                        persistedText(value, "bindingFingerprintSha256"),
                        persistedText(value, "backendFingerprintSha256"),
                        persistedText(value, "sessionFingerprintSha256"),
                        persistedText(value, "sourceSnapshotSha256"),
                        persistedText(value, "operationId"));
                }
                return alternate(
                    persistedText(value, "connectionNamespace"),
                    persistedInteger(value, "catalogVersion"),
                    persistedText(value, "sourceProfileId"),
                    persistedText(value, "targetProfileId"),
                    persistedText(value, "panelPairSha256"),
                    persistedText(value, "bindingFingerprintSha256"),
                    persistedText(value, "backendFingerprintSha256"),
                    persistedText(value, "sessionFingerprintSha256"),
                    persistedText(value, "sourceSnapshotSha256"),
                    persistedText(value, "operationId"));
            } catch (IllegalArgumentException invalid) {
                throw parseFailure(LockReason.INVALID_SCHEMA);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Identity)) return false;
            Identity that = (Identity) other;
            return catalogVersion == that.catalogVersion
                && flow == that.flow
                && connectionNamespace.equals(that.connectionNamespace)
                && Objects.equals(profileId, that.profileId)
                && Objects.equals(sourceProfileId, that.sourceProfileId)
                && Objects.equals(targetProfileId, that.targetProfileId)
                && panelPairSha256.equals(that.panelPairSha256)
                && bindingFingerprintSha256.equals(that.bindingFingerprintSha256)
                && backendFingerprintSha256.equals(that.backendFingerprintSha256)
                && sessionFingerprintSha256.equals(that.sessionFingerprintSha256)
                && sourceSnapshotSha256.equals(that.sourceSnapshotSha256)
                && operationId.equals(that.operationId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(flow, connectionNamespace, catalogVersion, profileId,
                sourceProfileId, targetProfileId, panelPairSha256,
                bindingFingerprintSha256, backendFingerprintSha256,
                sessionFingerprintSha256, sourceSnapshotSha256, operationId);
        }
    }

    /** Result of loading one durable storage slot. */
    static final class RestoreResult {
        final RestoreKind kind;
        final UploadReplayBarrier barrier;
        final LockReason lockReason;

        private RestoreResult(RestoreKind kind, UploadReplayBarrier barrier,
                              LockReason lockReason) {
            this.kind = kind;
            this.barrier = barrier;
            this.lockReason = lockReason;
        }

        private static RestoreResult none() {
            return new RestoreResult(RestoreKind.NONE, null, null);
        }

        private static RestoreResult restored(UploadReplayBarrier barrier) {
            return new RestoreResult(RestoreKind.RESTORED, barrier, null);
        }

        private static RestoreResult locked(LockReason reason) {
            return new RestoreResult(RestoreKind.LOCKED, null, reason);
        }
    }

    final State state;
    final Identity identity;

    private UploadReplayBarrier(State state, Identity identity) {
        if (state == null) throw invalid("state is required");
        if (identity == null) throw invalid("identity is required");
        this.state = state;
        this.identity = identity;
    }

    /** Creates STARTED only when authoritative storage proves the slot is absent. */
    static UploadReplayBarrier prepare(Identity requested, RestoreResult slot) {
        if (requested == null) throw invalid("requested identity is required");
        if (slot == null) throw invalid("restored slot is required");
        if (slot.kind != RestoreKind.NONE) {
            throw new IllegalStateException("upload barrier slot is not empty");
        }
        return new UploadReplayBarrier(State.STARTED, requested);
    }

    /** Full identity equality; no single fingerprint or profile id can unlock a barrier. */
    boolean matches(Identity expected) {
        return expected != null && identity.equals(expected);
    }

    JSONObject toJson() {
        try {
            return new JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("state", state.name())
                .put("identity", identity.toJson());
        } catch (Exception impossible) {
            throw new IllegalStateException("cannot serialize upload barrier", impossible);
        }
    }

    String toJsonString() {
        return toJson().toString();
    }

    /**
     * Restores a persisted slot. Null alone means absent; empty, malformed, future, or otherwise
     * unreadable content remains locked.
     */
    static RestoreResult restore(String persisted) {
        if (persisted == null) return RestoreResult.none();
        if (persisted.trim().isEmpty()) {
            return RestoreResult.locked(LockReason.EMPTY_RECORD);
        }
        try {
            JSONObject root = new JSONObject(persisted);
            requireExactKeys(root, ROOT_KEYS);
            Object rawVersion = root.opt("schemaVersion");
            if (!isExactInteger(rawVersion, SCHEMA_VERSION)) {
                throw parseFailure(LockReason.UNKNOWN_VERSION);
            }
            Object rawState = root.opt("state");
            if (!(rawState instanceof String)) {
                throw parseFailure(LockReason.INVALID_SCHEMA);
            }
            final State state;
            try {
                state = State.valueOf((String) rawState);
            } catch (IllegalArgumentException unknown) {
                throw parseFailure(LockReason.UNKNOWN_STATE);
            }
            Object rawIdentity = root.opt("identity");
            if (!(rawIdentity instanceof JSONObject)) {
                throw parseFailure(LockReason.INVALID_SCHEMA);
            }
            return RestoreResult.restored(new UploadReplayBarrier(
                state, Identity.fromJson((JSONObject) rawIdentity)));
        } catch (ParseFailure invalid) {
            return RestoreResult.locked(invalid.reason);
        } catch (Exception malformed) {
            return RestoreResult.locked(LockReason.MALFORMED_JSON);
        }
    }

    /** Restores an untrusted key/value-store entry without assuming its runtime type. */
    static RestoreResult restoreStoredValue(boolean present, Object persisted) {
        if (!present) return RestoreResult.none();
        if (!(persisted instanceof String)) {
            return RestoreResult.locked(LockReason.INVALID_STORAGE_TYPE);
        }
        return restore((String) persisted);
    }

    private static String requiredNamespace(String value) {
        String exact = requiredText(value, "connectionNamespace", 20);
        if (!exact.matches("[0-9a-f]{20}")) {
            throw invalid("connectionNamespace must be lowercase hex20");
        }
        return exact;
    }

    private static int positiveCatalogVersion(int value) {
        if (value <= 0) throw invalid("catalogVersion must be positive");
        return value;
    }

    private static String requiredProfileId(String value, String label) {
        String exact = requiredText(value, label, MAX_PROFILE_ID_LENGTH);
        for (int index = 0; index < exact.length(); index += 1) {
            if (Character.isISOControl(exact.charAt(index))) {
                throw invalid(label + " contains a control character");
            }
        }
        return exact;
    }

    private static String requiredSha256(String value, String label) {
        String exact = requiredText(value, label, 64);
        if (!exact.matches("[0-9a-f]{64}")) {
            throw invalid(label + " must be lowercase SHA-256");
        }
        return exact;
    }

    private static String requiredOperationId(String value) {
        String exact = requiredText(value, "operationId", MAX_OPERATION_ID_LENGTH);
        String expression = "[A-Za-z0-9_-]{" + MIN_OPERATION_ID_LENGTH + ","
            + MAX_OPERATION_ID_LENGTH + "}";
        if (!exact.matches(expression)) throw invalid("operationId is invalid");
        return exact;
    }

    private static String requiredText(String value, String label, int maximumLength) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) {
            throw invalid(label + " is required and must not contain surrounding whitespace");
        }
        if (value.length() > maximumLength) throw invalid(label + " is too long");
        return value;
    }

    private static String persistedText(JSONObject value, String name) throws ParseFailure {
        Object raw = value == null ? null : value.opt(name);
        if (!(raw instanceof String)) throw parseFailure(LockReason.INVALID_SCHEMA);
        return (String) raw;
    }

    private static int persistedInteger(JSONObject value, String name) throws ParseFailure {
        Object raw = value == null ? null : value.opt(name);
        if (!(raw instanceof Byte || raw instanceof Short
                || raw instanceof Integer || raw instanceof Long)) {
            throw parseFailure(LockReason.INVALID_SCHEMA);
        }
        long number = ((Number) raw).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw parseFailure(LockReason.INVALID_SCHEMA);
        }
        return (int) number;
    }

    private static boolean isExactInteger(Object value, int expected) {
        return (value instanceof Byte || value instanceof Short
            || value instanceof Integer || value instanceof Long)
            && ((Number) value).longValue() == expected;
    }

    private static void requireExactKeys(JSONObject value, Set<String> expected)
            throws ParseFailure {
        if (value == null || value.length() != expected.size()) {
            throw parseFailure(LockReason.INVALID_SCHEMA);
        }
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            if (!expected.contains(keys.next())) {
                throw parseFailure(LockReason.INVALID_SCHEMA);
            }
        }
        for (String key : expected) {
            if (!value.has(key)) throw parseFailure(LockReason.INVALID_SCHEMA);
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
