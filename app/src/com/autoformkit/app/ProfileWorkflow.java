package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** Profile-owned workflow switches. No production workflow is enabled implicitly. */
final class ProfileWorkflow {
    static final String ACTION_BLOCK = "block";
    static final String ACTION_CONFIRM = "confirm";
    static final String ACTION_CONTINUE = "continue";
    static final String ACTION_SKIP_AS_SUBMITTED = "skip_as_submitted";
    static final String ACTION_AUTO = "auto";
    static final String ACTION_REMOVE = "remove";
    static final String ACTION_REQUIRE_ARTIFACT = "require_artifact";
    static final String IDENTIFIER_CASE_PRESERVE = "preserve";
    static final String IDENTIFIER_CASE_MATCH_EXISTING = "match_existing";
    static final String DUPLICATE_AGE_DAYS = "days";
    static final String DUPLICATE_AGE_CALENDAR_MONTHS = "calendar_months";
    static final String PRINT_STATUS_FAILED = "failed";
    static final String PRINT_STATUS_ONGOING = "ongoing";
    static final String PRINT_STATUS_UNKNOWN = "unknown";
    static final String PRINT_BATCH_END_INLINE_ONLY = "inline_only";
    static final String PRINT_BATCH_END_DEFERRED_MISSING_TWO_PASS =
        "deferred_missing_two_pass";
    static final String PRINT_UNKNOWN_PRESENTATION_AS_ONGOING = "as_ongoing";
    static final String PRINT_UNKNOWN_PRESENTATION_DISTINCT = "distinct";
    static final String STRUCTURED_NON_SUCCESS_LOCK = "lock";
    static final String STRUCTURED_NON_SUCCESS_REJECT_AS_NOT_WRITTEN =
        "reject_as_not_written";

    final boolean declared;
    final boolean operationalPoliciesExplicit;
    final boolean previousStepsEnabled;
    final boolean scanPrecheckEnabled;
    final Set<String> scanPrecheckExcludedResultKeys;
    final Set<String> previousStepTriggerResultKeys;
    final Set<String> directCreateResultKeys;
    final List<WorkflowArtifact> workflowArtifacts;
    final String legacyDraftArtifactKey;
    final List<PreviousStepRecipe> previousStepRecipes;
    final List<DynamicPreviousStepRecipe> dynamicPreviousStepRecipes;
    final List<String> dynamicPreviousStepErrors;
    final boolean identifierCorrectionEnabled;
    final List<IdentifierSubstitution> identifierSubstitutions;
    final Set<String> identifierCorrectionResultKeys;
    final String identifierCorrectionApplyAction;
    final String identifierCasePolicy;
    final int scanPrecheckMaxMissingAttempts;
    final String scanPrecheckBeforeLimitAction;
    final String scanPrecheckAtLimitAction;
    final int previousStepVerifyAttempts;
    final long previousStepVerifyDelayMs;
    final int previousStepRecipeMaxAttempts;
    final long previousStepRecipeRetryDelayMs;
    final boolean includeOptionalPhotoSlots;
    final boolean duplicateCheckEnabled;
    final String duplicateAgeUnit;
    final int duplicateAgeValue;
    final String duplicateUnknownDateAction;
    final String duplicateRecentAction;
    final String duplicateEligibleAction;
    final boolean refreshMaterialsBeforeSubmit;
    final boolean missingRecoveryEnabled;
    final boolean missingRecoveryLocalNotice;
    final boolean submissionSummaryNotificationEnabled;
    final String notificationProfileLabel;
    final boolean printingEnabled;
    final String printingPreflightAction;
    final int printingConfirmationPolls;
    final long printingConfirmationPollIntervalMs;
    final int printingMaxAutoReprints;
    final long printingFinalRecheckDelayMs;
    final String printingOnUnconfirmed;
    final String printingBatchEndRecheckMode;
    final String printingUnknownStatusPresentation;
    final boolean printingManualReprintEnabled;
    final Set<String> printingManualReprintStatuses;
    final boolean printingManualReprintRequiresConfirmation;
    final int submissionMaxAttempts;
    final long submissionRetryDelayMs;
    final long submissionInterUnitDelayMs;
    final int roundLedgerRetentionDays;
    final int submissionMaxConsecutiveFailures;
    final String submissionStructuredNonSuccessAction;
    final int submissionNetworkRetryMaxAttempts;
    final long submissionNetworkRetryBaseDelayMs;
    final long submissionNetworkRetryMaxDelayMs;

    private ProfileWorkflow(boolean declared, boolean operationalPoliciesExplicit,
                            boolean previousStepsEnabled,
                            boolean scanPrecheckEnabled, Set<String> scanPrecheckExcludedResultKeys,
                            Set<String> previousStepTriggerResultKeys,
                            Set<String> directCreateResultKeys,
                            List<WorkflowArtifact> workflowArtifacts,
                            String legacyDraftArtifactKey,
                            List<PreviousStepRecipe> previousStepRecipes,
                            List<DynamicPreviousStepRecipe> dynamicPreviousStepRecipes,
                            List<String> dynamicPreviousStepErrors,
                            ParsedPreviousStepPolicies previousPolicies,
                            boolean includeOptionalPhotoSlots,
                            boolean duplicateCheckEnabled,
                            boolean refreshMaterialsBeforeSubmit,
                            boolean submissionSummaryNotificationEnabled,
                            String notificationProfileLabel,
                            ParsedPolicies policies) {
        this.declared = declared;
        this.operationalPoliciesExplicit = operationalPoliciesExplicit;
        this.previousStepsEnabled = previousStepsEnabled;
        this.scanPrecheckEnabled = scanPrecheckEnabled;
        this.scanPrecheckExcludedResultKeys = Collections.unmodifiableSet(
            new LinkedHashSet<>(scanPrecheckExcludedResultKeys));
        this.previousStepTriggerResultKeys = Collections.unmodifiableSet(
            new LinkedHashSet<>(previousStepTriggerResultKeys));
        this.directCreateResultKeys = Collections.unmodifiableSet(
            new LinkedHashSet<>(directCreateResultKeys));
        this.workflowArtifacts = Collections.unmodifiableList(new ArrayList<>(workflowArtifacts));
        this.legacyDraftArtifactKey = legacyDraftArtifactKey == null
            ? "" : legacyDraftArtifactKey;
        this.previousStepRecipes = Collections.unmodifiableList(new ArrayList<>(previousStepRecipes));
        this.dynamicPreviousStepRecipes = Collections.unmodifiableList(
            new ArrayList<>(dynamicPreviousStepRecipes));
        this.dynamicPreviousStepErrors = Collections.unmodifiableList(
            new ArrayList<>(dynamicPreviousStepErrors));
        this.identifierCorrectionEnabled = previousPolicies.identifierCorrectionEnabled;
        this.identifierSubstitutions = Collections.unmodifiableList(
            new ArrayList<>(previousPolicies.identifierSubstitutions));
        this.identifierCorrectionResultKeys = Collections.unmodifiableSet(
            new LinkedHashSet<>(previousPolicies.identifierCorrectionResultKeys));
        this.identifierCorrectionApplyAction = previousPolicies.identifierCorrectionApplyAction;
        this.identifierCasePolicy = previousPolicies.identifierCasePolicy;
        this.scanPrecheckMaxMissingAttempts = previousPolicies.scanPrecheckMaxMissingAttempts;
        this.scanPrecheckBeforeLimitAction = previousPolicies.scanPrecheckBeforeLimitAction;
        this.scanPrecheckAtLimitAction = previousPolicies.scanPrecheckAtLimitAction;
        this.previousStepVerifyAttempts = previousPolicies.previousStepVerifyAttempts;
        this.previousStepVerifyDelayMs = previousPolicies.previousStepVerifyDelayMs;
        this.previousStepRecipeMaxAttempts = previousPolicies.previousStepRecipeMaxAttempts;
        this.previousStepRecipeRetryDelayMs = previousPolicies.previousStepRecipeRetryDelayMs;
        this.includeOptionalPhotoSlots = includeOptionalPhotoSlots;
        this.duplicateCheckEnabled = duplicateCheckEnabled;
        this.duplicateAgeUnit = policies.duplicateAgeUnit;
        this.duplicateAgeValue = policies.duplicateAgeValue;
        this.duplicateUnknownDateAction = policies.duplicateUnknownDateAction;
        this.duplicateRecentAction = policies.duplicateRecentAction;
        this.duplicateEligibleAction = policies.duplicateEligibleAction;
        this.refreshMaterialsBeforeSubmit = refreshMaterialsBeforeSubmit;
        this.missingRecoveryEnabled = policies.missingRecoveryEnabled;
        this.missingRecoveryLocalNotice = policies.missingRecoveryLocalNotice;
        this.submissionSummaryNotificationEnabled = submissionSummaryNotificationEnabled;
        this.notificationProfileLabel = notificationProfileLabel == null
            ? "" : notificationProfileLabel;
        this.printingEnabled = policies.printingEnabled;
        this.printingPreflightAction = policies.printingPreflightAction;
        this.printingConfirmationPolls = policies.printingConfirmationPolls;
        this.printingConfirmationPollIntervalMs = policies.printingConfirmationPollIntervalMs;
        this.printingMaxAutoReprints = policies.printingMaxAutoReprints;
        this.printingFinalRecheckDelayMs = policies.printingFinalRecheckDelayMs;
        this.printingOnUnconfirmed = policies.printingOnUnconfirmed;
        this.printingBatchEndRecheckMode = policies.printingBatchEndRecheckMode;
        this.printingUnknownStatusPresentation =
            policies.printingUnknownStatusPresentation;
        this.printingManualReprintEnabled = policies.printingManualReprintEnabled;
        this.printingManualReprintStatuses = Collections.unmodifiableSet(
            new LinkedHashSet<>(policies.printingManualReprintStatuses));
        this.printingManualReprintRequiresConfirmation =
            policies.printingManualReprintRequiresConfirmation;
        this.submissionMaxAttempts = policies.submissionMaxAttempts;
        this.submissionRetryDelayMs = policies.submissionRetryDelayMs;
        this.submissionInterUnitDelayMs = policies.submissionInterUnitDelayMs;
        this.roundLedgerRetentionDays = policies.roundLedgerRetentionDays;
        this.submissionMaxConsecutiveFailures = policies.submissionMaxConsecutiveFailures;
        this.submissionStructuredNonSuccessAction =
            policies.submissionStructuredNonSuccessAction;
        this.submissionNetworkRetryMaxAttempts = policies.submissionNetworkRetryMaxAttempts;
        this.submissionNetworkRetryBaseDelayMs = policies.submissionNetworkRetryBaseDelayMs;
        this.submissionNetworkRetryMaxDelayMs = policies.submissionNetworkRetryMaxDelayMs;
    }

    static ProfileWorkflow from(JSONObject profile) {
        JSONObject workflow = profile == null ? null : profile.optJSONObject("workflow");
        if (workflow == null) {
            return new ProfileWorkflow(false, false, false, false, Collections.emptySet(),
                Collections.emptySet(), Collections.emptySet(), Collections.emptyList(), "",
                Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                ParsedPreviousStepPolicies.defaults(),
                false, false, false, false, "", ParsedPolicies.defaults());
        }
        JSONObject previous = workflow.optJSONObject("previousSteps");
        JSONObject photos = workflow.optJSONObject("photos");
        JSONObject duplicate = workflow.optJSONObject("duplicateCheck");
        JSONObject materials = workflow.optJSONObject("materials");
        JSONObject notifications = workflow.optJSONObject("notifications");
        JSONObject printing = workflow.optJSONObject("printing");
        JSONObject submission = workflow.optJSONObject("submission");

        boolean previousEnabled = previous != null && previous.optBoolean("enabled", false);
        boolean scanPrecheck = previousEnabled && previous.optBoolean("scanPrecheck", false);
        Set<String> exclusions = new LinkedHashSet<>();
        JSONArray excluded = previous == null ? null
            : previous.optJSONArray("scanPrecheckExcludedResultKeys");
        for (int i = 0; excluded != null && i < excluded.length(); i++) {
            String value = excluded.optString(i, "").trim();
            if (!value.isEmpty()) exclusions.add(value);
        }

        Set<String> triggers = strings(previous == null ? null
            : previous.optJSONArray("triggerResultKeys"));
        Set<String> directCreate = strings(previous == null ? null
            : previous.optJSONArray("directCreateResultKeys"));
        List<String> dynamicRecipeErrors = new ArrayList<>();
        List<WorkflowArtifact> artifacts = new ArrayList<>();
        JSONArray artifactJson = previous == null ? null : previous.optJSONArray("artifacts");
        for (int i = 0; artifactJson != null && i < artifactJson.length(); i++) {
            JSONObject item = artifactJson.optJSONObject(i);
            WorkflowArtifact artifact = WorkflowArtifact.from(item, i, dynamicRecipeErrors);
            if (artifact != null) artifacts.add(artifact);
        }
        Object rawLegacyArtifactKey = previous == null
            ? null : previous.opt("legacyDraftArtifactKey");
        String legacyArtifactKey = rawLegacyArtifactKey instanceof String
            ? ((String) rawLegacyArtifactKey).trim() : "";
        List<PreviousStepRecipe> recipes = new ArrayList<>();
        List<DynamicPreviousStepRecipe> dynamicRecipes = new ArrayList<>();
        JSONArray recipeJson = previous == null ? null : previous.optJSONArray("templates");
        for (int i = 0; recipeJson != null && i < recipeJson.length(); i++) {
            JSONObject rawRecipe = recipeJson.optJSONObject(i);
            if (rawRecipe != null && rawRecipe.has("mode")) {
                DynamicPreviousStepRecipe recipe = DynamicPreviousStepRecipe.from(
                    rawRecipe, i, dynamicRecipeErrors);
                if (recipe != null) dynamicRecipes.add(recipe);
            } else {
                PreviousStepRecipe recipe = PreviousStepRecipe.from(rawRecipe, i);
                if (recipe != null) recipes.add(recipe);
            }
        }

        boolean duplicateEnabled = duplicate != null && duplicate.optBoolean("enabled", false);
        boolean includeOptionalPhotos = photos != null
            && photos.optBoolean("includeOptionalSlots", false);
        boolean refreshMaterials = materials != null
            && materials.optBoolean("refreshBeforeSubmit", false);
        boolean submissionSummaryNotification = notifications != null
            && notifications.optBoolean("submissionSummary", false);
        Object rawNotificationProfileLabel = notifications == null
            ? null : notifications.opt("profileLabel");
        String notificationProfileLabel = rawNotificationProfileLabel instanceof String
            ? ((String) rawNotificationProfileLabel).trim() : "";
        if (notificationProfileLabel.length() > 160) notificationProfileLabel = "";
        ParsedPreviousStepPolicies previousPolicies = ParsedPreviousStepPolicies.from(previous);
        ParsedPolicies policies = ParsedPolicies.from(
            duplicate, printing, materials, submission);
        return new ProfileWorkflow(true, hasExplicitOperationalPolicies(
                workflow, previous, photos, duplicate, printing, materials, submission, notifications),
            previousEnabled, scanPrecheck, exclusions,
            triggers, directCreate, artifacts, legacyArtifactKey, recipes, dynamicRecipes,
            dynamicRecipeErrors, previousPolicies,
            includeOptionalPhotos, duplicateEnabled, refreshMaterials,
            submissionSummaryNotification, notificationProfileLabel, policies);
    }

    private static boolean hasExplicitOperationalPolicies(
            JSONObject workflow, JSONObject previous, JSONObject photos,
            JSONObject duplicate, JSONObject printing,
            JSONObject materials, JSONObject submission, JSONObject notifications) {
        JSONObject correction = previous == null
            ? null : previous.optJSONObject("identifierCorrection");
        JSONObject precheckPolicy = previous == null
            ? null : previous.optJSONObject("scanPrecheckPolicy");
        JSONObject recovery = materials == null
            ? null : materials.optJSONObject("missingRecovery");
        JSONObject networkRetry = submission == null
            ? null : submission.optJSONObject("networkRetry");
        return strictBoolean(workflow, "compatibilityReviewed", false)
            && hasKeys(previous, "enabled", "scanPrecheck", "scanPrecheckExcludedResultKeys",
                "triggerResultKeys", "directCreateResultKeys", "artifacts",
                "legacyDraftArtifactKey", "templates",
                "identifierCorrection",
                "identifierCasePolicy", "scanPrecheckPolicy", "verifyAttempts", "verifyDelayMs",
                "recipeMaxAttempts", "recipeRetryDelayMs")
            && hasKeys(correction, "enabled", "substitutions", "resultKeys", "applyAction")
            && hasKeys(precheckPolicy, "maxMissingAttempts", "beforeLimitAction", "atLimitAction")
            && hasKeys(photos, "includeOptionalSlots")
            && hasKeys(duplicate, "enabled", "agePolicy", "unknownDateAction",
                "recentAction", "eligibleAction")
            && hasKeys(duplicate.optJSONObject("agePolicy"), "unit", "value")
            && hasKeys(printing, "enabled", "preflightAction", "onUnconfirmed",
                "manualReprintEnabled", "manualReprintStatuses",
                "manualReprintRequiresConfirmation", "confirmationPolls",
                "confirmationPollIntervalMs", "maxAutoReprints", "finalRecheckDelayMs")
            && hasKeys(materials, "refreshBeforeSubmit", "missingRecovery")
            && hasKeys(recovery, "enabled", "localNotice")
            && hasKeys(submission, "maxAttempts", "retryDelayMs", "interUnitDelayMs",
                "roundLedgerRetentionDays", "maxConsecutiveFailures", "networkRetry")
            && hasKeys(networkRetry, "maxAttempts", "baseDelayMs", "maxDelayMs")
            && hasKeys(notifications, "submissionSummary");
    }

    private static boolean hasKeys(JSONObject object, String... keys) {
        if (object == null) return false;
        for (String key : keys) if (!object.has(key)) return false;
        return true;
    }

    boolean shouldScanPrecheck(String grade) {
        String value = grade == null ? "" : grade.trim();
        return scanPrecheckEnabled && !scanPrecheckExcludedResultKeys.contains(value);
    }

    boolean shouldAutoCreatePreviousSteps(String resultKey) {
        String value = resultKey == null ? "" : resultKey.trim();
        return previousStepsEnabled && !previousStepRecipes.isEmpty()
            && previousStepTriggerResultKeys.contains(value);
    }

    /** Panel-owned opt-in for results whose previous-step chain is known not to exist yet. */
    boolean shouldDirectCreatePreviousSteps(String resultKey) {
        String value = resultKey == null ? "" : resultKey.trim();
        return previousStepsEnabled && directCreateResultKeys.contains(value);
    }

    /** Dynamic recipes remain typed separately; MainActivity merges them by sourceIndex at runtime. */
    boolean shouldAutoCreateDynamicPreviousSteps(String resultKey) {
        String value = resultKey == null ? "" : resultKey.trim();
        return previousStepsEnabled && dynamicPreviousStepErrors.isEmpty()
            && !dynamicPreviousStepRecipes.isEmpty()
            && previousStepTriggerResultKeys.contains(value);
    }

    boolean shouldAttemptIdentifierCorrection(String resultKey) {
        String value = resultKey == null ? "" : resultKey.trim();
        return previousStepsEnabled && identifierCorrectionEnabled
            && !identifierSubstitutions.isEmpty()
            && (identifierCorrectionResultKeys.isEmpty()
                || identifierCorrectionResultKeys.contains(value));
    }

    boolean shouldMatchExistingIdentifierCase() {
        return IDENTIFIER_CASE_MATCH_EXISTING.equals(identifierCasePolicy);
    }

    boolean allowsManualReprint(String status) {
        String value = status == null ? "" : status.trim();
        return printingEnabled && printingManualReprintEnabled
            && printingManualReprintStatuses.contains(value);
    }

    boolean usesDeferredMissingTwoPassRecheck() {
        return PRINT_BATCH_END_DEFERRED_MISSING_TWO_PASS.equals(
            printingBatchEndRecheckMode);
    }

    boolean presentsUnknownPrintStatusAsOngoing() {
        return PRINT_UNKNOWN_PRESENTATION_AS_ONGOING.equals(
            printingUnknownStatusPresentation);
    }

    boolean isDuplicateEligible(long recordMillis, long nowMillis, TimeZone timeZone) {
        if (recordMillis == Long.MIN_VALUE) return false;
        if (duplicateAgeValue <= 0) return true;
        if (DUPLICATE_AGE_CALENDAR_MONTHS.equals(duplicateAgeUnit)) {
            Calendar threshold = Calendar.getInstance(
                timeZone == null ? TimeZone.getDefault() : timeZone);
            threshold.setTimeInMillis(nowMillis);
            threshold.add(Calendar.MONTH, -duplicateAgeValue);
            return recordMillis <= threshold.getTimeInMillis();
        }
        long ageMillis;
        try {
            ageMillis = Math.multiplyExact((long) duplicateAgeValue, 24L * 60L * 60L * 1000L);
        } catch (ArithmeticException ignored) {
            ageMillis = Long.MAX_VALUE;
        }
        return recordMillis <= nowMillis - ageMillis;
    }

    WorkflowArtifact legacyArtifactTarget() {
        // A v1 draft had one unnamed previous-step photo. Artifact count is not semantic proof:
        // only an exact Panel-owned compatibility mapping may turn it into a current upload source.
        if (legacyDraftArtifactKey.isEmpty()) return null;
        WorkflowArtifact match = null;
        for (WorkflowArtifact artifact : workflowArtifacts) {
            if (!legacyDraftArtifactKey.equals(artifact.key)) continue;
            if (match != null) return null;
            match = artifact;
        }
        return match;
    }

    String workflowArtifactUploadName(String source, String identifier, int index) {
        for (WorkflowArtifact artifact : workflowArtifacts) {
            if (artifact.key.equals(source)) return artifact.formatUploadName(identifier, index);
        }
        return "";
    }

    String canonicalizeIdentifier(String value) {
        if (!identifierCorrectionEnabled || value == null || value.isEmpty()
                || identifierSubstitutions.isEmpty()) {
            return value == null ? "" : value;
        }
        StringBuilder out = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            String replacement = identifierReplacement(character);
            out.append(replacement == null ? character : replacement);
            offset += Character.charCount(codePoint);
        }
        return out.toString();
    }

    List<String> identifierCorrectionCandidates(String value, int maximum) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (!identifierCorrectionEnabled || value == null || value.isEmpty() || maximum <= 0) {
            return new ArrayList<>();
        }
        List<String> characters = identifierCharacters(value);
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < characters.size(); i++) {
            String replacement = identifierReplacement(characters.get(i));
            if (replacement != null && !replacement.equals(characters.get(i))) positions.add(i);
        }
        for (int changes = 1; changes <= positions.size() && out.size() < maximum; changes++) {
            collectIdentifierCorrectionCandidates(
                characters, positions, 0, changes, out, value, maximum);
        }
        return new ArrayList<>(out);
    }

    private void collectIdentifierCorrectionCandidates(List<String> characters,
                                                        List<Integer> positions,
                                                        int start, int changesLeft,
                                                        LinkedHashSet<String> out,
                                                        String original, int maximum) {
        if (out.size() >= maximum) return;
        if (changesLeft == 0) {
            StringBuilder candidate = new StringBuilder();
            for (String character : characters) candidate.append(character);
            String value = candidate.toString();
            if (!value.equals(original)) out.add(value);
            return;
        }
        for (int i = start; i <= positions.size() - changesLeft && out.size() < maximum; i++) {
            int position = positions.get(i);
            String old = characters.get(position);
            String replacement = identifierReplacement(old);
            if (replacement == null || replacement.equals(old)) continue;
            characters.set(position, replacement);
            collectIdentifierCorrectionCandidates(
                characters, positions, i + 1, changesLeft - 1, out, original, maximum);
            characters.set(position, old);
        }
    }

    private String identifierReplacement(String character) {
        for (IdentifierSubstitution substitution : identifierSubstitutions) {
            if (substitution.from.equals(character)) return substitution.to;
        }
        return null;
    }

    private static List<String> identifierCharacters(String value) {
        List<String> out = new ArrayList<>();
        for (int offset = 0; value != null && offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            out.add(new String(Character.toChars(codePoint)));
            offset += Character.charCount(codePoint);
        }
        return out;
    }

    private static Set<String> strings(JSONArray values) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; values != null && i < values.length(); i++) {
            String value = values.optString(i, "").trim();
            if (!value.isEmpty()) out.add(value);
        }
        return out;
    }

    private static boolean strictBoolean(JSONObject object, String key, boolean fallback) {
        if (object == null) return fallback;
        Object value = object.opt(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static int boundedInt(JSONObject object, String key, int fallback,
                                  int minimum, int maximum) {
        if (object == null) return fallback;
        Object raw = object.opt(key);
        if (!(raw instanceof Number)) return fallback;
        double numeric = ((Number) raw).doubleValue();
        if (!Double.isFinite(numeric) || Math.rint(numeric) != numeric) return fallback;
        if (numeric < minimum) return minimum;
        if (numeric > maximum) return maximum;
        return (int) numeric;
    }

    private static String allowedAction(JSONObject object, String key, String fallback,
                                        String... allowed) {
        if (object == null) return fallback;
        Object raw = object.opt(key);
        if (!(raw instanceof String)) return fallback;
        String value = ((String) raw).trim();
        for (String candidate : allowed) {
            if (candidate.equals(value)) return value;
        }
        return fallback;
    }

    private static boolean isSingleVisibleCharacter(String value) {
        return value != null && !value.trim().isEmpty()
            && value.codePointCount(0, value.length()) == 1;
    }

    private static final class ParsedPreviousStepPolicies {
        final boolean identifierCorrectionEnabled;
        final List<IdentifierSubstitution> identifierSubstitutions;
        final Set<String> identifierCorrectionResultKeys;
        final String identifierCorrectionApplyAction;
        final String identifierCasePolicy;
        final int scanPrecheckMaxMissingAttempts;
        final String scanPrecheckBeforeLimitAction;
        final String scanPrecheckAtLimitAction;
        final int previousStepVerifyAttempts;
        final long previousStepVerifyDelayMs;
        final int previousStepRecipeMaxAttempts;
        final long previousStepRecipeRetryDelayMs;

        private ParsedPreviousStepPolicies(boolean identifierCorrectionEnabled,
                                           List<IdentifierSubstitution> identifierSubstitutions,
                                           Set<String> identifierCorrectionResultKeys,
                                           String identifierCorrectionApplyAction,
                                           String identifierCasePolicy,
                                           int scanPrecheckMaxMissingAttempts,
                                           String scanPrecheckBeforeLimitAction,
                                           String scanPrecheckAtLimitAction,
                                           int previousStepVerifyAttempts,
                                           long previousStepVerifyDelayMs,
                                           int previousStepRecipeMaxAttempts,
                                           long previousStepRecipeRetryDelayMs) {
            this.identifierCorrectionEnabled = identifierCorrectionEnabled;
            this.identifierSubstitutions = identifierSubstitutions;
            this.identifierCorrectionResultKeys = identifierCorrectionResultKeys;
            this.identifierCorrectionApplyAction = identifierCorrectionApplyAction;
            this.identifierCasePolicy = identifierCasePolicy;
            this.scanPrecheckMaxMissingAttempts = scanPrecheckMaxMissingAttempts;
            this.scanPrecheckBeforeLimitAction = scanPrecheckBeforeLimitAction;
            this.scanPrecheckAtLimitAction = scanPrecheckAtLimitAction;
            this.previousStepVerifyAttempts = previousStepVerifyAttempts;
            this.previousStepVerifyDelayMs = previousStepVerifyDelayMs;
            this.previousStepRecipeMaxAttempts = previousStepRecipeMaxAttempts;
            this.previousStepRecipeRetryDelayMs = previousStepRecipeRetryDelayMs;
        }

        static ParsedPreviousStepPolicies defaults() {
            return from(null);
        }

        static ParsedPreviousStepPolicies from(JSONObject previous) {
            JSONObject correction = previous == null
                ? null : previous.optJSONObject("identifierCorrection");
            boolean correctionEnabled = strictBoolean(correction, "enabled", false);
            String correctionAction = allowedAction(correction, "applyAction", ACTION_BLOCK,
                ACTION_AUTO, ACTION_CONFIRM, ACTION_BLOCK);
            List<IdentifierSubstitution> substitutions = new ArrayList<>();
            Set<String> seenFrom = new LinkedHashSet<>();
            JSONArray values = correction == null ? null : correction.optJSONArray("substitutions");
            for (int i = 0; values != null && i < values.length() && substitutions.size() < 8; i++) {
                JSONObject item = values.optJSONObject(i);
                if (item == null) continue;
                String from = item.optString("from", null);
                String to = item.optString("to", null);
                if (!isSingleVisibleCharacter(from) || !isSingleVisibleCharacter(to)
                        || !seenFrom.add(from)) continue;
                substitutions.add(new IdentifierSubstitution(from, to));
            }
            Set<String> correctionResultKeys = strings(
                correction == null ? null : correction.optJSONArray("resultKeys"));

            String casePolicy = allowedAction(previous, "identifierCasePolicy",
                IDENTIFIER_CASE_PRESERVE,
                IDENTIFIER_CASE_PRESERVE, IDENTIFIER_CASE_MATCH_EXISTING);
            JSONObject precheck = previous == null
                ? null : previous.optJSONObject("scanPrecheckPolicy");
            int maxMissingAttempts = boundedInt(precheck, "maxMissingAttempts", 1, 1, 10);
            String beforeLimitAction = allowedAction(precheck, "beforeLimitAction", ACTION_BLOCK,
                ACTION_REMOVE, ACTION_BLOCK);
            String atLimitAction = allowedAction(precheck, "atLimitAction", ACTION_BLOCK,
                ACTION_REQUIRE_ARTIFACT, ACTION_BLOCK);
            int verifyAttempts = boundedInt(previous, "verifyAttempts", 1, 1, 10);
            int verifyDelayMs = boundedInt(previous, "verifyDelayMs", 0, 0, 30_000);
            int recipeMaxAttempts = boundedInt(previous, "recipeMaxAttempts", 1, 1, 10);
            int recipeRetryDelayMs = boundedInt(
                previous, "recipeRetryDelayMs", 0, 0, 60_000);
            return new ParsedPreviousStepPolicies(correctionEnabled, substitutions,
                correctionResultKeys,
                correctionAction, casePolicy, maxMissingAttempts, beforeLimitAction,
                atLimitAction, verifyAttempts, verifyDelayMs,
                recipeMaxAttempts, recipeRetryDelayMs);
        }
    }

    private static final class ParsedPolicies {
        final String duplicateAgeUnit;
        final int duplicateAgeValue;
        final String duplicateUnknownDateAction;
        final String duplicateRecentAction;
        final String duplicateEligibleAction;
        final boolean printingEnabled;
        final String printingPreflightAction;
        final int printingConfirmationPolls;
        final long printingConfirmationPollIntervalMs;
        final int printingMaxAutoReprints;
        final long printingFinalRecheckDelayMs;
        final String printingOnUnconfirmed;
        final String printingBatchEndRecheckMode;
        final String printingUnknownStatusPresentation;
        final boolean printingManualReprintEnabled;
        final Set<String> printingManualReprintStatuses;
        final boolean printingManualReprintRequiresConfirmation;
        final boolean missingRecoveryEnabled;
        final boolean missingRecoveryLocalNotice;
        final int submissionMaxAttempts;
        final long submissionRetryDelayMs;
        final long submissionInterUnitDelayMs;
        final int roundLedgerRetentionDays;
        final int submissionMaxConsecutiveFailures;
        final String submissionStructuredNonSuccessAction;
        final int submissionNetworkRetryMaxAttempts;
        final long submissionNetworkRetryBaseDelayMs;
        final long submissionNetworkRetryMaxDelayMs;

        private ParsedPolicies(String duplicateAgeUnit, int duplicateAgeValue,
                               String duplicateUnknownDateAction,
                               String duplicateRecentAction, String duplicateEligibleAction,
                               boolean printingEnabled, String printingPreflightAction,
                               int printingConfirmationPolls,
                               long printingConfirmationPollIntervalMs,
                               int printingMaxAutoReprints, long printingFinalRecheckDelayMs,
                               String printingOnUnconfirmed,
                               String printingBatchEndRecheckMode,
                               String printingUnknownStatusPresentation,
                               boolean printingManualReprintEnabled,
                               Set<String> printingManualReprintStatuses,
                               boolean printingManualReprintRequiresConfirmation,
                               boolean missingRecoveryEnabled, boolean missingRecoveryLocalNotice,
                               int submissionMaxAttempts, long submissionRetryDelayMs,
                               long submissionInterUnitDelayMs, int roundLedgerRetentionDays,
                               int submissionMaxConsecutiveFailures,
                               String submissionStructuredNonSuccessAction,
                               int submissionNetworkRetryMaxAttempts,
                               long submissionNetworkRetryBaseDelayMs,
                               long submissionNetworkRetryMaxDelayMs) {
            this.duplicateAgeUnit = duplicateAgeUnit;
            this.duplicateAgeValue = duplicateAgeValue;
            this.duplicateUnknownDateAction = duplicateUnknownDateAction;
            this.duplicateRecentAction = duplicateRecentAction;
            this.duplicateEligibleAction = duplicateEligibleAction;
            this.printingEnabled = printingEnabled;
            this.printingPreflightAction = printingPreflightAction;
            this.printingConfirmationPolls = printingConfirmationPolls;
            this.printingConfirmationPollIntervalMs = printingConfirmationPollIntervalMs;
            this.printingMaxAutoReprints = printingMaxAutoReprints;
            this.printingFinalRecheckDelayMs = printingFinalRecheckDelayMs;
            this.printingOnUnconfirmed = printingOnUnconfirmed;
            this.printingBatchEndRecheckMode = printingBatchEndRecheckMode;
            this.printingUnknownStatusPresentation = printingUnknownStatusPresentation;
            this.printingManualReprintEnabled = printingManualReprintEnabled;
            this.printingManualReprintStatuses = printingManualReprintStatuses;
            this.printingManualReprintRequiresConfirmation =
                printingManualReprintRequiresConfirmation;
            this.missingRecoveryEnabled = missingRecoveryEnabled;
            this.missingRecoveryLocalNotice = missingRecoveryLocalNotice;
            this.submissionMaxAttempts = submissionMaxAttempts;
            this.submissionRetryDelayMs = submissionRetryDelayMs;
            this.submissionInterUnitDelayMs = submissionInterUnitDelayMs;
            this.roundLedgerRetentionDays = roundLedgerRetentionDays;
            this.submissionMaxConsecutiveFailures = submissionMaxConsecutiveFailures;
            this.submissionStructuredNonSuccessAction =
                submissionStructuredNonSuccessAction;
            this.submissionNetworkRetryMaxAttempts = submissionNetworkRetryMaxAttempts;
            this.submissionNetworkRetryBaseDelayMs = submissionNetworkRetryBaseDelayMs;
            this.submissionNetworkRetryMaxDelayMs = submissionNetworkRetryMaxDelayMs;
        }

        static ParsedPolicies defaults() {
            return from(null, null, null, null);
        }

        static ParsedPolicies from(JSONObject duplicate, JSONObject printing,
                                   JSONObject materials, JSONObject submission) {
            JSONObject agePolicy = duplicate == null ? null : duplicate.optJSONObject("agePolicy");
            int legacyDays = boundedInt(duplicate, "minAgeDaysToResubmit", 0, 0, 36_500);
            String ageUnit = allowedAction(agePolicy, "unit", DUPLICATE_AGE_DAYS,
                DUPLICATE_AGE_DAYS, DUPLICATE_AGE_CALENDAR_MONTHS);
            int ageValue = agePolicy == null ? legacyDays
                : boundedInt(agePolicy, "value", 0, 0, 36_500);
            String unknownDateAction = allowedAction(duplicate, "unknownDateAction", ACTION_BLOCK,
                ACTION_SKIP_AS_SUBMITTED, ACTION_CONFIRM, ACTION_BLOCK);
            String recentAction = allowedAction(duplicate, "recentAction", ACTION_BLOCK,
                ACTION_SKIP_AS_SUBMITTED, ACTION_CONFIRM, ACTION_BLOCK);
            String eligibleAction = allowedAction(duplicate, "eligibleAction", ACTION_BLOCK,
                ACTION_CONTINUE, ACTION_CONFIRM, ACTION_BLOCK);

            boolean printEnabled = strictBoolean(printing, "enabled", false);
            String preflightAction = allowedAction(printing, "preflightAction", ACTION_BLOCK,
                ACTION_BLOCK, ACTION_CONFIRM, ACTION_CONTINUE);
            int confirmationPolls = boundedInt(printing, "confirmationPolls", 1, 1, 12);
            int confirmationPollIntervalMs = boundedInt(
                printing, "confirmationPollIntervalMs", 250, 250, 30_000);
            int maxAutoReprints = boundedInt(printing, "maxAutoReprints", 0, 0, 3);
            int finalRecheckDelayMs = boundedInt(
                printing, "finalRecheckDelayMs", 0, 0, 120_000);
            String onUnconfirmed = allowedAction(printing, "onUnconfirmed", ACTION_BLOCK,
                "stop", ACTION_CONTINUE);
            if (ACTION_BLOCK.equals(onUnconfirmed)) onUnconfirmed = "stop";
            String batchEndRecheckMode = allowedAction(
                printing, "batchEndRecheckMode",
                PRINT_BATCH_END_DEFERRED_MISSING_TWO_PASS,
                PRINT_BATCH_END_INLINE_ONLY,
                PRINT_BATCH_END_DEFERRED_MISSING_TWO_PASS);
            String unknownStatusPresentation = allowedAction(
                printing, "unknownStatusPresentation",
                PRINT_UNKNOWN_PRESENTATION_DISTINCT,
                PRINT_UNKNOWN_PRESENTATION_AS_ONGOING,
                PRINT_UNKNOWN_PRESENTATION_DISTINCT);
            boolean manualReprint = strictBoolean(printing, "manualReprintEnabled", false);
            Set<String> manualReprintStatuses = strings(
                printing == null ? null : printing.optJSONArray("manualReprintStatuses"));
            manualReprintStatuses.retainAll(new LinkedHashSet<>(java.util.Arrays.asList(
                PRINT_STATUS_FAILED, PRINT_STATUS_ONGOING, PRINT_STATUS_UNKNOWN)));
            boolean manualReprintRequiresConfirmation = strictBoolean(
                printing, "manualReprintRequiresConfirmation", true);

            JSONObject missingRecovery = materials == null
                ? null : materials.optJSONObject("missingRecovery");
            boolean recoveryEnabled = strictBoolean(missingRecovery, "enabled", false);
            boolean localNotice = strictBoolean(missingRecovery, "localNotice", false);

            int maxAttempts = boundedInt(submission, "maxAttempts", 1, 1, 10);
            int retryDelayMs = boundedInt(submission, "retryDelayMs", 0, 0, 60_000);
            int interUnitDelayMs = boundedInt(
                submission, "interUnitDelayMs", 0, 0, 60_000);
            int retentionDays = boundedInt(
                submission, "roundLedgerRetentionDays", 1, 1, 30);
            int maxConsecutiveFailures = boundedInt(
                submission, "maxConsecutiveFailures", 1, 1, 100);
            String structuredNonSuccessAction = allowedAction(
                submission, "structuredNonSuccessAction",
                STRUCTURED_NON_SUCCESS_LOCK,
                STRUCTURED_NON_SUCCESS_LOCK,
                STRUCTURED_NON_SUCCESS_REJECT_AS_NOT_WRITTEN);
            JSONObject networkRetry = submission == null
                ? null : submission.optJSONObject("networkRetry");
            int networkMaxAttempts = boundedInt(networkRetry, "maxAttempts", 0, 0, 100);
            int networkBaseDelayMs = boundedInt(
                networkRetry, "baseDelayMs", 3_000, 250, 60_000);
            int defaultMaxDelayMs = Math.max(networkBaseDelayMs, 30_000);
            int networkMaxDelayMs = boundedInt(
                networkRetry, "maxDelayMs", defaultMaxDelayMs,
                networkBaseDelayMs, 300_000);

            return new ParsedPolicies(ageUnit, ageValue, unknownDateAction,
                recentAction, eligibleAction,
                printEnabled, preflightAction, confirmationPolls,
                confirmationPollIntervalMs, maxAutoReprints, finalRecheckDelayMs,
                onUnconfirmed, batchEndRecheckMode, unknownStatusPresentation,
                manualReprint, manualReprintStatuses,
                manualReprintRequiresConfirmation,
                recoveryEnabled, localNotice, maxAttempts, retryDelayMs,
                interUnitDelayMs, retentionDays,
                maxConsecutiveFailures, structuredNonSuccessAction,
                networkMaxAttempts, networkBaseDelayMs,
                networkMaxDelayMs);
        }
    }

    static final class IdentifierSubstitution {
        final String from;
        final String to;

        private IdentifierSubstitution(String from, String to) {
            this.from = from;
            this.to = to;
        }
    }

    static final class WorkflowArtifact {
        private static final Set<String> ALLOWED_KEYS = Collections.unmodifiableSet(
            new LinkedHashSet<>(java.util.Arrays.asList(
                "key", "title", "titleI18n", "required", "uploadNameTemplate")));
        final String key;
        final String title;
        final JSONObject titleI18n;
        final boolean required;
        final String uploadNameTemplate;

        private WorkflowArtifact(String key, String title, JSONObject titleI18n, boolean required,
                                 String uploadNameTemplate) {
            this.key = key;
            this.title = title;
            this.titleI18n = titleI18n;
            this.required = required;
            this.uploadNameTemplate = uploadNameTemplate;
        }

        static WorkflowArtifact from(JSONObject json, int index, List<String> errors) {
            String path = "workflow.previousSteps.artifacts[" + index + "]";
            if (json == null) {
                errors.add(path);
                return null;
            }
            boolean valid = true;
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!ALLOWED_KEYS.contains(key)) {
                    errors.add(path + "." + key);
                    valid = false;
                }
            }
            String key = json.optString("key", "").trim();
            String title = json.optString("title", "").trim();
            if (key.isEmpty()) {
                errors.add(path + ".key");
                valid = false;
            }
            if (title.isEmpty()) {
                errors.add(path + ".title");
                valid = false;
            }
            if (!(json.opt("required") instanceof Boolean)) {
                errors.add(path + ".required");
                valid = false;
            }
            Object rawTemplate = json.opt("uploadNameTemplate");
            String uploadNameTemplate = rawTemplate instanceof String
                ? (String) rawTemplate : "";
            if (!validUploadNameTemplate(uploadNameTemplate)) {
                errors.add(path + ".uploadNameTemplate");
                valid = false;
            }
            return valid ? new WorkflowArtifact(key, title, json.optJSONObject("titleI18n"),
                json.optBoolean("required", false), uploadNameTemplate) : null;
        }

        String formatUploadName(String identifier, int index) {
            if (identifier == null || identifier.trim().isEmpty()
                    || !identifier.equals(identifier.trim()) || index < 1) {
                throw new IllegalArgumentException("Invalid workflow artifact upload filename input");
            }
            String formatted = uploadNameTemplate
                .replace("{identifier}", safeFilenameComponent(identifier))
                .replace("{index}", String.valueOf(index));
            if (!validFormattedUploadName(formatted)) {
                throw new IllegalArgumentException("Invalid workflow artifact upload filename");
            }
            return formatted;
        }

        private static boolean validUploadNameTemplate(String value) {
            if (value == null || value.isEmpty() || !value.equals(value.trim())
                    || !value.contains("{identifier}") || hasUnsafeFilenameCharacter(value)) {
                return false;
            }
            String remaining = value.replace("{identifier}", "").replace("{index}", "");
            return remaining.indexOf('{') < 0 && remaining.indexOf('}') < 0;
        }

        private static boolean validFormattedUploadName(String value) {
            return value != null && !value.isEmpty() && !hasUnsafeFilenameCharacter(value)
                && value.indexOf('{') < 0 && value.indexOf('}') < 0;
        }

        private static String safeFilenameComponent(String value) {
            StringBuilder out = new StringBuilder(value.length());
            for (int index = 0; index < value.length(); index++) {
                char item = value.charAt(index);
                boolean allowed = (item >= 'A' && item <= 'Z')
                    || (item >= 'a' && item <= 'z')
                    || (item >= '0' && item <= '9')
                    || item == '.' || item == '_' || item == '-';
                out.append(allowed ? item : '_');
            }
            return out.toString();
        }

        private static boolean hasUnsafeFilenameCharacter(String value) {
            for (int index = 0; index < value.length(); index++) {
                char item = value.charAt(index);
                if (item == '/' || item == '\\' || item == ':' || item == '"'
                        || item <= 0x1f || item == 0x7f) return true;
            }
            return false;
        }

        String localizedTitle(String lang) {
            if (titleI18n != null && lang != null && !"zh".equals(lang)) {
                String translated = titleI18n.optString(lang, "").trim();
                if (!translated.isEmpty()) return translated;
            }
            return title;
        }
    }

    static final class PhotoBinding {
        final String targetField;
        final String source;

        private PhotoBinding(String targetField, String source) {
            this.targetField = targetField;
            this.source = source;
        }

        static PhotoBinding from(JSONObject json) {
            if (json == null) return null;
            String target = json.optString("targetField", "").trim();
            String source = json.optString("source", "").trim();
            return target.isEmpty() || source.isEmpty() ? null : new PhotoBinding(target, source);
        }
    }

    /** Panel-owned template-detail recipe. It never falls back to static recipe fields. */
    static final class DynamicPreviousStepRecipe {
        static final String MODE_TEMPLATE_DETAIL = "template_detail";

        final Object templateId;
        final String resolverId;
        final Object expectedStep;
        final Map<String, String> sources;
        final long delayAfterMs;
        final int sourceIndex;

        DynamicPreviousStepRecipe(Object templateId, String resolverId,
                                  Object expectedStep, Map<String, String> sources,
                                  long delayAfterMs, int sourceIndex) {
            this.templateId = templateId;
            this.resolverId = resolverId;
            this.expectedStep = expectedStep;
            this.sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
            this.delayAfterMs = delayAfterMs;
            this.sourceIndex = sourceIndex;
        }

        static DynamicPreviousStepRecipe from(JSONObject json, int index, List<String> errors) {
            String path = "workflow.previousSteps.templates[" + index + "]";
            if (json == null) {
                errors.add(path);
                return null;
            }
            Set<String> allowed = new LinkedHashSet<>(java.util.Arrays.asList(
                "templateId", "mode", "resolverId", "expectedStep", "sources",
                "delayAfterMs"));
            Iterator<String> keys = json.keys();
            boolean valid = true;
            while (keys.hasNext()) {
                String key = keys.next();
                if (!allowed.contains(key)) {
                    errors.add(path + "." + key);
                    valid = false;
                }
            }
            for (String key : allowed) {
                if (!json.has(key)) {
                    errors.add(path + "." + key);
                    valid = false;
                }
            }
            if (!MODE_TEMPLATE_DETAIL.equals(json.opt("mode"))) {
                errors.add(path + ".mode");
                valid = false;
            }
            Object templateId = json.opt("templateId");
            if (!validDynamicIdentity(templateId)) {
                errors.add(path + ".templateId");
                valid = false;
            }
            Object expectedStep = json.opt("expectedStep");
            if (!validDynamicIdentity(expectedStep)) {
                errors.add(path + ".expectedStep");
                valid = false;
            }
            String resolverId = json.opt("resolverId") instanceof String
                ? ((String) json.opt("resolverId")).trim() : "";
            if (!safeResolverId(resolverId)) {
                errors.add(path + ".resolverId");
                valid = false;
            }
            Map<String, String> sources = new LinkedHashMap<>();
            JSONObject sourceJson = json.optJSONObject("sources");
            if (sourceJson == null || sourceJson.length() > 32) {
                errors.add(path + ".sources");
                valid = false;
            } else {
                Iterator<String> sourceKeys = sourceJson.keys();
                while (sourceKeys.hasNext()) {
                    String alias = sourceKeys.next();
                    Object rawSource = sourceJson.opt(alias);
                    String source = rawSource instanceof String
                        ? ((String) rawSource).trim() : "";
                    if (!safeSourceAlias(alias) || source.isEmpty() || source.length() > 4096) {
                        errors.add(path + ".sources." + alias);
                        valid = false;
                    } else {
                        sources.put(alias, source);
                    }
                }
            }
            Object rawDelay = json.opt("delayAfterMs");
            long delay = exactLong(rawDelay);
            if (delay < 0L || delay > 120_000L) {
                errors.add(path + ".delayAfterMs");
                valid = false;
            }
            return valid ? new DynamicPreviousStepRecipe(
                copyScalar(templateId), resolverId, copyScalar(expectedStep), sources, delay,
                index) : null;
        }

        JSONObject compilerRecipe() throws Exception {
            JSONObject sourceJson = new JSONObject();
            for (Map.Entry<String, String> source : sources.entrySet()) {
                sourceJson.put(source.getKey(), source.getValue());
            }
            return new JSONObject()
                .put("templateId", templateId)
                .put("mode", MODE_TEMPLATE_DETAIL)
                .put("resolverId", resolverId)
                .put("expectedStep", expectedStep)
                .put("sources", sourceJson)
                .put("delayAfterMs", delayAfterMs);
        }

        private static boolean validDynamicIdentity(Object value) {
            if (value instanceof String) {
                String text = ((String) value).trim();
                return !text.isEmpty() && text.length() <= 4096;
            }
            if (!(value instanceof Number)) return false;
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) && number == Math.rint(number)
                && Math.abs(number) <= 9_007_199_254_740_991d;
        }

        private static long exactLong(Object value) {
            if (!(value instanceof Number)) return Long.MIN_VALUE;
            double number = ((Number) value).doubleValue();
            long integer = ((Number) value).longValue();
            return Double.isFinite(number) && number == integer ? integer : Long.MIN_VALUE;
        }

        private static boolean safeResolverId(String value) {
            return safeIdentifier(value, true);
        }

        private static boolean safeSourceAlias(String value) {
            return safeIdentifier(value, false);
        }

        private static boolean safeIdentifier(String value, boolean allowDot) {
            if (value == null || value.isEmpty() || value.length() > 128
                    || "__proto__".equals(value) || "prototype".equals(value)
                    || "constructor".equals(value)) {
                return false;
            }
            char first = value.charAt(0);
            if (!isAsciiLetter(first)) return false;
            for (int i = 1; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (!isAsciiLetter(ch) && (ch < '0' || ch > '9')
                        && ch != '_' && ch != '-' && (!allowDot || ch != '.')) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isAsciiLetter(char ch) {
            return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
        }

        private static Object copyScalar(Object value) {
            return value instanceof String ? ((String) value).trim() : value;
        }
    }

    static final class PreviousStepRecipe {
        final int templateId;
        final Object warehouseId;
        final Object sku;
        final JSONObject fixedData;
        final String serialField;
        final List<PhotoBinding> photoBindings;
        final long delayAfterMs;
        final int sourceIndex;

        PreviousStepRecipe(int templateId, Object warehouseId, Object sku,
                           JSONObject fixedData, String serialField,
                           List<PhotoBinding> photoBindings, long delayAfterMs,
                           int sourceIndex) {
            this.templateId = templateId;
            this.warehouseId = warehouseId;
            this.sku = sku;
            this.fixedData = fixedData;
            this.serialField = serialField;
            this.photoBindings = Collections.unmodifiableList(new ArrayList<>(photoBindings));
            this.delayAfterMs = delayAfterMs;
            this.sourceIndex = sourceIndex;
        }

        static PreviousStepRecipe from(JSONObject json, int sourceIndex) {
            if (json == null) return null;
            int templateId = json.optInt("templateId", 0);
            Object warehouseId = json.opt("warehouseId");
            Object sku = json.opt("sku");
            String serialField = json.optString("serialField", "").trim();
            JSONObject fixedData = json.optJSONObject("fixedData");
            if (templateId <= 0 || !hasConfiguredValue(warehouseId) || !hasConfiguredValue(sku)
                    || serialField.isEmpty() || fixedData == null) return null;
            List<PhotoBinding> bindings = new ArrayList<>();
            JSONArray values = json.optJSONArray("photoBindings");
            for (int i = 0; values != null && i < values.length(); i++) {
                PhotoBinding binding = PhotoBinding.from(values.optJSONObject(i));
                if (binding != null) bindings.add(binding);
            }
            return new PreviousStepRecipe(templateId, warehouseId, sku,
                fixedData, serialField, bindings, Math.max(0L, json.optLong("delayAfterMs", 0L)),
                sourceIndex);
        }

        private static boolean hasConfiguredValue(Object value) {
            return value != null && value != JSONObject.NULL
                && !String.valueOf(value).trim().isEmpty();
        }
    }
}
