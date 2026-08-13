package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure fail-closed validation for a catalog before it can become an active Panel snapshot.
 *
 * <p>The Panel remains the authoring authority, but the downloaded JSON is an untrusted cache
 * input. This gate validates only contracts the Android App actually dereferences or executes:
 * profile identity/picker semantics, submit identity, identifier and photo mappings, executable
 * workflow policies, previous-step source closure, alternate-entry target closure, and the
 * optional backend-adapter capabilities selected by those workflows.
 *
 * <p>Three compatibility rules are intentional and bounded:
 * <ul>
 *   <li>If no profile declares {@code pickerVisible}, every profile is picker-visible, matching
 *       {@link ProfilePickerRules} for old catalogs.</li>
 *   <li>A missing {@code defaultPhotoOrder} retains the App's historical grouped order. A present
 *       but malformed value is rejected.</li>
 *   <li>A profile may use legacy {@code uploadFields} when it has no non-empty
 *       {@code photoSlots} array.</li>
 * </ul>
 * These compatibility rules do not make an old, policy-less form executable: every picker-visible
 * profile and every profile participating in an alternate entry must carry the explicit policy
 * set already required by {@link ProfileWorkflow#operationalPoliciesExplicit}.
 */
final class CatalogPromotionValidator {
    private static final int MAX_PROFILE_ID_LENGTH = 256;
    private static final int MAX_ALTERNATE_PHOTOS = 20;
    private static final int MAX_DAILY_STATS_GROUPS = 16;
    private static final int MAX_DAILY_STATS_RESULT_KEYS = 128;
    private static final int MAX_DAILY_STATS_V2_FLAT_SUMMARIES = 8;
    private static final int MAX_DAILY_STATS_V2_SELECTORS = 512;
    private static final int MAX_DAILY_STATS_ID_LENGTH = 128;
    private static final int MAX_DAILY_STATS_LABEL_LENGTH = 160;

    private CatalogPromotionValidator() {}

    /** Intrinsic catalog validation used before a downloaded catalog candidate is written. */
    static boolean isStructurallyValid(JSONObject catalogRoot) {
        try {
            validate(catalogRoot, null, false);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * Whole-pair validation used immediately before promotion and whenever an active pair is read.
     * This repeats intrinsic validation and additionally closes every profile-to-adapter reference.
     */
    static boolean isExecutableWithConfig(JSONObject catalogRoot, JSONObject appConfig) {
        try {
            validate(catalogRoot, appConfig, true);
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static void validate(JSONObject catalogRoot, JSONObject appConfig,
                                 boolean requireAdapter) {
        if (catalogRoot == null) reject("catalog");
        Object rawProfiles = catalogRoot.opt("profiles");
        if (!(rawProfiles instanceof JSONArray)) reject("profiles");
        JSONArray profiles = (JSONArray) rawProfiles;
        if (profiles.length() == 0) reject("profiles.empty");

        boolean legacyPicker = true;
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject profile = objectAt(profiles, index, "profiles");
            if (profile.has("pickerVisible")) legacyPicker = false;
        }

        List<ProfileState> states = new ArrayList<>();
        Map<String, ProfileState> profilesById = new LinkedHashMap<>();
        int visibleCount = 0;
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject profile = objectAt(profiles, index, "profiles");
            String path = "profiles[" + index + "]";
            String id = requiredText(profile.opt("id"), path + ".id");
            if (id.length() > MAX_PROFILE_ID_LENGTH) reject(path + ".id.length");
            if (profilesById.containsKey(id)) reject(path + ".id.duplicate");

            boolean visible;
            if (legacyPicker) {
                visible = true;
            } else if (!profile.has("pickerVisible")) {
                visible = false;
            } else {
                Object rawVisible = profile.opt("pickerVisible");
                if (!(rawVisible instanceof Boolean)) reject(path + ".pickerVisible");
                visible = (Boolean) rawVisible;
            }
            if (visible) visibleCount++;

            ProfileState state = validateProfile(profile, id, path, visible);
            states.add(state);
            profilesById.put(id, state);
        }
        if (visibleCount == 0) reject("profiles.picker");
        validateDailyStats(catalogRoot.optJSONObject("settings"), states);
        validateDailyStatsV2(catalogRoot.optJSONObject("settings"), states);

        // Resolve alternate links only after all unique profile ids are known.
        for (ProfileState source : states) {
            validateAlternateEntries(source, profiles, profilesById);
        }
        validateDailyStatsAlternateEntries(
            catalogRoot.optJSONObject("settings"), states);
        validateDailyStatsAlternateOnlyFlatCoverage(
            catalogRoot.optJSONObject("settings"));

        // A visible profile or either side of an alternate link can open a remote workflow.
        for (ProfileState state : states) {
            if (state.executable) requireExecutablePolicy(state);
        }

        if (requireAdapter) {
            if (appConfig == null) reject("appConfig");
            BackendAdapter adapter = BackendAdapter.from(
                appConfig, catalogRoot.optJSONObject("settings"));
            if (!adapter.isSupported()) reject("backendAdapter");
            for (ProfileState state : states) {
                if (state.executable) {
                    validateAdapterCapabilities(state, adapter, appConfig);
                }
            }
            for (ProfileState source : states) {
                validateAlternateAdapterReferences(source, profiles, profilesById, adapter);
            }
        }
    }

    private static ProfileState validateProfile(JSONObject profile, String id,
                                                String path, boolean visible) {
        validateOptionalUiColor(profile, "uiColor", path + ".uiColor");
        validateSubmitIdentity(profile, path);
        validateIdentifierContract(profile, path);
        PhotoContract photos = validatePhotos(profile, path);
        Set<String> resultKeys = validateResultMap(profile, path);
        boolean hasMaterialItems = validatePayloadFields(profile, path, photos.activeFields);

        if (profile.has("defaultPhotoOrder")) {
            Object rawOrder = profile.opt("defaultPhotoOrder");
            if (!(rawOrder instanceof String)
                    || !(PhotoOrderRules.GROUPED.equals(rawOrder)
                        || PhotoOrderRules.PER_RECORD.equals(rawOrder))) {
                reject(path + ".defaultPhotoOrder");
            }
        }

        ProfileWorkflow workflow = ProfileWorkflow.from(profile);
        validateConfiguredWorkflow(profile, workflow, photos, resultKeys, path);
        return new ProfileState(profile, id, path, visible, workflow, photos,
            resultKeys, hasMaterialItems);
    }

    private static void validateSubmitIdentity(JSONObject profile, String path) {
        JSONObject template = requiredObject(profile.opt("template"), path + ".template");
        positiveInt(template.opt("id"), path + ".template.id");
        positiveInt(template.opt("warehouseId"), path + ".template.warehouseId");
        requiredText(template.opt("sku"), path + ".template.sku");
    }

    private static void validateIdentifierContract(JSONObject profile, String path) {
        JSONObject fields = requiredObject(profile.opt("snFields"), path + ".snFields");
        String primary = requiredText(fields.opt("primary"), path + ".snFields.primary");

        boolean requiresSecond = false;
        if (profile.has("requiresSecondSn")) {
            Object raw = profile.opt("requiresSecondSn");
            if (!(raw instanceof Boolean)) reject(path + ".requiresSecondSn");
            requiresSecond = (Boolean) raw;
        }
        String secondary = optionalText(fields.opt("secondary"), path + ".snFields.secondary");
        if (requiresSecond && secondary.isEmpty()) reject(path + ".snFields.secondary");
        if (!secondary.isEmpty() && primary.equals(secondary)) {
            reject(path + ".snFields.duplicate");
        }

        if (profile.has("expectedSnLength")) {
            positiveInt(profile.opt("expectedSnLength"), path + ".expectedSnLength");
        }
        validateScanner(profile.opt("scanner"), profile.has("scanner"),
            path + ".scanner", profile, true);

        Set<String> pluginKeys = new LinkedHashSet<>();
        validateIdentifierPlugins(profile, "snPlugins", fields, pluginKeys, path);
        validateIdentifierPlugins(profile, "snPluginsHidden", fields,
            new LinkedHashSet<>(), path);
    }

    private static void validateIdentifierPlugins(JSONObject profile, String key,
                                                  JSONObject snFields,
                                                  Set<String> pluginKeys,
                                                  String path) {
        if (!configured(profile, key)) return;
        JSONArray plugins = requiredArray(profile.opt(key), path + "." + key);
        for (int index = 0; index < plugins.length(); index++) {
            JSONObject plugin = objectAt(plugins, index, path + "." + key);
            String itemPath = path + "." + key + "[" + index + "]";
            String pluginKey = requiredText(plugin.opt("key"), itemPath + ".key");
            String field = requiredText(plugin.opt("field"), itemPath + ".field");
            if (!pluginKeys.add(pluginKey)) reject(itemPath + ".key.duplicate");
            if (plugin.has("visible") && !(plugin.opt("visible") instanceof Boolean)) {
                reject(itemPath + ".visible");
            }
            if (plugin.has("required") && !(plugin.opt("required") instanceof Boolean)) {
                reject(itemPath + ".required");
            }
            if (plugin.has("scan") && !(plugin.opt("scan") instanceof Boolean)) {
                reject(itemPath + ".scan");
            }
            validateOptionalInputPlaceholder(plugin, itemPath);
            if (("primary".equals(pluginKey) || "secondary".equals(pluginKey))) {
                String roleField = optionalText(snFields.opt(pluginKey),
                    itemPath + ".field.binding");
                if (!field.equals(roleField)) reject(itemPath + ".field.binding");
            }
            validateScanner(plugin.opt("scanner"), plugin.has("scanner"),
                itemPath + ".scanner", profile, "primary".equals(pluginKey));
        }
    }

    private static void validateScanner(Object raw, boolean configured, String path,
                                        JSONObject profile,
                                        boolean useLegacyPrimaryFallback) {
        if (!configured) return;
        JSONObject scanner = requiredObject(raw, path);
        JSONObject effective = scannerWithLegacyExpectedFallback(
            scanner, profile, useLegacyPrimaryFallback, path);
        if (!SnScanRules.Policy.from(effective).valid) reject(path);
    }

    private static PhotoContract validatePhotos(JSONObject profile, String path) {
        JSONArray requiredSlots = optionalArray(profile, "photoSlots", path);
        JSONArray optionalSlots = optionalArray(profile, "optionalSlots", path);
        JSONArray uploadFields = optionalArray(profile, "uploadFields", path);
        boolean slotMode = requiredSlots != null && requiredSlots.length() > 0;

        Set<String> slotPhotoFields = new LinkedHashSet<>();
        Set<String> requiredFields = validateSlotArray(
            requiredSlots, path + ".photoSlots", slotPhotoFields);
        Set<String> optionalFields = validateSlotArray(
            optionalSlots, path + ".optionalSlots", slotPhotoFields);
        Set<String> uploadPhotoFields = new LinkedHashSet<>();
        Set<String> uploadFieldNames = validateUploadFields(
            uploadFields, path + ".uploadFields", uploadPhotoFields);

        if (!slotMode && uploadFieldNames.isEmpty()) reject(path + ".photos");

        boolean includeOptional = false;
        JSONObject workflow = profile.optJSONObject("workflow");
        JSONObject photoPolicy = workflow == null ? null : workflow.optJSONObject("photos");
        try {
            PhotoInputSourceRules.from(photoPolicy);
        } catch (IllegalArgumentException invalid) {
            reject(path + ".workflow.photos." + PhotoInputSourceRules.KEY);
        }
        if (photoPolicy != null && photoPolicy.has("includeOptionalSlots")) {
            Object raw = photoPolicy.opt("includeOptionalSlots");
            if (!(raw instanceof Boolean)) reject(path + ".workflow.photos.includeOptionalSlots");
            includeOptional = (Boolean) raw;
        }
        if (includeOptional && !slotMode) {
            reject(path + ".workflow.photos.includeOptionalSlots");
        }

        Set<String> active = new LinkedHashSet<>(
            slotMode ? requiredFields : uploadFieldNames);
        if (slotMode && includeOptional) active.addAll(optionalFields);
        Set<String> allPhotoFields = new LinkedHashSet<>(slotPhotoFields);
        allPhotoFields.addAll(uploadPhotoFields);
        return new PhotoContract(slotMode, includeOptional,
            Collections.unmodifiableSet(active),
            Collections.unmodifiableSet(allPhotoFields));
    }

    private static Set<String> validateSlotArray(JSONArray slots, String path,
                                                 Set<String> allPhotoFields) {
        Set<String> fields = new LinkedHashSet<>();
        for (int index = 0; slots != null && index < slots.length(); index++) {
            JSONObject slot = objectAt(slots, index, path);
            String itemPath = path + "[" + index + "]";
            String field = requiredText(slot.opt("field"), itemPath + ".field");
            if (!fields.add(field) || !allPhotoFields.add(field)) {
                reject(itemPath + ".field.duplicate");
            }
            int minimum = nonNegativeInt(slot.opt("minPhotos"), itemPath + ".minPhotos");
            int maximum = positiveInt(slot.opt("maxPhotos"), itemPath + ".maxPhotos");
            if (maximum < minimum) reject(itemPath + ".photoBounds");
            if (slot.has("required") && !(slot.opt("required") instanceof Boolean)) {
                reject(itemPath + ".required");
            }
            if (slot.has("conditional") && !(slot.opt("conditional") instanceof Boolean)) {
                reject(itemPath + ".conditional");
            }
            try {
                PhotoInputSourceRules.from(slot);
            } catch (IllegalArgumentException invalid) {
                reject(itemPath + "." + PhotoInputSourceRules.KEY);
            }
        }
        return fields;
    }

    private static Set<String> validateUploadFields(JSONArray fields, String path,
                                                    Set<String> allPhotoFields) {
        Set<String> out = new LinkedHashSet<>();
        for (int index = 0; fields != null && index < fields.length(); index++) {
            JSONObject item = objectAt(fields, index, path);
            String field = requiredText(item.opt("field"), path + "[" + index + "].field");
            if (!out.add(field) || !allPhotoFields.add(field)) {
                reject(path + "[" + index + "].field.duplicate");
            }
            if (item.has("sources")) {
                JSONArray sources = requiredArray(item.opt("sources"),
                    path + "[" + index + "].sources");
                if (sources.length() == 0) {
                    reject(path + "[" + index + "].sources.empty");
                }
                Set<String> seenSources = new LinkedHashSet<>();
                for (int sourceIndex = 0; sourceIndex < sources.length(); sourceIndex++) {
                    String sourcePath = path + "[" + index + "].sources["
                        + sourceIndex + "]";
                    String source = requiredText(sources.opt(sourceIndex), sourcePath);
                    if (!("front".equals(source) || "back".equals(source))
                            || !seenSources.add(source)) {
                        reject(sourcePath);
                    }
                }
            }
        }
        return out;
    }

    private static Set<String> validateResultMap(JSONObject profile, String path) {
        if (!configured(profile, "gradeMap")) return Collections.emptySet();
        JSONObject results = requiredObject(profile.opt("gradeMap"), path + ".gradeMap");
        Set<String> keys = new LinkedHashSet<>();
        for (String key : objectKeys(results)) {
            if (key.trim().isEmpty() || !key.equals(key.trim())) {
                reject(path + ".gradeMap.key");
            }
            JSONObject item = requiredObject(results.opt(key),
                path + ".gradeMap." + key);
            requiredText(item.opt("field"), path + ".gradeMap." + key + ".field");
            if (!item.has("value")) reject(path + ".gradeMap." + key + ".value");
            validateOptionalBoundedText(item, "operatorLabel",
                path + ".gradeMap." + key + ".operatorLabel",
                MAX_DAILY_STATS_LABEL_LENGTH);
            validateOptionalLocalizedText(item, "operatorLabelI18n",
                path + ".gradeMap." + key + ".operatorLabelI18n");
            validateOptionalUiColor(item, "uiColor",
                path + ".gradeMap." + key + ".uiColor");
            keys.add(key);
        }
        return Collections.unmodifiableSet(keys);
    }

    private static void validateDailyStats(JSONObject settings, List<ProfileState> states) {
        if (settings == null || !settings.has("dailyStats")) return;
        String path = "settings.dailyStats";
        JSONObject dailyStats = requiredObject(settings.opt("dailyStats"), path);
        requireOnlyKeys(dailyStats, setOf("scope", "groups"), path);
        oneOf(dailyStats.opt("scope"), path + ".scope",
            DailyStatsRules.ALL_PROFILES_SCOPE);
        JSONArray groups = requiredArray(dailyStats.opt("groups"), path + ".groups");
        if (groups.length() < 1 || groups.length() > MAX_DAILY_STATS_GROUPS) {
            reject(path + ".groups.length");
        }

        Set<String> visibleResultKeys = new LinkedHashSet<>();
        for (ProfileState state : states) {
            if (state.visible && Boolean.TRUE.equals(state.profile.opt("pickerVisible"))) {
                visibleResultKeys.addAll(state.resultKeys);
            }
        }
        Set<String> groupIds = new LinkedHashSet<>();
        Set<String> groupedResultKeys = new LinkedHashSet<>();
        Set<String> allowedGroupKeys = setOf(
            "id", "label", "labelI18n", "uiColor", "resultKeys");
        for (int index = 0; index < groups.length(); index++) {
            String groupPath = path + ".groups[" + index + "]";
            JSONObject group = objectAt(groups, index, path + ".groups");
            requireOnlyKeys(group, allowedGroupKeys, groupPath);

            String id = boundedText(group.opt("id"), groupPath + ".id",
                MAX_DAILY_STATS_ID_LENGTH);
            if (!groupIds.add(id)) reject(groupPath + ".id.duplicate");
            boundedText(group.opt("label"), groupPath + ".label",
                MAX_DAILY_STATS_LABEL_LENGTH);
            validateOptionalLocalizedText(group, "labelI18n", groupPath + ".labelI18n");
            validateRequiredUiColor(group.opt("uiColor"), groupPath + ".uiColor");

            JSONArray rawResultKeys = requiredArray(
                group.opt("resultKeys"), groupPath + ".resultKeys");
            if (rawResultKeys.length() < 1
                    || rawResultKeys.length() > MAX_DAILY_STATS_RESULT_KEYS) {
                reject(groupPath + ".resultKeys.length");
            }
            Set<String> localResultKeys = new LinkedHashSet<>();
            for (int keyIndex = 0; keyIndex < rawResultKeys.length(); keyIndex++) {
                String keyPath = groupPath + ".resultKeys[" + keyIndex + "]";
                String key = boundedText(rawResultKeys.opt(keyIndex), keyPath,
                    MAX_PROFILE_ID_LENGTH);
                if (!localResultKeys.add(key)) reject(keyPath + ".duplicate");
                if (!groupedResultKeys.add(key)) reject(keyPath + ".overlap");
                if (!visibleResultKeys.contains(key)) reject(keyPath + ".unreachable");
            }
        }
    }

    private static void validateDailyStatsV2(JSONObject settings, List<ProfileState> states) {
        if (settings == null || !settings.has("dailyStatsV2")) return;
        String path = "settings.dailyStatsV2";
        JSONObject dailyStats = requiredObject(settings.opt("dailyStatsV2"), path);
        requireOnlyKeys(dailyStats,
            setOf("version", "scope", "groups", "flatSummaries"), path);
        rangedInt(dailyStats.opt("version"), DailyStatsRules.DAILY_STATS_V2_VERSION,
            DailyStatsRules.DAILY_STATS_V2_VERSION, path + ".version");
        oneOf(dailyStats.opt("scope"), path + ".scope",
            DailyStatsRules.ALL_PROFILES_SCOPE);
        JSONArray groups = requiredArray(dailyStats.opt("groups"), path + ".groups");
        JSONArray flatSummaries = requiredArray(
            dailyStats.opt("flatSummaries"), path + ".flatSummaries");
        if (groups.length() < 1 || groups.length() > MAX_DAILY_STATS_GROUPS) {
            reject(path + ".groups.length");
        }
        if (flatSummaries.length() > MAX_DAILY_STATS_V2_FLAT_SUMMARIES) {
            reject(path + ".flatSummaries.length");
        }

        Map<String, Set<String>> visibleResultsByProfile = new LinkedHashMap<>();
        for (ProfileState state : states) {
            if (state.visible && Boolean.TRUE.equals(state.profile.opt("pickerVisible"))) {
                visibleResultsByProfile.put(state.id, state.resultKeys);
            }
        }
        Set<String> itemIds = new LinkedHashSet<>();
        Set<String> groupPairs = new LinkedHashSet<>();
        Set<String> flatPairs = new LinkedHashSet<>();
        Set<String> assignedLegacyKeys = new LinkedHashSet<>();
        validateDailyStatsV2Items(groups, false, path + ".groups",
            visibleResultsByProfile, itemIds, groupPairs, assignedLegacyKeys);
        validateDailyStatsV2Items(flatSummaries, true, path + ".flatSummaries",
            visibleResultsByProfile, itemIds, flatPairs, null);
    }

    private static void validateDailyStatsV2Items(
            JSONArray items, boolean flat, String path,
            Map<String, Set<String>> visibleResultsByProfile,
            Set<String> itemIds, Set<String> assignedPairs,
            Set<String> assignedLegacyKeys) {
        Set<String> allowedItemKeys = flat
            ? setOf("id", "label", "labelI18n", "uiColor", "selectors")
            : setOf("id", "label", "labelI18n", "uiColor", "selectors",
                "legacyResultKeys");
        for (int index = 0; index < items.length(); index++) {
            String itemPath = path + "[" + index + "]";
            JSONObject item = objectAt(items, index, path);
            requireOnlyKeys(item, allowedItemKeys, itemPath);
            String id = boundedText(item.opt("id"), itemPath + ".id",
                MAX_DAILY_STATS_ID_LENGTH);
            if (!itemIds.add(id)) reject(itemPath + ".id.duplicate");
            boundedText(item.opt("label"), itemPath + ".label",
                MAX_DAILY_STATS_LABEL_LENGTH);
            validateOptionalLocalizedText(item, "labelI18n", itemPath + ".labelI18n");
            validateRequiredUiColor(item.opt("uiColor"), itemPath + ".uiColor");

            JSONArray selectors = requiredArray(item.opt("selectors"), itemPath + ".selectors");
            if ((!flat && selectors.length() < 1)
                    || selectors.length() > MAX_DAILY_STATS_V2_SELECTORS) {
                reject(itemPath + ".selectors.length");
            }
            Set<String> localPairs = new LinkedHashSet<>();
            Set<String> selectedResultKeys = new LinkedHashSet<>();
            for (int selectorIndex = 0; selectorIndex < selectors.length(); selectorIndex++) {
                String selectorPath = itemPath + ".selectors[" + selectorIndex + "]";
                JSONObject selector = objectAt(selectors, selectorIndex, itemPath + ".selectors");
                requireOnlyKeys(selector, setOf("profileId", "resultKey"), selectorPath);
                String profileId = boundedText(selector.opt("profileId"),
                    selectorPath + ".profileId", MAX_PROFILE_ID_LENGTH);
                String resultKey = boundedText(selector.opt("resultKey"),
                    selectorPath + ".resultKey", MAX_PROFILE_ID_LENGTH);
                Set<String> profileResults = visibleResultsByProfile.get(profileId);
                if (profileResults == null || !profileResults.contains(resultKey)) {
                    reject(selectorPath + ".unreachable");
                }
                String pair = profileResultPair(profileId, resultKey);
                if (!localPairs.add(pair)) reject(selectorPath + ".duplicate");
                if (!assignedPairs.add(pair)) reject(selectorPath + ".overlap");
                selectedResultKeys.add(resultKey);
            }

            if (!flat && item.has("legacyResultKeys")) {
                JSONArray legacyResultKeys = requiredArray(
                    item.opt("legacyResultKeys"), itemPath + ".legacyResultKeys");
                if (legacyResultKeys.length() < 1
                        || legacyResultKeys.length() > MAX_DAILY_STATS_RESULT_KEYS) {
                    reject(itemPath + ".legacyResultKeys.length");
                }
                Set<String> localLegacyKeys = new LinkedHashSet<>();
                for (int keyIndex = 0; keyIndex < legacyResultKeys.length(); keyIndex++) {
                    String keyPath = itemPath + ".legacyResultKeys[" + keyIndex + "]";
                    String resultKey = boundedText(legacyResultKeys.opt(keyIndex), keyPath,
                        MAX_PROFILE_ID_LENGTH);
                    if (!selectedResultKeys.contains(resultKey)) reject(keyPath + ".unreachable");
                    if (!localLegacyKeys.add(resultKey)) reject(keyPath + ".duplicate");
                    if (assignedLegacyKeys == null || !assignedLegacyKeys.add(resultKey)) {
                        reject(keyPath + ".overlap");
                    }
                }
            }
        }
    }

    /**
     * Validates the optional independent-entry contribution map. Its selectors intentionally use
     * source profile + entry id rather than a result key, so recording an independent workflow can
     * never increment or reinterpret a main-form grade counter.
     */
    private static void validateDailyStatsAlternateEntries(
            JSONObject settings, List<ProfileState> states) {
        if (settings == null || !settings.has("dailyStatsAlternateEntries")) return;
        String path = "settings.dailyStatsAlternateEntries";
        JSONObject configured = requiredObject(
            settings.opt("dailyStatsAlternateEntries"), path);
        requireOnlyKeys(configured,
            setOf("version", "scope", "groups", "flatSummaries"), path);
        rangedInt(configured.opt("version"),
            DailyStatsRules.DAILY_STATS_ALTERNATE_ENTRIES_VERSION,
            DailyStatsRules.DAILY_STATS_ALTERNATE_ENTRIES_VERSION,
            path + ".version");
        oneOf(configured.opt("scope"), path + ".scope",
            DailyStatsRules.ALL_PROFILES_SCOPE);
        JSONArray groups = requiredArray(configured.opt("groups"), path + ".groups");
        JSONArray flatSummaries = requiredArray(
            configured.opt("flatSummaries"), path + ".flatSummaries");
        if (groups.length() > MAX_DAILY_STATS_GROUPS) {
            reject(path + ".groups.length");
        }
        if (flatSummaries.length() > MAX_DAILY_STATS_V2_FLAT_SUMMARIES) {
            reject(path + ".flatSummaries.length");
        }

        JSONObject v2 = settings.optJSONObject("dailyStatsV2");
        if (v2 == null) reject(path + ".dailyStatsV2");
        Set<String> groupIds = dailyStatsItemIds(
            requiredArray(v2.opt("groups"), "settings.dailyStatsV2.groups"));
        Set<String> flatIds = dailyStatsItemIds(requiredArray(
            v2.opt("flatSummaries"), "settings.dailyStatsV2.flatSummaries"));

        Map<String, Set<String>> enabledEntriesByVisibleSource = new LinkedHashMap<>();
        for (ProfileState state : states) {
            if (!state.visible || !Boolean.TRUE.equals(state.profile.opt("pickerVisible"))) {
                continue;
            }
            JSONArray entries;
            try {
                entries = AlternateEntryRules.configuredEntries(
                    state.profile.optJSONObject("workflow"));
            } catch (RuntimeException invalid) {
                reject(state.path + ".workflow.alternateEntries");
                return;
            }
            Set<String> entryIds = new LinkedHashSet<>();
            for (int index = 0; index < entries.length(); index++) {
                JSONObject entry = objectAt(entries, index,
                    state.path + ".workflow.alternateEntries.entries");
                String entryId = boundedText(entry.opt("id"),
                    state.path + ".workflow.alternateEntries.entries[" + index + "].id",
                    MAX_PROFILE_ID_LENGTH);
                if (!entryIds.add(entryId)) {
                    reject(state.path + ".workflow.alternateEntries.entries["
                        + index + "].id.duplicate");
                }
            }
            enabledEntriesByVisibleSource.put(state.id, entryIds);
        }

        validateDailyStatsAlternateItems(groups, path + ".groups", groupIds,
            enabledEntriesByVisibleSource);
        validateDailyStatsAlternateItems(flatSummaries, path + ".flatSummaries", flatIds,
            enabledEntriesByVisibleSource);
    }

    /**
     * A v2 flat row without ordinary result selectors is meaningful only when the supplemental
     * independent-entry contract supplies at least one exact source/entry selector for the same
     * id. The preceding validators establish the shape, reference closure, and uniqueness of both
     * objects; this final joint check prevents an unbacked zero row from reaching the App.
     */
    private static void validateDailyStatsAlternateOnlyFlatCoverage(JSONObject settings) {
        JSONObject v2 = settings == null ? null : settings.optJSONObject("dailyStatsV2");
        if (v2 == null) return;
        JSONArray flatSummaries = requiredArray(
            v2.opt("flatSummaries"), "settings.dailyStatsV2.flatSummaries");
        Set<String> alternateFlatIds = new LinkedHashSet<>();
        JSONObject alternate = settings.optJSONObject("dailyStatsAlternateEntries");
        JSONArray alternateFlat = alternate == null ? null
            : alternate.optJSONArray("flatSummaries");
        for (int index = 0; alternateFlat != null && index < alternateFlat.length(); index++) {
            JSONObject item = objectAt(alternateFlat, index,
                "settings.dailyStatsAlternateEntries.flatSummaries");
            JSONArray selectors = requiredArray(item.opt("selectors"),
                "settings.dailyStatsAlternateEntries.flatSummaries[" + index
                    + "].selectors");
            if (selectors.length() > 0) {
                alternateFlatIds.add(requiredText(item.opt("id"),
                    "settings.dailyStatsAlternateEntries.flatSummaries[" + index + "].id"));
            }
        }
        for (int index = 0; index < flatSummaries.length(); index++) {
            JSONObject item = objectAt(flatSummaries, index,
                "settings.dailyStatsV2.flatSummaries");
            JSONArray selectors = requiredArray(item.opt("selectors"),
                "settings.dailyStatsV2.flatSummaries[" + index + "].selectors");
            if (selectors.length() == 0) {
                String id = requiredText(item.opt("id"),
                    "settings.dailyStatsV2.flatSummaries[" + index + "].id");
                if (!alternateFlatIds.contains(id)) {
                    reject("settings.dailyStatsV2.flatSummaries[" + index
                        + "].selectors.alternateCoverage");
                }
            }
        }
    }

    private static void validateDailyStatsAlternateItems(
            JSONArray items, String path, Set<String> availableItemIds,
            Map<String, Set<String>> enabledEntriesByVisibleSource) {
        Set<String> itemIds = new LinkedHashSet<>();
        Set<String> assignedPairs = new LinkedHashSet<>();
        for (int index = 0; index < items.length(); index++) {
            String itemPath = path + "[" + index + "]";
            JSONObject item = objectAt(items, index, path);
            requireOnlyKeys(item, setOf("id", "selectors"), itemPath);
            String id = boundedText(item.opt("id"), itemPath + ".id",
                MAX_DAILY_STATS_ID_LENGTH);
            if (!availableItemIds.contains(id)) reject(itemPath + ".id.unreachable");
            if (!itemIds.add(id)) reject(itemPath + ".id.duplicate");

            JSONArray selectors = requiredArray(
                item.opt("selectors"), itemPath + ".selectors");
            if (selectors.length() < 1
                    || selectors.length() > MAX_DAILY_STATS_V2_SELECTORS) {
                reject(itemPath + ".selectors.length");
            }
            for (int selectorIndex = 0; selectorIndex < selectors.length(); selectorIndex++) {
                String selectorPath = itemPath + ".selectors[" + selectorIndex + "]";
                JSONObject selector = objectAt(
                    selectors, selectorIndex, itemPath + ".selectors");
                requireOnlyKeys(selector, setOf("profileId", "entryId"), selectorPath);
                String profileId = boundedText(selector.opt("profileId"),
                    selectorPath + ".profileId", MAX_PROFILE_ID_LENGTH);
                String entryId = boundedText(selector.opt("entryId"),
                    selectorPath + ".entryId", MAX_PROFILE_ID_LENGTH);
                Set<String> enabledEntries = enabledEntriesByVisibleSource.get(profileId);
                if (enabledEntries == null || !enabledEntries.contains(entryId)) {
                    reject(selectorPath + ".unreachable");
                }
                String pair = profileResultPair(profileId, entryId);
                if (!assignedPairs.add(pair)) reject(selectorPath + ".overlap");
            }
        }
    }

    private static Set<String> dailyStatsItemIds(JSONArray items) {
        Set<String> out = new LinkedHashSet<>();
        for (int index = 0; index < items.length(); index++) {
            JSONObject item = objectAt(items, index, "settings.dailyStatsV2.items");
            String id = boundedText(item.opt("id"),
                "settings.dailyStatsV2.items[" + index + "].id",
                MAX_DAILY_STATS_ID_LENGTH);
            if (!out.add(id)) reject("settings.dailyStatsV2.items.id.duplicate");
        }
        return out;
    }

    private static String profileResultPair(String profileId, String resultKey) {
        return profileId.length() + ":" + profileId + resultKey;
    }

    private static void validateOptionalLocalizedText(JSONObject owner, String key,
                                                      String path) {
        if (owner == null || !owner.has(key)) return;
        JSONObject values = requiredObject(owner.opt(key), path);
        requireOnlyKeys(values, setOf("en", "es"), path);
        for (String locale : objectKeys(values)) {
            boundedText(values.opt(locale), path + "." + locale,
                MAX_DAILY_STATS_LABEL_LENGTH);
        }
    }

    /** Presentation-only input hints may be empty to request the App's field-label fallback. */
    private static void validateOptionalInputPlaceholder(JSONObject plugin, String path) {
        if (plugin.has("placeholder")) {
            Object raw = plugin.opt("placeholder");
            if (!(raw instanceof String)
                    || ((String) raw).length() > MAX_DAILY_STATS_LABEL_LENGTH) {
                reject(path + ".placeholder");
            }
        }
        if (!plugin.has("placeholderI18n")) return;
        JSONObject values = requiredObject(
            plugin.opt("placeholderI18n"), path + ".placeholderI18n");
        requireOnlyKeys(values, setOf("en", "es"), path + ".placeholderI18n");
        for (String locale : objectKeys(values)) {
            Object raw = values.opt(locale);
            if (!(raw instanceof String)
                    || ((String) raw).length() > MAX_DAILY_STATS_LABEL_LENGTH) {
                reject(path + ".placeholderI18n." + locale);
            }
        }
    }

    private static void validateOptionalBoundedText(JSONObject owner, String key,
                                                    String path, int maximumLength) {
        if (owner == null || !owner.has(key)) return;
        boundedText(owner.opt(key), path, maximumLength);
    }

    private static void validateOptionalUiColor(JSONObject owner, String key, String path) {
        if (owner == null || !owner.has(key)) return;
        validateRequiredUiColor(owner.opt(key), path);
    }

    private static void validateRequiredUiColor(Object raw, String path) {
        String color = requiredText(raw, path);
        if (!color.matches("#[0-9A-Fa-f]{6}")) reject(path);
    }

    private static boolean validatePayloadFields(JSONObject profile, String path,
                                                 Set<String> activePhotoFields) {
        Map<String, String> owners = new LinkedHashMap<>();
        JSONObject snFields = requiredObject(profile.opt("snFields"), path + ".snFields");
        registerField(owners, requiredText(snFields.opt("primary"),
            path + ".snFields.primary"), path + ".snFields.primary");
        if (Boolean.TRUE.equals(profile.opt("requiresSecondSn"))) {
            registerField(owners, requiredText(snFields.opt("secondary"),
                path + ".snFields.secondary"), path + ".snFields.secondary");
        }

        JSONArray plugins = optionalArray(profile, "snPlugins", path);
        for (int index = 0; plugins != null && index < plugins.length(); index++) {
            JSONObject plugin = objectAt(plugins, index, path + ".snPlugins");
            String key = requiredText(plugin.opt("key"), path + ".snPlugins[" + index + "].key");
            if ("primary".equals(key) || "secondary".equals(key)) continue;
            registerField(owners, requiredText(plugin.opt("field"),
                path + ".snPlugins[" + index + "].field"),
                path + ".snPlugins[" + index + "]");
        }
        for (String field : activePhotoFields) {
            registerField(owners, field, path + ".photos");
        }

        JSONObject results = profile.optJSONObject("gradeMap");
        Set<String> resultFields = new LinkedHashSet<>();
        for (String key : objectKeys(results)) {
            JSONObject item = results.optJSONObject(key);
            if (item != null) resultFields.add(requiredText(
                item.opt("field"), path + ".gradeMap." + key + ".field"));
        }
        for (String field : resultFields) {
            registerField(owners, field, path + ".gradeMap");
        }

        validatePayloadArray(profile, "conditionalFields", false, owners, path);
        validatePayloadArray(profile, "operationFields", true, owners, path);
        validateChoiceFields(profile, owners, path);

        boolean hasMaterialItems = false;
        Set<String> materialCodes = new LinkedHashSet<>();
        JSONArray groups = optionalArray(profile, "materialGroups", path);
        for (int index = 0; groups != null && index < groups.length(); index++) {
            JSONObject group = objectAt(groups, index, path + ".materialGroups");
            String itemPath = path + ".materialGroups[" + index + "]";
            registerField(owners, requiredText(group.opt("field"), itemPath + ".field"),
                itemPath);
            JSONArray materials = requiredArray(group.opt("materials"),
                itemPath + ".materials");
            hasMaterialItems |= materials.length() > 0;
            for (int materialIndex = 0; materialIndex < materials.length();
                    materialIndex++) {
                String materialPath = itemPath + ".materials[" + materialIndex + "]";
                JSONObject material = objectAt(materials, materialIndex,
                    itemPath + ".materials");
                String code = requiredText(material.opt("code"), materialPath + ".code");
                requiredText(material.opt("name"), materialPath + ".name");
                positiveInt(material.opt("defaultQty"), materialPath + ".defaultQty");
                if (!materialCodes.add(code)) {
                    reject(materialPath + ".code.duplicate");
                }
            }
        }
        Object rawMaterialPattern = profile.opt("materialCodePattern");
        if (rawMaterialPattern != null && rawMaterialPattern != JSONObject.NULL) {
            if (!(rawMaterialPattern instanceof String)) {
                reject(path + ".materialCodePattern");
            }
            String configuredPattern = ((String) rawMaterialPattern).trim();
            if (!configuredPattern.isEmpty()) {
                try {
                    java.util.regex.Pattern.compile(configuredPattern);
                } catch (RuntimeException invalidPattern) {
                    reject(path + ".materialCodePattern");
                }
            }
        }
        return hasMaterialItems;
    }

    private static void validatePayloadArray(JSONObject profile, String key,
                                             boolean requiresValue,
                                             Map<String, String> owners,
                                             String path) {
        JSONArray values = optionalArray(profile, key, path);
        Set<String> localFields = new LinkedHashSet<>();
        for (int index = 0; values != null && index < values.length(); index++) {
            JSONObject item = objectAt(values, index, path + "." + key);
            String itemPath = path + "." + key + "[" + index + "]";
            String field = requiredText(item.opt("field"), itemPath + ".field");
            if (!localFields.add(field)) reject(itemPath + ".field.duplicate");
            if (requiresValue && !item.has("value")) reject(itemPath + ".value");
            registerField(owners, field, itemPath);
        }
    }

    private static void validateChoiceFields(JSONObject profile,
                                             Map<String, String> owners,
                                             String path) {
        JSONArray choices = optionalArray(profile, "choiceFields", path);
        Set<String> localFields = new LinkedHashSet<>();
        for (int index = 0; choices != null && index < choices.length(); index++) {
            JSONObject choice = objectAt(choices, index, path + ".choiceFields");
            String itemPath = path + ".choiceFields[" + index + "]";
            String field = requiredText(choice.opt("field"), itemPath + ".field");
            if (!localFields.add(field)) reject(itemPath + ".field.duplicate");

            String kind = oneOf(choice.opt("kind"), itemPath + ".kind",
                "single", "multi");
            if (choice.has("required")
                    && !(choice.opt("required") instanceof Boolean)) {
                reject(itemPath + ".required");
            }
            if (choice.has("visible")
                    && !(choice.opt("visible") instanceof Boolean)) {
                reject(itemPath + ".visible");
            }
            if (choice.has("reviewRequired")) {
                if (!(choice.opt("reviewRequired") instanceof Boolean)
                        || Boolean.TRUE.equals(choice.opt("reviewRequired"))) {
                    reject(itemPath + ".reviewRequired");
                }
            }

            JSONArray options = requiredArray(choice.opt("options"), itemPath + ".options");
            if (options.length() == 0) reject(itemPath + ".options.empty");
            List<Object> optionValues = new ArrayList<>();
            for (int optionIndex = 0; optionIndex < options.length(); optionIndex++) {
                String optionPath = itemPath + ".options[" + optionIndex + "]";
                JSONObject option = objectAt(options, optionIndex, itemPath + ".options");
                if (!option.has("value")) reject(optionPath + ".value");
                Object optionValue = option.opt("value");
                validateChoiceOptionValue(optionValue, optionPath + ".value");
                requiredText(option.opt("label"), optionPath + ".label");
                if (containsChoiceValue(optionValues, optionValue)) {
                    reject(optionPath + ".value.duplicate");
                }
                optionValues.add(optionValue);
            }

            if (!choice.has("value")) reject(itemPath + ".value");
            Object selected = choice.opt("value");
            boolean required = Boolean.TRUE.equals(choice.opt("required"));
            if ("single".equals(kind)) {
                if (!(selected instanceof String)) reject(itemPath + ".value");
                String selectedValue = (String) selected;
                if (selectedValue.isEmpty()) {
                    if (required) reject(itemPath + ".value.required");
                } else if (!containsChoiceValue(optionValues, selectedValue)) {
                    reject(itemPath + ".value.option");
                }
            } else {
                JSONArray selectedValues = requiredArray(selected, itemPath + ".value");
                if (required && selectedValues.length() == 0) {
                    reject(itemPath + ".value.required");
                }
                for (int valueIndex = 0; valueIndex < selectedValues.length(); valueIndex++) {
                    if (!containsChoiceValue(optionValues, selectedValues.opt(valueIndex))) {
                        reject(itemPath + ".value[" + valueIndex + "].option");
                    }
                }
            }

            if (!Boolean.FALSE.equals(choice.opt("visible"))) {
                registerField(owners, field, itemPath);
            }
        }
    }

    private static void validateChoiceOptionValue(Object value, String path) {
        if (value == null || value == JSONObject.NULL
                || value instanceof JSONObject || value instanceof JSONArray) {
            reject(path);
        }
        if (value instanceof String) requiredText(value, path);
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
            reject(path);
        }
    }

    private static boolean containsChoiceValue(List<Object> values, Object target) {
        for (Object value : values) {
            if (choiceValuesEqual(value, target)) return true;
        }
        return false;
    }

    private static boolean choiceValuesEqual(Object left, Object right) {
        if (left == right) return true;
        if (left == null || right == null
                || left == JSONObject.NULL || right == JSONObject.NULL) {
            return false;
        }
        if (left instanceof Number && right instanceof Number) {
            return Double.compare(((Number) left).doubleValue(),
                ((Number) right).doubleValue()) == 0;
        }
        return left.equals(right);
    }

    private static void validateConfiguredWorkflow(JSONObject profile,
                                                   ProfileWorkflow workflow,
                                                   PhotoContract photos,
                                                   Set<String> resultKeys,
                                                   String path) {
        if (!profile.has("workflow")) return;
        JSONObject root = requiredObject(profile.opt("workflow"), path + ".workflow");

        // An incomplete legacy workflow on an unreachable hidden profile remains inert. Once the
        // complete policy envelope is present, validate the exact values rather than accepting the
        // runtime's conservative fallbacks as authored decisions.
        if (workflow.operationalPoliciesExplicit) {
            validateOperationalPolicies(profile, root, workflow, photos, resultKeys, path);
        }

        if (configured(root, "previousSteps")) {
            validatePreviousStepReferences(profile,
                requiredObject(root.opt("previousSteps"), path + ".workflow.previousSteps"),
                workflow, photos, resultKeys, path);
        }
        if (configured(root, "alternateEntries")) {
            // The cross-profile pass invokes the strict runtime parser after ids are indexed.
            requiredObject(root.opt("alternateEntries"),
                path + ".workflow.alternateEntries");
        }
    }

    private static void validateOperationalPolicies(JSONObject profile, JSONObject root,
                                                    ProfileWorkflow workflow,
                                                    PhotoContract photos,
                                                    Set<String> resultKeys,
                                                    String path) {
        if (!Boolean.TRUE.equals(root.opt("compatibilityReviewed"))) {
            reject(path + ".workflow.compatibilityReviewed");
        }
        JSONObject previous = requiredObject(root.opt("previousSteps"),
            path + ".workflow.previousSteps");
        requiredBoolean(previous.opt("enabled"), path + ".workflow.previousSteps.enabled");
        boolean precheck = requiredBoolean(previous.opt("scanPrecheck"),
            path + ".workflow.previousSteps.scanPrecheck");
        if (precheck && !workflow.previousStepsEnabled) {
            reject(path + ".workflow.previousSteps.scanPrecheck");
        }
        stringArray(previous.opt("scanPrecheckExcludedResultKeys"),
            path + ".workflow.previousSteps.scanPrecheckExcludedResultKeys", resultKeys);
        Set<String> triggerResultKeys = stringArray(previous.opt("triggerResultKeys"),
            path + ".workflow.previousSteps.triggerResultKeys", resultKeys);
        Set<String> directCreateResultKeys = previous.has("directCreateResultKeys")
            ? stringArray(previous.opt("directCreateResultKeys"),
                path + ".workflow.previousSteps.directCreateResultKeys", resultKeys)
            : Collections.emptySet();
        if (!triggerResultKeys.containsAll(directCreateResultKeys)) {
            reject(path + ".workflow.previousSteps.directCreateResultKeys");
        }
        if (!directCreateResultKeys.isEmpty() && !workflow.previousStepsEnabled) {
            reject(path + ".workflow.previousSteps.directCreateResultKeys");
        }

        JSONObject correction = requiredObject(previous.opt("identifierCorrection"),
            path + ".workflow.previousSteps.identifierCorrection");
        boolean correctionEnabled = requiredBoolean(correction.opt("enabled"),
            path + ".workflow.previousSteps.identifierCorrection.enabled");
        JSONArray substitutions = requiredArray(correction.opt("substitutions"),
            path + ".workflow.previousSteps.identifierCorrection.substitutions");
        if (correctionEnabled && substitutions.length() == 0) {
            reject(path + ".workflow.previousSteps.identifierCorrection.substitutions");
        }
        Set<String> substitutionsFrom = new LinkedHashSet<>();
        for (int index = 0; index < substitutions.length(); index++) {
            JSONObject substitution = objectAt(substitutions, index,
                path + ".workflow.previousSteps.identifierCorrection.substitutions");
            String from = oneCodePointText(substitution.opt("from"),
                path + ".workflow.previousSteps.identifierCorrection.substitutions["
                    + index + "].from");
            oneCodePointText(substitution.opt("to"),
                path + ".workflow.previousSteps.identifierCorrection.substitutions["
                    + index + "].to");
            if (!substitutionsFrom.add(from)) {
                reject(path + ".workflow.previousSteps.identifierCorrection.substitutions");
            }
        }
        stringArray(correction.opt("resultKeys"),
            path + ".workflow.previousSteps.identifierCorrection.resultKeys", resultKeys);
        oneOf(correction.opt("applyAction"),
            path + ".workflow.previousSteps.identifierCorrection.applyAction",
            "auto", "confirm", "block");
        if (correctionEnabled && !workflow.previousStepsEnabled) {
            reject(path + ".workflow.previousSteps.identifierCorrection.enabled");
        }
        oneOf(previous.opt("identifierCasePolicy"),
            path + ".workflow.previousSteps.identifierCasePolicy",
            "preserve", "match_existing");

        JSONObject precheckPolicy = requiredObject(previous.opt("scanPrecheckPolicy"),
            path + ".workflow.previousSteps.scanPrecheckPolicy");
        int maxMissingAttempts = rangedInt(precheckPolicy.opt("maxMissingAttempts"), 1, 10,
            path + ".workflow.previousSteps.scanPrecheckPolicy.maxMissingAttempts");
        oneOf(precheckPolicy.opt("beforeLimitAction"),
            path + ".workflow.previousSteps.scanPrecheckPolicy.beforeLimitAction",
            "remove", "block");
        String atLimit = oneOf(precheckPolicy.opt("atLimitAction"),
            path + ".workflow.previousSteps.scanPrecheckPolicy.atLimitAction",
            "require_artifact", "block");
        if (maxMissingAttempts < 1) reject(path + ".workflow.previousSteps.scanPrecheckPolicy");
        rangedInt(previous.opt("verifyAttempts"), 1, 10,
            path + ".workflow.previousSteps.verifyAttempts");
        rangedInt(previous.opt("verifyDelayMs"), 0, 30_000,
            path + ".workflow.previousSteps.verifyDelayMs");
        rangedInt(previous.opt("recipeMaxAttempts"), 1, 10,
            path + ".workflow.previousSteps.recipeMaxAttempts");
        rangedInt(previous.opt("recipeRetryDelayMs"), 0, 60_000,
            path + ".workflow.previousSteps.recipeRetryDelayMs");

        JSONObject photoPolicy = requiredObject(root.opt("photos"),
            path + ".workflow.photos");
        boolean includeOptional = requiredBoolean(photoPolicy.opt("includeOptionalSlots"),
            path + ".workflow.photos.includeOptionalSlots");
        if (includeOptional != photos.includeOptional || (includeOptional && !photos.slotMode)) {
            reject(path + ".workflow.photos.includeOptionalSlots");
        }

        JSONObject duplicate = requiredObject(root.opt("duplicateCheck"),
            path + ".workflow.duplicateCheck");
        requiredBoolean(duplicate.opt("enabled"), path + ".workflow.duplicateCheck.enabled");
        JSONObject age = requiredObject(duplicate.opt("agePolicy"),
            path + ".workflow.duplicateCheck.agePolicy");
        oneOf(age.opt("unit"), path + ".workflow.duplicateCheck.agePolicy.unit",
            "days", "calendar_months");
        rangedInt(age.opt("value"), 0, 36_500,
            path + ".workflow.duplicateCheck.agePolicy.value");
        oneOf(duplicate.opt("unknownDateAction"),
            path + ".workflow.duplicateCheck.unknownDateAction",
            "skip_as_submitted", "confirm", "block");
        oneOf(duplicate.opt("recentAction"),
            path + ".workflow.duplicateCheck.recentAction",
            "skip_as_submitted", "confirm", "block");
        oneOf(duplicate.opt("eligibleAction"),
            path + ".workflow.duplicateCheck.eligibleAction",
            "continue", "confirm", "block");

        JSONObject printing = requiredObject(root.opt("printing"),
            path + ".workflow.printing");
        boolean printingEnabled = requiredBoolean(printing.opt("enabled"),
            path + ".workflow.printing.enabled");
        oneOf(printing.opt("preflightAction"),
            path + ".workflow.printing.preflightAction",
            "block", "confirm", "continue");
        oneOf(printing.opt("onUnconfirmed"),
            path + ".workflow.printing.onUnconfirmed", "stop", "continue");
        // Older promoted catalogs did not carry these Panel-owned compatibility controls.
        // Their parser fallbacks preserve the current behavior; once present, malformed values
        // must still fail promotion. The Panel requires both keys on every new publish.
        if (printing.has("batchEndRecheckMode")) {
            oneOf(printing.opt("batchEndRecheckMode"),
                path + ".workflow.printing.batchEndRecheckMode",
                "inline_only", "deferred_missing_two_pass");
        }
        if (printing.has("unknownStatusPresentation")) {
            oneOf(printing.opt("unknownStatusPresentation"),
                path + ".workflow.printing.unknownStatusPresentation",
                "as_ongoing", "distinct");
        }
        boolean manualReprint = requiredBoolean(printing.opt("manualReprintEnabled"),
            path + ".workflow.printing.manualReprintEnabled");
        Set<String> statuses = stringArray(printing.opt("manualReprintStatuses"),
            path + ".workflow.printing.manualReprintStatuses",
            setOf("failed", "ongoing", "unknown"));
        if (manualReprint && statuses.isEmpty()) {
            reject(path + ".workflow.printing.manualReprintStatuses");
        }
        requiredBoolean(printing.opt("manualReprintRequiresConfirmation"),
            path + ".workflow.printing.manualReprintRequiresConfirmation");
        rangedInt(printing.opt("confirmationPolls"), 1, 12,
            path + ".workflow.printing.confirmationPolls");
        rangedInt(printing.opt("confirmationPollIntervalMs"), 250, 30_000,
            path + ".workflow.printing.confirmationPollIntervalMs");
        rangedInt(printing.opt("maxAutoReprints"), 0, 3,
            path + ".workflow.printing.maxAutoReprints");
        rangedInt(printing.opt("finalRecheckDelayMs"), 0, 120_000,
            path + ".workflow.printing.finalRecheckDelayMs");
        if (printingEnabled != workflow.printingEnabled) {
            reject(path + ".workflow.printing.enabled");
        }

        JSONObject materials = requiredObject(root.opt("materials"),
            path + ".workflow.materials");
        boolean refreshMaterials = requiredBoolean(materials.opt("refreshBeforeSubmit"),
            path + ".workflow.materials.refreshBeforeSubmit");
        JSONObject recovery = requiredObject(materials.opt("missingRecovery"),
            path + ".workflow.materials.missingRecovery");
        boolean recoveryEnabled = requiredBoolean(recovery.opt("enabled"),
            path + ".workflow.materials.missingRecovery.enabled");
        requiredBoolean(recovery.opt("localNotice"),
            path + ".workflow.materials.missingRecovery.localNotice");

        JSONObject submission = requiredObject(root.opt("submission"),
            path + ".workflow.submission");
        int maxAttempts = rangedInt(submission.opt("maxAttempts"), 1, 10,
            path + ".workflow.submission.maxAttempts");
        rangedInt(submission.opt("retryDelayMs"), 0, 60_000,
            path + ".workflow.submission.retryDelayMs");
        rangedInt(submission.opt("interUnitDelayMs"), 0, 60_000,
            path + ".workflow.submission.interUnitDelayMs");
        rangedInt(submission.opt("roundLedgerRetentionDays"), 1, 30,
            path + ".workflow.submission.roundLedgerRetentionDays");
        rangedInt(submission.opt("maxConsecutiveFailures"), 1, 100,
            path + ".workflow.submission.maxConsecutiveFailures");
        JSONObject retry = requiredObject(submission.opt("networkRetry"),
            path + ".workflow.submission.networkRetry");
        rangedInt(retry.opt("maxAttempts"), 0, 100,
            path + ".workflow.submission.networkRetry.maxAttempts");
        int baseDelay = rangedInt(retry.opt("baseDelayMs"), 250, 60_000,
            path + ".workflow.submission.networkRetry.baseDelayMs");
        int maxDelay = rangedInt(retry.opt("maxDelayMs"), 250, 300_000,
            path + ".workflow.submission.networkRetry.maxDelayMs");
        if (maxDelay < baseDelay) reject(path + ".workflow.submission.networkRetry");
        if (recoveryEnabled && maxAttempts < 2) {
            reject(path + ".workflow.materials.missingRecovery.enabled");
        }
        if (refreshMaterials && !hasNonEmptyArray(profile, "materialGroups")) {
            reject(path + ".workflow.materials.refreshBeforeSubmit");
        }

        JSONObject notifications = requiredObject(root.opt("notifications"),
            path + ".workflow.notifications");
        requiredBoolean(notifications.opt("submissionSummary"),
            path + ".workflow.notifications.submissionSummary");

        boolean hasRequiredWorkflowArtifact = false;
        for (ProfileWorkflow.WorkflowArtifact item : workflow.workflowArtifacts) {
            if (item.required) {
                hasRequiredWorkflowArtifact = true;
                break;
            }
        }
        if ("require_artifact".equals(atLimit) && !hasRequiredWorkflowArtifact) {
            reject(path + ".workflow.previousSteps.scanPrecheckPolicy.atLimitAction");
        }
    }

    private static void validatePreviousStepReferences(JSONObject profile,
                                                       JSONObject previous,
                                                       ProfileWorkflow workflow,
                                                       PhotoContract photos,
                                                       Set<String> resultKeys,
                                                       String path) {
        Set<String> artifactKeys = new LinkedHashSet<>();
        JSONArray artifacts = optionalArray(previous, "artifacts",
            path + ".workflow.previousSteps");
        for (int index = 0; artifacts != null && index < artifacts.length(); index++) {
            JSONObject artifact = objectAt(artifacts, index,
                path + ".workflow.previousSteps.artifacts");
            String key = requiredText(artifact.opt("key"),
                path + ".workflow.previousSteps.artifacts[" + index + "].key");
            if (!artifactKeys.add(key)) {
                reject(path + ".workflow.previousSteps.artifacts[" + index + "].key");
            }
            if (ProfileWorkflow.WorkflowArtifact.from(
                    artifact, index, new ArrayList<>()) == null) {
                reject(path + ".workflow.previousSteps.artifacts[" + index + "]");
            }
        }

        if (previous.has("legacyDraftArtifactKey")) {
            Object raw = previous.opt("legacyDraftArtifactKey");
            if (!(raw instanceof String)) {
                reject(path + ".workflow.previousSteps.legacyDraftArtifactKey");
            }
            String legacy = ((String) raw).trim();
            if (!legacy.equals(raw) || (!legacy.isEmpty() && !artifactKeys.contains(legacy))) {
                reject(path + ".workflow.previousSteps.legacyDraftArtifactKey");
            }
        }

        if (configured(previous, "triggerResultKeys")) {
            stringArray(previous.opt("triggerResultKeys"),
                path + ".workflow.previousSteps.triggerResultKeys", resultKeys);
        }
        if (configured(previous, "directCreateResultKeys")) {
            Set<String> direct = stringArray(previous.opt("directCreateResultKeys"),
                path + ".workflow.previousSteps.directCreateResultKeys", resultKeys);
            if (!workflow.previousStepTriggerResultKeys.containsAll(direct)) {
                reject(path + ".workflow.previousSteps.directCreateResultKeys");
            }
        }
        if (configured(previous, "scanPrecheckExcludedResultKeys")) {
            stringArray(previous.opt("scanPrecheckExcludedResultKeys"),
                path + ".workflow.previousSteps.scanPrecheckExcludedResultKeys", resultKeys);
        }

        JSONArray templates = optionalArray(previous, "templates",
            path + ".workflow.previousSteps");
        Set<String> validSources = new LinkedHashSet<>(artifactKeys);
        validSources.addAll(photos.activeFields);
        int parsedStatic = 0;
        int parsedDynamic = 0;
        for (int index = 0; templates != null && index < templates.length(); index++) {
            JSONObject template = objectAt(templates, index,
                path + ".workflow.previousSteps.templates");
            String itemPath = path + ".workflow.previousSteps.templates[" + index + "]";
            if (template.has("mode")) {
                List<String> parseErrors = new ArrayList<>();
                ProfileWorkflow.DynamicPreviousStepRecipe parsed =
                    ProfileWorkflow.DynamicPreviousStepRecipe.from(
                        template, index, parseErrors);
                if (parsed == null || !parseErrors.isEmpty()) reject(itemPath);
                for (String source : parsed.sources.values()) {
                    if (!validSources.contains(source)) reject(itemPath + ".sources");
                }
                parsedDynamic++;
                continue;
            }

            ProfileWorkflow.PreviousStepRecipe parsed =
                ProfileWorkflow.PreviousStepRecipe.from(template, index);
            if (parsed == null) reject(itemPath);
            positiveInt(template.opt("templateId"), itemPath + ".templateId");
            positiveInt(template.opt("warehouseId"), itemPath + ".warehouseId");
            requiredText(template.opt("sku"), itemPath + ".sku");
            nonNegativeInt(template.opt("delayAfterMs"), itemPath + ".delayAfterMs");
            JSONArray bindings = requiredArray(template.opt("photoBindings"),
                itemPath + ".photoBindings");
            Set<String> targetFields = new LinkedHashSet<>();
            Set<String> fixedFields = new LinkedHashSet<>(
                objectKeys(requiredObject(template.opt("fixedData"),
                    itemPath + ".fixedData")));
            String serialField = requiredText(template.opt("serialField"),
                itemPath + ".serialField");
            if (fixedFields.contains(serialField)) reject(itemPath + ".serialField");
            for (int bindingIndex = 0; bindingIndex < bindings.length(); bindingIndex++) {
                JSONObject binding = objectAt(bindings, bindingIndex,
                    itemPath + ".photoBindings");
                String target = requiredText(binding.opt("targetField"),
                    itemPath + ".photoBindings[" + bindingIndex + "].targetField");
                String source = requiredText(binding.opt("source"),
                    itemPath + ".photoBindings[" + bindingIndex + "].source");
                if (!targetFields.add(target)
                        || target.equals(serialField)
                        || fixedFields.contains(target)) {
                    reject(itemPath + ".photoBindings[" + bindingIndex + "].targetField");
                }
                if (!validSources.contains(source)) {
                    reject(itemPath + ".photoBindings[" + bindingIndex + "].source");
                }
            }
            parsedStatic++;
        }
        if (templates != null
                && parsedStatic + parsedDynamic != templates.length()) {
            reject(path + ".workflow.previousSteps.templates");
        }
        if (!workflow.dynamicPreviousStepErrors.isEmpty()) {
            reject(path + ".workflow.previousSteps.templates");
        }
        if (!workflow.previousStepTriggerResultKeys.isEmpty()
                && (templates == null || templates.length() == 0)) {
            reject(path + ".workflow.previousSteps.templates");
        }
        JSONObject precheck = previous.optJSONObject("scanPrecheckPolicy");
        if (precheck != null
                && "require_artifact".equals(precheck.opt("atLimitAction"))) {
            Set<String> requiredArtifacts = new LinkedHashSet<>();
            for (ProfileWorkflow.WorkflowArtifact artifact : workflow.workflowArtifacts) {
                if (artifact.required) requiredArtifacts.add(artifact.key);
            }
            boolean bound = false;
            for (int index = 0; templates != null && index < templates.length(); index++) {
                JSONObject template = templates.optJSONObject(index);
                if (template == null) continue;
                if (template.has("mode")) {
                    JSONObject sources = template.optJSONObject("sources");
                    for (String alias : objectKeys(sources)) {
                        if (requiredArtifacts.contains(sources.optString(alias, ""))) {
                            bound = true;
                        }
                    }
                } else {
                    JSONArray bindings = template.optJSONArray("photoBindings");
                    for (int bindingIndex = 0;
                            bindings != null && bindingIndex < bindings.length();
                            bindingIndex++) {
                        JSONObject binding = bindings.optJSONObject(bindingIndex);
                        if (binding != null && requiredArtifacts.contains(
                                binding.optString("source", ""))) {
                            bound = true;
                        }
                    }
                }
            }
            if (!bound) reject(path + ".workflow.previousSteps.scanPrecheckPolicy");
        }
    }

    private static void validateAlternateEntries(ProfileState source,
                                                 JSONArray profiles,
                                                 Map<String, ProfileState> profilesById) {
        JSONObject root = source.profile.optJSONObject("workflow");
        if (root == null || !root.has("alternateEntries")) return;
        final JSONArray entries;
        try {
            entries = AlternateEntryRules.configuredEntries(root);
        } catch (RuntimeException invalid) {
            reject(source.path + ".workflow.alternateEntries");
            return;
        }
        if (entries.length() > 16) reject(source.path + ".workflow.alternateEntries.entries");

        Set<String> entryIds = new LinkedHashSet<>();
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = objectAt(entries, index,
                source.path + ".workflow.alternateEntries.entries");
            String entryPath = source.path + ".workflow.alternateEntries.entries[" + index + "]";
            String entryId = boundedText(entry.opt("id"), entryPath + ".id",
                MAX_PROFILE_ID_LENGTH);
            if (!entryIds.add(entryId)) reject(entryPath + ".id.duplicate");
            String targetId = requiredText(entry.opt("targetProfileId"),
                entryPath + ".targetProfileId");
            ProfileState target = profilesById.get(targetId);
            if (target == null || target == source) reject(entryPath + ".targetProfileId");

            try {
                JSONObject targetProfile = AlternateEntryRules.targetProfile(profiles, entry);
                int minimum = rangedInt(entry.opt("minPhotos"), 1, MAX_ALTERNATE_PHOTOS,
                    entryPath + ".minPhotos");
                List<String> placeholderUrls = new ArrayList<>();
                for (int photo = 0; photo < minimum; photo++) {
                    placeholderUrls.add("https://example.invalid/catalog-validation/"
                        + (photo + 1));
                }
                AlternateEntryRules.resolveForUiPreflight(
                    source.profile, profiles, entry, "CATALOG-VALIDATION",
                    placeholderUrls, Collections.emptyMap());
                JSONObject alternateScanner = AlternateEntryRules.applyScannerScopeOverrides(
                    effectiveScannerForProfile(source.profile, false, source.path), entry);
                if (!SnScanRules.Policy.from(alternateScanner).valid) {
                    reject(entryPath + ".scanner");
                }
            } catch (Exception invalid) {
                reject(entryPath);
            }
            source.executable = true;
            target.executable = true;
        }
    }

    private static void requireExecutablePolicy(ProfileState state) {
        if (!state.workflow.operationalPoliciesExplicit) {
            reject(state.path + ".workflow.compatibilityReviewed");
        }
        // Scanner policies are optional, but once configured they must remain executable.
        validateEffectiveScanner(state.profile, false, state.path);
        if (Boolean.TRUE.equals(state.profile.opt("requiresSecondSn"))) {
            validateEffectiveScanner(state.profile, true, state.path);
        }
    }

    private static void validateEffectiveScanner(JSONObject profile, boolean secondary,
                                                 String path) {
        JSONObject effective = effectiveScannerForProfile(profile, secondary, path);
        if (!SnScanRules.Policy.from(effective).valid) {
            reject(path + (secondary ? ".secondaryScanner" : ".primaryScanner"));
        }
    }

    private static JSONObject effectiveScannerForProfile(JSONObject profile,
                                                          boolean secondary,
                                                          String path) {
        JSONObject plugin = identifierPlugin(profile, secondary ? "secondary" : "primary");
        if (plugin != null && SnScanRules.cameraScanEnabled(plugin)
                && !plugin.has("scanner")
                && (secondary || !profile.has("scanner"))) {
            reject(path + (secondary ? ".secondaryScanner" : ".primaryScanner"));
        }
        JSONObject raw = null;
        if (plugin != null && plugin.has("scanner")) {
            raw = plugin.optJSONObject("scanner");
        } else if (!secondary && profile.has("scanner")) {
            raw = profile.optJSONObject("scanner");
        }
        if (raw == null) raw = new JSONObject();
        return scannerWithLegacyExpectedFallback(
            raw, profile, !secondary, path + ".scanner");
    }

    /** Mirrors MainActivity's primary expectedSnLength fallback before policy validation. */
    private static JSONObject scannerWithLegacyExpectedFallback(
            JSONObject scanner, JSONObject profile, boolean useLegacyPrimaryFallback,
            String path) {
        try {
            JSONObject effective = new JSONObject(scanner.toString());
            if (useLegacyPrimaryFallback && !effective.has("expectedLength")
                    && profile != null && profile.has("expectedSnLength")) {
                effective.put("expectedLength", profile.opt("expectedSnLength"));
            }
            return effective;
        } catch (Exception invalid) {
            reject(path);
            return new JSONObject();
        }
    }

    private static JSONObject identifierPlugin(JSONObject profile, String key) {
        JSONArray plugins = profile.optJSONArray("snPlugins");
        JSONObject match = null;
        for (int index = 0; plugins != null && index < plugins.length(); index++) {
            JSONObject item = plugins.optJSONObject(index);
            if (item == null || !key.equals(item.optString("key", ""))) continue;
            if (match != null) return null;
            match = item;
        }
        return match;
    }

    private static void validateAdapterCapabilities(ProfileState state,
                                                    BackendAdapter adapter,
                                                    JSONObject appConfig) {
        ProfileWorkflow workflow = state.workflow;
        List<String> missing = adapter.missingForSubmit(
            workflow.previousStepsEnabled,
            workflow.duplicateCheckEnabled,
            state.hasMaterialItems,
            workflow.printingEnabled,
            workflow.refreshMaterialsBeforeSubmit);
        if (!missing.isEmpty()) reject(state.path + ".backendAdapter.submit");

        BackendAdapter.Submit submit = adapter.operations.submit;
        if (workflow.submissionMaxAttempts > 1
                && !submit.hasRetryableNotWrittenRules()) {
            reject(state.path
                + ".backendAdapter.submit.outcomePolicy.retryableNotWrittenRules");
        }
        if (workflow.missingRecoveryEnabled) {
            if (!submit.hasMissingMaterialNotWrittenRules()) {
                reject(state.path
                    + ".backendAdapter.submit.outcomePolicy.missingMaterialNotWrittenRules");
            }
            if (state.hasMaterialItems) {
                Object rawPattern = state.profile.opt("materialCodePattern");
                if (!(rawPattern instanceof String)
                        || ((String) rawPattern).trim().isEmpty()) {
                    reject(state.path + ".materialCodePattern");
                }
            }
        }

        if (!workflow.dynamicPreviousStepRecipes.isEmpty()
                || !workflow.dynamicPreviousStepErrors.isEmpty()) {
            if (!adapter.missingForDynamicPreviousSteps(workflow).isEmpty()) {
                reject(state.path + ".backendAdapter.previousSteps");
            }
            validateDynamicResolverExecutability(state, appConfig);
        }
        if (workflow.previousStepsEnabled
                && !PreviousStepSafetyRules.sideEffectCapabilityErrors(
                    workflow, adapter).isEmpty()) {
            reject(state.path + ".backendAdapter.previousSteps");
        }
        boolean executableRecipePath = workflow.previousStepsEnabled
            && !workflow.previousStepTriggerResultKeys.isEmpty()
            && (!workflow.previousStepRecipes.isEmpty()
                || !workflow.dynamicPreviousStepRecipes.isEmpty());
        if (executableRecipePath && workflow.previousStepRecipeMaxAttempts > 1
                && !adapter.operations.previousSteps
                    .hasRecipeRetryableNotWrittenRules()) {
            reject(state.path
                + ".backendAdapter.previousSteps.recipeOutcomePolicy"
                + ".retryableNotWrittenRules");
        }
        if (executableRecipePath
                && !adapter.operations.previousSteps.alreadyExistsMessagePatterns.isEmpty()
                && !adapter.operations.previousSteps
                    .hasRecipeAlreadyExistsAcknowledgedRules()) {
            reject(state.path
                + ".backendAdapter.previousSteps.recipeOutcomePolicy"
                + ".alreadyExistsAcknowledgedRules");
        }
    }

    /**
     * Rejects resolver placeholders that satisfy the JSON shape but can be proven never to
     * select a field. Such a resolver is not an executable compatibility migration: accepting it
     * would merely move a known production failure from Panel promotion to the first live unit.
     *
     * <p>This check is deliberately conservative. It rejects only direct logical contradictions
     * that are decidable without a live template-detail fixture. Exact field/detail closure is a
     * separate private migration gate and remains mandatory before a deployment pair is approved.
     */
    private static void validateDynamicResolverExecutability(ProfileState state,
                                                               JSONObject appConfig) {
        JSONObject backend = appConfig == null
            ? null : appConfig.optJSONObject("backendAdapter");
        JSONObject operations = backend == null
            ? null : backend.optJSONObject("operations");
        JSONObject previous = operations == null
            ? null : operations.optJSONObject("previousSteps");
        JSONObject resolvers = previous == null
            ? null : previous.optJSONObject("recipeResolvers");
        for (ProfileWorkflow.DynamicPreviousStepRecipe recipe
                : state.workflow.dynamicPreviousStepRecipes) {
            JSONObject resolver = resolvers == null
                ? null : resolvers.optJSONObject(recipe.resolverId);
            if (resolver == null || resolverContainsImpossibleSelector(resolver)) {
                reject(state.path + ".backendAdapter.previousSteps.resolverExecutable");
            }
        }
    }

    private static boolean resolverContainsImpossibleSelector(JSONObject resolver) {
        JSONArray kinds = resolver.optJSONArray("kindSelectors");
        for (int index = 0; kinds != null && index < kinds.length(); index++) {
            JSONObject item = kinds.optJSONObject(index);
            if (item == null || selectorDefinitelyImpossible(
                    item.optJSONObject("selector"))) {
                return true;
            }
        }
        JSONArray rules = resolver.optJSONArray("rules");
        for (int index = 0; rules != null && index < rules.length(); index++) {
            JSONObject rule = rules.optJSONObject(index);
            if (rule == null || selectorDefinitelyImpossible(
                    rule.optJSONObject("selector"))) {
                return true;
            }
            JSONObject action = rule.optJSONObject("action");
            JSONArray optionSelectors = action == null
                ? null : action.optJSONArray("optionSelectors");
            for (int optionIndex = 0;
                    optionSelectors != null && optionIndex < optionSelectors.length();
                    optionIndex++) {
                JSONObject option = optionSelectors.optJSONObject(optionIndex);
                if (option == null || selectorDefinitelyImpossible(
                        option.optJSONObject("selector"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A selector is impossible when a required predicate is also forbidden verbatim. */
    private static boolean selectorDefinitelyImpossible(JSONObject selector) {
        if (selector == null) return true;
        Set<String> forbidden = predicateKeys(selector.optJSONArray("noneOf"));
        if (forbidden.isEmpty()) return false;
        for (String required : predicateKeys(selector.optJSONArray("allOf"))) {
            if (forbidden.contains(required)) return true;
        }
        Set<String> alternatives = predicateKeys(selector.optJSONArray("anyOf"));
        return !alternatives.isEmpty() && forbidden.containsAll(alternatives);
    }

    private static Set<String> predicateKeys(JSONArray predicates) {
        Set<String> out = new LinkedHashSet<>();
        for (int index = 0; predicates != null && index < predicates.length(); index++) {
            JSONObject predicate = predicates.optJSONObject(index);
            if (predicate == null) continue;
            String attribute = predicate.optString("attribute", "");
            Object caseSensitive = predicate.opt("caseSensitive");
            String operator = predicate.has("equalsAny") ? "equalsAny"
                : predicate.has("containsAny") ? "containsAny"
                : predicate.has("present") ? "present" : "";
            if (attribute.isEmpty() || operator.isEmpty()) continue;
            Object operand = predicate.opt(operator);
            out.add(attribute + "\u0000" + String.valueOf(caseSensitive)
                + "\u0000" + operator + "\u0000" + String.valueOf(operand));
        }
        return out;
    }

    private static void validateAlternateAdapterReferences(
            ProfileState source, JSONArray profiles,
            Map<String, ProfileState> profilesById, BackendAdapter adapter) {
        JSONObject root = source.profile.optJSONObject("workflow");
        if (root == null || !root.has("alternateEntries")) return;
        JSONArray entries;
        try {
            entries = AlternateEntryRules.configuredEntries(root);
        } catch (RuntimeException invalid) {
            reject(source.path + ".workflow.alternateEntries");
            return;
        }
        for (int index = 0; index < entries.length(); index++) {
            JSONObject entry = entries.optJSONObject(index);
            if (entry == null) reject(source.path + ".workflow.alternateEntries.entries");
            JSONObject retry = entry.optJSONObject("submissionRetry");
            int maxAttempts = retry == null ? 1 : rangedInt(
                retry.opt("maxAttempts"), 1, 10,
                source.path + ".workflow.alternateEntries.entries[" + index
                    + "].submissionRetry.maxAttempts");
            if (maxAttempts > 1
                    && !adapter.operations.submit.hasRetryableNotWrittenRules()) {
                reject(source.path
                    + ".backendAdapter.submit.outcomePolicy.retryableNotWrittenRules");
            }
            ProfileState target = profilesById.get(entry.optString("targetProfileId", ""));
            if (target == null) reject(source.path + ".workflow.alternateEntries.target");
            if (!RemoteSideEffectSafetyRules.alternateEntryCapabilityErrors(
                    source.profile, target.profile, adapter).isEmpty()) {
                reject(source.path + ".backendAdapter.alternateEntries");
            }
            try {
                JSONObject targetProfile =
                    AlternateEntryRules.targetProfile(profiles, entry);
                adapter.alternateEntryDynamicOverrideConfig(
                    entry, targetProfile, Collections.emptyMap());
            } catch (Exception invalid) {
                reject(source.path + ".backendAdapter.alternateEntries");
            }
        }
    }

    private static JSONArray optionalArray(JSONObject owner, String key, String path) {
        if (!configured(owner, key)) return null;
        return requiredArray(owner.opt(key), path + "." + key);
    }

    private static boolean configured(JSONObject owner, String key) {
        return owner != null && owner.has(key) && !owner.isNull(key);
    }

    private static boolean hasNonEmptyArray(JSONObject owner, String key) {
        JSONArray value = owner == null ? null : owner.optJSONArray(key);
        return value != null && value.length() > 0;
    }

    private static JSONObject requiredObject(Object raw, String path) {
        if (!(raw instanceof JSONObject)) reject(path);
        return (JSONObject) raw;
    }

    private static JSONArray requiredArray(Object raw, String path) {
        if (!(raw instanceof JSONArray)) reject(path);
        return (JSONArray) raw;
    }

    private static JSONObject objectAt(JSONArray values, int index, String path) {
        Object raw = values == null ? null : values.opt(index);
        if (!(raw instanceof JSONObject)) reject(path + "[" + index + "]");
        return (JSONObject) raw;
    }

    private static String requiredText(Object raw, String path) {
        if (!(raw instanceof String)) reject(path);
        String value = (String) raw;
        if (value.isEmpty() || !value.equals(value.trim())) reject(path);
        return value;
    }

    private static String boundedText(Object raw, String path, int maximumLength) {
        String value = requiredText(raw, path);
        if (value.length() > maximumLength) {
            reject(path + ".length");
        }
        return value;
    }

    private static String optionalText(Object raw, String path) {
        if (raw == null || raw == JSONObject.NULL) return "";
        if (raw instanceof String && ((String) raw).isEmpty()) return "";
        return requiredText(raw, path);
    }

    private static String oneCodePointText(Object raw, String path) {
        String value = requiredText(raw, path);
        if (value.codePointCount(0, value.length()) != 1) reject(path);
        return value;
    }

    private static boolean requiredBoolean(Object raw, String path) {
        if (!(raw instanceof Boolean)) reject(path);
        return (Boolean) raw;
    }

    private static int positiveInt(Object raw, String path) {
        return rangedInt(raw, 1, Integer.MAX_VALUE, path);
    }

    private static int nonNegativeInt(Object raw, String path) {
        return rangedInt(raw, 0, Integer.MAX_VALUE, path);
    }

    private static int rangedInt(Object raw, int minimum, int maximum, String path) {
        if (!(raw instanceof Byte || raw instanceof Short
                || raw instanceof Integer || raw instanceof Long)) {
            reject(path);
        }
        long value = ((Number) raw).longValue();
        if (value < minimum || value > maximum) reject(path);
        return (int) value;
    }

    private static String oneOf(Object raw, String path, String... allowed) {
        if (!(raw instanceof String)) reject(path);
        String value = (String) raw;
        for (String item : allowed) {
            if (item.equals(value)) return value;
        }
        reject(path);
        return "";
    }

    private static Set<String> stringArray(Object raw, String path,
                                           Set<String> allowed) {
        JSONArray values = requiredArray(raw, path);
        Set<String> out = new LinkedHashSet<>();
        for (int index = 0; index < values.length(); index++) {
            String value = requiredText(values.opt(index), path + "[" + index + "]");
            if (!out.add(value) || (allowed != null && !allowed.contains(value))) {
                reject(path + "[" + index + "]");
            }
        }
        return out;
    }

    private static void registerField(Map<String, String> owners,
                                      String field, String owner) {
        String existing = owners.put(field, owner);
        if (existing != null) reject(owner + ".fieldConflict");
    }

    private static Set<String> objectKeys(JSONObject value) {
        Set<String> out = new LinkedHashSet<>();
        if (value == null) return out;
        java.util.Iterator<String> keys = value.keys();
        while (keys.hasNext()) out.add(keys.next());
        return out;
    }

    private static void requireOnlyKeys(JSONObject value, Set<String> allowed, String path) {
        for (String key : objectKeys(value)) {
            if (!allowed.contains(key)) reject(path + "." + key);
        }
    }

    private static Set<String> setOf(String... values) {
        Set<String> out = new LinkedHashSet<>();
        Collections.addAll(out, values);
        return Collections.unmodifiableSet(out);
    }

    private static void reject(String path) {
        throw new IllegalArgumentException("catalog promotion rejected: " + path);
    }

    private static final class PhotoContract {
        final boolean slotMode;
        final boolean includeOptional;
        final Set<String> activeFields;
        final Set<String> allFields;

        PhotoContract(boolean slotMode, boolean includeOptional,
                      Set<String> activeFields, Set<String> allFields) {
            this.slotMode = slotMode;
            this.includeOptional = includeOptional;
            this.activeFields = activeFields;
            this.allFields = allFields;
        }
    }

    private static final class ProfileState {
        final JSONObject profile;
        final String id;
        final String path;
        final boolean visible;
        final ProfileWorkflow workflow;
        final PhotoContract photos;
        final Set<String> resultKeys;
        final boolean hasMaterialItems;
        boolean executable;

        ProfileState(JSONObject profile, String id, String path, boolean visible,
                     ProfileWorkflow workflow, PhotoContract photos,
                     Set<String> resultKeys, boolean hasMaterialItems) {
            this.profile = profile;
            this.id = id;
            this.path = path;
            this.visible = visible;
            this.executable = visible;
            this.workflow = workflow;
            this.photos = photos;
            this.resultKeys = resultKeys;
            this.hasMaterialItems = hasMaterialItems;
        }
    }
}
