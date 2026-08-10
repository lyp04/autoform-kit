package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure, fail-closed plan for Panel-owned alternate-entry overrides resolved from live templates.
 *
 * <p>The profile decides which toggle activates each provider, the exact target template and step,
 * and the only payload field that provider may replace. The backend adapter owns the closed live
 * field/option selector and typed value builder. No field name, option value, or label is inferred
 * by the App.</p>
 */
final class AlternateEntryDynamicOverrideRules {
    private static final int MAX_PROVIDERS = 32;
    private static final int MAX_ID_LENGTH = 128;
    private static final BigDecimal MAX_SAFE_INTEGER =
        new BigDecimal("9007199254740991");
    private static final Set<String> PROVIDER_KEYS = setOf(
        "id", "triggerToggleKey", "templateId", "expectedStep", "resolverId", "outputField");
    private static final Set<String> IDENTITY_FIELDS = setOf(
        "template", "identity", "templateId", "warehouseId", "sku",
        "template.id", "template.warehouseId", "template.sku");

    private AlternateEntryDynamicOverrideRules() {}

    static final class Request {
        final String providerId;
        final Object templateId;
        final Object expectedStep;
        final String resolverId;
        final String outputField;

        private final Object warehouseId;
        private final Object sku;
        private final JSONObject resolver;

        private Request(Provider provider, Object warehouseId, Object sku,
                        JSONObject resolver) {
            this.providerId = provider.id;
            this.templateId = copyUnchecked(provider.templateId);
            this.expectedStep = copyUnchecked(provider.expectedStep);
            this.resolverId = provider.resolverId;
            this.outputField = provider.outputField;
            this.warehouseId = copyUnchecked(warehouseId);
            this.sku = copyUnchecked(sku);
            this.resolver = copyObject(resolver);
        }
    }

    static final class Plan {
        private final List<Request> requests;
        private final JSONObject fieldMapping;

        private Plan(List<Request> requests, JSONObject fieldMapping) {
            this.requests = Collections.unmodifiableList(new ArrayList<>(requests));
            this.fieldMapping = copyObject(fieldMapping);
        }

        List<Request> requests() {
            return requests;
        }

        boolean isEmpty() {
            return requests.isEmpty();
        }

        /** Resolves every active provider. The input object must contain exactly one live fixture
         * per active provider id and no unused response. */
        JSONObject resolve(JSONObject liveTemplatesByProviderId) throws Exception {
            JSONObject live = liveTemplatesByProviderId == null
                ? new JSONObject() : liveTemplatesByProviderId;
            Set<String> expectedIds = new LinkedHashSet<>();
            for (Request request : requests) expectedIds.add(request.providerId);
            for (String key : keys(live)) {
                if (!expectedIds.contains(key)) {
                    throw invalid("unexpected live template for provider " + key);
                }
                if (!(live.opt(key) instanceof JSONObject)) {
                    throw invalid("live template for provider " + key + " must be an object");
                }
            }
            JSONObject overrides = new JSONObject();
            for (Request request : requests) {
                JSONObject template = live.optJSONObject(request.providerId);
                if (template == null) {
                    throw invalid("missing live template for provider " + request.providerId);
                }
                Object value = DynamicPreviousStepRules.resolveExactLiveOptionValue(
                    template, copyObject(request.resolver), copyObject(fieldMapping),
                    request.templateId, request.expectedStep, request.warehouseId, request.sku,
                    request.outputField);
                if (overrides.has(request.outputField)) {
                    throw invalid("duplicate resolved output field " + request.outputField);
                }
                overrides.put(request.outputField, copyValue(value));
            }
            return overrides;
        }
    }

    /**
     * Validates profile configuration plus every referenced adapter resolver before network or
     * upload work, and returns requests only for toggles that are effectively enabled.
     */
    static Plan compile(JSONObject entryConfig, JSONObject targetProfile,
                        Map<String, Boolean> toggleStates, JSONObject resolverMap,
                        JSONObject fieldMapping) throws Exception {
        ProfileConfig profile = parseProfile(entryConfig, targetProfile, toggleStates);
        JSONObject resolvers = resolverMap == null ? new JSONObject() : resolverMap;
        if (resolvers.length() > MAX_PROVIDERS) throw invalid("resolver map is too large");
        Map<String, JSONObject> referenced = new LinkedHashMap<>();
        for (Provider provider : profile.providers) {
            JSONObject resolver = resolvers.optJSONObject(provider.resolverId);
            if (resolver == null) {
                throw invalid("missing resolver " + provider.resolverId);
            }
            DynamicPreviousStepRules.validateExactLiveOptionResolver(resolver);
            referenced.put(provider.id, copyObject(resolver));
        }
        if (!profile.providers.isEmpty()) {
            DynamicPreviousStepRules.validateLiveTemplateFieldMapping(fieldMapping);
        }
        List<Request> active = new ArrayList<>();
        for (Provider provider : profile.providers) {
            if (!profile.activeProviderIds.contains(provider.id)) continue;
            active.add(new Request(provider, profile.warehouseId, profile.sku,
                referenced.get(provider.id)));
        }
        return new Plan(active, fieldMapping == null ? new JSONObject() : fieldMapping);
    }

    /** Used by AlternateEntryRules to require exactly the runtime fields implied by toggles. */
    static Set<String> expectedActiveFields(JSONObject entryConfig, JSONObject targetProfile,
                                            Map<String, Boolean> toggleStates) {
        return parseProfile(entryConfig, targetProfile, toggleStates).activeOutputFields;
    }

    private static ProfileConfig parseProfile(JSONObject entryConfig, JSONObject targetProfile,
                                              Map<String, Boolean> toggleStates) {
        if (entryConfig == null) throw invalid("entry config is required");
        if (targetProfile == null) throw invalid("target profile is required");
        JSONObject template = targetProfile.optJSONObject("template");
        if (template == null) throw invalid("target profile.template is required");
        Object targetTemplateId = positiveInteger(template.opt("id"),
            "target profile.template.id");
        Object warehouseId = positiveInteger(template.opt("warehouseId"),
            "target profile.template.warehouseId");
        String sku = requiredText(template.opt("sku"), "target profile.template.sku");

        Set<String> knownFields = declaredFields(targetProfile);
        Set<String> protectedFields = protectedFields(targetProfile);
        Set<String> staticOverrideFields = staticOverrideFields(entryConfig);
        Set<String> allowedOutputs = stringSet(entryConfig.opt("dynamicOverrideFields"),
            "entry config.dynamicOverrideFields", MAX_PROVIDERS);

        Object rawProviders = entryConfig.opt("dynamicOverrideProviders");
        if (!(rawProviders instanceof JSONArray)) {
            throw invalid("entry config.dynamicOverrideProviders must be an array");
        }
        JSONArray providerArray = (JSONArray) rawProviders;
        if (providerArray.length() > MAX_PROVIDERS) {
            throw invalid("entry config.dynamicOverrideProviders is too large");
        }

        ToggleConfig toggles = toggleConfig(entryConfig, toggleStates);
        List<Provider> providers = new ArrayList<>();
        Set<String> providerIds = new LinkedHashSet<>();
        Set<String> outputFields = new LinkedHashSet<>();
        Set<String> activeProviderIds = new LinkedHashSet<>();
        Set<String> activeOutputFields = new LinkedHashSet<>();
        for (int index = 0; index < providerArray.length(); index++) {
            JSONObject raw = providerArray.optJSONObject(index);
            String path = "entry config.dynamicOverrideProviders[" + index + "]";
            if (raw == null) throw invalid(path + " must be an object");
            rejectUnknownKeys(raw, PROVIDER_KEYS, path);
            String id = safeId(raw.opt("id"), path + ".id");
            String trigger = safeId(raw.opt("triggerToggleKey"), path + ".triggerToggleKey");
            Object templateId = positiveInteger(raw.opt("templateId"), path + ".templateId");
            Object expectedStep = positiveInteger(raw.opt("expectedStep"), path + ".expectedStep");
            String resolverId = safeId(raw.opt("resolverId"), path + ".resolverId");
            String outputField = requiredText(raw.opt("outputField"), path + ".outputField");
            if (!providerIds.add(id)) throw invalid("duplicate provider id " + id);
            if (!toggles.effective.containsKey(trigger)) {
                throw invalid(path + ".triggerToggleKey is unknown");
            }
            if (!sameInteger(templateId, targetTemplateId)) {
                throw invalid(path + ".templateId must match target profile.template.id");
            }
            if (!allowedOutputs.contains(outputField)) {
                throw invalid(path + ".outputField is not allow-listed");
            }
            validateOutputField(outputField, knownFields, protectedFields,
                staticOverrideFields, path + ".outputField");
            if (!outputFields.add(outputField)) {
                throw invalid("duplicate dynamic output field " + outputField);
            }
            Provider provider = new Provider(id, trigger, templateId, expectedStep,
                resolverId, outputField);
            providers.add(provider);
            if (Boolean.TRUE.equals(toggles.effective.get(trigger))) {
                activeProviderIds.add(id);
                activeOutputFields.add(outputField);
            }
        }
        if (!allowedOutputs.equals(outputFields)) {
            throw invalid("dynamicOverrideFields must exactly match provider output fields");
        }
        return new ProfileConfig(providers, activeProviderIds, activeOutputFields,
            warehouseId, sku);
    }

    private static void validateOutputField(String field, Set<String> knownFields,
                                            Set<String> protectedFields,
                                            Set<String> staticOverrideFields, String path) {
        if (IDENTITY_FIELDS.contains(field)) throw invalid(path + " cannot replace identity");
        if (!knownFields.contains(field)) throw invalid(path + " is not declared by target profile");
        if (protectedFields.contains(field)) {
            throw invalid(path + " cannot replace serial, result, or photo data");
        }
        if (staticOverrideFields.contains(field)) {
            throw invalid(path + " conflicts with a static or toggle override");
        }
    }

    private static ToggleConfig toggleConfig(JSONObject entryConfig,
                                             Map<String, Boolean> requestedStates) {
        Object raw = entryConfig.opt("toggles");
        if (!(raw instanceof JSONArray)) throw invalid("entry config.toggles must be an array");
        JSONArray toggles = (JSONArray) raw;
        Map<String, Boolean> effective = new LinkedHashMap<>();
        for (int index = 0; index < toggles.length(); index++) {
            JSONObject toggle = toggles.optJSONObject(index);
            if (toggle == null) throw invalid("entry config.toggles[" + index + "] must be an object");
            String key = safeId(toggle.opt("key"), "entry config.toggles[" + index + "].key");
            if (!(toggle.opt("default") instanceof Boolean)) {
                throw invalid("entry config.toggles[" + index + "].default must be a boolean");
            }
            if (effective.put(key, toggle.optBoolean("default", false)) != null) {
                throw invalid("duplicate toggle key " + key);
            }
        }
        JSONObject presets = entryConfig.optJSONObject("resultPresets");
        JSONArray presetItems = presets == null ? null : presets.optJSONArray("items");
        for (int index = 0; presetItems != null && index < presetItems.length(); index++) {
            JSONObject preset = presetItems.optJSONObject(index);
            if (preset == null) {
                throw invalid("entry config.resultPresets.items[" + index
                    + "] must be an object");
            }
            String key = safeId(preset.opt("key"),
                "entry config.resultPresets.items[" + index + "].key");
            if (effective.put(key, false) != null) {
                throw invalid("duplicate toggle or result preset key " + key);
            }
        }
        Map<String, Boolean> requested = requestedStates == null
            ? Collections.emptyMap() : requestedStates;
        for (Map.Entry<String, Boolean> state : requested.entrySet()) {
            if (state.getKey() == null || !effective.containsKey(state.getKey())) {
                throw invalid("unknown toggle state " + state.getKey());
            }
            if (state.getValue() == null) throw invalid("toggle state must be boolean");
            effective.put(state.getKey(), state.getValue());
        }
        return new ToggleConfig(effective);
    }

    private static Set<String> declaredFields(JSONObject profile) {
        Set<String> out = new LinkedHashSet<>();
        collectIdentifierFields(profile.optJSONObject("snFields"), out,
            "target profile.snFields");
        collectGradeFields(profile.optJSONObject("gradeMap"), out);
        for (String key : new String[]{"snPlugins", "snPluginsHidden", "uploadFields",
                "photoSlots", "optionalSlots", "conditionalFields", "operationFields",
                "choiceFields", "materialGroups"}) {
            collectArrayFields(profile, key, out);
        }
        return out;
    }

    private static Set<String> protectedFields(JSONObject profile) {
        Set<String> out = new LinkedHashSet<>();
        collectIdentifierFields(profile.optJSONObject("snFields"), out,
            "target profile.snFields");
        collectGradeFields(profile.optJSONObject("gradeMap"), out);
        for (String key : new String[]{"uploadFields", "photoSlots", "optionalSlots"}) {
            collectArrayFields(profile, key, out);
        }
        return out;
    }

    private static Set<String> staticOverrideFields(JSONObject entryConfig) {
        Set<String> out = new LinkedHashSet<>();
        collectObjectKeys(entryConfig.opt("dataOverrides"), out, "entry config.dataOverrides");
        JSONArray toggles = entryConfig.optJSONArray("toggles");
        for (int index = 0; toggles != null && index < toggles.length(); index++) {
            JSONObject toggle = toggles.optJSONObject(index);
            if (toggle == null) continue;
            collectObjectKeys(toggle.opt("dataOverrides"), out,
                "entry config.toggles[" + index + "].dataOverrides");
        }
        JSONObject presets = entryConfig.optJSONObject("resultPresets");
        JSONArray presetItems = presets == null ? null : presets.optJSONArray("items");
        for (int index = 0; presetItems != null && index < presetItems.length(); index++) {
            JSONObject preset = presetItems.optJSONObject(index);
            if (preset == null) continue;
            collectObjectKeysAllowingPresetOverlap(preset.opt("dataOverrides"), out,
                "entry config.resultPresets.items[" + index + "].dataOverrides");
        }
        return out;
    }

    private static void collectObjectKeysAllowingPresetOverlap(
            Object raw, Set<String> out, String path) {
        if (!(raw instanceof JSONObject)) throw invalid(path + " must be an object");
        for (String field : keys((JSONObject) raw)) out.add(field);
    }

    private static void collectIdentifierFields(JSONObject value, Set<String> out,
                                                String path) {
        if (value == null) throw invalid(path + " must be an object");
        out.add(requiredText(value.opt("primary"), path + ".primary"));
        for (String key : keys(value)) {
            if ("primary".equals(key)) continue;
            Object raw = value.opt(key);
            // Optional identifier roles are represented as empty strings by legacy catalogs.
            // They are absent mappings, not malformed destination field names.
            if (raw instanceof String && ((String) raw).isEmpty()) continue;
            out.add(requiredText(raw, path + "." + key));
        }
    }

    private static void collectGradeFields(JSONObject value, Set<String> out) {
        if (value == null) throw invalid("target profile.gradeMap must be an object");
        for (String key : keys(value)) {
            JSONObject grade = value.optJSONObject(key);
            if (grade == null) throw invalid("target profile.gradeMap entry must be an object");
            out.add(requiredText(grade.opt("field"), "target profile.gradeMap field"));
        }
    }

    private static void collectArrayFields(JSONObject profile, String key, Set<String> out) {
        if (!profile.has(key) || profile.isNull(key)) return;
        JSONArray values = profile.optJSONArray(key);
        if (values == null) throw invalid("target profile." + key + " must be an array");
        for (int index = 0; index < values.length(); index++) {
            JSONObject item = values.optJSONObject(index);
            if (item == null) throw invalid("target profile." + key + " item must be an object");
            out.add(requiredText(item.opt("field"), "target profile." + key + " field"));
        }
    }

    private static void collectObjectKeys(Object raw, Set<String> out, String path) {
        if (!(raw instanceof JSONObject)) throw invalid(path + " must be an object");
        for (String field : keys((JSONObject) raw)) {
            if (!out.add(field)) throw invalid("duplicate override field " + field);
        }
    }

    private static Set<String> stringSet(Object raw, String path, int limit) {
        if (!(raw instanceof JSONArray)) throw invalid(path + " must be an array");
        JSONArray values = (JSONArray) raw;
        if (values.length() > limit) throw invalid(path + " is too large");
        Set<String> out = new LinkedHashSet<>();
        for (int index = 0; index < values.length(); index++) {
            String value = requiredText(values.opt(index), path + "[" + index + "]");
            if (!out.add(value)) throw invalid(path + " contains a duplicate");
        }
        return out;
    }

    private static String safeId(Object raw, String path) {
        String value = requiredText(raw, path);
        if (value.length() > MAX_ID_LENGTH
                || !value.matches("[A-Za-z][A-Za-z0-9_.-]*")
                || "__proto__".equals(value) || "prototype".equals(value)
                || "constructor".equals(value)) {
            throw invalid(path + " must be a safe bounded identifier");
        }
        return value;
    }

    private static Object positiveInteger(Object raw, String path) {
        if (!(raw instanceof Number)) throw invalid(path + " must be a positive integer");
        BigDecimal numeric;
        try {
            numeric = new BigDecimal(String.valueOf(raw));
        } catch (NumberFormatException error) {
            throw invalid(path + " must be a positive integer");
        }
        if (numeric.scale() > 0 && numeric.stripTrailingZeros().scale() > 0
                || numeric.signum() <= 0
                || numeric.compareTo(MAX_SAFE_INTEGER) > 0) {
            throw invalid(path + " must be a positive integer");
        }
        return raw;
    }

    private static boolean sameInteger(Object left, Object right) {
        if (!(left instanceof Number) || !(right instanceof Number)) return false;
        try {
            return new BigDecimal(String.valueOf(left))
                .compareTo(new BigDecimal(String.valueOf(right))) == 0;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static String requiredText(Object raw, String path) {
        if (!(raw instanceof String)) throw invalid(path + " must be a string");
        String value = (String) raw;
        if (value.isEmpty() || !value.equals(value.trim()) || value.length() > 4096) {
            throw invalid(path + " must be a non-empty trimmed string");
        }
        return value;
    }

    private static void rejectUnknownKeys(JSONObject value, Set<String> allowed, String path) {
        for (String key : keys(value)) {
            if (!allowed.contains(key)) throw invalid(path + " contains unknown key " + key);
        }
        if (value.length() != allowed.size()) throw invalid(path + " is missing required keys");
    }

    private static List<String> keys(JSONObject value) {
        List<String> out = new ArrayList<>();
        Iterator<String> iterator = value.keys();
        while (iterator.hasNext()) out.add(iterator.next());
        return out;
    }

    private static JSONObject copyObject(JSONObject value) {
        if (value == null) return new JSONObject();
        try {
            return new JSONObject(value.toString());
        } catch (JSONException error) {
            throw invalid("JSON object cannot be copied");
        }
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
        if (value instanceof Number && Double.isFinite(((Number) value).doubleValue())) return value;
        throw invalid("value is not JSON-compatible");
    }

    private static Object copyUnchecked(Object value) {
        return copyValue(value);
    }

    private static Set<String> setOf(String... values) {
        Set<String> out = new LinkedHashSet<>();
        Collections.addAll(out, values);
        return Collections.unmodifiableSet(out);
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("alternate live override rejected: " + detail);
    }

    private static final class Provider {
        final String id;
        final String triggerToggleKey;
        final Object templateId;
        final Object expectedStep;
        final String resolverId;
        final String outputField;

        Provider(String id, String triggerToggleKey, Object templateId, Object expectedStep,
                 String resolverId, String outputField) {
            this.id = id;
            this.triggerToggleKey = triggerToggleKey;
            this.templateId = copyUnchecked(templateId);
            this.expectedStep = copyUnchecked(expectedStep);
            this.resolverId = resolverId;
            this.outputField = outputField;
        }
    }

    private static final class ToggleConfig {
        final Map<String, Boolean> effective;

        ToggleConfig(Map<String, Boolean> effective) {
            this.effective = Collections.unmodifiableMap(new LinkedHashMap<>(effective));
        }
    }

    private static final class ProfileConfig {
        final List<Provider> providers;
        final Set<String> activeProviderIds;
        final Set<String> activeOutputFields;
        final Object warehouseId;
        final Object sku;

        ProfileConfig(List<Provider> providers, Set<String> activeProviderIds,
                      Set<String> activeOutputFields, Object warehouseId, Object sku) {
            this.providers = Collections.unmodifiableList(new ArrayList<>(providers));
            this.activeProviderIds = Collections.unmodifiableSet(
                new LinkedHashSet<>(activeProviderIds));
            this.activeOutputFields = Collections.unmodifiableSet(
                new LinkedHashSet<>(activeOutputFields));
            this.warehouseId = copyUnchecked(warehouseId);
            this.sku = copyUnchecked(sku);
        }
    }
}
