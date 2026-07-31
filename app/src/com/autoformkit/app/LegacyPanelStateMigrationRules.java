package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

/** Pure shape and profile-ownership checks for unscoped state written by signed-v1 Apps. */
final class LegacyPanelStateMigrationRules {
    private static final int REMOTE_STATUS_MISSING = -1;
    private static final int REMOTE_STATUS_PRINTED = 1;
    private static final int REMOTE_STATUS_FAILED = 2;
    private static final int REMOTE_STATUS_ONGOING = 3;
    private static final int REMOTE_STATUS_UNKNOWN = 4;
    private static final String REMOTE_PRINT_STATUS_KEY = "remotePrintStatus";
    private static final String REMOTE_PRINT_ID_KEY = "remotePrintId";
    private static final String LEGACY_REMOTE_STATUS_KEY = "cloud" + "Status";
    private static final String LEGACY_REMOTE_ID_KEY = "cloud" + "Id";
    private static final String PREVIOUS_ROUND_PREFIX = "prevRoundMissing_";

    private LegacyPanelStateMigrationRules() {}

    /** Current neutral names win; signed-v1 print fields remain read-only fallback input. */
    static int remotePrintStatus(JSONObject unit, int fallback) {
        if (unit == null) return fallback;
        if (unit.has(REMOTE_PRINT_STATUS_KEY)) {
            return unit.optInt(REMOTE_PRINT_STATUS_KEY, fallback);
        }
        return unit.optInt(LEGACY_REMOTE_STATUS_KEY, fallback);
    }

    static long remotePrintId(JSONObject unit) {
        if (unit == null) return 0L;
        if (unit.has(REMOTE_PRINT_ID_KEY)) return unit.optLong(REMOTE_PRINT_ID_KEY, 0L);
        return unit.optLong(LEGACY_REMOTE_ID_KEY, 0L);
    }

    /** Structural validation for an already receipt-bound mirror across later catalog revisions. */
    static boolean validRoundLedger(String raw) {
        return validRoundLedger(raw, null, false);
    }

    /** Initial unscoped adoption additionally needs every row owned by the active exact catalog. */
    static boolean validRoundLedger(String raw, JSONArray activeProfiles) {
        return validRoundLedger(raw, activeProfiles, true);
    }

    private static boolean validRoundLedger(String raw, JSONArray activeProfiles,
                                            boolean requireActiveProfile) {
        try {
            JSONArray ledger = new JSONArray(raw);
            for (int index = 0; index < ledger.length(); index++) {
                JSONObject round = ledger.optJSONObject(index);
                Object timestamp = round == null ? null : round.opt("ts");
                boolean validTimestamp = timestamp instanceof Byte
                    || timestamp instanceof Short || timestamp instanceof Integer
                    || timestamp instanceof Long;
                Object rawProfileId = round == null ? null : round.opt("profileId");
                String profileId = rawProfileId instanceof String
                    ? (String) rawProfileId : "";
                JSONArray units = round == null ? null : round.optJSONArray("units");
                if (round == null || !validTimestamp
                        || ((Number) timestamp).longValue() <= 0L
                        || profileId.isEmpty()
                        || (requireActiveProfile
                            && !uniqueProfile(activeProfiles, profileId))
                        || units == null || units.length() == 0
                        || !optionalNonEmptyString(round, "tsText")
                        || !optionalIntegerRange(round, "retentionDays", 1, 30)) return false;
                for (int unitIndex = 0; unitIndex < units.length(); unitIndex++) {
                    JSONObject unit = units.optJSONObject(unitIndex);
                    if (!validLedgerUnit(unit)) return false;
                }
            }
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }

    /** The profile id encoded in the old dynamic preference key must still be unique and current. */
    static boolean validPreviousRoundKey(String logicalKey, JSONArray activeProfiles) {
        if (logicalKey == null || !logicalKey.startsWith(PREVIOUS_ROUND_PREFIX)) return false;
        String profileId = logicalKey.substring(PREVIOUS_ROUND_PREFIX.length());
        return uniqueProfile(activeProfiles, profileId);
    }

    /**
     * Structural validation for an already receipt-bound stats mirror. Legacy root result counters
     * are global aggregates and therefore have no profile owner.
     */
    static boolean validDailyStats(String raw) {
        return validDailyStats(raw, null, false);
    }

    /** Initial unscoped per-profile maps additionally need active exact-catalog owners. */
    static boolean validDailyStats(String raw, JSONArray activeProfiles) {
        return validDailyStats(raw, activeProfiles, true);
    }

    private static boolean validDailyStats(String raw, JSONArray activeProfiles,
                                           boolean requireActiveProfile) {
        try {
            JSONObject stats = new JSONObject(raw);
            JSONArray counted = stats.optJSONArray("counted");
            if (stats.has("counted") && counted == null) return false;
            for (int index = 0; counted != null && index < counted.length(); index++) {
                if (!(counted.opt(index) instanceof String)) return false;
            }

            JSONObject results = stats.optJSONObject("results");
            if (stats.has("results") && results == null) return false;
            JSONArray profileNames = results == null ? null : results.names();
            for (int index = 0; profileNames != null && index < profileNames.length(); index++) {
                String profileId = profileNames.optString(index, "");
                if (profileId.isEmpty()
                        || (requireActiveProfile
                            && !uniqueProfile(activeProfiles, profileId))
                        || !validCounterValues(results.optJSONObject(profileId))) return false;
            }

            JSONObject legacy = stats.optJSONObject(DailyStatsRules.LEGACY_RESULTS);
            if (stats.has(DailyStatsRules.LEGACY_RESULTS) && legacy == null) return false;
            if (legacy != null && !validCounterValues(legacy)) return false;

            JSONArray rootKeys = stats.names();
            for (int index = 0; rootKeys != null && index < rootKeys.length(); index++) {
                String key = rootKeys.optString(index, "");
                if ("counted".equals(key) || "results".equals(key)
                        || DailyStatsRules.LEGACY_RESULTS.equals(key)) continue;
                if (!validCounterValue(stats.opt(key))) return false;
            }
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }

    private static boolean uniqueProfile(JSONArray profiles, String profileId) {
        if (profiles == null || profileId == null || profileId.isEmpty()) return false;
        int matches = 0;
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject profile = profiles.optJSONObject(index);
            if (profile != null && profileId.equals(profile.optString("id", ""))) matches++;
        }
        return matches == 1;
    }

    /**
     * Signed-v1 writers always emitted this exact control tuple. Optional display metadata and
     * unknown forward fields remain compatible, but fields that decide cloud verification or
     * reprint eligibility must keep their original JSON types and value relationships.
     */
    private static boolean validLedgerUnit(JSONObject unit) {
        if (unit == null) return false;
        Object rawSn = unit.opt("sn");
        Object rawSubmit = unit.opt("submit");
        Object rawPrinted = unit.opt("printed");
        if (!(rawSn instanceof String) || ((String) rawSn).trim().isEmpty()
                || !(rawSubmit instanceof String) || !(rawPrinted instanceof String)) {
            return false;
        }
        String submit = (String) rawSubmit;
        String printed = (String) rawPrinted;
        boolean submitted = "ok".equals(submit);
        if (!submitted && !"failed".equals(submit)) return false;
        if (submitted) {
            if (!"ok".equals(printed) && !"unconfirmed".equals(printed)) return false;
        } else if (!"na".equals(printed)) {
            return false;
        }
        if (!optionalNonEmptyString(unit, "grade")) return false;
        if (!validRemotePair(unit, REMOTE_PRINT_STATUS_KEY, REMOTE_PRINT_ID_KEY, false)
                || !validRemotePair(unit, LEGACY_REMOTE_STATUS_KEY,
                    LEGACY_REMOTE_ID_KEY, true)) return false;
        boolean hasCurrent = unit.has(REMOTE_PRINT_STATUS_KEY);
        boolean hasLegacy = unit.has(LEGACY_REMOTE_STATUS_KEY);
        if (!submitted && (hasCurrent || hasLegacy)) return false;
        if (hasCurrent && hasLegacy) {
            int current = ((Number) unit.opt(REMOTE_PRINT_STATUS_KEY)).intValue();
            int legacy = canonicalLegacyStatus(
                ((Number) unit.opt(LEGACY_REMOTE_STATUS_KEY)).intValue());
            long currentId = ((Number) unit.opt(REMOTE_PRINT_ID_KEY)).longValue();
            long legacyId = ((Number) unit.opt(LEGACY_REMOTE_ID_KEY)).longValue();
            if (current != legacy || currentId != legacyId) return false;
        }
        return true;
    }

    private static boolean validRemotePair(JSONObject unit, String statusKey, String idKey,
                                           boolean legacy) {
        boolean hasStatus = unit.has(statusKey);
        boolean hasId = unit.has(idKey);
        if (hasStatus != hasId) return false;
        if (!hasStatus) return true;
        Object rawStatus = unit.opt(statusKey);
        Object rawId = unit.opt(idKey);
        if (!exactInteger(rawStatus) || !exactInteger(rawId)) return false;
        long id = ((Number) rawId).longValue();
        if (id < 0L) return false;
        long status = ((Number) rawStatus).longValue();
        boolean validStatus = legacy
            ? status == REMOTE_STATUS_MISSING || status == 0
                || status == REMOTE_STATUS_PRINTED || status == REMOTE_STATUS_FAILED
            : status == REMOTE_STATUS_MISSING || status == REMOTE_STATUS_PRINTED
                || status == REMOTE_STATUS_FAILED || status == REMOTE_STATUS_ONGOING
                || status == REMOTE_STATUS_UNKNOWN;
        return validStatus && (status != REMOTE_STATUS_MISSING || id == 0L);
    }

    private static int canonicalLegacyStatus(int status) {
        return status == 0 ? REMOTE_STATUS_ONGOING : status;
    }

    private static boolean optionalNonEmptyString(JSONObject value, String key) {
        if (!value.has(key)) return true;
        Object raw = value.opt(key);
        return raw instanceof String && !((String) raw).trim().isEmpty();
    }

    private static boolean optionalIntegerRange(JSONObject value, String key,
                                                int minimum, int maximum) {
        if (!value.has(key)) return true;
        Object raw = value.opt(key);
        if (!exactInteger(raw)) return false;
        long number = ((Number) raw).longValue();
        return number >= minimum && number <= maximum;
    }

    private static boolean exactInteger(Object value) {
        return value instanceof Byte || value instanceof Short
            || value instanceof Integer || value instanceof Long;
    }

    private static boolean validCounterValues(JSONObject values) {
        if (values == null) return false;
        JSONArray keys = values.names();
        for (int index = 0; keys != null && index < keys.length(); index++) {
            if (!validCounterValue(values.opt(keys.optString(index)))) return false;
        }
        return true;
    }

    private static boolean validCounterValue(Object value) {
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) return false;
        long number = ((Number) value).longValue();
        return number >= 0L && number <= Integer.MAX_VALUE;
    }
}
