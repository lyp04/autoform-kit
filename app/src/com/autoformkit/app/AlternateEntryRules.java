package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, fail-closed resolver for a configured entry that submits to a hidden profile.
 *
 * <p>This class deliberately knows nothing about naming conventions, neighboring templates, UI
 * state, or backend field suffixes. Every destination comes from the source entry configuration
 * and the selected target profile.
 */
final class AlternateEntryRules {
    private static final int MAX_REQUIRED_PHOTOS = 20;
    private static final Set<String> SECTION_KEYS = setOf("enabled", "entries");
    private static final Set<String> ENTRY_KEYS = setOf(
        "id", "title", "titleI18n", "targetProfileId", "identifierRole", "resultKey",
        "photoTargetFields", "joinWith", "minPhotos", "maxPhotos", "uploadNameTemplate",
        "scanner", "submissionRetry", "toggles", "flags", "dataOverrides", "dynamicOverrideFields",
        "dynamicOverrideProviders");
    private static final Set<String> ENTRY_SCANNER_KEYS = setOf("applyExpectedLengthTo");
    private static final Set<String> SUBMISSION_RETRY_KEYS = setOf(
        "maxAttempts", "retryDelayMs");
    private static final Set<String> TOGGLE_KEYS = setOf(
        "key", "label", "labelI18n", "default", "retainUntilExit", "dataOverrides");
    private static final Set<String> FLAG_KEYS = setOf(
        "duplicateCheck", "previousSteps", "printing");
    private static final Set<String> IDENTITY_OVERRIDE_KEYS = setOf(
        "template", "identity", "templateId", "warehouseId", "sku",
        "template.id", "template.warehouseId", "template.sku");

    private AlternateEntryRules() {}

    /**
     * Parses the panel-owned {@code workflow.alternateEntries} section.
     *
     * <p>An absent section is the only legacy-compatible case and means no alternate entries.
     * Once present, the envelope is strict so malformed or ambiguous configuration cannot expose
     * an entry accidentally. The returned array is detached from the source configuration.
     */
    static JSONArray configuredEntries(JSONObject workflow) {
        if (workflow == null || !workflow.has("alternateEntries")) {
            return new JSONArray();
        }
        Object raw = workflow.opt("alternateEntries");
        if (!(raw instanceof JSONObject)) {
            throw invalid("workflow.alternateEntries must be an object");
        }
        JSONObject section = (JSONObject) raw;
        rejectUnknownKeys(section, SECTION_KEYS, "workflow.alternateEntries");
        boolean enabled = requiredBoolean(section, "enabled",
            "workflow.alternateEntries.enabled");
        JSONArray entries = requiredArray(section, "entries",
            "workflow.alternateEntries.entries");
        for (int index = 0; index < entries.length(); index++) {
            if (!(entries.opt(index) instanceof JSONObject)) {
                throw invalid("workflow.alternateEntries.entries[" + index
                    + "] must be an object");
            }
        }
        if (!enabled) {
            if (entries.length() != 0) {
                throw invalid("workflow.alternateEntries.entries must be empty when disabled");
            }
            return new JSONArray();
        }
        if (entries.length() == 0) {
            throw invalid("workflow.alternateEntries.entries must not be empty when enabled");
        }
        return copyArray(entries);
    }

    static final class SubmitIdentity {
        final Object templateId;
        final Object warehouseId;
        final String sku;

        private SubmitIdentity(Object templateId, Object warehouseId, String sku) {
            this.templateId = templateId;
            this.warehouseId = warehouseId;
            this.sku = sku;
        }
    }

    /** UI-neutral toggle metadata, including the requested lifetime policy. */
    static final class TogglePolicy {
        final String key;
        final String label;
        final JSONObject labelI18n;
        final boolean defaultValue;
        final boolean retainUntilExit;

        private TogglePolicy(String key, String label, JSONObject labelI18n,
                             boolean defaultValue, boolean retainUntilExit) {
            this.key = key;
            this.label = label;
            this.labelI18n = copyObject(labelI18n);
            this.defaultValue = defaultValue;
            this.retainUntilExit = retainUntilExit;
        }

        String localizedLabel(String locale) {
            if (locale != null && !locale.isEmpty() && labelI18n != null) {
                String translated = text(labelI18n.opt(locale));
                if (!translated.isEmpty()) return translated;
            }
            return label;
        }
    }

    static final class ExecutionFlags {
        final boolean duplicateCheck;
        final boolean previousSteps;
        final boolean printing;

        private ExecutionFlags(boolean duplicateCheck, boolean previousSteps, boolean printing) {
            this.duplicateCheck = duplicateCheck;
            this.previousSteps = previousSteps;
            this.printing = printing;
        }
    }

    /** Retry only after the backend has explicitly proved that the exact POST was not written. */
    static final class SubmissionRetryPolicy {
        final int maxAttempts;
        final long retryDelayMs;

        private SubmissionRetryPolicy(int maxAttempts, long retryDelayMs) {
            this.maxAttempts = maxAttempts;
            this.retryDelayMs = retryDelayMs;
        }
    }

    static final class Resolution {
        final String entryId;
        final String title;
        final JSONObject targetProfile;
        final SubmitIdentity identity;
        final JSONObject data;
        final List<TogglePolicy> togglePolicies;
        final Map<String, Boolean> effectiveToggleStates;
        final ExecutionFlags flags;
        final SubmissionRetryPolicy submissionRetry;

        private Resolution(String entryId, String title, JSONObject targetProfile,
                           SubmitIdentity identity, JSONObject data,
                           List<TogglePolicy> togglePolicies,
                           Map<String, Boolean> effectiveToggleStates,
                           ExecutionFlags flags,
                           SubmissionRetryPolicy submissionRetry) {
            this.entryId = entryId;
            this.title = title;
            this.targetProfile = copyObject(targetProfile);
            this.identity = identity;
            this.data = copyObject(data);
            this.togglePolicies = Collections.unmodifiableList(
                new ArrayList<>(togglePolicies));
            this.effectiveToggleStates = Collections.unmodifiableMap(
                new LinkedHashMap<>(effectiveToggleStates));
            this.flags = flags;
            this.submissionRetry = submissionRetry;
        }
    }

    /** Resolves and validates the one hidden target before any live lookup or upload starts. */
    static JSONObject targetProfile(JSONArray catalogProfiles, JSONObject entryConfig) {
        if (catalogProfiles == null) throw invalid("catalog profiles are required");
        if (entryConfig == null) throw invalid("entry config is required");
        String targetId = requiredText(entryConfig, "targetProfileId",
            "entry config.targetProfileId");
        JSONObject target = uniqueTarget(catalogProfiles, targetId);
        requireHiddenTarget(target);
        return copyObject(target);
    }

    /**
     * Validates an entry for UI rendering before any live provider lookup is allowed to run.
     *
     * <p>Enabled dynamic providers still own required runtime fields. The UI only needs validated
     * toggle metadata, so this path supplies synthetic non-null values for exactly those fields.
     * Submission must continue to call {@link #resolve} with provider-resolved values.</p>
     */
    static Resolution resolveForUiPreflight(
            JSONObject sourceProfile, JSONArray catalogProfiles,
            JSONObject entryConfig, String serial, List<String> uploadedUrls,
            Map<String, Boolean> toggleStates) {
        JSONObject target = targetProfile(catalogProfiles, entryConfig);
        JSONObject dynamicPlaceholders = new JSONObject();
        for (String field : AlternateEntryDynamicOverrideRules.expectedActiveFields(
                entryConfig, target, toggleStates)) {
            putJson(dynamicPlaceholders, field, "__autoform_ui_preflight__");
        }
        return resolve(sourceProfile, catalogProfiles, entryConfig, serial,
            uploadedUrls, toggleStates, dynamicPlaceholders);
    }

    /** Resolves the exact hidden target and builds its canonical submission data. */
    static Resolution resolve(JSONObject sourceProfile, JSONArray catalogProfiles,
                              JSONObject entryConfig, String serial,
                              List<String> uploadedUrls, Map<String, Boolean> toggleStates,
                              JSONObject dynamicOverrideData) {
        if (sourceProfile == null) throw invalid("source profile is required");
        if (catalogProfiles == null) throw invalid("catalog profiles are required");
        if (entryConfig == null) throw invalid("entry config is required");
        rejectUnknownKeys(entryConfig, ENTRY_KEYS, "entry config");

        String sourceId = requiredText(sourceProfile, "id", "source profile.id");
        String entryId = requiredText(entryConfig, "id", "entry config.id");
        String title = requiredText(entryConfig, "title", "entry config.title");
        validateTitleI18n(entryConfig);
        String targetId = requiredText(entryConfig, "targetProfileId",
            "entry config.targetProfileId");
        if (sourceId.equals(targetId)) {
            throw invalid("source and target profiles must differ");
        }
        String identifierRole = requiredText(entryConfig, "identifierRole",
            "entry config.identifierRole");
        if (!"primary".equals(identifierRole)) {
            throw invalid("entry config.identifierRole must be primary");
        }
        expectedLengthSources(entryConfig);
        SubmissionRetryPolicy submissionRetry = submissionRetry(entryConfig);

        JSONObject target = uniqueTarget(catalogProfiles, targetId);
        requireHiddenTarget(target);
        SubmitIdentity identity = identity(target);

        String serialValue = text(serial);
        if (serialValue.isEmpty()) throw invalid("serial is required");
        JSONObject snFields = target.optJSONObject("snFields");
        String serialField = snFields == null ? "" : text(snFields.opt("primary"));
        if (serialField.isEmpty()) throw invalid("target profile.snFields.primary is required");

        Set<String> knownFields = declaredProfileFields(target);
        Set<String> declaredPhotoFields = declaredPhotoFields(target);
        String resultKey = requiredText(entryConfig, "resultKey", "entry config.resultKey");
        JSONObject gradeMap = target.optJSONObject("gradeMap");
        JSONObject result = gradeMap == null ? null : gradeMap.optJSONObject(resultKey);
        if (result == null) throw invalid("target resultKey is not declared");
        String resultField = requiredText(result, "field", "target result field");
        Object resultValue = requiredJsonValue(result, "value", "target result value");

        JSONArray photoFields = requiredArray(entryConfig, "photoTargetFields",
            "entry config.photoTargetFields");
        if (photoFields.length() == 0) {
            throw invalid("entry config.photoTargetFields must not be empty");
        }
        String joinWith = requiredText(entryConfig, "joinWith", "entry config.joinWith");
        int minPhotos = requiredNonNegativeInt(entryConfig, "minPhotos",
            "entry config.minPhotos");
        int maxPhotos = requiredNonNegativeInt(entryConfig, "maxPhotos",
            "entry config.maxPhotos");
        // maxPhotos=0 is an explicit, Panel-owned "unlimited" policy. The legacy
        // independent-entry flow did not impose an App-side cap, so migrated live
        // panels can retain that behavior without hiding a production decision in
        // the framework. Positive values remain strict finite limits.
        if (minPhotos < 1 || minPhotos > MAX_REQUIRED_PHOTOS
                || (maxPhotos > 0 && maxPhotos < minPhotos)) {
            throw invalid("entry config photo bounds are invalid");
        }
        validateUploadNameTemplate(entryConfig);
        List<String> urls = validatedUrls(uploadedUrls, minPhotos, maxPhotos);
        String joinedUrls = join(urls, joinWith);

        JSONArray toggles = requiredArray(entryConfig, "toggles", "entry config.toggles");
        ExecutionFlags flags = flags(entryConfig);

        JSONObject data = new JSONObject();
        Set<String> baseOwners = new LinkedHashSet<>();
        putBase(data, baseOwners, serialField, serialValue, "serial");
        putBase(data, baseOwners, resultField, resultValue, "result");
        appendOperationFields(target, data, baseOwners);
        appendVisibleChoiceFields(target, data, baseOwners);

        Set<String> configuredPhotoFields = new LinkedHashSet<>();
        for (int index = 0; index < photoFields.length(); index++) {
            String field = arrayText(photoFields, index,
                "entry config.photoTargetFields[" + index + "]");
            if (!configuredPhotoFields.add(field)) {
                throw invalid("duplicate photo target field " + field);
            }
            if (!declaredPhotoFields.contains(field)) {
                throw invalid("photo target field is not declared by target profile: " + field);
            }
            putBase(data, baseOwners, field, joinedUrls, "photo target");
        }

        Set<String> overrideOwners = new LinkedHashSet<>();
        JSONObject configuredOverrides = optionalObject(entryConfig, "dataOverrides",
            "entry config.dataOverrides");
        validateOverrideObject(configuredOverrides, "entry config.dataOverrides", knownFields,
            serialField, overrideOwners);

        List<TogglePolicy> policies = new ArrayList<>();
        Map<String, JSONObject> toggleOverrides = new LinkedHashMap<>();
        Set<String> toggleKeys = new LinkedHashSet<>();
        for (int index = 0; index < toggles.length(); index++) {
            JSONObject toggle = toggles.optJSONObject(index);
            if (toggle == null) {
                throw invalid("entry config.toggles[" + index + "] must be an object");
            }
            String path = "entry config.toggles[" + index + "]";
            rejectUnknownKeys(toggle, TOGGLE_KEYS, path);
            String key = requiredText(toggle, "key", path + ".key");
            if (!toggleKeys.add(key)) throw invalid("duplicate toggle key " + key);
            String label = requiredText(toggle, "label", path + ".label");
            JSONObject labelI18n = optionalLocalizedText(toggle, "labelI18n",
                path + ".labelI18n");
            boolean defaultValue = requiredBoolean(toggle, "default", path + ".default");
            boolean retainUntilExit = requiredBoolean(toggle, "retainUntilExit",
                path + ".retainUntilExit");
            JSONObject overrides = requiredObject(toggle, "dataOverrides",
                path + ".dataOverrides");
            validateOverrideObject(overrides, path + ".dataOverrides", knownFields,
                serialField, overrideOwners);
            policies.add(new TogglePolicy(key, label, labelI18n,
                defaultValue, retainUntilExit));
            toggleOverrides.put(key, overrides);
        }

        Map<String, Boolean> states = effectiveToggleStates(
            policies, toggleKeys, toggleStates);
        Set<String> activeDynamicFields =
            AlternateEntryDynamicOverrideRules.expectedActiveFields(
                entryConfig, target, states);
        JSONObject dynamicOverrides = dynamicOverrideData == null
            ? new JSONObject() : dynamicOverrideData;
        validateDynamicOverrideData(dynamicOverrides, activeDynamicFields);
        applyOverrides(data, configuredOverrides);
        for (TogglePolicy policy : policies) {
            if (Boolean.TRUE.equals(states.get(policy.key))) {
                applyOverrides(data, toggleOverrides.get(policy.key));
            }
        }
        applyOverrides(data, dynamicOverrides);
        return new Resolution(entryId, title, target, identity, data, policies, states, flags,
            submissionRetry);
    }

    /**
     * Formats the Panel-owned upload filename without interpreting arbitrary expressions.
     * Identifier characters outside the App's filename-safe ASCII set are replaced with `_`,
     * matching the existing photo filename policy.
     */
    static String formatUploadName(JSONObject entryConfig, String identifier, int index) {
        if (entryConfig == null) throw invalid("entry config is required");
        String template = validateUploadNameTemplate(entryConfig);
        if (identifier == null || identifier.trim().isEmpty()) {
            throw invalid("upload filename identifier is required");
        }
        if (!identifier.equals(identifier.trim())) {
            throw invalid("upload filename identifier must not have surrounding whitespace");
        }
        if (index < 1) throw invalid("upload filename index must be positive");
        String safeIdentifier = safeFilenameComponent(identifier);
        String formatted = template
            .replace("{identifier}", safeIdentifier)
            .replace("{index}", String.valueOf(index));
        if (hasUnsafeFilenameCharacter(formatted)
                || formatted.indexOf('{') >= 0 || formatted.indexOf('}') >= 0) {
            throw invalid("formatted upload filename is unsafe");
        }
        return formatted;
    }

    /**
     * Returns the explicit source scope used only by this independent entry.
     *
     * <p>The source profile still owns the expected length and every other normalization rule.
     * Keeping this one override on the entry lets a migrated deployment preserve the legacy
     * distinction where the main form length-checked typed identifiers while the independent
     * entry length-checked only OCR/barcode values.
     */
    static Set<String> expectedLengthSources(JSONObject entryConfig) {
        if (entryConfig == null) throw invalid("entry config is required");
        Object rawScanner = entryConfig.opt("scanner");
        if (!(rawScanner instanceof JSONObject)) {
            throw invalid("entry config.scanner is required");
        }
        JSONObject scanner = (JSONObject) rawScanner;
        rejectUnknownKeys(scanner, ENTRY_SCANNER_KEYS, "entry config.scanner");
        JSONArray rawSources = requiredArray(scanner, "applyExpectedLengthTo",
            "entry config.scanner.applyExpectedLengthTo");
        if (rawSources.length() == 0 || rawSources.length() > 3) {
            throw invalid("entry config.scanner.applyExpectedLengthTo must contain 1 to 3 sources");
        }
        LinkedHashSet<String> sources = new LinkedHashSet<>();
        for (int index = 0; index < rawSources.length(); index++) {
            Object raw = rawSources.opt(index);
            if (!(raw instanceof String)) {
                throw invalid("entry config.scanner.applyExpectedLengthTo[" + index
                    + "] must be a string");
            }
            String source = (String) raw;
            if (!(SnScanRules.SOURCE_OCR.equals(source)
                    || SnScanRules.SOURCE_BARCODE.equals(source)
                    || SnScanRules.SOURCE_ENTERED.equals(source))) {
                throw invalid("entry config.scanner.applyExpectedLengthTo[" + index
                    + "] is unsupported");
            }
            if (!sources.add(source)) {
                throw invalid("entry config.scanner.applyExpectedLengthTo contains duplicate "
                    + source);
            }
        }
        return Collections.unmodifiableSet(sources);
    }

    private static String validateUploadNameTemplate(JSONObject entryConfig) {
        Object raw = entryConfig.opt("uploadNameTemplate");
        if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) {
            throw invalid("entry config.uploadNameTemplate is required");
        }
        String template = (String) raw;
        if (!template.equals(template.trim())) {
            throw invalid("entry config.uploadNameTemplate must not have surrounding whitespace");
        }
        if (hasUnsafeFilenameCharacter(template)) {
            throw invalid("entry config.uploadNameTemplate must not contain path separators, "
                + "colon, quotes, or control characters");
        }
        if (!template.contains("{identifier}")) {
            throw invalid("entry config.uploadNameTemplate must contain {identifier}");
        }
        if (!template.contains("{index}")) {
            throw invalid("entry config.uploadNameTemplate must contain {index}");
        }
        String remaining = template
            .replace("{identifier}", "")
            .replace("{index}", "");
        if (remaining.indexOf('{') >= 0 || remaining.indexOf('}') >= 0) {
            throw invalid("entry config.uploadNameTemplate may only use {identifier} and "
                + "{index} placeholders");
        }
        return template;
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
                    || item <= 0x1f || item == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static JSONObject uniqueTarget(JSONArray profiles, String targetId) {
        JSONObject match = null;
        int matches = 0;
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject candidate = profiles.optJSONObject(index);
            if (candidate == null) continue;
            if (targetId.equals(text(candidate.opt("id")))) {
                matches++;
                match = candidate;
            }
        }
        if (matches != 1) {
            throw invalid(matches == 0 ? "target profile is missing"
                : "target profile id is not unique");
        }
        return match;
    }

    private static void requireHiddenTarget(JSONObject target) {
        if (!target.has("pickerVisible") || !(target.opt("pickerVisible") instanceof Boolean)
                || target.optBoolean("pickerVisible", true)) {
            throw invalid("target profile must declare pickerVisible=false");
        }
    }

    private static SubmitIdentity identity(JSONObject target) {
        JSONObject template = target.optJSONObject("template");
        if (template == null) throw invalid("target profile.template is required");
        Object templateId = requiredPositiveInteger(template, "id",
            "target profile.template.id");
        Object warehouseId = requiredPositiveInteger(template, "warehouseId",
            "target profile.template.warehouseId");
        String sku = requiredText(template, "sku", "target profile.template.sku");
        return new SubmitIdentity(copyValue(templateId), copyValue(warehouseId), sku);
    }

    private static ExecutionFlags flags(JSONObject entry) {
        JSONObject flags = requiredObject(entry, "flags", "entry config.flags");
        rejectUnknownKeys(flags, FLAG_KEYS, "entry config.flags");
        boolean duplicate = requiredBoolean(flags, "duplicateCheck",
            "entry config.flags.duplicateCheck");
        boolean previous = requiredBoolean(flags, "previousSteps",
            "entry config.flags.previousSteps");
        boolean printing = requiredBoolean(flags, "printing",
            "entry config.flags.printing");
        if (duplicate || previous || printing) {
            throw invalid("alternate entry flags must all be false");
        }
        return new ExecutionFlags(false, false, false);
    }

    private static SubmissionRetryPolicy submissionRetry(JSONObject entry) {
        if (!entry.has("submissionRetry") || entry.isNull("submissionRetry")) {
            // Old catalogs remain readable, but receive no automatic POST retry until the Panel
            // explicitly owns the decision. Migrated private catalogs declare the legacy values.
            return new SubmissionRetryPolicy(1, 0L);
        }
        JSONObject policy = requiredObject(
            entry, "submissionRetry", "entry config.submissionRetry");
        rejectUnknownKeys(policy, SUBMISSION_RETRY_KEYS,
            "entry config.submissionRetry");
        int maxAttempts = requiredNonNegativeInt(
            policy, "maxAttempts", "entry config.submissionRetry.maxAttempts");
        int retryDelayMs = requiredNonNegativeInt(
            policy, "retryDelayMs", "entry config.submissionRetry.retryDelayMs");
        if (maxAttempts < 1 || maxAttempts > 10) {
            throw invalid("entry config.submissionRetry.maxAttempts must be from 1 to 10");
        }
        if (retryDelayMs > 60_000) {
            throw invalid("entry config.submissionRetry.retryDelayMs must be from 0 to 60000");
        }
        return new SubmissionRetryPolicy(maxAttempts, retryDelayMs);
    }

    private static Set<String> declaredProfileFields(JSONObject profile) {
        Set<String> out = new LinkedHashSet<>();
        JSONObject snFields = profile.optJSONObject("snFields");
        if (snFields == null) throw invalid("target profile.snFields is required");
        for (String role : keys(snFields)) {
            String field = text(snFields.opt(role));
            if (!field.isEmpty()) out.add(field);
        }
        JSONObject gradeMap = profile.optJSONObject("gradeMap");
        if (gradeMap == null) throw invalid("target profile.gradeMap is required");
        for (String resultKey : keys(gradeMap)) {
            JSONObject result = gradeMap.optJSONObject(resultKey);
            if (result == null) throw invalid("target profile.gradeMap entry must be an object");
            String field = requiredText(result, "field", "target profile.gradeMap field");
            out.add(field);
        }
        collectArrayFields(profile, "snPlugins", out);
        collectArrayFields(profile, "snPluginsHidden", out);
        collectArrayFields(profile, "uploadFields", out);
        collectArrayFields(profile, "photoSlots", out);
        collectArrayFields(profile, "optionalSlots", out);
        collectArrayFields(profile, "conditionalFields", out);
        collectArrayFields(profile, "operationFields", out);
        collectArrayFields(profile, "choiceFields", out);
        collectArrayFields(profile, "materialGroups", out);
        return out;
    }

    private static Set<String> declaredPhotoFields(JSONObject profile) {
        Set<String> out = new LinkedHashSet<>();
        collectUniqueArrayFields(profile, "uploadFields", out);
        collectUniqueArrayFields(profile, "photoSlots", out);
        collectUniqueArrayFields(profile, "optionalSlots", out);
        return out;
    }

    private static void collectArrayFields(JSONObject profile, String key, Set<String> target) {
        if (!profile.has(key) || profile.isNull(key)) return;
        JSONArray values = profile.optJSONArray(key);
        if (values == null) throw invalid("target profile." + key + " must be an array");
        for (int index = 0; index < values.length(); index++) {
            JSONObject item = values.optJSONObject(index);
            if (item == null) {
                throw invalid("target profile." + key + "[" + index + "] must be an object");
            }
            String field = requiredText(item, "field",
                "target profile." + key + "[" + index + "].field");
            target.add(field);
        }
    }

    private static void collectUniqueArrayFields(JSONObject profile, String key,
                                                 Set<String> target) {
        if (!profile.has(key) || profile.isNull(key)) return;
        JSONArray values = profile.optJSONArray(key);
        if (values == null) throw invalid("target profile." + key + " must be an array");
        for (int index = 0; index < values.length(); index++) {
            JSONObject item = values.optJSONObject(index);
            if (item == null) {
                throw invalid("target profile." + key + "[" + index + "] must be an object");
            }
            String field = requiredText(item, "field",
                "target profile." + key + "[" + index + "].field");
            if (!target.add(field)) {
                throw invalid("duplicate target photo field " + field);
            }
        }
    }

    private static void appendOperationFields(JSONObject target, JSONObject data,
                                              Set<String> owners) {
        JSONArray fields = target.optJSONArray("operationFields");
        for (int index = 0; fields != null && index < fields.length(); index++) {
            JSONObject item = fields.optJSONObject(index);
            if (item == null) {
                throw invalid("target profile.operationFields[" + index + "] must be an object");
            }
            String field = requiredText(item, "field",
                "target profile.operationFields[" + index + "].field");
            Object value = requiredJsonValue(item, "value",
                "target profile.operationFields[" + index + "].value");
            putBase(data, owners, field, value, "operation field");
        }
    }

    private static void appendVisibleChoiceFields(JSONObject target, JSONObject data,
                                                  Set<String> owners) {
        JSONArray fields = target.optJSONArray("choiceFields");
        for (int index = 0; fields != null && index < fields.length(); index++) {
            JSONObject item = fields.optJSONObject(index);
            if (item == null) {
                throw invalid("target profile.choiceFields[" + index + "] must be an object");
            }
            if (!item.optBoolean("visible", true)) continue;
            String field = requiredText(item, "field",
                "target profile.choiceFields[" + index + "].field");
            Object value = requiredJsonValue(item, "value",
                "target profile.choiceFields[" + index + "].value");
            putBase(data, owners, field, value, "choice field");
        }
    }

    private static void putBase(JSONObject data, Set<String> owners, String field, Object value,
                                String owner) {
        if (!owners.add(field)) {
            throw invalid("target field conflict at " + field + " while adding " + owner);
        }
        putJson(data, field, copyValue(value));
    }

    private static void validateOverrideObject(JSONObject overrides, String path,
                                               Set<String> knownFields, String serialField,
                                               Set<String> owners) {
        for (String field : keys(overrides)) {
            validateOverrideField(field, path, knownFields, serialField);
            if (!owners.add(field)) throw invalid("duplicate override field " + field);
            copyValue(overrides.opt(field));
        }
    }

    private static void validateOverrideField(String field, String path,
                                              Set<String> knownFields, String serialField) {
        String value = text(field);
        if (value.isEmpty() || !value.equals(field)) {
            throw invalid(path + " contains an empty or untrimmed field");
        }
        if (value.equals(serialField)) throw invalid("override cannot replace serial field");
        if (IDENTITY_OVERRIDE_KEYS.contains(value)) {
            throw invalid("override cannot replace template identity");
        }
        if (!knownFields.contains(value)) {
            throw invalid("override field is not declared by target profile: " + value);
        }
    }

    private static Map<String, Boolean> effectiveToggleStates(
            List<TogglePolicy> policies, Set<String> knownKeys,
            Map<String, Boolean> requestedStates) {
        Map<String, Boolean> requested = requestedStates == null
            ? Collections.emptyMap() : requestedStates;
        for (Map.Entry<String, Boolean> state : requested.entrySet()) {
            if (state.getKey() == null || !knownKeys.contains(state.getKey())) {
                throw invalid("unknown toggle state " + state.getKey());
            }
            if (state.getValue() == null) {
                throw invalid("toggle state must be boolean for " + state.getKey());
            }
        }
        Map<String, Boolean> effective = new LinkedHashMap<>();
        for (TogglePolicy policy : policies) {
            boolean value = requested.containsKey(policy.key)
                ? requested.get(policy.key) : policy.defaultValue;
            effective.put(policy.key, value);
        }
        return effective;
    }

    private static void applyOverrides(JSONObject data, JSONObject overrides) {
        for (String field : keys(overrides)) {
            putJson(data, field, copyValue(overrides.opt(field)));
        }
    }

    private static void validateDynamicOverrideData(JSONObject overrides,
                                                    Set<String> expectedFields) {
        Set<String> actual = new LinkedHashSet<>(keys(overrides));
        if (!actual.equals(expectedFields)) {
            throw invalid("runtime dynamic override fields do not match active providers");
        }
        for (String field : actual) {
            Object value = overrides.opt(field);
            if (value == null || value == JSONObject.NULL) {
                throw invalid("runtime dynamic override value must not be null for " + field);
            }
            copyValue(value);
        }
    }

    private static void putJson(JSONObject target, String field, Object value) {
        try {
            target.put(field, value);
        } catch (JSONException error) {
            throw invalid("cannot encode payload field " + field);
        }
    }

    private static List<String> validatedUrls(List<String> uploadedUrls, int minPhotos,
                                              int maxPhotos) {
        List<String> source = uploadedUrls == null ? Collections.emptyList() : uploadedUrls;
        if (source.size() < minPhotos || (maxPhotos > 0 && source.size() > maxPhotos)) {
            throw invalid("uploaded photo count is outside configured bounds");
        }
        List<String> out = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            String value = text(source.get(index));
            if (value.isEmpty()) throw invalid("uploaded photo URL is empty at index " + index);
            out.add(value);
        }
        return out;
    }

    private static String join(List<String> values, String separator) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) out.append(separator);
            out.append(values.get(index));
        }
        return out.toString();
    }

    private static void validateTitleI18n(JSONObject config) {
        if (!config.has("titleI18n") || config.isNull("titleI18n")) return;
        optionalLocalizedText(config, "titleI18n", "entry config.titleI18n");
    }

    private static JSONObject optionalLocalizedText(JSONObject owner, String key, String path) {
        if (!owner.has(key) || owner.isNull(key)) return new JSONObject();
        JSONObject values = owner.optJSONObject(key);
        if (values == null) throw invalid(path + " must be an object");
        for (String locale : keys(values)) {
            if (text(locale).isEmpty() || text(values.opt(locale)).isEmpty()) {
                throw invalid(path + " entries must be non-empty strings");
            }
            if (!(values.opt(locale) instanceof String)) {
                throw invalid(path + " entries must be strings");
            }
        }
        return copyObject(values);
    }

    private static void rejectUnknownKeys(JSONObject value, Set<String> allowed, String path) {
        for (String key : keys(value)) {
            if (!allowed.contains(key)) throw invalid(path + " contains unknown field " + key);
        }
    }

    private static JSONObject requiredObject(JSONObject owner, String key, String path) {
        JSONObject value = owner.optJSONObject(key);
        if (value == null) throw invalid(path + " must be an object");
        return value;
    }

    private static JSONObject optionalObject(JSONObject owner, String key, String path) {
        if (!owner.has(key) || owner.isNull(key)) return new JSONObject();
        return requiredObject(owner, key, path);
    }

    private static JSONArray requiredArray(JSONObject owner, String key, String path) {
        JSONArray value = owner.optJSONArray(key);
        if (value == null) throw invalid(path + " must be an array");
        return value;
    }

    private static String requiredText(JSONObject owner, String key, String path) {
        if (!owner.has(key) || !(owner.opt(key) instanceof String)) {
            throw invalid(path + " is required");
        }
        String value = text(owner.opt(key));
        if (value.isEmpty()) throw invalid(path + " is required");
        return value;
    }

    private static String arrayText(JSONArray values, int index, String path) {
        Object raw = values.opt(index);
        if (!(raw instanceof String)) throw invalid(path + " must be a string");
        String value = text(raw);
        if (value.isEmpty() || !value.equals(raw)) {
            throw invalid(path + " must be a non-empty trimmed string");
        }
        return value;
    }

    private static boolean requiredBoolean(JSONObject owner, String key, String path) {
        Object value = owner.opt(key);
        if (!(value instanceof Boolean)) throw invalid(path + " must be a boolean");
        return (Boolean) value;
    }

    private static int requiredNonNegativeInt(JSONObject owner, String key, String path) {
        Object value = owner.opt(key);
        if (!(value instanceof Number)) throw invalid(path + " must be an integer");
        double numeric = ((Number) value).doubleValue();
        int integer = ((Number) value).intValue();
        if (!Double.isFinite(numeric) || numeric != integer || integer < 0) {
            throw invalid(path + " must be a non-negative integer");
        }
        return integer;
    }

    private static Object requiredPositiveInteger(JSONObject owner, String key, String path) {
        Object value = owner.opt(key);
        if (!(value instanceof Number)) throw invalid(path + " must be a positive integer");
        double numeric = ((Number) value).doubleValue();
        long integer = ((Number) value).longValue();
        if (!Double.isFinite(numeric) || numeric != integer || integer <= 0L) {
            throw invalid(path + " must be a positive integer");
        }
        return value;
    }

    private static Object requiredJsonValue(JSONObject owner, String key, String path) {
        if (!owner.has(key)) throw invalid(path + " is required");
        return copyValue(owner.opt(key));
    }

    private static Object copyValue(Object value) {
        if (value == null || value == JSONObject.NULL) return JSONObject.NULL;
        if (value instanceof JSONObject) return copyObject((JSONObject) value);
        if (value instanceof JSONArray) {
            try {
                return new JSONArray(value.toString());
            } catch (JSONException error) {
                throw invalid("JSON array cannot be copied");
            }
        }
        if (value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number) {
            double numeric = ((Number) value).doubleValue();
            if (!Double.isFinite(numeric)) throw invalid("JSON number must be finite");
            return value;
        }
        throw invalid("override value must be JSON-compatible");
    }

    private static JSONArray copyArray(JSONArray value) {
        try {
            return new JSONArray(value.toString());
        } catch (JSONException error) {
            throw invalid("JSON array cannot be copied");
        }
    }

    private static JSONObject copyObject(JSONObject value) {
        try {
            return new JSONObject(value.toString());
        } catch (JSONException error) {
            throw invalid("JSON object cannot be copied");
        }
    }

    private static String text(Object value) {
        return value == null || value == JSONObject.NULL ? "" : String.valueOf(value).trim();
    }

    private static Set<String> setOf(String... values) {
        Set<String> out = new LinkedHashSet<>();
        Collections.addAll(out, values);
        return Collections.unmodifiableSet(out);
    }

    private static List<String> keys(JSONObject value) {
        List<String> out = new ArrayList<>();
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) out.add(keys.next());
        return out;
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("alternate entry rejected: " + detail);
    }
}
