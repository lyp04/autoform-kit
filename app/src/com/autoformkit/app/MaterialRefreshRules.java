package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Pure, fail-closed merge for Panel-enabled pre-submit item refresh. */
final class MaterialRefreshRules {
    private MaterialRefreshRules() {}

    static JSONArray refreshedGroups(JSONObject profile, Object templateData,
                                     BackendAdapter.MaterialRefresh mapping) throws Exception {
        if (profile == null) throw invalid("profile");
        if (mapping == null || !mapping.errors.isEmpty()) {
            throw invalid(mapping == null ? "backendAdapter.materialRefresh"
                : mapping.errors.get(0));
        }

        JSONArray configured = profile.optJSONArray("materialGroups");
        if (configured == null || configured.length() == 0) {
            throw invalid("profile.materialGroups");
        }

        Map<String, JSONObject> configuredByField = new LinkedHashMap<>();
        Map<String, JSONObject> configuredItemByCode = new LinkedHashMap<>();
        for (int groupIndex = 0; groupIndex < configured.length(); groupIndex++) {
            JSONObject group = configured.optJSONObject(groupIndex);
            String field = text(group == null ? null : group.opt("field"));
            JSONArray items = group == null ? null : group.optJSONArray("materials");
            if (field.isEmpty() || items == null || items.length() == 0
                    || configuredByField.put(field, group) != null) {
                throw invalid("profile.materialGroups[" + groupIndex + "]");
            }
            for (int itemIndex = 0; itemIndex < items.length(); itemIndex++) {
                JSONObject item = items.optJSONObject(itemIndex);
                String code = text(item == null ? null : item.opt("code"));
                int quantity = positiveInteger(item == null ? null : item.opt("defaultQty"));
                if (code.isEmpty() || quantity <= 0
                        || configuredItemByCode.put(code, item) != null) {
                    throw invalid("profile.materialGroups[" + groupIndex
                        + "].materials[" + itemIndex + "]");
                }
            }
        }

        Object rawFieldsValue = BackendAdapter.valueAt(templateData, mapping.fieldListPath);
        if (!(rawFieldsValue instanceof JSONArray)) {
            throw invalid("backend response template field list");
        }
        JSONArray rawFields = (JSONArray) rawFieldsValue;
        Map<String, JSONObject> refreshedByField = new LinkedHashMap<>();
        Set<String> refreshedCodes = new LinkedHashSet<>();
        for (int fieldIndex = 0; fieldIndex < rawFields.length(); fieldIndex++) {
            JSONObject rawField = rawFields.optJSONObject(fieldIndex);
            if (rawField == null || !mapping.isItemField(rawField)) continue;
            String field = text(BackendAdapter.valueAt(rawField, mapping.fieldIdPath));
            JSONObject configuredGroup = configuredByField.get(field);
            if (configuredGroup == null) continue; // Panel owns which payload fields may be refreshed.
            if (refreshedByField.containsKey(field)) {
                throw invalid("duplicate refreshed material group " + field);
            }
            Object rawOptionsValue = BackendAdapter.valueAt(rawField, mapping.fieldOptionsPath);
            if (!(rawOptionsValue instanceof JSONArray)
                    || ((JSONArray) rawOptionsValue).length() == 0) {
                throw invalid("empty refreshed material group " + field);
            }

            JSONArray refreshedItems = new JSONArray();
            JSONArray rawOptions = (JSONArray) rawOptionsValue;
            for (int optionIndex = 0; optionIndex < rawOptions.length(); optionIndex++) {
                JSONObject rawOption = rawOptions.optJSONObject(optionIndex);
                if (rawOption == null) {
                    throw invalid("material option " + field + "[" + optionIndex + "]");
                }
                String code = text(BackendAdapter.valueAt(rawOption, mapping.optionCodePath));
                if (code.isEmpty() || !refreshedCodes.add(code)) {
                    throw invalid("duplicate or empty refreshed material code");
                }

                JSONObject configuredItem = configuredItemByCode.get(code);
                int configuredQuantity = positiveInteger(
                    configuredItem == null ? null : configuredItem.opt("defaultQty"));
                Object rawQuantity = BackendAdapter.valueAt(rawOption, mapping.optionQuantityPath);
                int liveQuantity = positiveInteger(rawQuantity);
                int quantity;
                if (rawQuantity == null || rawQuantity == JSONObject.NULL
                        || text(rawQuantity).isEmpty()) {
                    if (configuredQuantity <= 0) {
                        throw invalid("new refreshed material has no positive quantity");
                    }
                    quantity = configuredQuantity;
                } else {
                    if (liveQuantity <= 0) {
                        throw invalid("refreshed material quantity must be a positive integer");
                    }
                    if (configuredQuantity > 0
                            && BackendAdapter.MaterialRefresh.EXISTING_QUANTITY_PROFILE.equals(
                                mapping.existingQuantityPolicy)) {
                        quantity = configuredQuantity;
                    } else if (configuredQuantity > 0 && liveQuantity != configuredQuantity) {
                        throw invalid("refreshed material quantity conflicts with Panel profile");
                    } else {
                        quantity = liveQuantity;
                    }
                }

                String liveLabel = text(BackendAdapter.valueAt(rawOption,
                    mapping.optionLabelPath));
                String liveEnglishLabel = text(BackendAdapter.valueAt(rawOption,
                    mapping.optionEnglishLabelPath));
                String existingLabel = configuredItem == null
                    ? "" : text(configuredItem.opt("name"));
                String name = firstNonEmpty(liveLabel, liveEnglishLabel, existingLabel, code);
                JSONArray aliases = new JSONArray();
                Set<String> uniqueAliases = new LinkedHashSet<>();
                addAlias(aliases, uniqueAliases, liveLabel, name);
                addAlias(aliases, uniqueAliases, liveEnglishLabel, name);

                JSONObject item = new JSONObject();
                item.put("code", code);
                item.put("name", name);
                item.put("aliases", aliases);
                item.put("defaultQty", quantity);
                refreshedItems.put(item);
            }

            JSONObject refreshedGroup = new JSONObject(configuredGroup.toString());
            refreshedGroup.put("materials", refreshedItems);
            refreshedByField.put(field, refreshedGroup);
        }

        JSONArray out = new JSONArray();
        for (int index = 0; index < configured.length(); index++) {
            String field = text(configured.optJSONObject(index).opt("field"));
            JSONObject refreshed = refreshedByField.get(field);
            if (refreshed == null) throw invalid("missing refreshed material group " + field);
            out.put(refreshed);
        }
        return out;
    }

    private static int positiveInteger(Object value) {
        if (value instanceof Number) {
            double numeric = ((Number) value).doubleValue();
            int integer = ((Number) value).intValue();
            return numeric == integer && integer > 0 ? integer : -1;
        }
        String text = text(value);
        if (!text.matches("[1-9]\\d{0,9}")) return -1;
        try {
            long parsed = Long.parseLong(text);
            return parsed <= Integer.MAX_VALUE ? (int) parsed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String text(Object value) {
        return value == null || value == JSONObject.NULL ? "" : String.valueOf(value).trim();
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.isEmpty()) return value;
        return "";
    }

    private static void addAlias(JSONArray aliases, Set<String> seen, String value,
                                 String primary) {
        String alias = value == null ? "" : value.trim();
        if (!alias.isEmpty() && !alias.equals(primary) && seen.add(alias)) aliases.put(alias);
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("material refresh rejected: " + detail);
    }
}
