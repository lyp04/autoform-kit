package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure, fail-closed selection rules for state mirrored for signed-v1 rollback builds. */
final class RollbackMirrorRules {
    static final int RECEIPT_VERSION = 1;
    private static final String RECEIPT_PREFIX = "rollback_mirror_receipt_v1_";

    enum Source {
        NONE,
        IDENTICAL,
        SCOPED,
        LEGACY,
        TOMBSTONE,
        BLOCKED
    }

    static final class Candidate {
        final String source;
        final boolean present;
        final String raw;
        final boolean valid;
        final boolean owned;
        final boolean selfBound;
        final long savedAt;

        private Candidate(String source, boolean present, String raw, boolean valid,
                          boolean owned, boolean selfBound, long savedAt) {
            this.source = source == null ? "" : source;
            this.present = present;
            this.raw = raw == null ? "" : raw;
            this.valid = valid;
            this.owned = owned;
            this.selfBound = selfBound;
            this.savedAt = savedAt;
        }

        static Candidate absent(String source) {
            return new Candidate(source, false, "", false, false, false, 0L);
        }

        static Candidate of(String source, String raw, boolean valid, boolean owned,
                            boolean selfBound, long savedAt) {
            return new Candidate(source, true, raw, valid, owned, selfBound, savedAt);
        }
    }

    static final class Decision {
        final Source source;
        final String value;
        final boolean mirrorAllowed;
        final String reason;

        private Decision(Source source, String value, boolean mirrorAllowed, String reason) {
            this.source = source;
            this.value = value == null ? "" : value;
            this.mirrorAllowed = mirrorAllowed;
            this.reason = reason == null ? "" : reason;
        }

        boolean blocked() {
            return source == Source.BLOCKED;
        }

        boolean tombstone() {
            return source == Source.TOMBSTONE;
        }
    }

    private RollbackMirrorRules() {}

    static String receiptPreferenceKey(String connectionNamespace, String logicalKey) {
        return RECEIPT_PREFIX + requiredNamespace(connectionNamespace) + "_"
            + sha256(requiredText(logicalKey)).substring(0, 20);
    }

    static JSONObject newReceipt(String connectionNamespace, String logicalKey, String value) {
        try {
            return new JSONObject()
                .put("version", RECEIPT_VERSION)
                .put("connectionNamespace", requiredNamespace(connectionNamespace))
                .put("logicalKeySha256", sha256(requiredText(logicalKey)))
                .put("mirroredValueSha256", sha256(value == null ? "" : value));
        } catch (Exception error) {
            throw new IllegalArgumentException("cannot serialize rollback receipt", error);
        }
    }

    static boolean receiptIdentifies(JSONObject receipt, String connectionNamespace,
                                     String logicalKey) {
        if (receipt == null || receipt.length() != 4
                || exactInteger(receipt.opt("version")) != RECEIPT_VERSION) return false;
        String namespace = connectionNamespace == null ? "" : connectionNamespace;
        String key = logicalKey == null ? "" : logicalKey;
        return namespace.matches("[0-9a-f]{20}")
            && !key.isEmpty()
            && namespace.equals(receipt.optString("connectionNamespace", ""))
            && sha256(key).equals(receipt.optString("logicalKeySha256", ""))
            && receipt.optString("mirroredValueSha256", "").matches("[0-9a-f]{64}");
    }

    static boolean receiptMatches(JSONObject receipt, String connectionNamespace,
                                  String logicalKey, String value) {
        return receiptIdentifies(receipt, connectionNamespace, logicalKey)
            && sha256(value == null ? "" : value).equals(
                receipt.optString("mirroredValueSha256", ""));
    }

    /**
     * Builds the one-time receipt used to adopt an unscoped signed-v1 value after an exact Panel
     * cache-pair migration.
     *
     * <p>This is deliberately narrower than ordinary mirror reconciliation: it is available only
     * before a scoped value or per-key receipt exists, only for a shape-validated global value,
     * and only while the shared global owner is absent or already names this connection. Callers
     * supply the independently verified cache-pair proof. Returning {@code null} means fail closed.
     */
    static JSONObject initialLegacyAdoptionReceipt(
            boolean scopedPresent, boolean legacyPresent, String legacyRaw,
            boolean legacyShapeValid, boolean logicalReceiptPresent,
            boolean globalOwnerCompatible, boolean verifiedPairReceipt,
            String connectionNamespace, String logicalKey) {
        if (scopedPresent || !legacyPresent || legacyRaw == null || legacyRaw.isEmpty()
                || !legacyShapeValid || logicalReceiptPresent
                || !globalOwnerCompatible || !verifiedPairReceipt) return null;
        return newReceipt(connectionNamespace, logicalKey, legacyRaw);
    }

    /**
     * Chooses among one or more draft/manual-queue copies. Every extant copy must be valid and
     * owned by the active Panel. A divergent unbound legacy copy additionally needs a receipt that
     * identifies this exact mirror and still matches at least one baseline copy. The newest
     * positive savedAt wins; equal timestamps with different bytes are deliberately ambiguous.
     */
    static Decision chooseNewestSnapshot(List<Candidate> candidates, JSONObject receipt,
                                         String connectionNamespace, String logicalKey) {
        List<Candidate> present = present(candidates);
        if (present.isEmpty()) return decision(Source.NONE, "", true, "");
        Candidate readable = firstReadable(present);
        for (Candidate candidate : present) {
            if (!candidate.valid || !candidate.owned || candidate.savedAt <= 0L) {
                return decision(Source.BLOCKED, readable == null ? "" : readable.raw, false,
                    "snapshot copy is malformed or not owned by the active Panel");
            }
        }

        // Identical timestamps cannot establish which divergent copy was written last. A mirror
        // receipt proves the old baseline, but it does not prove that the other bytes came from a
        // later signed-v1 write rather than a same-clock stale/corrupt copy.
        for (int left = 0; left < present.size(); left++) {
            for (int right = left + 1; right < present.size(); right++) {
                Candidate first = present.get(left);
                Candidate second = present.get(right);
                if (first.savedAt == second.savedAt && !first.raw.equals(second.raw)) {
                    return decision(Source.BLOCKED,
                        readable == null ? "" : readable.raw, false,
                        "snapshots have the same savedAt but different contents");
                }
            }
        }

        List<String> values = distinctValues(present);
        if (values.size() == 1) {
            return decision(sourceFor(present.get(0), true), values.get(0), true, "");
        }

        boolean identified = receiptIdentifies(receipt, connectionNamespace, logicalKey);
        boolean everyCopySelfBound = true;
        boolean baselinePresent = false;
        for (Candidate candidate : present) {
            everyCopySelfBound &= candidate.selfBound;
            baselinePresent |= receiptMatches(
                receipt, connectionNamespace, logicalKey, candidate.raw);
        }
        if ((!identified && !everyCopySelfBound) || (identified && !baselinePresent)) {
            return decision(Source.BLOCKED, readable == null ? "" : readable.raw, false,
                "divergent snapshot ownership cannot be proven");
        }

        // A receipt baseline is the exact state written before rollback. When every changed copy
        // agrees, the byte difference itself proves the signed-v1 side changed it; wall-clock time
        // is irrelevant because the device clock may have moved backwards while v1 was installed.
        if (identified) {
            List<Candidate> changed = new ArrayList<>();
            List<String> changedValues = new ArrayList<>();
            for (Candidate candidate : present) {
                if (receiptMatches(receipt, connectionNamespace, logicalKey, candidate.raw)) {
                    continue;
                }
                changed.add(candidate);
                if (!changedValues.contains(candidate.raw)) changedValues.add(candidate.raw);
            }
            if (changedValues.size() == 1) {
                return decision(sourceFor(changed.get(0), false),
                    changedValues.get(0), true, "");
            }
            present = changed;
        }

        long newest = 0L;
        for (Candidate candidate : present) newest = Math.max(newest, candidate.savedAt);
        String selected = null;
        Candidate selectedCandidate = null;
        for (Candidate candidate : present) {
            if (candidate.savedAt != newest) continue;
            if (selected == null) {
                selected = candidate.raw;
                selectedCandidate = candidate;
            } else if (!selected.equals(candidate.raw)) {
                return decision(Source.BLOCKED, readable == null ? "" : readable.raw, false,
                    "newest snapshots have the same savedAt but different contents");
            }
        }
        return decision(sourceFor(selectedCandidate, false), selected, true, "");
    }

    /**
     * Draft-store-only deletion rule. Signed v1 clearAllDrafts removes the legacy preference but
     * cannot see the scoped key. If the surviving scoped bytes are exactly the durable receipt
     * baseline, absence of the legacy key is an authenticated tombstone rather than missing data.
     */
    static Decision chooseDraftStore(Candidate scoped, Candidate legacy, JSONObject receipt,
                                     String connectionNamespace, String logicalKey) {
        if (scoped != null && scoped.present && (legacy == null || !legacy.present)
                && scoped.valid && scoped.owned
                && receiptMatches(receipt, connectionNamespace, logicalKey, scoped.raw)) {
            return decision(Source.TOMBSTONE, "", false,
                "signed-v1 removed the receipt-bound legacy draft store");
        }
        return chooseNewestSnapshot(asList(scoped, legacy), receipt,
            connectionNamespace, logicalKey);
    }

    /**
     * Selects a JSON mirror without an intrinsic timestamp. Only a receipt-bound change relative
     * to the exact old mirror is accepted. This is used for ledger/stat/set values written by v1.
     */
    static Decision chooseReceiptBoundValue(Candidate scoped, Candidate legacy,
                                            JSONObject receipt, String connectionNamespace,
                                            String logicalKey) {
        List<Candidate> present = present(asList(scoped, legacy));
        if (present.isEmpty()) return decision(Source.NONE, "", true, "");
        Candidate readable = firstReadable(present);
        for (Candidate candidate : present) {
            if (!candidate.valid || !candidate.owned) {
                return decision(Source.BLOCKED, readable == null ? "" : readable.raw, false,
                    "mirror copy is malformed or not owned by the active Panel");
            }
        }
        if (present.size() == 1) {
            Candidate only = present.get(0);
            if (only == scoped) {
                return decision(Source.SCOPED, only.raw, true, "");
            }
            if (receiptMatches(receipt, connectionNamespace, logicalKey, only.raw)) {
                return decision(Source.LEGACY, only.raw, true, "");
            }
            return decision(Source.BLOCKED, only.raw, false,
                "unscoped mirror has no exact ownership receipt");
        }
        if (scoped.raw.equals(legacy.raw)) {
            return decision(Source.IDENTICAL, scoped.raw, true, "");
        }
        if (!receiptIdentifies(receipt, connectionNamespace, logicalKey)) {
            return decision(Source.BLOCKED, scoped.raw, false,
                "divergent mirror has no ownership receipt");
        }
        boolean scopedWasBaseline = receiptMatches(
            receipt, connectionNamespace, logicalKey, scoped.raw);
        boolean legacyWasBaseline = receiptMatches(
            receipt, connectionNamespace, logicalKey, legacy.raw);
        if (scopedWasBaseline == legacyWasBaseline) {
            return decision(Source.BLOCKED, scoped.raw, false,
                "divergent mirror baseline is ambiguous");
        }
        return scopedWasBaseline
            ? decision(Source.LEGACY, legacy.raw, true, "")
            : decision(Source.SCOPED, scoped.raw, true, "");
    }

    static boolean validJsonObject(String raw) {
        try {
            return raw != null && !raw.isEmpty() && new JSONObject(raw) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean validJsonArray(String raw) {
        try {
            return raw != null && !raw.isEmpty() && new JSONArray(raw) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    static boolean validStringArray(String raw) {
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                if (!(array.opt(index) instanceof String)) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static long exactSavedAt(JSONObject value) {
        if (value == null) return 0L;
        Object raw = value.opt("savedAt");
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer
                || raw instanceof Long)) return 0L;
        long result = ((Number) raw).longValue();
        return result > 0L ? result : 0L;
    }

    static String sha256(String value) {
        try {
            byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                out.append(String.format(Locale.US, "%02x", item & 0xff));
            }
            return out.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static List<Candidate> asList(Candidate first, Candidate second) {
        List<Candidate> out = new ArrayList<>();
        if (first != null) out.add(first);
        if (second != null) out.add(second);
        return out;
    }

    private static List<Candidate> present(List<Candidate> candidates) {
        List<Candidate> out = new ArrayList<>();
        if (candidates == null) return out;
        for (Candidate candidate : candidates) {
            if (candidate != null && candidate.present) out.add(candidate);
        }
        return out;
    }

    private static Candidate firstReadable(List<Candidate> candidates) {
        for (Candidate candidate : candidates) {
            if (candidate.valid && candidate.owned) return candidate;
        }
        return null;
    }

    private static List<String> distinctValues(List<Candidate> candidates) {
        List<String> out = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (!out.contains(candidate.raw)) out.add(candidate.raw);
        }
        return out;
    }

    private static Source sourceFor(Candidate candidate, boolean identical) {
        if (identical) return Source.IDENTICAL;
        return candidate != null && candidate.source.startsWith("legacy")
            ? Source.LEGACY : Source.SCOPED;
    }

    private static Decision decision(Source source, String value,
                                     boolean mirrorAllowed, String reason) {
        return new Decision(source, value, mirrorAllowed, reason);
    }

    static int exactInteger(Object raw) {
        if (!(raw instanceof Byte || raw instanceof Short
                || raw instanceof Integer || raw instanceof Long)) return 0;
        long value = ((Number) raw).longValue();
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
            ? (int) value : 0;
    }

    private static String requiredNamespace(String value) {
        if (value == null || !value.matches("[0-9a-f]{20}")) {
            throw new IllegalArgumentException("invalid connection namespace");
        }
        return value;
    }

    private static String requiredText(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("logical key is required");
        }
        return value;
    }
}
