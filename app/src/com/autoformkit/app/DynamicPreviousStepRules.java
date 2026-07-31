package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure compiler for a Panel-owned dynamic previous-step recipe.
 *
 * <p>The compiler deliberately has two phases. {@link #compile} validates the live, already
 * unwrapped template-detail object and produces an immutable plan before any upload starts.
 * {@link CompiledPlan#materialize} accepts the later upload results and returns the identity and
 * form data needed by the submit adapter. No selector supports scripts, regular expressions, or
 * implicit label/type fallbacks.</p>
 *
 * <p>The resolver root is exactly {@code version}, {@code identity},
 * {@code searchTextAttributes}, {@code optionSearchTextAttributes}, {@code kindSelectors}, and
 * {@code rules}. Selectors contain one or more of {@code allOf}, {@code anyOf}, and
 * {@code noneOf}; every predicate explicitly supplies {@code attribute} and
 * {@code caseSensitive}, plus exactly one of {@code equalsAny}, {@code containsAny}, or
 * {@code present}. Rules use cardinality {@code exactly_one} or
 * {@code first_in_backend_order}.</p>
 *
 * <p>Actions are {@code serial}, {@code photo(source, joinWith)},
 * {@code fixedOption(optionSelectors, valueBuilder, onNoMatch)}, and
 * {@code omit(allowRequired)}. Typed builders are {@code literal}, {@code present},
 * {@code firstNonEmpty}, {@code integer}, and {@code object}. Builder paths are rooted at one of
 * {@code template}, {@code field}, {@code option}, {@code input}, or {@code identity}; template,
 * field, and option names are the canonical keys of the supplied adapter {@code fields} mapping.
 * Recipe {@code sources} is an alias-to-Panel-artifact-key object, and photo actions reference the
 * alias.</p>
 */
public final class DynamicPreviousStepRules {
    private static final int MAX_FIELDS = 256;
    private static final int MAX_OPTIONS = 256;
    private static final int MAX_KIND_SELECTORS = 32;
    private static final int MAX_RULES = 32;
    private static final int MAX_SOURCES = 32;
    private static final int MAX_SELECTOR_GROUP = 16;
    private static final int MAX_SELECTOR_PREDICATES = 32;
    private static final int MAX_BUILDER_DEPTH = 8;
    private static final int MAX_OBJECT_MEMBERS = 32;
    private static final int MAX_BUILDER_PATHS = 16;
    private static final int MAX_PATH_DEPTH = 16;
    private static final int MAX_PATH_LENGTH = 512;
    private static final int MAX_TEXT_LENGTH = 4096;
    private static final int MAX_LITERAL_CONTAINER = 256;

    private static final Set<String> CANONICAL_ATTRIBUTES = immutableSet(
        "id", "kind", "type", "parentType", "typeName", "title", "englishTitle",
        "searchText", "required", "visible", "hasOptions");
    private static final Set<String> STRING_ATTRIBUTES = immutableSet(
        "id", "kind", "type", "parentType", "typeName", "title", "englishTitle",
        "searchText");
    private static final Set<String> ALL_BUILDER_ROOTS = immutableSet(
        "template", "field", "option", "input", "identity");
    private static final Set<String> IDENTITY_BUILDER_ROOTS = immutableSet(
        "template", "input");
    private static final Set<String> LIVE_OVERRIDE_BUILDER_ROOTS = immutableSet(
        "template", "field", "option", "identity");
    private static final Set<String> TEMPLATE_PATH_KEYS = immutableSet(
        "id", "name", "sku", "step", "warehouseId", "fieldList");
    private static final Set<String> FIELD_PATH_KEYS = immutableSet(
        "id", "type", "parentType", "typeName", "title", "englishTitle", "required",
        "visible", "maxCount", "options", "kind", "searchText", "hasOptions");
    private static final Set<String> OPTION_PATH_KEYS = immutableSet(
        "id", "title", "englishTitle", "quantity", "searchText", "hasOptions");
    private static final Set<String> INPUT_PATH_KEYS = immutableSet("serial");
    private static final Set<String> IDENTITY_PATH_KEYS = immutableSet(
        "templateId", "expectedStep", "warehouseId", "sku");

    private DynamicPreviousStepRules() {
    }

    /**
     * Compiles and validates a recipe without requiring uploaded URLs.
     *
     * @param templateData already-unwrapped template-detail data
     * @param resolver strict resolver DSL described in the class documentation
     * @param optionValueBuilders the adapter previous-step named builder object
     * @param fieldMapping the adapter {@code fields} object, passed through unchanged
     * @param recipe one complete Panel dynamic template recipe
     * @param unitSerial non-empty serial to bind to the one resolved serial field
     */
    public static CompiledPlan compile(JSONObject templateData, JSONObject resolver,
                                       JSONObject optionValueBuilders, JSONObject fieldMapping,
                                       JSONObject recipe, String unitSerial) throws Exception {
        if (templateData == null) throw invalid("templateData");
        if (resolver == null) throw invalid("resolver");
        if (optionValueBuilders == null) throw invalid("optionValueBuilders");
        if (fieldMapping == null) throw invalid("fieldMapping");
        if (recipe == null) throw invalid("recipe");
        String serial = requiredText(unitSerial, "unitSerial");

        requireExactKeys(resolver, setOf("version", "identity", "searchTextAttributes",
            "optionSearchTextAttributes", "kindSelectors", "rules"), "resolver");
        if (!isExactInteger(resolver.opt("version"), 1L)) {
            throw invalid("resolver.version");
        }
        requireExactKeys(recipe, setOf("templateId", "mode", "resolverId", "expectedStep",
            "sources", "delayAfterMs"), "recipe");
        if (!"template_detail".equals(recipe.opt("mode"))) throw invalid("recipe.mode");
        validateRecipeId(requiredText(recipe.opt("resolverId"), "recipe.resolverId"),
            "recipe.resolverId");
        Object rawDelay = strictInteger(recipe.opt("delayAfterMs"), "recipe.delayAfterMs");
        long delayAfterMs = ((Number) rawDelay).longValue();
        if (delayAfterMs < 0L || delayAfterMs > 120_000L) {
            throw invalid("recipe.delayAfterMs");
        }
        Object requestedTemplateId = requiredRecipeIdentityScalar(
            recipe.opt("templateId"), "recipe.templateId");
        Object requestedStep = requiredRecipeIdentityScalar(
            recipe.opt("expectedStep"), "recipe.expectedStep");
        Map<String, String> recipeSources = parseSources(recipe.optJSONObject("sources"));

        Mapping mapping = Mapping.from(fieldMapping);
        MappedObject template = MappedObject.from(templateData, mapping.template, "template");
        Object liveTemplateId = template.required("id", "template identity id");
        Object liveStep = template.required("step", "template identity step");
        Object liveWarehouseId = template.required("warehouseId", "template identity warehouseId");
        Object liveSku = template.required("sku", "template identity sku");
        requiredIdentityScalar(liveTemplateId, "template identity id");
        requiredIdentityScalar(liveStep, "template identity step");
        requiredIdentityScalar(liveWarehouseId, "template identity warehouseId");
        requiredIdentityScalar(liveSku, "template identity sku");
        if (!identityEquals(requestedTemplateId, liveTemplateId)) {
            throw invalid("templateId does not match requested recipe");
        }
        if (!identityEquals(requestedStep, liveStep)) {
            throw invalid("expectedStep does not match live template");
        }

        JSONObject input = new JSONObject().put("serial", serial);
        JSONObject emptyIdentity = new JSONObject();
        EvalContext identityContext = new EvalContext(template.values, null, null,
            input, emptyIdentity);
        JSONObject identityDsl = resolver.optJSONObject("identity");
        if (identityDsl == null) throw invalid("resolver.identity");
        requireExactKeys(identityDsl, setOf("templateId", "expectedStep", "warehouseId", "sku"),
            "resolver.identity");
        JSONObject identity = new JSONObject();
        BuilderBudget builderBudget = new BuilderBudget();
        for (String key : Arrays.asList("templateId", "expectedStep", "warehouseId", "sku")) {
            JSONObject rawBuilder = identityDsl.optJSONObject(key);
            if (rawBuilder == null) throw invalid("resolver.identity." + key);
            Object value = Builder.parse(rawBuilder, 1, builderBudget,
                IDENTITY_BUILDER_ROOTS, "resolver.identity." + key).build(identityContext);
            requiredIdentityScalar(value, "resolved identity " + key);
            Object live = "templateId".equals(key) ? liveTemplateId
                : "expectedStep".equals(key) ? liveStep
                : "warehouseId".equals(key) ? liveWarehouseId : liveSku;
            if (!identityEquals(value, live)) {
                throw invalid("resolved identity " + key + " conflicts with live template");
            }
            identity.put(key, copyJsonValue(value, 0));
        }

        if (optionValueBuilders.length() > MAX_SOURCES) throw invalid("optionValueBuilders");
        Map<String, Builder> namedBuilders = new LinkedHashMap<>();
        Iterator<String> builderIds = optionValueBuilders.keys();
        while (builderIds.hasNext()) {
            String id = builderIds.next();
            validateRecipeId(id, "optionValueBuilders key");
            JSONObject rawBuilder = optionValueBuilders.optJSONObject(id);
            if (rawBuilder == null) throw invalid("optionValueBuilders." + id);
            namedBuilders.put(id, Builder.parse(rawBuilder, 1, builderBudget,
                ALL_BUILDER_ROOTS, "optionValueBuilders." + id));
        }

        List<String> searchTextAttributes = parseAttributeNames(
            resolver.optJSONArray("searchTextAttributes"), "resolver.searchTextAttributes");
        List<String> optionSearchTextAttributes = parseAttributeNames(
            resolver.optJSONArray("optionSearchTextAttributes"),
            "resolver.optionSearchTextAttributes");

        Object rawFieldList = template.required("fieldList", "template field list");
        if (!(rawFieldList instanceof JSONArray)) throw invalid("template field list");
        JSONArray fieldArray = (JSONArray) rawFieldList;
        if (fieldArray.length() == 0 || fieldArray.length() > MAX_FIELDS) {
            throw invalid("template field count");
        }
        List<Field> fields = new ArrayList<>();
        Set<String> fieldIds = new LinkedHashSet<>();
        for (int index = 0; index < fieldArray.length(); index++) {
            JSONObject rawField = fieldArray.optJSONObject(index);
            if (rawField == null) throw invalid("template field[" + index + "]");
            Field field = Field.from(rawField, mapping, searchTextAttributes,
                optionSearchTextAttributes, index);
            if (!fieldIds.add(field.id)) throw invalid("duplicate field id " + field.id);
            fields.add(field);
        }

        JSONArray rawKindSelectors = resolver.optJSONArray("kindSelectors");
        if (rawKindSelectors == null || rawKindSelectors.length() == 0
                || rawKindSelectors.length() > MAX_KIND_SELECTORS) {
            throw invalid("resolver.kindSelectors");
        }
        List<KindRule> kindRules = new ArrayList<>();
        Set<String> kindNames = new LinkedHashSet<>();
        for (int index = 0; index < rawKindSelectors.length(); index++) {
            KindRule kindRule = KindRule.parse(rawKindSelectors.optJSONObject(index), index);
            if (!kindNames.add(kindRule.kind)) {
                throw invalid("duplicate resolver kind " + kindRule.kind);
            }
            kindRules.add(kindRule);
        }
        for (Field field : fields) {
            String matchedKind = "";
            for (KindRule kindRule : kindRules) {
                if (!kindRule.selector.matches(field.attributes)) continue;
                if (!matchedKind.isEmpty()) {
                    throw invalid("ambiguous kind for field " + field.id);
                }
                matchedKind = kindRule.kind;
            }
            field.setKind(matchedKind);
        }

        JSONArray rawRules = resolver.optJSONArray("rules");
        if (rawRules == null || rawRules.length() == 0 || rawRules.length() > MAX_RULES) {
            throw invalid("resolver.rules");
        }
        List<Rule> rules = new ArrayList<>();
        for (int index = 0; index < rawRules.length(); index++) {
            rules.add(Rule.parse(rawRules.optJSONObject(index), index));
        }

        List<Assignment> assignments = new ArrayList<>();
        Set<String> claimedFields = new LinkedHashSet<>();
        Set<String> usedSourceAliases = new LinkedHashSet<>();
        int serialCount = 0;

        EvalContext baseContext = new EvalContext(template.values, null, null, input, identity);
        for (int ruleIndex = 0; ruleIndex < rules.size(); ruleIndex++) {
            Rule rule = rules.get(ruleIndex);
            List<Field> matched = new ArrayList<>();
            for (Field field : fields) {
                if (rule.selector.matches(field.attributes)) matched.add(field);
            }
            Field selected = selectByCardinality(matched, rule.cardinality,
                "resolver.rules[" + ruleIndex + "] field selector");
            if (!claimedFields.add(selected.id)) {
                throw invalid("output field conflict " + selected.id);
            }
            Assignment assignment = compileAction(rule.action, selected, mapping, namedBuilders,
                recipeSources, usedSourceAliases, baseContext, builderBudget,
                "resolver.rules[" + ruleIndex + "].action");
            assignments.add(assignment);
            if (assignment.kind == Assignment.SERIAL) serialCount++;
        }

        if (serialCount != 1) throw invalid("resolved serial field count must be exactly one");

        for (Field field : fields) {
            if (field.required && field.visible && !claimedFields.contains(field.id)) {
                throw invalid("unknown required visible field " + field.id);
            }
        }
        if (!usedSourceAliases.equals(recipeSources.keySet())) {
            throw invalid("recipe.sources contains an unused or missing alias");
        }

        return new CompiledPlan(identity, assignments, recipeSources, delayAfterMs);
    }

    /**
     * Reuses the closed selector and builder DSL for one live-template override.
     *
     * <p>Both field and option cardinality are hard-wired to exactly one. The selected live field
     * must equal the Panel-declared output field, and all four live identity values must equal the
     * expected hidden-target identity before a value can be produced. Callers are expected to run
     * this phase before any upload or submit side effect.</p>
     */
    static Object resolveExactLiveOptionValue(JSONObject templateData, JSONObject resolver,
                                              JSONObject fieldMapping,
                                              Object expectedTemplateId,
                                              Object expectedStep,
                                              Object expectedWarehouseId,
                                              Object expectedSku,
                                              String expectedOutputField) throws Exception {
        if (templateData == null) throw invalid("templateData");
        if (resolver == null) throw invalid("live override resolver");
        if (fieldMapping == null) throw invalid("fieldMapping");
        String outputField = requiredText(expectedOutputField, "expectedOutputField");
        validateExactLiveOptionResolver(resolver);
        
        Mapping mapping = Mapping.from(fieldMapping);
        MappedObject template = MappedObject.from(templateData, mapping.template, "template");
        Object liveTemplateId = template.required("id", "template identity id");
        Object liveStep = template.required("step", "template identity step");
        Object liveWarehouseId = template.required("warehouseId",
            "template identity warehouseId");
        Object liveSku = template.required("sku", "template identity sku");
        Object[] expected = new Object[]{
            requiredIdentityScalar(expectedTemplateId, "expected templateId"),
            requiredIdentityScalar(expectedStep, "expected step"),
            requiredIdentityScalar(expectedWarehouseId, "expected warehouseId"),
            requiredIdentityScalar(expectedSku, "expected sku")
        };
        Object[] live = new Object[]{liveTemplateId, liveStep, liveWarehouseId, liveSku};
        String[] identityKeys = new String[]{"templateId", "expectedStep", "warehouseId", "sku"};
        JSONObject identity = new JSONObject();
        for (int index = 0; index < identityKeys.length; index++) {
            requiredIdentityScalar(live[index], "live " + identityKeys[index]);
            if (!identityEquals(expected[index], live[index])) {
                throw invalid("live " + identityKeys[index] + " does not match expected identity");
            }
            identity.put(identityKeys[index], copyJsonValue(live[index], 0));
        }

        List<String> searchTextAttributes = parseAttributeNames(
            resolver.optJSONArray("searchTextAttributes"),
            "live override resolver.searchTextAttributes");
        List<String> optionSearchTextAttributes = parseAttributeNames(
            resolver.optJSONArray("optionSearchTextAttributes"),
            "live override resolver.optionSearchTextAttributes");
        Object rawFieldList = template.required("fieldList", "template field list");
        if (!(rawFieldList instanceof JSONArray)) throw invalid("template field list");
        JSONArray fieldArray = (JSONArray) rawFieldList;
        if (fieldArray.length() == 0 || fieldArray.length() > MAX_FIELDS) {
            throw invalid("template field count");
        }
        List<Field> matchedFields = new ArrayList<>();
        Selector fieldSelector = Selector.parse(resolver.optJSONObject("fieldSelector"),
            "live override resolver.fieldSelector");
        if (fieldSelector.references("kind")) {
            throw invalid("live override fieldSelector cannot reference kind");
        }
        Set<String> fieldIds = new LinkedHashSet<>();
        for (int index = 0; index < fieldArray.length(); index++) {
            JSONObject rawField = fieldArray.optJSONObject(index);
            if (rawField == null) throw invalid("template field[" + index + "]");
            Field field = Field.from(rawField, mapping, searchTextAttributes,
                optionSearchTextAttributes, index);
            if (!fieldIds.add(field.id)) throw invalid("duplicate field id " + field.id);
            field.setKind("");
            if (fieldSelector.matches(field.attributes)) matchedFields.add(field);
        }
        Field selectedField = selectByCardinality(matchedFields, "exactly_one",
            "live override fieldSelector");
        if (!outputField.equals(selectedField.id)) {
            throw invalid("selected live field does not match expected output field");
        }
        if (!selectedField.hasOptions || selectedField.options.isEmpty()) {
            throw invalid("selected live field has no options");
        }

        Selector optionSelector = Selector.parse(resolver.optJSONObject("optionSelector"),
            "live override resolver.optionSelector");
        if (optionSelector.references("kind")) {
            throw invalid("live override optionSelector cannot reference kind");
        }
        List<Option> matchedOptions = new ArrayList<>();
        for (Option option : selectedField.options) {
            if (optionSelector.matches(option.attributes)) matchedOptions.add(option);
        }
        Option selectedOption = selectByCardinality(matchedOptions, "exactly_one",
            "live override optionSelector");
        Builder builder = Builder.parse(resolver.optJSONObject("valueBuilder"), 1,
            new BuilderBudget(), LIVE_OVERRIDE_BUILDER_ROOTS,
            "live override resolver.valueBuilder");
        Object built = builder.build(new EvalContext(template.values, selectedField.values,
            selectedOption.values, new JSONObject(), identity));
        if (built == null || built == JSONObject.NULL) {
            throw invalid("live override valueBuilder returned null");
        }
        return copyJsonValue(built, 0);
    }

    static void validateExactLiveOptionResolver(JSONObject resolver) throws Exception {
        if (resolver == null) throw invalid("live override resolver");
        requireExactKeys(resolver, setOf("version", "searchTextAttributes",
            "optionSearchTextAttributes", "fieldSelector", "optionSelector", "valueBuilder"),
            "live override resolver");
        if (!isExactInteger(resolver.opt("version"), 1L)) {
            throw invalid("live override resolver.version");
        }
        parseAttributeNames(resolver.optJSONArray("searchTextAttributes"),
            "live override resolver.searchTextAttributes");
        parseAttributeNames(resolver.optJSONArray("optionSearchTextAttributes"),
            "live override resolver.optionSearchTextAttributes");
        Selector fieldSelector = Selector.parse(resolver.optJSONObject("fieldSelector"),
            "live override resolver.fieldSelector");
        Selector optionSelector = Selector.parse(resolver.optJSONObject("optionSelector"),
            "live override resolver.optionSelector");
        if (fieldSelector.references("kind") || optionSelector.references("kind")) {
            throw invalid("live override selectors cannot reference kind");
        }
        Builder.parse(resolver.optJSONObject("valueBuilder"), 1, new BuilderBudget(),
            LIVE_OVERRIDE_BUILDER_ROOTS, "live override resolver.valueBuilder");
    }

    /** Validates the canonical template/form-field/option mapping before any live fetch. */
    static void validateLiveTemplateFieldMapping(JSONObject fieldMapping) {
        if (fieldMapping == null) throw invalid("fieldMapping");
        Mapping.from(fieldMapping);
    }

    /** Immutable output of the upload-independent compilation phase. */
    public static final class CompiledPlan {
        private final JSONObject identity;
        private final List<Assignment> assignments;
        private final Map<String, String> recipeSources;
        private final long delayAfterMs;

        private CompiledPlan(JSONObject identity, List<Assignment> assignments,
                             Map<String, String> recipeSources, long delayAfterMs) throws Exception {
            this.identity = (JSONObject) copyJsonValue(identity, 0);
            this.assignments = Collections.unmodifiableList(new ArrayList<>(assignments));
            this.recipeSources = Collections.unmodifiableMap(
                new LinkedHashMap<>(recipeSources));
            this.delayAfterMs = delayAfterMs;
        }

        public Object templateId() {
            return copyUnchecked(identity.opt("templateId"));
        }

        public Object expectedStep() {
            return copyUnchecked(identity.opt("expectedStep"));
        }

        public Object warehouseId() {
            return copyUnchecked(identity.opt("warehouseId"));
        }

        public Object sku() {
            return copyUnchecked(identity.opt("sku"));
        }

        public JSONObject identity() {
            return (JSONObject) copyUnchecked(identity);
        }

        public long delayAfterMs() {
            return delayAfterMs;
        }

        /**
         * Builds a fresh payload fragment. Upload values are looked up by the Panel artifact/photo
         * key stored in recipe.sources, not by the recipe alias.
         */
        public CompiledPayload materialize(JSONObject uploadedSourceUrls) throws Exception {
            if (uploadedSourceUrls == null) throw invalid("uploadedSourceUrls");
            JSONObject data = new JSONObject();
            for (Assignment assignment : assignments) {
                if (assignment.kind == Assignment.OMIT) continue;
                Object value;
                if (assignment.kind == Assignment.PHOTO) {
                    String sourceKey = recipeSources.get(assignment.sourceAlias);
                    if (sourceKey == null || !uploadedSourceUrls.has(sourceKey)) {
                        throw invalid("missing uploaded source " + assignment.sourceAlias);
                    }
                    value = joinedUrls(uploadedSourceUrls.opt(sourceKey), assignment.joinWith,
                        assignment.sourceAlias);
                } else {
                    value = copyJsonValue(assignment.value, 0);
                }
                if (data.has(assignment.fieldId)) {
                    throw invalid("output field conflict " + assignment.fieldId);
                }
                data.put(assignment.fieldId, value);
            }
            return new CompiledPayload(identity, data);
        }
    }

    /** Identity plus form data needed by the existing submit adapter. */
    public static final class CompiledPayload {
        private final JSONObject identity;
        private final JSONObject data;

        private CompiledPayload(JSONObject identity, JSONObject data) throws Exception {
            this.identity = (JSONObject) copyJsonValue(identity, 0);
            this.data = (JSONObject) copyJsonValue(data, 0);
        }

        public Object templateId() {
            return copyUnchecked(identity.opt("templateId"));
        }

        public Object expectedStep() {
            return copyUnchecked(identity.opt("expectedStep"));
        }

        public Object warehouseId() {
            return copyUnchecked(identity.opt("warehouseId"));
        }

        public Object sku() {
            return copyUnchecked(identity.opt("sku"));
        }

        public JSONObject identity() {
            return (JSONObject) copyUnchecked(identity);
        }

        public JSONObject data() {
            return (JSONObject) copyUnchecked(data);
        }
    }

    private static Assignment compileAction(JSONObject rawAction, Field field, Mapping mapping,
                                            Map<String, Builder> namedBuilders,
                                            Map<String, String> recipeSources,
                                            Set<String> usedSourceAliases,
                                            EvalContext baseContext,
                                            BuilderBudget builderBudget,
                                            String path) throws Exception {
        if (rawAction == null) throw invalid(path);
        String type = requiredText(rawAction.opt("type"), path + ".type");
        EvalContext fieldContext = baseContext.withField(field.values);
        switch (type) {
            case "serial":
                requireExactKeys(rawAction, setOf("type"), path);
                return Assignment.value(field.id, Assignment.SERIAL,
                    fieldContext.input.opt("serial"));
            case "photo": {
                requireExactKeys(rawAction, setOf("type", "source", "joinWith"), path);
                String alias = requiredText(rawAction.opt("source"), path + ".source");
                if (!recipeSources.containsKey(alias)) {
                    throw invalid(path + ".source is not declared by recipe.sources");
                }
                Object joinValue = rawAction.opt("joinWith");
                if (!(joinValue instanceof String)
                        || ((String) joinValue).length() > MAX_TEXT_LENGTH) {
                    throw invalid(path + ".joinWith");
                }
                usedSourceAliases.add(alias);
                return Assignment.photo(field.id, alias, (String) joinValue);
            }
            case "omit": {
                requireExactKeys(rawAction, setOf("type", "allowRequired"), path);
                if (!(rawAction.opt("allowRequired") instanceof Boolean)) {
                    throw invalid(path + ".allowRequired");
                }
                boolean allowRequired = rawAction.optBoolean("allowRequired", false);
                if (field.required && !allowRequired) {
                    throw invalid(path + " cannot omit a required field");
                }
                return Assignment.omit(field.id);
            }
            case "fixedOption": {
                requireExactKeys(rawAction,
                    setOf("type", "optionSelectors", "valueBuilder", "onNoMatch"), path);
                if (!field.hasOptions || field.options.isEmpty()) {
                    throw invalid(path + " selected a field without options");
                }
                JSONArray rawSelectors = rawAction.optJSONArray("optionSelectors");
                if (rawSelectors == null || rawSelectors.length() == 0
                        || rawSelectors.length() > MAX_BUILDER_PATHS) {
                    throw invalid(path + ".optionSelectors");
                }
                List<OptionRule> optionRules = new ArrayList<>();
                for (int index = 0; index < rawSelectors.length(); index++) {
                    optionRules.add(OptionRule.parse(rawSelectors.optJSONObject(index), index,
                        path + ".optionSelectors"));
                }
                String builderId = requiredText(rawAction.opt("valueBuilder"),
                    path + ".valueBuilder");
                validateRecipeId(builderId, path + ".valueBuilder");
                Builder builder = namedBuilders.get(builderId);
                if (builder == null) throw invalid(path + ".valueBuilder is unknown");
                String onNoMatch = requiredText(rawAction.opt("onNoMatch"),
                    path + ".onNoMatch");
                if (!"reject".equals(onNoMatch) && !"use_value_builder".equals(onNoMatch)) {
                    throw invalid(path + ".onNoMatch");
                }

                Option selected = null;
                Object override = null;
                boolean hasOverride = false;
                for (int index = 0; index < optionRules.size(); index++) {
                    OptionRule optionRule = optionRules.get(index);
                    List<Option> matched = new ArrayList<>();
                    for (Option option : field.options) {
                        if (optionRule.selector.matches(option.attributes)) matched.add(option);
                    }
                    if (matched.isEmpty()) continue;
                    selected = selectByCardinality(matched, optionRule.cardinality,
                        path + ".optionSelectors[" + index + "]");
                    if (optionRule.hasLiteralOverride) {
                        hasOverride = true;
                        override = copyJsonValue(optionRule.literalOverride, 0);
                    }
                    break;
                }
                Object value;
                if (selected == null) {
                    if ("reject".equals(onNoMatch)) {
                        throw invalid(path + " did not match an option");
                    }
                    value = builder.build(fieldContext.withOption(null));
                } else if (hasOverride) {
                    value = override;
                } else {
                    value = builder.build(fieldContext.withOption(selected.values));
                }
                return Assignment.value(field.id, Assignment.FIXED, value);
            }
            default:
                throw invalid(path + ".type");
        }
    }

    private static Object joinedUrls(Object raw, String joinWith, String alias) {
        List<String> urls = new ArrayList<>();
        if (raw instanceof String) {
            urls.add(requiredText(raw, "uploaded source " + alias));
        } else if (raw instanceof JSONArray) {
            JSONArray values = (JSONArray) raw;
            if (values.length() == 0 || values.length() > MAX_SOURCES) {
                throw invalid("uploaded source " + alias);
            }
            for (int index = 0; index < values.length(); index++) {
                Object value = values.opt(index);
                if (!(value instanceof String)) throw invalid("uploaded source " + alias);
                urls.add(requiredText(value, "uploaded source " + alias));
            }
        } else {
            throw invalid("uploaded source " + alias);
        }
        StringBuilder joined = new StringBuilder();
        for (String url : urls) {
            if (joined.length() > 0) joined.append(joinWith);
            joined.append(url);
            if (joined.length() > MAX_TEXT_LENGTH * MAX_SOURCES) {
                throw invalid("uploaded source " + alias + " is too large");
            }
        }
        return joined.toString();
    }

    private static final class Mapping {
        final JSONObject template;
        final JSONObject formField;
        final JSONObject option;

        private Mapping(JSONObject template, JSONObject formField, JSONObject option) {
            this.template = template;
            this.formField = formField;
            this.option = option;
        }

        static Mapping from(JSONObject mapping) {
            JSONObject template = mapping.optJSONObject("template");
            JSONObject formField = mapping.optJSONObject("formField");
            JSONObject option = mapping.optJSONObject("option");
            if (template == null || formField == null || option == null) {
                throw invalid("fieldMapping requires template, formField, and option");
            }
            requireMappingKeys(template,
                setOf("id", "step", "warehouseId", "sku", "fieldList"),
                "fieldMapping.template");
            requireMappingKeys(formField,
                setOf("id", "type", "parentType", "typeName", "title", "englishTitle",
                    "required", "visible", "options"),
                "fieldMapping.formField");
            requireMappingKeys(option, setOf("value", "label", "englishLabel"),
                "fieldMapping.option");
            validateMapping(template, "fieldMapping.template");
            validateMapping(formField, "fieldMapping.formField");
            validateMapping(option, "fieldMapping.option");
            return new Mapping(template, formField, option);
        }
    }

    private static final class MappedObject {
        final JSONObject values;
        final Set<String> present;

        private MappedObject(JSONObject values, Set<String> present) {
            this.values = values;
            this.present = present;
        }

        static MappedObject from(JSONObject raw, JSONObject mapping, String path) throws Exception {
            JSONObject values = new JSONObject();
            Set<String> present = new LinkedHashSet<>();
            Iterator<String> keys = mapping.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object rawPath = mapping.opt(key);
                if (!(rawPath instanceof String)) continue;
                Lookup found = lookupRaw(raw, (String) rawPath);
                if (!found.present) continue;
                present.add(key);
                values.put(key, copyJsonValue(found.value, 0));
            }
            return new MappedObject(values, Collections.unmodifiableSet(present));
        }

        Object required(String key, String path) {
            if (!present.contains(key)) throw invalid(path);
            return values.opt(key);
        }
    }

    private static final class Field {
        final String id;
        final boolean required;
        final boolean visible;
        final boolean hasOptions;
        final JSONObject values;
        final Attributes attributes;
        final List<Option> options;
        String kind = "";

        private Field(String id, boolean required, boolean visible, JSONObject values,
                      Attributes attributes, List<Option> options) {
            this.id = id;
            this.required = required;
            this.visible = visible;
            this.values = values;
            this.attributes = attributes;
            this.options = Collections.unmodifiableList(new ArrayList<>(options));
            this.hasOptions = !options.isEmpty();
        }

        static Field from(JSONObject raw, Mapping mapping, List<String> searchTextAttributes,
                          List<String> optionSearchTextAttributes, int index) throws Exception {
            MappedObject mapped = MappedObject.from(raw, mapping.formField,
                "template field[" + index + "]");
            String id = requiredText(mapped.required("id", "template field id"),
                "template field id");
            boolean required = strictBoolean(mapped.required("required",
                "template field " + id + " required"), "template field " + id + " required");
            boolean visible = strictBoolean(mapped.required("visible",
                "template field " + id + " visible"), "template field " + id + " visible");
            List<Option> options = new ArrayList<>();
            if (mapped.present.contains("options")) {
                Object rawOptions = mapped.values.opt("options");
                if (rawOptions != null && rawOptions != JSONObject.NULL) {
                    if (!(rawOptions instanceof JSONArray)) {
                        throw invalid("template field " + id + " options");
                    }
                    JSONArray array = (JSONArray) rawOptions;
                    if (array.length() > MAX_OPTIONS) {
                        throw invalid("template field " + id + " option count");
                    }
                    for (int optionIndex = 0; optionIndex < array.length(); optionIndex++) {
                        JSONObject rawOption = array.optJSONObject(optionIndex);
                        if (rawOption == null) {
                            throw invalid("template field " + id + " option[" + optionIndex + "]");
                        }
                        options.add(Option.from(rawOption, mapping.option,
                            optionSearchTextAttributes, id, optionIndex));
                    }
                }
            }
            Attributes attrs = mappedAttributes(mapped, searchTextAttributes);
            attrs.put("id", true, id);
            attrs.put("required", true, required);
            attrs.put("visible", true, visible);
            attrs.put("hasOptions", true, !options.isEmpty());
            // id is canonicalized above rather than copied by mappedAttributes. Build searchText
            // only after id is present so an explicit ["id"] configuration behaves identically
            // for fields and options.
            addSearchText(attrs, searchTextAttributes);
            return new Field(id, required, visible, mapped.values, attrs, options);
        }

        void setKind(String value) throws Exception {
            kind = value;
            if (!value.isEmpty()) {
                attributes.put("kind", true, value);
                values.put("kind", value);
            }
            values.put("searchText", attributes.value("searchText"));
            values.put("hasOptions", hasOptions);
        }
    }

    private static final class Option {
        final JSONObject values;
        final Attributes attributes;

        private Option(JSONObject values, Attributes attributes) {
            this.values = values;
            this.attributes = attributes;
        }

        static Option from(JSONObject raw, JSONObject mapping,
                           List<String> searchTextAttributes, String fieldId,
                           int index) throws Exception {
            MappedObject mapped = MappedObject.from(raw, mapping,
                "field " + fieldId + " option[" + index + "]");
            Attributes attrs = new Attributes();
            if (mapped.present.contains("value")) {
                Object value = mapped.values.opt("value");
                attrs.put("id", true, value);
                mapped.values.put("id", copyJsonValue(value, 0));
            }
            copyOptionAlias(mapped, attrs, "label", "title");
            copyOptionAlias(mapped, attrs, "englishLabel", "englishTitle");
            addSearchText(attrs, searchTextAttributes);
            attrs.put("hasOptions", true, false);
            mapped.values.put("searchText", attrs.value("searchText"));
            mapped.values.put("hasOptions", false);
            return new Option(mapped.values, attrs);
        }

        private static void copyOptionAlias(MappedObject mapped, Attributes attrs,
                                            String from, String to) throws Exception {
            if (!mapped.present.contains(from)) return;
            Object value = mapped.values.opt(from);
            if (value instanceof JSONObject || value instanceof JSONArray) {
                throw invalid("option " + from + " must be scalar");
            }
            Object canonical = value == null ? JSONObject.NULL : value;
            attrs.put(to, true, canonical == JSONObject.NULL
                ? JSONObject.NULL : String.valueOf(canonical));
            mapped.values.put(to, canonical == JSONObject.NULL ? JSONObject.NULL
                : String.valueOf(canonical));
        }
    }

    private static Attributes mappedAttributes(MappedObject mapped,
                                               List<String> searchTextAttributes) {
        Attributes attrs = new Attributes();
        for (String key : STRING_ATTRIBUTES) {
            if ("kind".equals(key) || "searchText".equals(key) || "id".equals(key)) continue;
            if (mapped.present.contains(key)) {
                Object value = mapped.values.opt(key);
                if (value != null && value != JSONObject.NULL
                        && !(value instanceof JSONObject) && !(value instanceof JSONArray)) {
                    attrs.put(key, true, String.valueOf(value));
                } else if (value == JSONObject.NULL) {
                    attrs.put(key, true, JSONObject.NULL);
                }
            }
        }
        addSearchText(attrs, searchTextAttributes);
        return attrs;
    }

    private static void addSearchText(Attributes attrs, List<String> searchTextAttributes) {
        StringBuilder search = new StringBuilder();
        for (String key : searchTextAttributes) {
            Attr value = attrs.get(key);
            if (value == null || !value.present || value.value == JSONObject.NULL) continue;
            String text = String.valueOf(value.value);
            if (text.isEmpty()) continue;
            if (search.length() > 0) search.append('\n');
            search.append(text);
        }
        attrs.put("searchText", true, search.toString());
    }

    private static final class Attributes {
        private final Map<String, Attr> values = new LinkedHashMap<>();

        void put(String key, boolean present, Object value) {
            values.put(key, new Attr(present, value));
        }

        Attr get(String key) {
            return values.get(key);
        }

        Object value(String key) {
            Attr value = values.get(key);
            return value == null ? JSONObject.NULL : value.value;
        }
    }

    private static final class Attr {
        final boolean present;
        final Object value;

        private Attr(boolean present, Object value) {
            this.present = present;
            this.value = value;
        }
    }

    private static final class KindRule {
        final String kind;
        final Selector selector;

        private KindRule(String kind, Selector selector) {
            this.kind = kind;
            this.selector = selector;
        }

        static KindRule parse(JSONObject raw, int index) {
            String path = "resolver.kindSelectors[" + index + "]";
            if (raw == null) throw invalid(path);
            requireExactKeys(raw, setOf("kind", "selector"), path);
            String kind = requiredText(raw.opt("kind"), path + ".kind");
            Selector selector = Selector.parse(raw.optJSONObject("selector"), path + ".selector");
            if (selector.references("kind")) {
                throw invalid(path + " cannot derive kind from kind");
            }
            return new KindRule(kind, selector);
        }
    }

    private static final class Rule {
        final Selector selector;
        final String cardinality;
        final JSONObject action;

        private Rule(Selector selector, String cardinality, JSONObject action) {
            this.selector = selector;
            this.cardinality = cardinality;
            this.action = action;
        }

        static Rule parse(JSONObject raw, int index) {
            String path = "resolver.rules[" + index + "]";
            if (raw == null) throw invalid(path);
            requireExactKeys(raw, setOf("selector", "cardinality", "action"), path);
            Selector selector = Selector.parse(raw.optJSONObject("selector"), path + ".selector");
            String cardinality = parseCardinality(raw.opt("cardinality"), path + ".cardinality");
            JSONObject action = raw.optJSONObject("action");
            if (action == null) throw invalid(path + ".action");
            return new Rule(selector, cardinality, action);
        }
    }

    private static final class OptionRule {
        final Selector selector;
        final String cardinality;
        final boolean hasLiteralOverride;
        final Object literalOverride;

        private OptionRule(Selector selector, String cardinality, boolean hasLiteralOverride,
                           Object literalOverride) {
            this.selector = selector;
            this.cardinality = cardinality;
            this.hasLiteralOverride = hasLiteralOverride;
            this.literalOverride = literalOverride;
        }

        static OptionRule parse(JSONObject raw, int index, String parent) throws Exception {
            String path = parent + "[" + index + "]";
            if (raw == null) throw invalid(path);
            requireAllowedKeys(raw, setOf("selector", "cardinality", "literalOverride"),
                setOf("selector", "cardinality"), path);
            Selector selector = Selector.parse(raw.optJSONObject("selector"), path + ".selector");
            String cardinality = parseCardinality(raw.opt("cardinality"), path + ".cardinality");
            boolean hasOverride = raw.has("literalOverride");
            Object override = hasOverride ? copyJsonValue(raw.opt("literalOverride"), 0) : null;
            return new OptionRule(selector, cardinality, hasOverride, override);
        }
    }

    private static final class Selector {
        final List<Predicate> allOf;
        final List<Predicate> anyOf;
        final List<Predicate> noneOf;

        private Selector(List<Predicate> allOf, List<Predicate> anyOf,
                         List<Predicate> noneOf) {
            this.allOf = allOf;
            this.anyOf = anyOf;
            this.noneOf = noneOf;
        }

        static Selector parse(JSONObject raw, String path) {
            if (raw == null) throw invalid(path);
            requireAllowedKeys(raw, setOf("allOf", "anyOf", "noneOf"),
                Collections.emptySet(), path);
            List<Predicate> all = parsePredicateGroup(raw, "allOf", path);
            List<Predicate> any = parsePredicateGroup(raw, "anyOf", path);
            List<Predicate> none = parsePredicateGroup(raw, "noneOf", path);
            int total = all.size() + any.size() + none.size();
            if (total == 0 || total > MAX_SELECTOR_PREDICATES) throw invalid(path);
            return new Selector(all, any, none);
        }

        boolean matches(Attributes attributes) {
            for (Predicate predicate : allOf) if (!predicate.matches(attributes)) return false;
            if (!anyOf.isEmpty()) {
                boolean found = false;
                for (Predicate predicate : anyOf) if (predicate.matches(attributes)) found = true;
                if (!found) return false;
            }
            for (Predicate predicate : noneOf) if (predicate.matches(attributes)) return false;
            return true;
        }

        boolean references(String attribute) {
            for (Predicate predicate : allOf) if (attribute.equals(predicate.attribute)) return true;
            for (Predicate predicate : anyOf) if (attribute.equals(predicate.attribute)) return true;
            for (Predicate predicate : noneOf) if (attribute.equals(predicate.attribute)) return true;
            return false;
        }
    }

    private static List<Predicate> parsePredicateGroup(JSONObject raw, String key, String path) {
        if (!raw.has(key)) return Collections.emptyList();
        JSONArray values = raw.optJSONArray(key);
        if (values == null || values.length() == 0 || values.length() > MAX_SELECTOR_GROUP) {
            throw invalid(path + "." + key);
        }
        List<Predicate> out = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            out.add(Predicate.parse(values.optJSONObject(index),
                path + "." + key + "[" + index + "]"));
        }
        return Collections.unmodifiableList(out);
    }

    private static final class Predicate {
        static final int EQUALS = 1;
        static final int CONTAINS = 2;
        static final int PRESENT = 3;

        final String attribute;
        final boolean caseSensitive;
        final int operation;
        final List<Object> candidates;
        final boolean expectedPresence;

        private Predicate(String attribute, boolean caseSensitive, int operation,
                          List<Object> candidates, boolean expectedPresence) {
            this.attribute = attribute;
            this.caseSensitive = caseSensitive;
            this.operation = operation;
            this.candidates = candidates;
            this.expectedPresence = expectedPresence;
        }

        static Predicate parse(JSONObject raw, String path) {
            if (raw == null) throw invalid(path);
            requireAllowedKeys(raw,
                setOf("attribute", "caseSensitive", "equalsAny", "containsAny", "present"),
                setOf("attribute", "caseSensitive"), path);
            String attribute = requiredText(raw.opt("attribute"), path + ".attribute");
            if (!CANONICAL_ATTRIBUTES.contains(attribute)) {
                throw invalid(path + ".attribute");
            }
            if (!(raw.opt("caseSensitive") instanceof Boolean)) {
                throw invalid(path + ".caseSensitive");
            }
            boolean caseSensitive = raw.optBoolean("caseSensitive", false);
            int operationCount = (raw.has("equalsAny") ? 1 : 0)
                + (raw.has("containsAny") ? 1 : 0) + (raw.has("present") ? 1 : 0);
            if (operationCount != 1) throw invalid(path + " predicate operation");
            if (raw.has("present")) {
                if (!(raw.opt("present") instanceof Boolean)) throw invalid(path + ".present");
                return new Predicate(attribute, caseSensitive, PRESENT,
                    Collections.emptyList(), raw.optBoolean("present", false));
            }
            String operator = raw.has("equalsAny") ? "equalsAny" : "containsAny";
            JSONArray values = raw.optJSONArray(operator);
            if (values == null || values.length() == 0 || values.length() > MAX_SELECTOR_GROUP) {
                throw invalid(path + "." + operator);
            }
            List<Object> candidates = new ArrayList<>();
            for (int index = 0; index < values.length(); index++) {
                Object value = values.opt(index);
                if (value instanceof JSONObject || value instanceof JSONArray || value == null) {
                    throw invalid(path + "." + operator + "[" + index + "]");
                }
                if ("containsAny".equals(operator)) {
                    if (!(value instanceof String) || ((String) value).isEmpty()
                            || ((String) value).length() > MAX_TEXT_LENGTH) {
                        throw invalid(path + ".containsAny[" + index + "]");
                    }
                }
                candidates.add(value);
            }
            return new Predicate(attribute, caseSensitive,
                "equalsAny".equals(operator) ? EQUALS : CONTAINS,
                Collections.unmodifiableList(candidates), false);
        }

        boolean matches(Attributes attributes) {
            Attr actual = attributes.get(attribute);
            if (operation == PRESENT) {
                return (actual != null && actual.present) == expectedPresence;
            }
            if (actual == null || !actual.present || actual.value == JSONObject.NULL) return false;
            if (operation == CONTAINS) {
                String text = String.valueOf(actual.value);
                String compared = caseSensitive ? text : text.toLowerCase(Locale.ROOT);
                for (Object candidate : candidates) {
                    String needle = (String) candidate;
                    if (!caseSensitive) needle = needle.toLowerCase(Locale.ROOT);
                    if (compared.contains(needle)) return true;
                }
                return false;
            }
            for (Object candidate : candidates) {
                if (equalsValue(actual.value, candidate, caseSensitive)) return true;
            }
            return false;
        }
    }

    private interface Builder {
        Object build(EvalContext context) throws Exception;

        static Builder parse(JSONObject raw, int depth, BuilderBudget budget,
                             Set<String> allowedRoots, String path) throws Exception {
            if (raw == null || depth > MAX_BUILDER_DEPTH || ++budget.count > MAX_RULES * 16) {
                throw invalid(path);
            }
            String type = requiredText(raw.opt("type"), path + ".type");
            switch (type) {
                case "literal": {
                    requireExactKeys(raw, setOf("type", "value"), path);
                    Object literal = copyJsonValue(raw.opt("value"), 0);
                    return context -> copyJsonValue(literal, 0);
                }
                case "present": {
                    requireExactKeys(raw, setOf("type", "path", "fallbackIfMissing"), path);
                    JsonPath valuePath = JsonPath.parse(raw.opt("path"), allowedRoots,
                        path + ".path");
                    Object fallback = copyJsonValue(raw.opt("fallbackIfMissing"), 0);
                    return context -> {
                        Lookup result = context.lookup(valuePath);
                        return copyJsonValue(result.present ? result.value : fallback, 0);
                    };
                }
                case "firstNonEmpty": {
                    requireExactKeys(raw, setOf("type", "paths"), path);
                    JSONArray rawPaths = raw.optJSONArray("paths");
                    if (rawPaths == null || rawPaths.length() == 0
                            || rawPaths.length() > MAX_BUILDER_PATHS) {
                        throw invalid(path + ".paths");
                    }
                    List<JsonPath> paths = new ArrayList<>();
                    for (int index = 0; index < rawPaths.length(); index++) {
                        paths.add(JsonPath.parse(rawPaths.opt(index), allowedRoots,
                            path + ".paths[" + index + "]"));
                    }
                    return context -> {
                        for (JsonPath candidate : paths) {
                            Lookup result = context.lookup(candidate);
                            if (result.present && !isEmpty(result.value)) {
                                return copyJsonValue(result.value, 0);
                            }
                        }
                        throw invalid(path + " found no non-empty value");
                    };
                }
                case "integer": {
                    requireExactKeys(raw, setOf("type", "path", "default"), path);
                    JsonPath valuePath = JsonPath.parse(raw.opt("path"), allowedRoots,
                        path + ".path");
                    Object defaultValue = strictInteger(raw.opt("default"), path + ".default");
                    return context -> {
                        Lookup result = context.lookup(valuePath);
                        return result.present
                            ? strictInteger(result.value, path + ".path value") : defaultValue;
                    };
                }
                case "object": {
                    requireExactKeys(raw, setOf("type", "members"), path);
                    JSONObject rawMembers = raw.optJSONObject("members");
                    if (rawMembers == null || rawMembers.length() == 0
                            || rawMembers.length() > MAX_OBJECT_MEMBERS) {
                        throw invalid(path + ".members");
                    }
                    Map<String, Builder> members = new LinkedHashMap<>();
                    Iterator<String> keys = rawMembers.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        validateName(key, path + ".members key");
                        JSONObject member = rawMembers.optJSONObject(key);
                        if (member == null) throw invalid(path + ".members." + key);
                        members.put(key, parse(member, depth + 1, budget, allowedRoots,
                            path + ".members." + key));
                    }
                    return context -> {
                        JSONObject object = new JSONObject();
                        for (Map.Entry<String, Builder> member : members.entrySet()) {
                            object.put(member.getKey(), member.getValue().build(context));
                        }
                        return object;
                    };
                }
                default:
                    throw invalid(path + ".type");
            }
        }
    }

    private static final class BuilderBudget {
        int count;
    }

    private static final class EvalContext {
        final JSONObject template;
        final JSONObject field;
        final JSONObject option;
        final JSONObject input;
        final JSONObject identity;

        private EvalContext(JSONObject template, JSONObject field, JSONObject option,
                            JSONObject input, JSONObject identity) {
            this.template = template;
            this.field = field;
            this.option = option;
            this.input = input;
            this.identity = identity;
        }

        EvalContext withField(JSONObject value) {
            return new EvalContext(template, value, option, input, identity);
        }

        EvalContext withOption(JSONObject value) {
            return new EvalContext(template, field, value, input, identity);
        }

        Lookup lookup(JsonPath path) {
            JSONObject root;
            switch (path.parts.get(0)) {
                case "template": root = template; break;
                case "field": root = field; break;
                case "option": root = option; break;
                case "input": root = input; break;
                case "identity": root = identity; break;
                default: return Lookup.missing();
            }
            if (root == null) return Lookup.missing();
            Object current = root;
            for (int index = 1; index < path.parts.size(); index++) {
                if (!(current instanceof JSONObject)) return Lookup.missing();
                JSONObject object = (JSONObject) current;
                String key = path.parts.get(index);
                if (!object.has(key)) return Lookup.missing();
                current = object.opt(key);
            }
            return Lookup.present(current == null ? JSONObject.NULL : current);
        }
    }

    private static final class JsonPath {
        final List<String> parts;

        private JsonPath(List<String> parts) {
            this.parts = parts;
        }

        static JsonPath parse(Object raw, Set<String> allowedRoots, String path) {
            if (!(raw instanceof String)) throw invalid(path);
            String value = (String) raw;
            if (value.isEmpty() || value.length() > MAX_PATH_LENGTH) throw invalid(path);
            List<String> parts = splitPath(value, path);
            if (parts.size() < 2 || parts.size() > MAX_PATH_DEPTH
                    || !allowedRoots.contains(parts.get(0))
                    || !allowedPathKey(parts.get(0), parts.get(1))) {
                throw invalid(path);
            }
            return new JsonPath(Collections.unmodifiableList(parts));
        }
    }

    private static final class Lookup {
        final boolean present;
        final Object value;

        private Lookup(boolean present, Object value) {
            this.present = present;
            this.value = value;
        }

        static Lookup present(Object value) {
            return new Lookup(true, value);
        }

        static Lookup missing() {
            return new Lookup(false, null);
        }
    }

    private static final class Assignment {
        static final int SERIAL = 1;
        static final int PHOTO = 2;
        static final int FIXED = 3;
        static final int OMIT = 4;

        final String fieldId;
        final int kind;
        final Object value;
        final String sourceAlias;
        final String joinWith;

        private Assignment(String fieldId, int kind, Object value,
                           String sourceAlias, String joinWith) {
            this.fieldId = fieldId;
            this.kind = kind;
            this.value = value;
            this.sourceAlias = sourceAlias;
            this.joinWith = joinWith;
        }

        static Assignment value(String fieldId, int kind, Object value) throws Exception {
            return new Assignment(fieldId, kind, copyJsonValue(value, 0), "", "");
        }

        static Assignment photo(String fieldId, String sourceAlias, String joinWith) {
            return new Assignment(fieldId, PHOTO, null, sourceAlias, joinWith);
        }

        static Assignment omit(String fieldId) {
            return new Assignment(fieldId, OMIT, null, "", "");
        }
    }

    private static <T> T selectByCardinality(List<T> matched, String cardinality, String path) {
        if (matched.isEmpty()) throw invalid(path + " matched no values");
        if ("exactly_one".equals(cardinality) && matched.size() != 1) {
            throw invalid(path + " is ambiguous");
        }
        return matched.get(0);
    }

    private static String parseCardinality(Object value, String path) {
        String cardinality = requiredText(value, path);
        if (!"exactly_one".equals(cardinality)
                && !"first_in_backend_order".equals(cardinality)) {
            throw invalid(path);
        }
        return cardinality;
    }

    private static Map<String, String> parseSources(JSONObject raw) {
        if (raw == null || raw.length() > MAX_SOURCES) throw invalid("recipe.sources");
        Map<String, String> sources = new LinkedHashMap<>();
        Iterator<String> keys = raw.keys();
        while (keys.hasNext()) {
            String alias = keys.next();
            validateName(alias, "recipe.sources alias");
            Object value = raw.opt(alias);
            if (!(value instanceof String)) throw invalid("recipe.sources." + alias);
            sources.put(alias, requiredText(value, "recipe.sources." + alias));
        }
        return sources;
    }

    private static List<String> parseAttributeNames(JSONArray raw, String path) {
        if (raw == null || raw.length() > CANONICAL_ATTRIBUTES.size()) {
            throw invalid(path);
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int index = 0; index < raw.length(); index++) {
            Object value = raw.opt(index);
            if (!(value instanceof String) || !STRING_ATTRIBUTES.contains(value)
                    || "kind".equals(value) || "searchText".equals(value)
                    || !seen.add((String) value)) {
                throw invalid(path + "[" + index + "]");
            }
            out.add((String) value);
        }
        return Collections.unmodifiableList(out);
    }

    private static void requireMappingKeys(JSONObject mapping, Set<String> required, String path) {
        for (String key : required) {
            Object value = mapping.opt(key);
            if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
                throw invalid(path + "." + key);
            }
        }
    }

    private static void validateMapping(JSONObject mapping, String path) {
        if (mapping.length() == 0 || mapping.length() > MAX_OBJECT_MEMBERS) throw invalid(path);
        Iterator<String> keys = mapping.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            validateName(key, path + " key");
            Object value = mapping.opt(key);
            if (!(value instanceof String)) throw invalid(path + "." + key);
            splitPath((String) value, path + "." + key);
        }
    }

    private static Lookup lookupRaw(JSONObject root, String path) {
        List<String> parts = splitPath(path, "mapping path");
        Object current = root;
        for (String part : parts) {
            if (!(current instanceof JSONObject)) return Lookup.missing();
            JSONObject object = (JSONObject) current;
            if (!object.has(part)) return Lookup.missing();
            current = object.opt(part);
        }
        return Lookup.present(current == null ? JSONObject.NULL : current);
    }

    private static List<String> splitPath(String value, String path) {
        if (value == null || value.isEmpty() || value.length() > MAX_PATH_LENGTH) {
            throw invalid(path);
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int index = 0; index <= value.length(); index++) {
            if (index != value.length() && value.charAt(index) != '.') continue;
            String part = value.substring(start, index);
            if (part.isEmpty() || !safeName(part)) throw invalid(path);
            parts.add(part);
            start = index + 1;
        }
        if (parts.isEmpty() || parts.size() > MAX_PATH_DEPTH) throw invalid(path);
        return parts;
    }

    private static boolean safeName(String value) {
        if (value == null || value.isEmpty() || value.length() > 128) return false;
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-')) {
                return false;
            }
        }
        return true;
    }

    private static boolean safeRecipeId(String value, boolean allowDot) {
        if (value == null || value.isEmpty() || value.length() > 128) return false;
        char first = value.charAt(0);
        if (!((first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z'))) {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (!((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-'
                    || (allowDot && ch == '.'))) {
                return false;
            }
        }
        return !"__proto__".equals(value) && !"prototype".equals(value)
            && !"constructor".equals(value);
    }

    private static void validateRecipeId(String value, String path) {
        if (!safeRecipeId(value, true)) throw invalid(path);
    }

    private static boolean allowedPathKey(String root, String key) {
        switch (root) {
            case "template": return TEMPLATE_PATH_KEYS.contains(key);
            case "field": return FIELD_PATH_KEYS.contains(key);
            case "option": return OPTION_PATH_KEYS.contains(key);
            case "input": return INPUT_PATH_KEYS.contains(key);
            case "identity": return IDENTITY_PATH_KEYS.contains(key);
            default: return false;
        }
    }

    private static void validateName(String value, String path) {
        if (!safeName(value)) throw invalid(path);
    }

    private static boolean strictBoolean(Object value, String path) {
        if (!(value instanceof Boolean)) throw invalid(path);
        return (Boolean) value;
    }

    private static Object strictInteger(Object value, String path) {
        long parsed;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            parsed = ((Number) value).longValue();
        } else if (value instanceof Long) {
            parsed = (Long) value;
        } else if (value instanceof Number) {
            double numeric = ((Number) value).doubleValue();
            if (!Double.isFinite(numeric) || numeric != Math.rint(numeric)
                    || numeric < Long.MIN_VALUE || numeric > Long.MAX_VALUE) {
                throw invalid(path);
            }
            parsed = (long) numeric;
        } else if (value instanceof String && isIntegerText((String) value)) {
            try {
                parsed = Long.parseLong((String) value);
            } catch (NumberFormatException error) {
                throw invalid(path);
            }
        } else {
            throw invalid(path);
        }
        return parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE ? (int) parsed : parsed;
    }

    private static boolean isIntegerText(String value) {
        if (value == null || value.isEmpty()) return false;
        int start = value.charAt(0) == '-' ? 1 : 0;
        if (start == value.length()) return false;
        for (int index = start; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    private static boolean isExactInteger(Object value, long expected) {
        try {
            return ((Number) strictInteger(value, "integer")).longValue() == expected;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static Object requiredIdentityScalar(Object value, String path) {
        if (value == null || value == JSONObject.NULL || value instanceof JSONObject
                || value instanceof JSONArray || value instanceof Boolean) {
            throw invalid(path);
        }
        if (value instanceof String) {
            requiredText(value, path);
        } else if (value instanceof Number) {
            double numeric = ((Number) value).doubleValue();
            if (!Double.isFinite(numeric)) throw invalid(path);
        } else {
            throw invalid(path);
        }
        return value;
    }

    private static Object requiredRecipeIdentityScalar(Object value, String path) {
        requiredIdentityScalar(value, path);
        if (value instanceof Number) {
            double numeric = ((Number) value).doubleValue();
            if (numeric != Math.rint(numeric) || Math.abs(numeric) > 9_007_199_254_740_991d) {
                throw invalid(path);
            }
        }
        return value;
    }

    private static boolean identityEquals(Object left, Object right) {
        BigDecimal leftNumber = identityNumber(left);
        BigDecimal rightNumber = identityNumber(right);
        if (leftNumber != null && rightNumber != null
                && (left instanceof Number || right instanceof Number)) {
            return leftNumber.compareTo(rightNumber) == 0;
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private static BigDecimal identityNumber(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) return null;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean equalsValue(Object left, Object right, boolean caseSensitive) {
        if (left == JSONObject.NULL || right == JSONObject.NULL) return left == right;
        if (left instanceof Number && right instanceof Number) {
            return identityNumber(left).compareTo(identityNumber(right)) == 0;
        }
        if (left instanceof String && right instanceof String) {
            return caseSensitive ? left.equals(right)
                : ((String) left).equalsIgnoreCase((String) right);
        }
        return left != null && left.equals(right);
    }

    private static boolean isEmpty(Object value) {
        if (value == null || value == JSONObject.NULL) return true;
        if (value instanceof String) return ((String) value).trim().isEmpty();
        if (value instanceof JSONArray) return ((JSONArray) value).length() == 0;
        if (value instanceof JSONObject) return ((JSONObject) value).length() == 0;
        return false;
    }

    private static String requiredText(Object value, String path) {
        if (!(value instanceof String)) throw invalid(path);
        String text = ((String) value).trim();
        if (text.isEmpty() || text.length() > MAX_TEXT_LENGTH) throw invalid(path);
        return text;
    }

    private static Object copyJsonValue(Object value, int depth) throws Exception {
        if (depth > MAX_BUILDER_DEPTH + 4) throw invalid("JSON value depth");
        if (value == null || value == JSONObject.NULL) return JSONObject.NULL;
        if (value instanceof String) {
            if (((String) value).length() > MAX_TEXT_LENGTH * MAX_SOURCES) {
                throw invalid("JSON string value is too large");
            }
            return value;
        }
        if (value instanceof Boolean) return value;
        if (value instanceof Number) {
            double numeric = ((Number) value).doubleValue();
            if (!Double.isFinite(numeric)) throw invalid("non-finite JSON number");
            return value;
        }
        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray) value;
            if (source.length() > MAX_LITERAL_CONTAINER) throw invalid("JSON array is too large");
            JSONArray copy = new JSONArray();
            for (int index = 0; index < source.length(); index++) {
                copy.put(copyJsonValue(source.opt(index), depth + 1));
            }
            return copy;
        }
        if (value instanceof JSONObject) {
            JSONObject source = (JSONObject) value;
            if (source.length() > MAX_LITERAL_CONTAINER) throw invalid("JSON object is too large");
            JSONObject copy = new JSONObject();
            Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.length() > MAX_TEXT_LENGTH) throw invalid("JSON object key is too large");
                copy.put(key, copyJsonValue(source.opt(key), depth + 1));
            }
            return copy;
        }
        throw invalid("unsupported JSON value");
    }

    private static Object copyUnchecked(Object value) {
        try {
            return copyJsonValue(value, 0);
        } catch (Exception error) {
            throw new IllegalStateException("compiled previous-step plan is invalid", error);
        }
    }

    private static void requireExactKeys(JSONObject object, Set<String> expected, String path) {
        requireAllowedKeys(object, expected, expected, path);
    }

    private static void requireAllowedKeys(JSONObject object, Set<String> allowed,
                                           Set<String> required, String path) {
        if (object == null) throw invalid(path);
        Iterator<String> keys = object.keys();
        Set<String> present = new LinkedHashSet<>();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowed.contains(key)) throw invalid(path + " contains unknown key " + key);
            present.add(key);
        }
        if (!present.containsAll(required)) throw invalid(path + " is missing required keys");
    }

    @SafeVarargs
    private static <T> Set<T> immutableSet(T... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }

    private static Set<String> setOf(String... values) {
        return immutableSet(values);
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("dynamic previous-step recipe rejected: " + detail);
    }
}
