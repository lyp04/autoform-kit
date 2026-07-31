package com.autoformkit.app;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

/**
 * Fail-closed journal for the ordered recipe POSTs which precede a main submission.
 *
 * <p>This is deliberately a different journal from {@link AlternateSubmissionAttempt}: a
 * previous-step chain may contain several ordered POSTs, and a completed prefix must survive a
 * process restart so an earlier recipe is never replayed just because a later recipe is pending.
 * The journal stores only identities and SHA-256 digests, never a reusable request body.
 *
 * <p>Legacy app builds do not understand this record. A rollback is therefore unsafe while this
 * slot exists and must be refused by the signed upgrade/rollback gate; the journal alone cannot
 * constrain code that predates it.
 */
final class PreviousStepSubmissionAttempt {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_TEXT_LENGTH = 4096;
    private static final int MAX_OPERATION_ID_LENGTH = 512;
    private static final int MAX_RECIPES = 256;
    private static final int MAX_ATTEMPTS = 1000;

    private static final Set<String> ROOT_KEYS = setOf("schemaVersion", "state", "key");
    private static final Set<String> KEY_KEYS = setOf(
        "chain", "completedRecipeCount", "recipe", "payloadSha256",
        "attemptNumber", "operationId");
    private static final Set<String> CHAIN_KEYS = setOf(
        "connectionNamespace", "catalogVersion", "profileId", "draftSemanticsSha256",
        "unitSequence", "serial", "unitSnapshotSha256", "recipeChainSha256",
        "dynamicResolvedSemanticsSha256", "recipeCount");
    private static final Set<String> RECIPE_KEYS = setOf(
        "order", "sourceIndex", "kind", "identitySha256");

    enum State {
        PREPARED,
        POSTING,
        UNCERTAIN,
        EXPLICITLY_REJECTED,
        COMPLETED
    }

    enum ConfirmationResult {
        WRITTEN,
        NOT_WRITTEN
    }

    enum RecipeKind {
        STATIC,
        DYNAMIC
    }

    enum RestoreKind {
        NONE,
        RESTORED,
        LOCKED
    }

    enum LockReason {
        EMPTY_RECORD,
        INVALID_STORAGE_TYPE,
        MALFORMED_JSON,
        UNKNOWN_VERSION,
        UNKNOWN_STATE,
        INVALID_SCHEMA
    }

    /** Identity of the exact draft, unit, Panel semantics and complete ordered recipe chain. */
    static final class ChainIdentity {
        final String connectionNamespace;
        final int catalogVersion;
        final String profileId;
        final String draftSemanticsSha256;
        final int unitSequence;
        final String serial;
        final String unitSnapshotSha256;
        final String recipeChainSha256;
        final String dynamicResolvedSemanticsSha256;
        final int recipeCount;

        private ChainIdentity(String connectionNamespace, int catalogVersion,
                              String profileId, String draftSemanticsSha256,
                              int unitSequence, String serial, String unitSnapshotSha256,
                              String recipeChainSha256,
                              String dynamicResolvedSemanticsSha256,
                              int recipeCount) {
            this.connectionNamespace = requiredText(
                connectionNamespace, "connectionNamespace", MAX_TEXT_LENGTH);
            if (!this.connectionNamespace.matches("[0-9a-f]{20}")) {
                throw invalid("connectionNamespace must be lowercase hex20");
            }
            this.catalogVersion = positive(catalogVersion, "catalogVersion", Integer.MAX_VALUE);
            this.profileId = requiredText(profileId, "profileId", MAX_TEXT_LENGTH);
            this.draftSemanticsSha256 = requiredSha256(
                draftSemanticsSha256, "draftSemanticsSha256");
            this.unitSequence = positive(unitSequence, "unitSequence", Integer.MAX_VALUE);
            this.serial = requiredText(serial, "serial", MAX_TEXT_LENGTH);
            this.unitSnapshotSha256 = requiredSha256(
                unitSnapshotSha256, "unitSnapshotSha256");
            this.recipeChainSha256 = requiredSha256(
                recipeChainSha256, "recipeChainSha256");
            this.dynamicResolvedSemanticsSha256 = requiredSha256(
                dynamicResolvedSemanticsSha256,
                "dynamicResolvedSemanticsSha256");
            this.recipeCount = positive(recipeCount, "recipeCount", MAX_RECIPES);
        }

        static ChainIdentity of(String connectionNamespace, int catalogVersion,
                                String profileId, String draftSemanticsSha256,
                                int unitSequence, String serial, String unitSnapshotSha256,
                                String recipeChainSha256,
                                String dynamicResolvedSemanticsSha256,
                                int recipeCount) {
            return new ChainIdentity(connectionNamespace, catalogVersion, profileId,
                draftSemanticsSha256, unitSequence, serial, unitSnapshotSha256,
                recipeChainSha256, dynamicResolvedSemanticsSha256, recipeCount);
        }

        private JSONObject toJson() {
            try {
                return new JSONObject()
                    .put("connectionNamespace", connectionNamespace)
                    .put("catalogVersion", catalogVersion)
                    .put("profileId", profileId)
                    .put("draftSemanticsSha256", draftSemanticsSha256)
                    .put("unitSequence", unitSequence)
                    .put("serial", serial)
                    .put("unitSnapshotSha256", unitSnapshotSha256)
                    .put("recipeChainSha256", recipeChainSha256)
                    .put("dynamicResolvedSemanticsSha256",
                        dynamicResolvedSemanticsSha256)
                    .put("recipeCount", recipeCount);
            } catch (Exception impossible) {
                throw invalid("cannot serialize chain identity");
            }
        }

        private static ChainIdentity fromJson(JSONObject json) throws ParseFailure {
            requireExactKeys(json, CHAIN_KEYS);
            try {
                return new ChainIdentity(
                    persistedText(json, "connectionNamespace"),
                    persistedInteger(json, "catalogVersion"),
                    persistedText(json, "profileId"),
                    persistedText(json, "draftSemanticsSha256"),
                    persistedInteger(json, "unitSequence"),
                    persistedText(json, "serial"),
                    persistedText(json, "unitSnapshotSha256"),
                    persistedText(json, "recipeChainSha256"),
                    persistedText(json, "dynamicResolvedSemanticsSha256"),
                    persistedInteger(json, "recipeCount"));
            } catch (IllegalArgumentException error) {
                throw parseFailure(LockReason.INVALID_SCHEMA);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ChainIdentity)) return false;
            ChainIdentity that = (ChainIdentity) other;
            return catalogVersion == that.catalogVersion
                && unitSequence == that.unitSequence
                && recipeCount == that.recipeCount
                && connectionNamespace.equals(that.connectionNamespace)
                && profileId.equals(that.profileId)
                && draftSemanticsSha256.equals(that.draftSemanticsSha256)
                && serial.equals(that.serial)
                && unitSnapshotSha256.equals(that.unitSnapshotSha256)
                && recipeChainSha256.equals(that.recipeChainSha256)
                && dynamicResolvedSemanticsSha256.equals(
                    that.dynamicResolvedSemanticsSha256);
        }

        @Override
        public int hashCode() {
            return Objects.hash(connectionNamespace, catalogVersion, profileId,
                draftSemanticsSha256, unitSequence, serial, unitSnapshotSha256,
                recipeChainSha256, dynamicResolvedSemanticsSha256, recipeCount);
        }
    }

    /** Panel recipe identity and its exact position in the merged execution order. */
    static final class RecipeIdentity {
        final int order;
        final int sourceIndex;
        final RecipeKind kind;
        final String identitySha256;

        private RecipeIdentity(int order, int sourceIndex, RecipeKind kind,
                               String identitySha256) {
            this.order = positive(order, "recipe.order", MAX_RECIPES);
            if (sourceIndex < 0 || sourceIndex >= MAX_RECIPES) {
                throw invalid("recipe.sourceIndex is out of range");
            }
            if (kind == null) throw invalid("recipe.kind is required");
            this.sourceIndex = sourceIndex;
            this.kind = kind;
            this.identitySha256 = requiredSha256(identitySha256, "recipe.identitySha256");
        }

        static RecipeIdentity of(int order, int sourceIndex, RecipeKind kind,
                                 String identitySha256) {
            return new RecipeIdentity(order, sourceIndex, kind, identitySha256);
        }

        private JSONObject toJson() {
            try {
                return new JSONObject()
                    .put("order", order)
                    .put("sourceIndex", sourceIndex)
                    .put("kind", kind.name())
                    .put("identitySha256", identitySha256);
            } catch (Exception impossible) {
                throw invalid("cannot serialize recipe identity");
            }
        }

        private static RecipeIdentity fromJson(JSONObject json) throws ParseFailure {
            requireExactKeys(json, RECIPE_KEYS);
            try {
                RecipeKind kind = RecipeKind.valueOf(persistedText(json, "kind"));
                return new RecipeIdentity(persistedInteger(json, "order"),
                    persistedInteger(json, "sourceIndex"), kind,
                    persistedText(json, "identitySha256"));
            } catch (IllegalArgumentException error) {
                throw parseFailure(LockReason.INVALID_SCHEMA);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof RecipeIdentity)) return false;
            RecipeIdentity that = (RecipeIdentity) other;
            return order == that.order && sourceIndex == that.sourceIndex
                && kind == that.kind && identitySha256.equals(that.identitySha256);
        }

        @Override
        public int hashCode() {
            return Objects.hash(order, sourceIndex, kind, identitySha256);
        }
    }

    /** Exact identity of one POST attempt; the payload digest covers uploaded real URLs. */
    static final class Key {
        final ChainIdentity chain;
        final int completedRecipeCount;
        final RecipeIdentity recipe;
        final String payloadSha256;
        final int attemptNumber;
        final String operationId;

        private Key(ChainIdentity chain, int completedRecipeCount,
                    RecipeIdentity recipe, String payloadSha256,
                    int attemptNumber, String operationId) {
            if (chain == null || recipe == null) throw invalid("chain and recipe are required");
            if (recipe.order > chain.recipeCount
                    || completedRecipeCount < 0
                    || completedRecipeCount != recipe.order - 1) {
                throw invalid("completed recipe prefix does not precede recipe order");
            }
            this.chain = chain;
            this.completedRecipeCount = completedRecipeCount;
            this.recipe = recipe;
            this.payloadSha256 = requiredSha256(payloadSha256, "payloadSha256");
            this.attemptNumber = positive(attemptNumber, "attemptNumber", MAX_ATTEMPTS);
            this.operationId = requiredText(
                operationId, "operationId", MAX_OPERATION_ID_LENGTH);
        }

        static Key of(ChainIdentity chain, int completedRecipeCount,
                      RecipeIdentity recipe, String payloadSha256,
                      int attemptNumber, String operationId) {
            return new Key(chain, completedRecipeCount, recipe, payloadSha256,
                attemptNumber, operationId);
        }

        private JSONObject toJson() {
            try {
                return new JSONObject()
                    .put("chain", chain.toJson())
                    .put("completedRecipeCount", completedRecipeCount)
                    .put("recipe", recipe.toJson())
                    .put("payloadSha256", payloadSha256)
                    .put("attemptNumber", attemptNumber)
                    .put("operationId", operationId);
            } catch (Exception impossible) {
                throw invalid("cannot serialize attempt key");
            }
        }

        private static Key fromJson(JSONObject json) throws ParseFailure {
            requireExactKeys(json, KEY_KEYS);
            if (!(json.opt("chain") instanceof JSONObject)
                    || !(json.opt("recipe") instanceof JSONObject)) {
                throw parseFailure(LockReason.INVALID_SCHEMA);
            }
            try {
                return new Key(ChainIdentity.fromJson(json.getJSONObject("chain")),
                    persistedInteger(json, "completedRecipeCount"),
                    RecipeIdentity.fromJson(json.getJSONObject("recipe")),
                    persistedText(json, "payloadSha256"),
                    persistedInteger(json, "attemptNumber"),
                    persistedText(json, "operationId"));
            } catch (ParseFailure error) {
                throw error;
            } catch (Exception error) {
                throw parseFailure(LockReason.INVALID_SCHEMA);
            }
        }

        private boolean sameRecipePosition(Key other) {
            return other != null && chain.equals(other.chain)
                && completedRecipeCount == other.completedRecipeCount
                && recipe.equals(other.recipe);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key that = (Key) other;
            return completedRecipeCount == that.completedRecipeCount
                && attemptNumber == that.attemptNumber
                && chain.equals(that.chain)
                && recipe.equals(that.recipe)
                && payloadSha256.equals(that.payloadSha256)
                && operationId.equals(that.operationId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(chain, completedRecipeCount, recipe, payloadSha256,
                attemptNumber, operationId);
        }
    }

    /** Exact backend reconciliation result for the currently uncertain recipe POST. */
    static final class Confirmation {
        final Key key;
        final ConfirmationResult result;

        private Confirmation(Key key, ConfirmationResult result) {
            if (key == null || result == null) {
                throw invalid("confirmation key and result are required");
            }
            this.key = key;
            this.result = result;
        }

        static Confirmation of(Key key, ConfirmationResult result) {
            return new Confirmation(key, result);
        }
    }

    static final class RestoreResult {
        final RestoreKind kind;
        final PreviousStepSubmissionAttempt attempt;
        final LockReason lockReason;
        final boolean requiresWriteBack;

        private RestoreResult(RestoreKind kind, PreviousStepSubmissionAttempt attempt,
                              LockReason lockReason, boolean requiresWriteBack) {
            this.kind = kind;
            this.attempt = attempt;
            this.lockReason = lockReason;
            this.requiresWriteBack = requiresWriteBack;
        }

        private static RestoreResult none() {
            return new RestoreResult(RestoreKind.NONE, null, null, false);
        }

        private static RestoreResult restored(PreviousStepSubmissionAttempt attempt,
                                              boolean requiresWriteBack) {
            return new RestoreResult(RestoreKind.RESTORED, attempt, null, requiresWriteBack);
        }

        private static RestoreResult locked(LockReason reason) {
            return new RestoreResult(RestoreKind.LOCKED, null, reason, false);
        }
    }

    final State state;
    final Key key;

    private PreviousStepSubmissionAttempt(State state, Key key) {
        if (state == null || key == null) throw invalid("state and key are required");
        this.state = state;
        this.key = key;
    }

    /**
     * Prepares one exact POST. PREPARED and explicit-rejection records can be replaced only at the
     * same recipe position; COMPLETED may advance by exactly one recipe. UNCERTAIN never advances.
     */
    static PreviousStepSubmissionAttempt prepare(Key requested, RestoreResult slot) {
        if (requested == null || slot == null) throw invalid("requested key and slot are required");
        if (slot.kind == RestoreKind.LOCKED) throw invalid("persisted attempt is locked");
        if (slot.kind == RestoreKind.NONE) {
            if (requested.completedRecipeCount != 0 || requested.recipe.order != 1) {
                throw invalid("a new chain must begin at recipe one");
            }
            return new PreviousStepSubmissionAttempt(State.PREPARED, requested);
        }
        PreviousStepSubmissionAttempt retained = slot.attempt;
        if (retained == null) throw invalid("restored attempt is required");
        if (retained.state == State.UNCERTAIN || retained.state == State.POSTING) {
            throw invalid("previous-step POST outcome is unresolved");
        }
        if (retained.state == State.PREPARED) {
            if (!retained.key.sameRecipePosition(requested)) {
                throw invalid("prepared recipe position does not match");
            }
            if (retained.key.operationId.equals(requested.operationId)) {
                throw invalid("operationId has already been used");
            }
            if (requested.attemptNumber != retained.key.attemptNumber) {
                throw invalid("an unposted prepared attempt must keep its attempt number");
            }
            return new PreviousStepSubmissionAttempt(State.PREPARED, requested);
        }
        if (retained.state == State.EXPLICITLY_REJECTED) {
            if (!retained.key.sameRecipePosition(requested)) {
                throw invalid("rejected recipe position does not match");
            }
            if (requested.attemptNumber != retained.key.attemptNumber + 1) {
                throw invalid("rejected recipe attempt must advance exactly once");
            }
            if (retained.key.operationId.equals(requested.operationId)) {
                throw invalid("operationId has already been used");
            }
            return new PreviousStepSubmissionAttempt(State.PREPARED, requested);
        }
        if (!retained.key.chain.equals(requested.chain)
                || requested.completedRecipeCount != retained.key.recipe.order
                || requested.recipe.order != retained.key.recipe.order + 1
                || requested.attemptNumber != 1
                || retained.key.operationId.equals(requested.operationId)) {
            throw invalid("completed recipe prefix cannot advance to requested recipe");
        }
        return new PreviousStepSubmissionAttempt(State.PREPARED, requested);
    }

    PreviousStepSubmissionAttempt beginPosting(Key expected) {
        requireExactKey(expected);
        requireState(State.PREPARED, "posting");
        return new PreviousStepSubmissionAttempt(State.POSTING, key);
    }

    PreviousStepSubmissionAttempt markUncertain(Key expected) {
        requireExactKey(expected);
        requireState(State.POSTING, "uncertain");
        return new PreviousStepSubmissionAttempt(State.UNCERTAIN, key);
    }

    PreviousStepSubmissionAttempt markExplicitlyRejected(Key expected) {
        requireExactKey(expected);
        requireState(State.POSTING, "explicit rejection");
        return new PreviousStepSubmissionAttempt(State.EXPLICITLY_REJECTED, key);
    }

    PreviousStepSubmissionAttempt markAcknowledged(Key expected) {
        requireExactKey(expected);
        requireState(State.POSTING, "completion");
        return new PreviousStepSubmissionAttempt(State.COMPLETED, key);
    }

    /**
     * Applies an authoritative exact reconciliation to an ambiguous recipe POST.
     *
     * <p>WRITTEN advances only this recipe to the durable completed prefix. NOT_WRITTEN becomes the
     * same explicit rejection state used by a classified backend response, so a later retry must
     * still create a new operation id and increment the persistent attempt number exactly once.
     * The trusted evidence verification is deliberately outside this state machine.
     */
    PreviousStepSubmissionAttempt applyConfirmation(Confirmation confirmation) {
        if (confirmation == null) throw invalid("confirmation is required");
        requireExactKey(confirmation.key);
        requireState(State.UNCERTAIN, "confirmation");
        return new PreviousStepSubmissionAttempt(
            confirmation.result == ConfirmationResult.WRITTEN
                ? State.COMPLETED : State.EXPLICITLY_REJECTED,
            key);
    }

    int completedRecipeCount() {
        return state == State.COMPLETED ? key.recipe.order : key.completedRecipeCount;
    }

    /** True while this durable slot still owns a recipe which has not been acknowledged. */
    boolean requiresRecipeContinuation() {
        return completedRecipeCount() < key.chain.recipeCount;
    }

    /** Cross-check the persisted current recipe against the active immutable recipe snapshot. */
    boolean recipeMatches(RecipeIdentity expected) {
        return expected != null && key.recipe.equals(expected);
    }

    boolean chainMatches(ChainIdentity expected) {
        return expected != null && key.chain.equals(expected);
    }

    JSONObject toJson() {
        try {
            return new JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("state", state.name())
                .put("key", key.toJson());
        } catch (Exception impossible) {
            throw invalid("cannot serialize previous-step attempt");
        }
    }

    String toJsonString() {
        return toJson().toString();
    }

    static RestoreResult restore(String persisted) {
        if (persisted == null) return RestoreResult.none();
        if (persisted.trim().isEmpty()) return RestoreResult.locked(LockReason.EMPTY_RECORD);
        try {
            JSONObject root = new JSONObject(persisted);
            requireExactKeys(root, ROOT_KEYS);
            if (persistedInteger(root, "schemaVersion") != SCHEMA_VERSION) {
                throw parseFailure(LockReason.UNKNOWN_VERSION);
            }
            State restoredState;
            try {
                restoredState = State.valueOf(persistedText(root, "state"));
            } catch (IllegalArgumentException error) {
                throw parseFailure(LockReason.UNKNOWN_STATE);
            }
            if (!(root.opt("key") instanceof JSONObject)) {
                throw parseFailure(LockReason.INVALID_SCHEMA);
            }
            Key key = Key.fromJson(root.getJSONObject("key"));
            boolean recoveredPosting = restoredState == State.POSTING;
            return RestoreResult.restored(new PreviousStepSubmissionAttempt(
                recoveredPosting ? State.UNCERTAIN : restoredState, key), recoveredPosting);
        } catch (ParseFailure error) {
            return RestoreResult.locked(error.reason);
        } catch (Exception error) {
            return RestoreResult.locked(LockReason.MALFORMED_JSON);
        }
    }

    static RestoreResult restoreStoredValue(boolean present, Object persisted) {
        if (!present) return restore(null);
        if (!(persisted instanceof String)) {
            return RestoreResult.locked(LockReason.INVALID_STORAGE_TYPE);
        }
        return restore((String) persisted);
    }

    private void requireExactKey(Key expected) {
        if (expected == null || !key.equals(expected)) throw invalid("key does not match");
    }

    private void requireState(State expected, String operation) {
        if (state != expected) throw invalid(operation + " is not allowed from " + state.name());
    }

    private static String persistedText(JSONObject json, String key) throws ParseFailure {
        Object value = json.opt(key);
        if (!(value instanceof String)) throw parseFailure(LockReason.INVALID_SCHEMA);
        String text = ((String) value).trim();
        if (text.isEmpty() || text.length() > MAX_TEXT_LENGTH) {
            throw parseFailure(LockReason.INVALID_SCHEMA);
        }
        return text;
    }

    private static int persistedInteger(JSONObject json, String key) throws ParseFailure {
        Object value = json.opt(key);
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) {
            throw parseFailure(LockReason.INVALID_SCHEMA);
        }
        long number = ((Number) value).longValue();
        if (number < 0L || number > Integer.MAX_VALUE) {
            throw parseFailure(LockReason.INVALID_SCHEMA);
        }
        return (int) number;
    }

    private static void requireExactKeys(JSONObject json, Set<String> expected)
            throws ParseFailure {
        if (json == null || json.length() != expected.size()) {
            throw parseFailure(LockReason.INVALID_SCHEMA);
        }
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            if (!expected.contains(keys.next())) throw parseFailure(LockReason.INVALID_SCHEMA);
        }
        for (String key : expected) {
            if (!json.has(key)) throw parseFailure(LockReason.INVALID_SCHEMA);
        }
    }

    private static int positive(int value, String label, int maximum) {
        if (value <= 0 || value > maximum) throw invalid(label + " is out of range");
        return value;
    }

    private static String requiredText(String value, String label, int maximum) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty() || text.length() > maximum) throw invalid(label + " is required");
        return text;
    }

    private static String requiredSha256(String value, String label) {
        String text = requiredText(value, label, 64);
        if (!text.matches("[0-9a-f]{64}")) throw invalid(label + " must be lowercase SHA-256");
        return text;
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("previous-step journal rejected: " + detail);
    }

    private static ParseFailure parseFailure(LockReason reason) {
        return new ParseFailure(reason);
    }

    private static final class ParseFailure extends Exception {
        final LockReason reason;

        ParseFailure(LockReason reason) {
            this.reason = reason;
        }
    }
}
