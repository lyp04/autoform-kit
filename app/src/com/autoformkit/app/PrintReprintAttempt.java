package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure durable journal model for non-idempotent label reprint POSTs. */
final class PrintReprintAttempt {
    static final int STORE_SCHEMA = 1;
    static final int MAX_UNRESOLVED_ATTEMPTS = 512;

    enum State {
        POSTING("posting"),
        UNCERTAIN("uncertain");

        final String wire;

        State(String wire) {
            this.wire = wire;
        }

        static State parse(String value) {
            for (State state : values()) {
                if (state.wire.equals(value)) return state;
            }
            throw new IllegalArgumentException("invalid reprint attempt state");
        }
    }

    /**
     * A generic business failure is not proof that a non-idempotent POST had no side effect.
     * Until the Panel owns a print-specific "definitely not accepted" contract, only its
     * configured success classifier is terminal.
     */
    enum ResponseDisposition {
        CONFIRMED_SUCCESS,
        UNCERTAIN
    }

    static ResponseDisposition responseDisposition(boolean configuredSuccess) {
        return configuredSuccess
            ? ResponseDisposition.CONFIRMED_SUCCESS
            : ResponseDisposition.UNCERTAIN;
    }

    static final class Attempt {
        final String operationId;
        final State state;
        final long startedAt;
        final long updatedAt;
        final String payloadSha256;
        final PrintRemoteBinding binding;

        private Attempt(String operationId, State state, long startedAt, long updatedAt,
                        String payloadSha256, PrintRemoteBinding binding) {
            this.operationId = requiredOperationId(operationId);
            if (state == null) throw new IllegalArgumentException("state is required");
            this.state = state;
            if (startedAt <= 0L || updatedAt < startedAt) {
                throw new IllegalArgumentException("invalid reprint attempt timestamps");
            }
            this.startedAt = startedAt;
            this.updatedAt = updatedAt;
            this.payloadSha256 = requiredSha256(payloadSha256, "payloadSha256");
            if (binding == null || binding.jobId <= 0L) {
                throw new IllegalArgumentException("positive print job binding is required");
            }
            this.binding = binding;
        }

        static Attempt posting(String operationId, long now, String payloadSha256,
                               PrintRemoteBinding binding) {
            return new Attempt(operationId, State.POSTING, now, now, payloadSha256, binding);
        }

        Attempt uncertain(long now) {
            return state == State.UNCERTAIN ? this
                : new Attempt(operationId, State.UNCERTAIN, startedAt,
                    Math.max(now, startedAt), payloadSha256, binding);
        }

        JSONObject toJson() {
            try {
                return new JSONObject()
                    .put("operationId", operationId)
                    .put("state", state.wire)
                    .put("startedAt", startedAt)
                    .put("updatedAt", updatedAt)
                    .put("payloadSha256", payloadSha256)
                    .put("binding", binding.toJson());
            } catch (Exception impossible) {
                throw new IllegalStateException("Cannot serialize reprint attempt", impossible);
            }
        }

        static Attempt fromJson(JSONObject value) {
            if (value == null || value.length() != 6) {
                throw new IllegalArgumentException("reprint attempt has invalid fields");
            }
            return new Attempt(requiredString(value, "operationId"),
                State.parse(requiredString(value, "state")),
                exactPositiveLong(value.opt("startedAt"), "startedAt"),
                exactPositiveLong(value.opt("updatedAt"), "updatedAt"),
                requiredString(value, "payloadSha256"),
                PrintRemoteBinding.fromJson(requiredObject(value, "binding")));
        }
    }

    static final class Store {
        final List<Attempt> attempts;

        private Store(List<Attempt> attempts) {
            List<Attempt> copy = attempts == null
                ? Collections.emptyList() : new ArrayList<>(attempts);
            if (copy.size() > MAX_UNRESOLVED_ATTEMPTS) {
                throw new IllegalArgumentException("too many unresolved reprint attempts");
            }
            Set<String> operationIds = new LinkedHashSet<>();
            for (int i = 0; i < copy.size(); i++) {
                Attempt attempt = copy.get(i);
                if (attempt == null || !operationIds.add(attempt.operationId)) {
                    throw new IllegalArgumentException("duplicate reprint operation id");
                }
                for (int j = 0; j < i; j++) {
                    if (copy.get(j).binding.sameRemoteTarget(attempt.binding)) {
                        throw new IllegalArgumentException("duplicate unresolved print target");
                    }
                }
            }
            this.attempts = Collections.unmodifiableList(copy);
        }

        static Store empty() {
            return new Store(Collections.emptyList());
        }

        static Store parse(String raw) {
            try {
                JSONObject root = new JSONObject(raw == null ? "" : raw);
                if (root.length() != 2
                        || exactPositiveLong(root.opt("schema"), "schema") != STORE_SCHEMA) {
                    throw new IllegalArgumentException("unsupported reprint journal schema");
                }
                Object attemptsValue = root.opt("attempts");
                if (!(attemptsValue instanceof JSONArray)) {
                    throw new IllegalArgumentException("reprint attempts array is required");
                }
                JSONArray array = (JSONArray) attemptsValue;
                if (array.length() > MAX_UNRESOLVED_ATTEMPTS) {
                    throw new IllegalArgumentException("too many unresolved reprint attempts");
                }
                List<Attempt> attempts = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.opt(i);
                    if (!(item instanceof JSONObject)) {
                        throw new IllegalArgumentException("invalid reprint attempt entry");
                    }
                    attempts.add(Attempt.fromJson((JSONObject) item));
                }
                return new Store(attempts);
            } catch (IllegalArgumentException invalid) {
                throw invalid;
            } catch (Exception invalid) {
                throw new IllegalArgumentException("unreadable reprint journal", invalid);
            }
        }

        String serialize() {
            try {
                JSONArray array = new JSONArray();
                for (Attempt attempt : attempts) array.put(attempt.toJson());
                return new JSONObject()
                    .put("schema", STORE_SCHEMA)
                    .put("attempts", array)
                    .toString();
            } catch (Exception impossible) {
                throw new IllegalStateException("Cannot serialize reprint journal", impossible);
            }
        }

        Attempt blocking(PrintRemoteBinding candidate) {
            if (candidate == null) return null;
            for (Attempt attempt : attempts) {
                if (attempt.binding.sameRemoteTarget(candidate)) return attempt;
            }
            return null;
        }

        /**
         * Returns the unresolved attempt which an exact-bound status read may retire. Missing,
         * failed, ongoing, unknown, generic response failure, or a different stable remote target
         * identity never resolves anything.
         */
        Attempt confirmedPrintedResolution(PrintRemoteBinding observedTarget,
                                           boolean responseSuccess,
                                           boolean printed) {
            if (!responseSuccess || !printed || observedTarget == null
                    || observedTarget.jobId <= 0L) return null;
            return blocking(observedTarget);
        }

        Attempt operation(String operationId) {
            for (Attempt attempt : attempts) {
                if (attempt.operationId.equals(operationId)) return attempt;
            }
            return null;
        }

        Store add(Attempt attempt) {
            if (attempt == null) throw new IllegalArgumentException("attempt is required");
            if (attempts.size() >= MAX_UNRESOLVED_ATTEMPTS) {
                throw new IllegalStateException("reprint journal is full");
            }
            if (blocking(attempt.binding) != null || operation(attempt.operationId) != null) {
                throw new IllegalStateException("reprint target is already unresolved");
            }
            List<Attempt> next = new ArrayList<>(attempts);
            next.add(attempt);
            return new Store(next);
        }

        Store markUncertain(String operationId, long now) {
            List<Attempt> next = new ArrayList<>(attempts.size());
            boolean found = false;
            for (Attempt attempt : attempts) {
                if (attempt.operationId.equals(operationId)) {
                    next.add(attempt.uncertain(now));
                    found = true;
                } else {
                    next.add(attempt);
                }
            }
            if (!found) throw new IllegalStateException("reprint attempt is missing");
            return new Store(next);
        }

        Store remove(String operationId) {
            List<Attempt> next = new ArrayList<>(attempts.size());
            boolean found = false;
            for (Attempt attempt : attempts) {
                if (attempt.operationId.equals(operationId)) found = true;
                else next.add(attempt);
            }
            if (!found) throw new IllegalStateException("reprint attempt is missing");
            return new Store(next);
        }

        Store recoverPosting(long now) {
            List<Attempt> next = new ArrayList<>(attempts.size());
            for (Attempt attempt : attempts) next.add(attempt.uncertain(now));
            return new Store(next);
        }

        boolean hasPosting() {
            for (Attempt attempt : attempts) {
                if (attempt.state == State.POSTING) return true;
            }
            return false;
        }
    }

    private PrintReprintAttempt() {
    }

    private static JSONObject requiredObject(JSONObject value, String key) {
        Object raw = value == null ? null : value.opt(key);
        if (!(raw instanceof JSONObject)) {
            throw new IllegalArgumentException(key + " must be an object");
        }
        return (JSONObject) raw;
    }

    private static String requiredString(JSONObject value, String key) {
        Object raw = value == null ? null : value.opt(key);
        if (!(raw instanceof String) || ((String) raw).isEmpty()) {
            throw new IllegalArgumentException(key + " must be a non-empty string");
        }
        return (String) raw;
    }

    private static String requiredOperationId(String value) {
        String exact = value == null ? "" : value;
        if (!exact.matches("[A-Za-z0-9_-]{16,96}")) {
            throw new IllegalArgumentException("operationId is invalid");
        }
        return exact;
    }

    private static String requiredSha256(String value, String label) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.US);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " must be SHA-256");
        }
        return normalized;
    }

    private static long exactPositiveLong(Object raw, String label) {
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer
                || raw instanceof Long)) {
            throw new IllegalArgumentException(label + " must be an integer");
        }
        long value = ((Number) raw).longValue();
        if (value <= 0L) throw new IllegalArgumentException(label + " must be positive");
        return value;
    }
}
