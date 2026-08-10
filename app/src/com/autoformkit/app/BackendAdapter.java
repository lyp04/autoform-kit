package com.autoformkit.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Versioned description of the backend protocol supplied by the panel.
 *
 * <p>The public app intentionally contains no deployment endpoint paths or printer protocol
 * constants. A panel must publish {@code backendAdapter.version == 1}. Missing values remain
 * missing: callers receive a configuration error before an HTTP connection can be opened.
 *
 * <p>For a staged panel migration, endpoint values may live either in
 * {@code backendAdapter.endpoints} or in the older top-level {@code endpoints} object. The
 * versioned adapter itself is still mandatory; accepting the old object here only lets a panel
 * move its already-entered paths without briefly duplicating them.
 */
final class BackendAdapter {
    static final int SUPPORTED_VERSION = 1;

    static final String ENDPOINT_CAPTCHA = "captcha";
    static final String ENDPOINT_LOGIN_VERIFY = "loginVerify";
    static final String ENDPOINT_LOGIN = "login";
    static final String ENDPOINT_USER_INFO = "userInfo";
    static final String ENDPOINT_PRINTER_STATE = "printerState";
    static final String ENDPOINT_MESSAGE_LIST = "messageList";
    static final String ENDPOINT_LABEL_RETRY = "labelRetry";
    static final String ENDPOINT_UPLOAD_FILE = "uploadFile";
    static final String ENDPOINT_SUBMIT_ENTRY = "submitEntry";
    static final String ENDPOINT_DETECTION_DATA = "detectionData";
    static final String ENDPOINT_SN_REPETITION = "snRepetition";
    static final String ENDPOINT_TEMPLATE_DETAIL = "templateDetail";

    private static final String[] AUTH_ENDPOINTS = new String[]{
        ENDPOINT_CAPTCHA, ENDPOINT_LOGIN, ENDPOINT_USER_INFO
    };
    private static final String[] SUBMIT_ENDPOINTS = new String[]{
        ENDPOINT_UPLOAD_FILE, ENDPOINT_SUBMIT_ENTRY
    };
    private static final String[] PRINT_ENDPOINTS = new String[]{
        ENDPOINT_PRINTER_STATE, ENDPOINT_MESSAGE_LIST, ENDPOINT_LABEL_RETRY
    };

    final int version;
    final boolean declared;
    final String baseUrl;
    final Map<String, String> endpoints;
    final Request request;
    final Response response;
    final Auth auth;
    final Fields fields;
    final Operations operations;
    final MaterialRefresh materialRefresh;
    final Printing printing;
    final BackendSessionErrors.Policy sessionInvalidPolicy;
    final List<String> parseErrors;

    private BackendAdapter(int version, boolean declared, String baseUrl,
                           Map<String, String> endpoints, Request request, Response response,
                           Auth auth, Fields fields, Operations operations,
                           MaterialRefresh materialRefresh, Printing printing,
                           BackendSessionErrors.Policy sessionInvalidPolicy,
                           List<String> parseErrors) {
        this.version = version;
        this.declared = declared;
        this.baseUrl = baseUrl == null ? "" : stripTrailingSlash(baseUrl.trim());
        this.endpoints = Collections.unmodifiableMap(new LinkedHashMap<>(endpoints));
        this.request = request;
        this.response = response;
        this.auth = auth;
        this.fields = fields;
        this.operations = operations;
        this.materialRefresh = materialRefresh;
        this.printing = printing;
        this.sessionInvalidPolicy = sessionInvalidPolicy == null
            ? BackendSessionErrors.Policy.empty() : sessionInvalidPolicy;
        this.parseErrors = Collections.unmodifiableList(new ArrayList<>(parseErrors));
    }

    static BackendAdapter from(JSONObject appConfig) {
        return from(appConfig, null);
    }

    static BackendAdapter from(JSONObject appConfig, JSONObject catalogSettings) {
        List<String> errors = new ArrayList<>();
        JSONObject adapter = appConfig == null ? null : appConfig.optJSONObject("backendAdapter");
        if (adapter == null && catalogSettings != null) {
            adapter = catalogSettings.optJSONObject("backendAdapter");
        }
        if (adapter == null) {
            errors.add("backendAdapter");
            return new BackendAdapter(0, false, "", Collections.emptyMap(), Request.invalid(),
                Response.invalid(), Auth.invalid(), Fields.invalid(), Operations.invalid(),
                MaterialRefresh.invalid(), Printing.disabled(),
                BackendSessionErrors.Policy.empty(), errors);
        }

        int version = adapter.optInt("version", 0);
        if (version != SUPPORTED_VERSION) {
            errors.add("backendAdapter.version=" + version);
        }
        String baseUrl = adapter.optString("baseUrl", "").trim();
        if (baseUrl.isEmpty()) errors.add("backendAdapter.baseUrl");

        JSONObject endpointJson = adapter.optJSONObject("endpoints");
        if (endpointJson == null && appConfig != null) {
            endpointJson = appConfig.optJSONObject("endpoints");
        }
        Map<String, String> endpoints = new LinkedHashMap<>();
        if (endpointJson != null) {
            java.util.Iterator<String> keys = endpointJson.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String path = endpointJson.optString(key, "").trim();
                if (!path.isEmpty()) endpoints.put(key, path);
            }
        }

        Request request = Request.from(adapter.optJSONObject("request"), errors);
        Response response = Response.from(adapter.optJSONObject("response"), errors);
        Auth auth = Auth.from(adapter.optJSONObject("auth"), errors);
        Fields fields = Fields.from(adapter.optJSONObject("fields"), errors);
        Operations operations = Operations.from(adapter.optJSONObject("operations"));
        MaterialRefresh materialRefresh = MaterialRefresh.from(adapter);
        List<String> printingErrors = new ArrayList<>();
        Printing printing = Printing.from(adapter.optJSONObject("printing"), printingErrors);
        operations.printingErrors.addAll(printingErrors);
        return new BackendAdapter(version, true, baseUrl, endpoints, request, response, auth, fields,
            operations, materialRefresh, printing,
            auth.sessionInvalidPolicy, errors);
    }

    boolean isSupported() {
        return declared && version == SUPPORTED_VERSION && parseErrors.isEmpty();
    }

    String endpoint(String key) {
        String value = endpoints.get(key);
        return value == null ? "" : value;
    }

    String requireEndpoint(String key) throws ConfigurationException {
        String value = endpoint(key);
        if (value.isEmpty()) throw new ConfigurationException("backendAdapter.endpoints." + key);
        return value;
    }

    /**
     * Resolve a panel-supplied endpoint without assuming it is a relative path.
     *
     * <p>A leading slash remains relative to {@code baseUrl}, including any path component in the
     * base. This matches the Panel clients: a base such as {@code https://example.invalid/api} and
     * an endpoint {@code /entries} resolve to {@code https://example.invalid/api/entries}.
     */
    static URL resolveEndpointUrl(String baseUrl, String pathOrUrl) throws IOException {
        String value = pathOrUrl == null ? "" : pathOrUrl.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) return new URL(value);

        String base = stripTrailingSlash(baseUrl == null ? "" : baseUrl.trim());
        if (base.isEmpty() || value.isEmpty()) {
            throw new IOException("Backend base URL and endpoint are required");
        }
        return new URL(base + "/" + value.replaceFirst("^/+", ""));
    }

    static URL resolveEndpointUrl(String baseUrl, String pathOrUrl, String query) throws IOException {
        URL resolved = resolveEndpointUrl(baseUrl, pathOrUrl);
        String suffix = query == null ? "" : query.trim().replaceFirst("^[?&]+", "");
        if (suffix.isEmpty()) return resolved;

        String external = resolved.toExternalForm();
        int fragmentAt = external.indexOf('#');
        String fragment = fragmentAt < 0 ? "" : external.substring(fragmentAt);
        String beforeFragment = fragmentAt < 0 ? external : external.substring(0, fragmentAt);
        String separator = beforeFragment.contains("?")
            ? (beforeFragment.endsWith("?") || beforeFragment.endsWith("&") ? "" : "&")
            : "?";
        return new URL(beforeFragment + separator + suffix + fragment);
    }

    List<String> missingForLogin() {
        return missing(AUTH_ENDPOINTS);
    }

    List<String> missingForOcr() {
        List<String> out = missing(ENDPOINT_USER_INFO);
        out.addAll(operations.ocrErrors);
        return deduplicate(out);
    }

    List<String> missingForSubmit(boolean needsPreviousSteps, boolean needsDuplicateCheck,
                                  boolean needsMaterialItems, boolean needsPrinting) {
        return missingForSubmit(needsPreviousSteps, needsDuplicateCheck, needsMaterialItems,
            needsPrinting, false);
    }

    List<String> missingForSubmit(boolean needsPreviousSteps, boolean needsDuplicateCheck,
                                  boolean needsMaterialItems, boolean needsPrinting,
                                  boolean needsMaterialRefresh) {
        LinkedHashSet<String> required = new LinkedHashSet<>();
        Collections.addAll(required, SUBMIT_ENDPOINTS);
        if (needsPreviousSteps) required.add(ENDPOINT_DETECTION_DATA);
        if (needsDuplicateCheck) required.add(ENDPOINT_SN_REPETITION);
        if (needsMaterialRefresh) required.add(ENDPOINT_TEMPLATE_DETAIL);
        if (needsPrinting) Collections.addAll(required, PRINT_ENDPOINTS);
        List<String> out = missing(required.toArray(new String[0]));
        if (needsPrinting && !printing.enabled) out.add("backendAdapter.printing.enabled");
        out.addAll(operations.uploadErrors);
        out.addAll(operations.submitErrors);
        if (needsMaterialItems) out.addAll(operations.materialItemErrors);
        if (needsMaterialRefresh) out.addAll(materialRefresh.errors);
        if (needsPreviousSteps) out.addAll(operations.previousStepErrors);
        if (needsDuplicateCheck) out.addAll(operations.duplicateCheckErrors);
        if (needsPrinting) out.addAll(operations.printingErrors);
        return deduplicate(out);
    }

    /** Read-only previous-step lookup gate; excludes upload and submit capabilities. */
    List<String> missingForPreviousStepLookup() {
        List<String> out = missing(ENDPOINT_DETECTION_DATA);
        out.addAll(operations.previousStepErrors);
        return deduplicate(out);
    }

    /** Release/recovery-only gate; ordinary submission remains compatible while capability migrates. */
    List<String> missingForControlledRecovery(ControlledRecoveryRules.Operation operation) {
        List<String> out = new ArrayList<>(operations.recoveryErrors);
        if (!operations.recovery.declared) {
            out.add("backendAdapter.operations.recovery");
        } else if (operation == null || operations.recovery.capability == null
                || !operations.recovery.capability.supports(operation)) {
            out.add("backendAdapter.operations.recovery.enabledOperations."
                + (operation == null ? "unknown" : operation.name()));
        }
        return deduplicate(out);
    }

    /** Dynamic capability gate kept separate so static recipes retain their original contract. */
    List<String> missingForDynamicPreviousSteps(ProfileWorkflow workflow) {
        if (workflow == null || workflow.dynamicPreviousStepRecipes.isEmpty()) {
            return workflow == null ? Collections.singletonList("profile.workflow")
                : new ArrayList<>(workflow.dynamicPreviousStepErrors);
        }
        List<String> out = new ArrayList<>(workflow.dynamicPreviousStepErrors);
        for (ProfileWorkflow.DynamicPreviousStepRecipe recipe
                : workflow.dynamicPreviousStepRecipes) {
            out.addAll(missingForDynamicPreviousStep(recipe));
        }
        return deduplicate(out);
    }

    List<String> missingForDynamicPreviousStep(
            ProfileWorkflow.DynamicPreviousStepRecipe recipe) {
        List<String> out = missing(ENDPOINT_TEMPLATE_DETAIL);
        if (operations.templateDetail.idParam.isEmpty()) {
            out.add("backendAdapter.operations.templateDetail.idParam");
        }
        out.addAll(fields.dynamicRecipeErrors);
        out.addAll(operations.previousSteps.dynamicRecipeErrors);
        if (recipe == null) {
            out.add("profile.workflow.previousSteps.templates");
            return deduplicate(out);
        }
        JSONObject resolver = operations.previousSteps.recipeResolvers.get(recipe.resolverId);
        if (resolver == null) {
            out.add("backendAdapter.operations.previousSteps.recipeResolvers."
                + recipe.resolverId);
        } else {
            Set<String> usedAliases = dynamicPhotoAliases(resolver);
            for (String alias : usedAliases) {
                if (!recipe.sources.containsKey(alias)) {
                    out.add("profile.workflow.previousSteps.sources." + alias);
                }
            }
            for (String alias : recipe.sources.keySet()) {
                if (!usedAliases.contains(alias)) {
                    out.add("profile.workflow.previousSteps.sources." + alias);
                }
            }
        }
        return deduplicate(out);
    }

    DynamicPreviousStepConfig dynamicPreviousStepConfig(
            ProfileWorkflow.DynamicPreviousStepRecipe recipe) throws ConfigurationException {
        List<String> missing = missingForDynamicPreviousStep(recipe);
        if (!missing.isEmpty()) throw new ConfigurationException(missing.get(0));
        return new DynamicPreviousStepConfig(
            operations.templateDetail.idParam,
            recipe,
            operations.previousSteps.recipeResolvers.get(recipe.resolverId),
            operations.previousSteps.optionValueBuilders(),
            fields.dynamicRecipeMapping());
    }

    /** Builds the complete no-network plan that MainActivity must validate before any upload. */
    AlternateEntryDynamicOverrideConfig alternateEntryDynamicOverrideConfig(
            JSONObject entryConfig, JSONObject targetProfile,
            Map<String, Boolean> toggleStates) throws Exception {
        JSONArray providers = entryConfig == null ? null
            : entryConfig.optJSONArray("dynamicOverrideProviders");
        boolean declaredProviders = providers != null && providers.length() > 0;
        if (declaredProviders) {
            List<String> missing = missing(ENDPOINT_TEMPLATE_DETAIL);
            if (!missing.isEmpty()) throw new ConfigurationException(missing.get(0));
            if (operations.templateDetail.idParam.isEmpty()) {
                throw new ConfigurationException(
                    "backendAdapter.operations.templateDetail.idParam");
            }
            if (!fields.dynamicRecipeErrors.isEmpty()) {
                throw new ConfigurationException(fields.dynamicRecipeErrors.get(0));
            }
            if (!operations.templateDetail.alternateEntryOverrideErrors.isEmpty()) {
                throw new ConfigurationException(
                    operations.templateDetail.alternateEntryOverrideErrors.get(0));
            }
        }
        AlternateEntryDynamicOverrideRules.Plan plan =
            AlternateEntryDynamicOverrideRules.compile(entryConfig, targetProfile,
                toggleStates, operations.templateDetail.alternateEntryResolvers(),
                fields.dynamicRecipeMapping());
        return new AlternateEntryDynamicOverrideConfig(
            operations.templateDetail.idParam, plan);
    }

    private List<String> missing(String... requiredEndpoints) {
        List<String> out = new ArrayList<>();
        if (!declared) out.add("backendAdapter");
        else if (version != SUPPORTED_VERSION) out.add("backendAdapter.version");
        if (baseUrl.isEmpty()) out.add("backendAdapter.baseUrl");
        out.addAll(parseErrors);
        for (String key : requiredEndpoints) {
            if (endpoint(key).isEmpty()) out.add("backendAdapter.endpoints." + key);
        }
        return deduplicate(out);
    }

    private static List<String> deduplicate(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    static final class ConfigurationException extends Exception {
        ConfigurationException(String missingKey) {
            super("Missing panel configuration: " + missingKey);
        }
    }

    /** Non-network bridge MainActivity can use after it gains dynamic recipe execution. */
    static final class DynamicPreviousStepConfig {
        final String templateDetailIdParam;
        final Object templateId;
        final Object expectedStep;
        final Map<String, String> sourceAliases;
        final Set<String> sourceKeys;
        final long delayAfterMs;
        final int sourceIndex;
        private final JSONObject recipe;
        private final JSONObject resolver;
        private final JSONObject optionValueBuilders;
        private final JSONObject fieldMapping;

        private DynamicPreviousStepConfig(String templateDetailIdParam,
                                          ProfileWorkflow.DynamicPreviousStepRecipe recipe,
                                          JSONObject resolver, JSONObject optionValueBuilders,
                                          JSONObject fieldMapping) {
            this.templateDetailIdParam = templateDetailIdParam;
            this.templateId = recipe.templateId;
            this.expectedStep = recipe.expectedStep;
            this.sourceAliases = Collections.unmodifiableMap(
                new LinkedHashMap<>(recipe.sources));
            this.sourceKeys = Collections.unmodifiableSet(
                new LinkedHashSet<>(recipe.sources.values()));
            this.delayAfterMs = recipe.delayAfterMs;
            this.sourceIndex = recipe.sourceIndex;
            JSONObject rawRecipe;
            try {
                rawRecipe = recipe.compilerRecipe();
            } catch (Exception ignored) {
                rawRecipe = new JSONObject();
            }
            this.recipe = copyObject(rawRecipe);
            this.resolver = copyObject(resolver);
            this.optionValueBuilders = copyObject(optionValueBuilders);
            this.fieldMapping = copyObject(fieldMapping);
        }

        DynamicPreviousStepRules.CompiledPlan compile(JSONObject unwrappedTemplateData,
                                                       String unitSerial) throws Exception {
            return DynamicPreviousStepRules.compile(
                unwrappedTemplateData,
                copyObject(resolver),
                copyObject(optionValueBuilders),
                copyObject(fieldMapping),
                copyObject(recipe),
                unitSerial);
        }
    }

    /** Network-neutral bridge for fetching and resolving active alternate-entry providers. */
    static final class AlternateEntryDynamicOverrideConfig {
        final String templateDetailIdParam;
        final AlternateEntryDynamicOverrideRules.Plan plan;

        private AlternateEntryDynamicOverrideConfig(String templateDetailIdParam,
                                                    AlternateEntryDynamicOverrideRules.Plan plan) {
            this.templateDetailIdParam = templateDetailIdParam == null
                ? "" : templateDetailIdParam;
            this.plan = plan;
        }

        List<AlternateEntryDynamicOverrideRules.Request> requests() {
            return plan.requests();
        }

        JSONObject resolve(JSONObject liveTemplatesByProviderId) throws Exception {
            return plan.resolve(liveTemplatesByProviderId);
        }
    }

    static final class Request {
        final String bodyEncoding;
        final String authScheme;
        final String fingerprintHeader;
        final String webUserAgent;
        final String webAcceptLanguage;

        private Request(String bodyEncoding, String authScheme, String fingerprintHeader,
                        String webUserAgent, String webAcceptLanguage) {
            this.bodyEncoding = bodyEncoding;
            this.authScheme = authScheme;
            this.fingerprintHeader = fingerprintHeader;
            this.webUserAgent = webUserAgent;
            this.webAcceptLanguage = webAcceptLanguage;
        }

        static Request invalid() { return new Request("", "", "", "", ""); }

        static Request from(JSONObject json, List<String> errors) {
            if (json == null) {
                errors.add("backendAdapter.request");
                return invalid();
            }
            String encoding = text(json, "bodyEncoding");
            String scheme = text(json, "authScheme");
            String fingerprint = text(json, "fingerprintHeader");
            String userAgent = explicitString(json, "webUserAgent", errors);
            String acceptLanguage = explicitString(json, "webAcceptLanguage", errors);
            if (!"form".equals(encoding) && !"json".equals(encoding)) {
                errors.add("backendAdapter.request.bodyEncoding");
            }
            if (scheme.isEmpty()) errors.add("backendAdapter.request.authScheme");
            return new Request(encoding, scheme, fingerprint, userAgent, acceptLanguage);
        }

        private static String explicitString(JSONObject json, String key, List<String> errors) {
            Object value = json.opt(key);
            if (!json.has(key) || !(value instanceof String)) {
                errors.add("backendAdapter.request." + key);
                return "";
            }
            return ((String) value).trim();
        }
    }

    static final class Auth {
        final Map<String, String> loginFields;
        final List<String> tokenFields;
        final List<String> userNameFields;
        final Set<String> sessionProofCodes;
        final List<String> successFieldsWhenCodeMissing;
        final boolean dataRootWhenCodeMissing;
        final BackendSessionErrors.Policy sessionInvalidPolicy;

        private Auth(Map<String, String> loginFields, List<String> tokenFields,
                     List<String> userNameFields, Set<String> sessionProofCodes,
                     List<String> successFieldsWhenCodeMissing,
                     boolean dataRootWhenCodeMissing,
                     BackendSessionErrors.Policy sessionInvalidPolicy) {
            this.loginFields = Collections.unmodifiableMap(new LinkedHashMap<>(loginFields));
            this.tokenFields = Collections.unmodifiableList(new ArrayList<>(tokenFields));
            this.userNameFields = Collections.unmodifiableList(new ArrayList<>(userNameFields));
            this.sessionProofCodes = immutableSet(sessionProofCodes);
            this.successFieldsWhenCodeMissing = Collections.unmodifiableList(
                new ArrayList<>(successFieldsWhenCodeMissing));
            this.dataRootWhenCodeMissing = dataRootWhenCodeMissing;
            this.sessionInvalidPolicy = sessionInvalidPolicy == null
                ? BackendSessionErrors.Policy.empty() : sessionInvalidPolicy;
        }

        static Auth invalid() {
            return new Auth(Collections.emptyMap(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptySet(),
                Collections.emptyList(), false,
                BackendSessionErrors.Policy.empty());
        }

        static Auth from(JSONObject json, List<String> errors) {
            if (json == null) {
                errors.add("backendAdapter.auth");
                return invalid();
            }
            Map<String, String> loginFields = new LinkedHashMap<>();
            JSONObject mapped = json.optJSONObject("loginFields");
            for (String key : new String[]{"account", "password", "captcha", "client"}) {
                String value = mapped == null ? "" : mapped.optString(key, "").trim();
                if (value.isEmpty()) errors.add("backendAdapter.auth.loginFields." + key);
                else loginFields.put(key, value);
            }
            List<String> tokenFields = stringList(json.optJSONArray("tokenFields"));
            List<String> userNameFields = stringList(json.optJSONArray("userNameFields"));
            if (tokenFields.isEmpty()) errors.add("backendAdapter.auth.tokenFields");
            if (userNameFields.isEmpty()) errors.add("backendAdapter.auth.userNameFields");
            List<String> successFieldsWhenCodeMissing = new ArrayList<>();
            Object rawSuccessFields = json.opt("successFieldsWhenCodeMissing");
            if (rawSuccessFields != null && rawSuccessFields != JSONObject.NULL) {
                if (!(rawSuccessFields instanceof JSONArray)) {
                    errors.add("backendAdapter.auth.successFieldsWhenCodeMissing");
                } else {
                    JSONArray values = (JSONArray) rawSuccessFields;
                    if (values.length() == 0) {
                        errors.add("backendAdapter.auth.successFieldsWhenCodeMissing");
                    }
                    Set<String> seen = new LinkedHashSet<>();
                    for (int i = 0; i < values.length(); i++) {
                        Object raw = values.opt(i);
                        String path = raw instanceof String ? ((String) raw).trim() : "";
                        if (path.isEmpty() || !seen.add(path)) {
                            errors.add("backendAdapter.auth.successFieldsWhenCodeMissing[" + i + "]");
                        } else {
                            successFieldsWhenCodeMissing.add(path);
                        }
                    }
                }
            }
            boolean dataRootWhenCodeMissing = false;
            Object rawDataRoot = json.opt("dataRootWhenCodeMissing");
            if (rawDataRoot != null && rawDataRoot != JSONObject.NULL) {
                if (rawDataRoot instanceof Boolean) {
                    dataRootWhenCodeMissing = (Boolean) rawDataRoot;
                } else {
                    errors.add("backendAdapter.auth.dataRootWhenCodeMissing");
                }
            }
            JSONArray invalidCodes = json.optJSONArray("sessionInvalidCodes");
            JSONArray invalidStatuses = json.optJSONArray("sessionInvalidHttpStatuses");
            JSONArray invalidMessages = json.optJSONArray("sessionInvalidMessagePatterns");
            if (invalidCodes == null) errors.add("backendAdapter.auth.sessionInvalidCodes");
            List<Object> statuses = httpStatuses(invalidStatuses, errors);
            if (invalidMessages == null) {
                errors.add("backendAdapter.auth.sessionInvalidMessagePatterns");
            }
            return new Auth(loginFields, tokenFields, userNameFields,
                stringSet(json.optJSONArray("sessionProofCodes")),
                successFieldsWhenCodeMissing, dataRootWhenCodeMissing,
                new BackendSessionErrors.Policy(statuses, objectList(invalidCodes),
                    stringList(invalidMessages)));
        }

        private static List<Object> httpStatuses(JSONArray array, List<String> errors) {
            List<Object> out = new ArrayList<>();
            if (array == null) {
                errors.add("backendAdapter.auth.sessionInvalidHttpStatuses");
                return out;
            }
            Set<Integer> seen = new LinkedHashSet<>();
            for (int i = 0; i < array.length(); i++) {
                Object raw = array.opt(i);
                if (!(raw instanceof Number)) {
                    errors.add("backendAdapter.auth.sessionInvalidHttpStatuses[" + i + "]");
                    continue;
                }
                double numeric = ((Number) raw).doubleValue();
                int status = ((Number) raw).intValue();
                if (numeric != status || status < 100 || status > 599 || !seen.add(status)) {
                    errors.add("backendAdapter.auth.sessionInvalidHttpStatuses[" + i + "]");
                    continue;
                }
                out.add(status);
            }
            return out;
        }

        String loginField(String canonical) { return loginFields.get(canonical); }

        JSONObject loginBody(String account, String password, String captcha, String client) {
            JSONObject out = new JSONObject();
            try {
                out.put(loginField("account"), account);
                out.put(loginField("password"), password);
                out.put(loginField("captcha"), captcha);
                out.put(loginField("client"), client);
            } catch (Exception ignored) {}
            return out;
        }

        String firstString(Object root, List<String> paths) {
            for (String path : paths) {
                Object value = valueAt(root, path);
                if (value != null && value != JSONObject.NULL && !String.valueOf(value).isEmpty()) {
                    return String.valueOf(value);
                }
            }
            return "";
        }

        /** Auth-only compatibility for explicitly declared legacy 2xx response envelopes. */
        boolean isSuccess(JSONObject body, Response response) {
            if (response == null) return false;
            if (response.isSuccess(body)) return true;
            if (body == null || response.codeField.isEmpty()) return false;
            Object code = response.code(body);
            if (code != null && code != JSONObject.NULL) return false;
            if (!response.configuredMessage(body).isEmpty()) return false;
            for (String path : successFieldsWhenCodeMissing) {
                if (hasPath(body, path)) return true;
            }
            return false;
        }

        /** Resolve auth data without changing the strict response contract used by submissions. */
        Object data(JSONObject body, Response response) {
            if (response == null) return null;
            Object configured = response.data(body);
            if (configured != null) return configured;
            Object code = response.code(body);
            if (dataRootWhenCodeMissing && body != null
                    && !response.codeField.isEmpty()
                    && (code == null || code == JSONObject.NULL)
                    && response.configuredMessage(body).isEmpty()) {
                return body;
            }
            return null;
        }
    }

    static final class Fields {
        final String captchaClient;
        final String captchaImage;
        final JSONObject dynamicRecipeMapping;
        final List<String> dynamicRecipeErrors;

        private Fields(String captchaClient, String captchaImage,
                       JSONObject dynamicRecipeMapping, List<String> dynamicRecipeErrors) {
            this.captchaClient = captchaClient;
            this.captchaImage = captchaImage;
            this.dynamicRecipeMapping = copyObject(dynamicRecipeMapping);
            this.dynamicRecipeErrors = immutableList(dynamicRecipeErrors);
        }

        static Fields invalid() {
            return new Fields("", "", new JSONObject(), Collections.singletonList(
                "backendAdapter.fields.template"));
        }

        static Fields from(JSONObject json, List<String> errors) {
            if (json == null) {
                errors.add("backendAdapter.fields");
                return invalid();
            }
            String client = text(json, "captchaClient");
            String image = text(json, "captchaImage");
            if (client.isEmpty()) errors.add("backendAdapter.fields.captchaClient");
            if (image.isEmpty()) errors.add("backendAdapter.fields.captchaImage");
            List<String> dynamicErrors = new ArrayList<>();
            JSONObject mapping = new JSONObject();
            JSONObject template = json.optJSONObject("template");
            JSONObject formField = json.optJSONObject("formField");
            JSONObject option = json.optJSONObject("option");
            requireMappedKeys(template,
                new String[]{"id", "name", "sku", "step", "warehouseId", "fieldList"},
                "fields.template", dynamicErrors);
            requireMappedKeys(formField,
                new String[]{"id", "type", "parentType", "typeName", "title",
                    "englishTitle", "required", "visible", "maxCount", "options"},
                "fields.formField", dynamicErrors);
            requireMappedKeys(option,
                new String[]{"value", "label", "englishLabel", "quantity"},
                "fields.option", dynamicErrors);
            try {
                if (template != null) mapping.put("template", copyObject(template));
                if (formField != null) mapping.put("formField", copyObject(formField));
                if (option != null) mapping.put("option", copyObject(option));
            } catch (Exception ignored) {
                dynamicErrors.add("backendAdapter.fields.dynamicRecipeMapping");
            }
            return new Fields(client, image, mapping, dynamicErrors);
        }

        JSONObject dynamicRecipeMapping() { return copyObject(dynamicRecipeMapping); }
    }

    static final class Response {
        final String codeField;
        final String dataField;
        final List<String> messageFields;
        final Set<String> successValues;
        final List<String> successFieldsWhenCodeMissing;
        final boolean dataRootWhenCodeMissing;
        final boolean rejectMessageWhenCodeMissing;

        private Response(String codeField, String dataField, List<String> messageFields,
                         Set<String> successValues,
                         List<String> successFieldsWhenCodeMissing,
                         boolean dataRootWhenCodeMissing,
                         boolean rejectMessageWhenCodeMissing) {
            this.codeField = codeField;
            this.dataField = dataField;
            this.messageFields = Collections.unmodifiableList(new ArrayList<>(messageFields));
            this.successValues = Collections.unmodifiableSet(new LinkedHashSet<>(successValues));
            this.successFieldsWhenCodeMissing = Collections.unmodifiableList(
                new ArrayList<>(successFieldsWhenCodeMissing));
            this.dataRootWhenCodeMissing = dataRootWhenCodeMissing;
            this.rejectMessageWhenCodeMissing = rejectMessageWhenCodeMissing;
        }

        static Response invalid() {
            return new Response("", "", Collections.emptyList(), Collections.emptySet(),
                Collections.emptyList(), false, true);
        }

        static Response from(JSONObject json, List<String> errors) {
            if (json == null) {
                errors.add("backendAdapter.response");
                return invalid();
            }
            String codeField = json.optString("codeField", "").trim();
            String dataField = json.optString("dataField", "").trim();
            List<String> messages = stringList(json.optJSONArray("messageFields"));
            Set<String> success = stringSet(json.optJSONArray("successValues"));
            List<String> codeMissingFields = new ArrayList<>();
            Object rawCodeMissingFields = json.opt("successFieldsWhenCodeMissing");
            boolean codeMissingCompatibilityDeclared = rawCodeMissingFields != null
                && rawCodeMissingFields != JSONObject.NULL;
            if (codeMissingCompatibilityDeclared) {
                if (!(rawCodeMissingFields instanceof JSONArray)) {
                    errors.add("backendAdapter.response.successFieldsWhenCodeMissing");
                } else {
                    JSONArray values = (JSONArray) rawCodeMissingFields;
                    if (values.length() == 0) {
                        errors.add("backendAdapter.response.successFieldsWhenCodeMissing");
                    }
                    Set<String> seen = new LinkedHashSet<>();
                    for (int i = 0; i < values.length(); i++) {
                        Object raw = values.opt(i);
                        String path = raw instanceof String ? ((String) raw).trim() : "";
                        if (path.isEmpty() || !seen.add(path)) {
                            errors.add("backendAdapter.response.successFieldsWhenCodeMissing["
                                + i + "]");
                        } else {
                            codeMissingFields.add(path);
                        }
                    }
                }
            }
            Object rawDataRoot = json.opt("dataRootWhenCodeMissing");
            boolean dataRootDeclared = rawDataRoot != null && rawDataRoot != JSONObject.NULL;
            Boolean dataRootWhenCodeMissing = rawDataRoot instanceof Boolean
                ? (Boolean) rawDataRoot : null;
            if (dataRootDeclared && dataRootWhenCodeMissing == null) {
                errors.add("backendAdapter.response.dataRootWhenCodeMissing");
            }
            Object rawRejectMessage = json.opt("rejectMessageWhenCodeMissing");
            boolean rejectMessageDeclared = rawRejectMessage != null
                && rawRejectMessage != JSONObject.NULL;
            Boolean rejectMessageWhenCodeMissing = rawRejectMessage instanceof Boolean
                ? (Boolean) rawRejectMessage : null;
            if (rejectMessageDeclared && rejectMessageWhenCodeMissing == null) {
                errors.add("backendAdapter.response.rejectMessageWhenCodeMissing");
            }
            if (codeMissingCompatibilityDeclared) {
                if (!dataRootDeclared) {
                    errors.add("backendAdapter.response.dataRootWhenCodeMissing");
                }
                if (!rejectMessageDeclared) {
                    errors.add("backendAdapter.response.rejectMessageWhenCodeMissing");
                }
            } else {
                if (dataRootDeclared) {
                    errors.add("backendAdapter.response.dataRootWhenCodeMissing");
                }
                if (rejectMessageDeclared) {
                    errors.add("backendAdapter.response.rejectMessageWhenCodeMissing");
                }
            }
            if (messages.isEmpty()) errors.add("backendAdapter.response.messageFields");
            if (!codeField.isEmpty() && success.isEmpty()) {
                errors.add("backendAdapter.response.successValues");
            }
            return new Response(codeField, dataField, messages, success,
                codeMissingFields,
                Boolean.TRUE.equals(dataRootWhenCodeMissing),
                rejectMessageWhenCodeMissing == null || rejectMessageWhenCodeMissing);
        }

        boolean isSuccess(JSONObject body) {
            if (codeField.isEmpty()) return true;
            Object code = code(body);
            if (code != null && code != JSONObject.NULL) {
                return successValues.contains(String.valueOf(code).trim());
            }
            return codeMissingCompatibilitySuccess(body);
        }

        private boolean codeMissingCompatibilitySuccess(JSONObject body) {
            if (body == null || codeField.isEmpty()
                    || successFieldsWhenCodeMissing.isEmpty()) return false;
            Object code = code(body);
            if (code != null && code != JSONObject.NULL) return false;
            if (rejectMessageWhenCodeMissing && hasConfiguredMessage(body)) return false;
            for (String path : successFieldsWhenCodeMissing) {
                if (hasPath(body, path)) return true;
            }
            return false;
        }

        Object code(JSONObject body) {
            return valueAt(body, codeField);
        }

        private static String scalarMessage(Object value) {
            if (value == null || value == JSONObject.NULL
                    || value instanceof JSONObject || value instanceof JSONArray) return "";
            return String.valueOf(value).trim();
        }

        /**
         * Keep code-missing compatibility fail-closed even when a configured message path points
         * at an unexpected object. Objects are evidence that the response is not the declared
         * success shape, but their serialized children must never become classifier input.
         */
        boolean hasConfiguredMessage(JSONObject body) {
            for (String path : messageFields) {
                Object value = valueAt(body, path);
                if (value == null || value == JSONObject.NULL) continue;
                if (!String.valueOf(value).trim().isEmpty()) return true;
            }
            return false;
        }

        /** Only scalar values at Panel-declared message paths participate in classification. */
        String configuredMessage(JSONObject body) {
            StringBuilder out = new StringBuilder();
            for (String path : messageFields) {
                String text = scalarMessage(valueAt(body, path));
                if (text.isEmpty()) continue;
                if (out.length() > 0) out.append('\n');
                out.append(text);
            }
            return out.toString();
        }

        Object data(JSONObject body) {
            Object data = valueAt(body, dataField);
            if (data != null && data != JSONObject.NULL) return data;
            return dataRootWhenCodeMissing && codeMissingCompatibilitySuccess(body)
                ? body : null;
        }

        String errorMessage(JSONObject body) {
            for (String path : messageFields) {
                String text = scalarMessage(valueAt(body, path));
                if (!text.isEmpty()) return text;
            }
            Object code = valueAt(body, codeField);
            return code == null || code == JSONObject.NULL
                ? "Unexpected API response"
                : "API error code " + code;
        }
    }

    static final class Operations {
        final Upload upload;
        final Ocr ocr;
        final Submit submit;
        final PreviousSteps previousSteps;
        final TemplateDetail templateDetail;
        final DuplicateCheck duplicateCheck;
        final Recovery recovery;
        final List<String> uploadErrors;
        final List<String> ocrErrors;
        final List<String> submitErrors;
        final List<String> materialItemErrors;
        final List<String> previousStepErrors;
        final List<String> duplicateCheckErrors;
        final List<String> recoveryErrors;
        final List<String> printingErrors;

        private Operations(Upload upload, Ocr ocr, Submit submit,
                           PreviousSteps previousSteps, TemplateDetail templateDetail,
                           DuplicateCheck duplicateCheck, Recovery recovery,
                           List<String> uploadErrors,
                           List<String> ocrErrors, List<String> submitErrors,
                           List<String> materialItemErrors, List<String> previousStepErrors,
                           List<String> duplicateCheckErrors, List<String> recoveryErrors) {
            this.upload = upload;
            this.ocr = ocr;
            this.submit = submit;
            this.previousSteps = previousSteps;
            this.templateDetail = templateDetail;
            this.duplicateCheck = duplicateCheck;
            this.recovery = recovery;
            this.uploadErrors = uploadErrors;
            this.ocrErrors = ocrErrors;
            this.submitErrors = submitErrors;
            this.materialItemErrors = materialItemErrors;
            this.previousStepErrors = previousStepErrors;
            this.duplicateCheckErrors = duplicateCheckErrors;
            this.recoveryErrors = recoveryErrors;
            this.printingErrors = new ArrayList<>();
        }

        static Operations invalid() {
            return new Operations(Upload.invalid(), Ocr.invalid(), Submit.invalid(),
                PreviousSteps.invalid(), TemplateDetail.invalid(), DuplicateCheck.invalid(),
                Recovery.absent(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        static Operations from(JSONObject json) {
            List<String> uploadErrors = new ArrayList<>();
            List<String> ocrErrors = new ArrayList<>();
            List<String> submitErrors = new ArrayList<>();
            List<String> materialErrors = new ArrayList<>();
            List<String> previousErrors = new ArrayList<>();
            List<String> duplicateErrors = new ArrayList<>();
            List<String> recoveryErrors = new ArrayList<>();
            if (json == null) json = new JSONObject();
            return new Operations(
                Upload.from(json.optJSONObject("upload"), uploadErrors),
                Ocr.from(json.optJSONObject("ocr"), ocrErrors),
                Submit.from(json.optJSONObject("submit"), submitErrors, materialErrors),
                PreviousSteps.from(json.optJSONObject("previousSteps"), previousErrors),
                TemplateDetail.from(json.optJSONObject("templateDetail")),
                DuplicateCheck.from(json.optJSONObject("duplicateCheck"), duplicateErrors),
                Recovery.from(json.has("recovery") ? json.opt("recovery") : null,
                    json.has("recovery"), recoveryErrors),
                uploadErrors, ocrErrors, submitErrors, materialErrors, previousErrors,
                duplicateErrors, recoveryErrors);
        }
    }

    /** Parsed public verification half of the Panel-owned controlled-recovery capability. */
    static final class Recovery {
        private static final Set<String> KEYS = Collections.unmodifiableSet(
            new LinkedHashSet<>(java.util.Arrays.asList(
                "version", "issuanceMode", "evidenceAlgorithm", "keyId",
                "publicKeySpkiHex", "maxEvidenceAgeSeconds",
                "reconciliationContractSha256", "enabledOperations")));

        final boolean declared;
        final ControlledRecoveryRules.Capability capability;

        private Recovery(boolean declared,
                         ControlledRecoveryRules.Capability capability) {
            this.declared = declared;
            this.capability = capability;
        }

        static Recovery absent() {
            return new Recovery(false, null);
        }

        static Recovery from(Object raw, boolean declared, List<String> errors) {
            if (!declared) return absent();
            if (!(raw instanceof JSONObject)) {
                errors.add("backendAdapter.operations.recovery");
                return new Recovery(true, null);
            }
            JSONObject json = (JSONObject) raw;
            if (!hasExactKeys(json, KEYS)) {
                errors.add("backendAdapter.operations.recovery.fields");
            }
            if (json.optInt("version", 0) != ControlledRecoveryRules.CAPABILITY_VERSION) {
                errors.add("backendAdapter.operations.recovery.version");
            }
            if (!"panel_signed_exact_reconciliation".equals(
                    text(json, "issuanceMode"))) {
                errors.add("backendAdapter.operations.recovery.issuanceMode");
            }
            if (!ControlledRecoveryRules.SIGNATURE_ALGORITHM.equals(
                    text(json, "evidenceAlgorithm"))) {
                errors.add("backendAdapter.operations.recovery.evidenceAlgorithm");
            }
            Set<ControlledRecoveryRules.Operation> enabled =
                EnumSet.noneOf(ControlledRecoveryRules.Operation.class);
            JSONArray values = json.optJSONArray("enabledOperations");
            if (values == null || values.length() == 0) {
                errors.add("backendAdapter.operations.recovery.enabledOperations");
            } else {
                for (int index = 0; index < values.length(); index++) {
                    Object value = values.opt(index);
                    if (!(value instanceof String)) {
                        errors.add("backendAdapter.operations.recovery.enabledOperations");
                        continue;
                    }
                    try {
                        if (!enabled.add(ControlledRecoveryRules.Operation.valueOf(
                                (String) value))) {
                            errors.add(
                                "backendAdapter.operations.recovery.enabledOperations");
                        }
                    } catch (IllegalArgumentException unknown) {
                        errors.add("backendAdapter.operations.recovery.enabledOperations");
                    }
                }
            }
            ControlledRecoveryRules.Capability capability = null;
            try {
                capability = ControlledRecoveryRules.Capability.of(
                    text(json, "keyId"), text(json, "publicKeySpkiHex"),
                    exactPositiveInteger(json.opt("maxEvidenceAgeSeconds")),
                    text(json, "reconciliationContractSha256"), enabled);
            } catch (IllegalArgumentException invalid) {
                errors.add("backendAdapter.operations.recovery.capability");
            }
            return new Recovery(true, capability);
        }
    }

    static final class TemplateDetail {
        final String idParam;
        final Map<String, JSONObject> alternateEntryResolvers;
        final List<String> alternateEntryOverrideErrors;

        private TemplateDetail(String idParam, Map<String, JSONObject> alternateEntryResolvers,
                               List<String> alternateEntryOverrideErrors) {
            this.idParam = idParam;
            this.alternateEntryResolvers = immutableJsonMap(alternateEntryResolvers);
            this.alternateEntryOverrideErrors = immutableList(alternateEntryOverrideErrors);
        }

        static TemplateDetail invalid() {
            return new TemplateDetail("", Collections.emptyMap(), Collections.emptyList());
        }

        static TemplateDetail from(JSONObject json) {
            if (json == null) return invalid();
            List<String> errors = new ArrayList<>();
            Map<String, JSONObject> resolvers = json.has("alternateEntryResolvers")
                ? parseDynamicObjectMap(json.opt("alternateEntryResolvers"),
                    "operations.templateDetail.alternateEntryResolvers", errors)
                : Collections.emptyMap();
            if (json.has("alternateEntryResolvers")) {
                for (Map.Entry<String, JSONObject> entry : resolvers.entrySet()) {
                    try {
                        DynamicPreviousStepRules.validateExactLiveOptionResolver(entry.getValue());
                    } catch (Exception error) {
                        errors.add("backendAdapter.operations.templateDetail.alternateEntryResolvers."
                            + entry.getKey());
                    }
                }
            }
            return new TemplateDetail(text(json, "idParam"), resolvers, errors);
        }

        JSONObject alternateEntryResolvers() {
            JSONObject out = new JSONObject();
            try {
                for (Map.Entry<String, JSONObject> entry : alternateEntryResolvers.entrySet()) {
                    out.put(entry.getKey(), copyObject(entry.getValue()));
                }
            } catch (Exception ignored) {
                return new JSONObject();
            }
            return out;
        }
    }

    static final class Upload {
        final String multipartField;
        final String resultPath;

        private Upload(String multipartField, String resultPath) {
            this.multipartField = multipartField;
            this.resultPath = resultPath;
        }

        static Upload invalid() { return new Upload("", ""); }

        static Upload from(JSONObject json, List<String> errors) {
            if (json == null) {
                errors.add("backendAdapter.operations.upload");
                return invalid();
            }
            String multipart = text(json, "multipartField");
            String result = text(json, "resultPath");
            require(errors, multipart, "operations.upload.multipartField");
            require(errors, result, "operations.upload.resultPath");
            return new Upload(multipart, result);
        }

        Object result(Object apiData) { return valueAt(apiData, resultPath); }
    }

    static final class Ocr {
        final String multipartField;
        final List<String> userInfoUrlFields;
        final List<String> resultPaths;

        private Ocr(String multipartField, List<String> userInfoUrlFields,
                    List<String> resultPaths) {
            this.multipartField = multipartField;
            this.userInfoUrlFields = immutableList(userInfoUrlFields);
            this.resultPaths = immutableList(resultPaths);
        }

        static Ocr invalid() {
            return new Ocr("", Collections.emptyList(), Collections.emptyList());
        }

        static Ocr from(JSONObject json, List<String> errors) {
            if (json == null) {
                errors.add("backendAdapter.operations.ocr");
                return invalid();
            }
            String multipart = text(json, "multipartField");
            List<String> userInfo = stringList(json.optJSONArray("userInfoUrlFields"));
            List<String> results = stringList(json.optJSONArray("resultPaths"));
            require(errors, multipart, "operations.ocr.multipartField");
            require(errors, userInfo, "operations.ocr.userInfoUrlFields");
            require(errors, results, "operations.ocr.resultPaths");
            return new Ocr(multipart, userInfo, results);
        }
    }

    static final class Submit {
        final String templateIdField;
        final String warehouseIdField;
        final String skuField;
        final String dataField;
        final String videoIdField;
        final Object videoIdValue;
        final MaterialItemMapping materialItemMapping;
        /** Legacy v1 values retained for old App/config compatibility only. */
        final List<String> retryableMessagePatterns;
        final List<String> missingMaterialMessagePatterns;
        final OutcomePolicy outcomePolicy;

        private Submit(String templateIdField, String warehouseIdField, String skuField,
                       String dataField, String videoIdField, Object videoIdValue,
                       MaterialItemMapping materialItemMapping,
                       List<String> retryableMessagePatterns,
                       List<String> missingMaterialMessagePatterns,
                       OutcomePolicy outcomePolicy) {
            this.templateIdField = templateIdField;
            this.warehouseIdField = warehouseIdField;
            this.skuField = skuField;
            this.dataField = dataField;
            this.videoIdField = videoIdField;
            this.videoIdValue = videoIdValue;
            this.materialItemMapping = materialItemMapping;
            this.retryableMessagePatterns = immutableList(retryableMessagePatterns);
            this.missingMaterialMessagePatterns = immutableList(missingMaterialMessagePatterns);
            this.outcomePolicy = outcomePolicy == null
                ? OutcomePolicy.absent() : outcomePolicy;
        }

        static Submit invalid() {
            return new Submit("", "", "", "", "", null, MaterialItemMapping.invalid(),
                Collections.emptyList(), Collections.emptyList(), OutcomePolicy.absent());
        }

        static Submit from(JSONObject json, List<String> errors, List<String> materialErrors) {
            if (json == null) {
                errors.add("backendAdapter.operations.submit");
                materialErrors.add("backendAdapter.operations.submit.materialItemMapping");
                return invalid();
            }
            String templateId = text(json, "templateIdField");
            String warehouseId = text(json, "warehouseIdField");
            String sku = text(json, "skuField");
            String data = text(json, "dataField");
            String videoId = text(json, "videoIdField");
            require(errors, templateId, "operations.submit.templateIdField");
            require(errors, warehouseId, "operations.submit.warehouseIdField");
            require(errors, sku, "operations.submit.skuField");
            require(errors, data, "operations.submit.dataField");
            require(errors, videoId, "operations.submit.videoIdField");
            requireDistinct(errors, "operations.submit.fieldNames",
                templateId, warehouseId, sku, data, videoId);
            if (!json.has("videoIdValue")) {
                errors.add("backendAdapter.operations.submit.videoIdValue");
            }
            if (json.optJSONArray("retryableMessagePatterns") == null) {
                errors.add("backendAdapter.operations.submit.retryableMessagePatterns");
            }
            if (json.optJSONArray("missingMaterialMessagePatterns") == null) {
                errors.add("backendAdapter.operations.submit.missingMaterialMessagePatterns");
            }
            MaterialItemMapping mapping = MaterialItemMapping.from(
                json.optJSONObject("materialItemMapping"), materialErrors);
            boolean outcomePolicyDeclared = json.has("outcomePolicy");
            OutcomePolicy outcomePolicy = OutcomePolicy.from(
                outcomePolicyDeclared ? json.opt("outcomePolicy") : null,
                outcomePolicyDeclared, errors);
            return new Submit(templateId, warehouseId, sku, data, videoId,
                json.opt("videoIdValue"), mapping,
                stringList(json.optJSONArray("retryableMessagePatterns")),
                stringList(json.optJSONArray("missingMaterialMessagePatterns")),
                outcomePolicy);
        }

        JSONObject wrap(Object templateId, Object warehouseId, Object sku, JSONObject data) {
            if (!areDistinctNonEmpty(
                    templateIdField, warehouseIdField, skuField, dataField, videoIdField)) {
                throw new IllegalStateException("backendAdapter.operations.submit.fieldNames");
            }
            JSONObject out = new JSONObject();
            try {
                out.put(templateIdField, templateId);
                out.put(warehouseIdField, warehouseId);
                out.put(skuField, sku);
                out.put(dataField, data);
                out.put(videoIdField, videoIdValue == null ? JSONObject.NULL : videoIdValue);
            } catch (Exception ignored) {}
            return out;
        }

        boolean isRetryableResponse(JSONObject body, Response response) {
            return outcomePolicy.matchesRetryable(body, response);
        }

        boolean isMissingMaterialResponse(JSONObject body, Response response) {
            return outcomePolicy.matchesMissingMaterial(body, response);
        }

        boolean hasRetryableNotWrittenRules() {
            return outcomePolicy.declared
                && !outcomePolicy.evidenceSha256.isEmpty()
                && !outcomePolicy.retryableNotWrittenRules.isEmpty();
        }

        boolean hasMissingMaterialNotWrittenRules() {
            return outcomePolicy.declared
                && !outcomePolicy.evidenceSha256.isEmpty()
                && !outcomePolicy.missingMaterialNotWrittenRules.isEmpty();
        }

        static final class OutcomePolicy {
            private static final Set<String> KEYS = Collections.unmodifiableSet(
                new LinkedHashSet<>(java.util.Arrays.asList(
                    "version", "evidenceSha256", "retryableNotWrittenRules",
                    "missingMaterialNotWrittenRules")));

            final boolean declared;
            final String evidenceSha256;
            final List<OutcomeRule> retryableNotWrittenRules;
            final List<OutcomeRule> missingMaterialNotWrittenRules;

            private OutcomePolicy(boolean declared, String evidenceSha256,
                                  List<OutcomeRule> retryableNotWrittenRules,
                                  List<OutcomeRule> missingMaterialNotWrittenRules) {
                this.declared = declared;
                this.evidenceSha256 = evidenceSha256 == null ? "" : evidenceSha256;
                this.retryableNotWrittenRules = immutableList(retryableNotWrittenRules);
                this.missingMaterialNotWrittenRules =
                    immutableList(missingMaterialNotWrittenRules);
            }

            static OutcomePolicy absent() {
                return new OutcomePolicy(false, "", Collections.emptyList(),
                    Collections.emptyList());
            }

            static OutcomePolicy from(Object raw, boolean declared, List<String> errors) {
                if (!declared) return absent();
                final String path = "backendAdapter.operations.submit.outcomePolicy";
                if (!(raw instanceof JSONObject)) {
                    errors.add(path);
                    return new OutcomePolicy(true, "", Collections.emptyList(),
                        Collections.emptyList());
                }
                JSONObject json = (JSONObject) raw;
                int errorCount = errors.size();
                if (!hasExactKeys(json, KEYS)) errors.add(path + ".fields");
                if (!exactDynamicInteger(json.opt("version"), 1L)) {
                    errors.add(path + ".version");
                }
                Object rawEvidence = json.opt("evidenceSha256");
                String evidence = rawEvidence instanceof String ? (String) rawEvidence : "";
                if (!evidence.matches("[0-9a-f]{64}")) {
                    errors.add(path + ".evidenceSha256");
                }
                List<OutcomeRule> retryable = OutcomeRule.list(
                    json.opt("retryableNotWrittenRules"),
                    path + ".retryableNotWrittenRules", errors);
                List<OutcomeRule> missing = OutcomeRule.list(
                    json.opt("missingMaterialNotWrittenRules"),
                    path + ".missingMaterialNotWrittenRules", errors);
                if (errors.size() != errorCount) {
                    // A partially valid policy must never authorize a side-effect retry.
                    return new OutcomePolicy(true, evidence, Collections.emptyList(),
                        Collections.emptyList());
                }
                return new OutcomePolicy(true, evidence, retryable, missing);
            }

            boolean matchesRetryable(JSONObject body, Response response) {
                return matches(retryableNotWrittenRules, body, response);
            }

            boolean matchesMissingMaterial(JSONObject body, Response response) {
                return matches(missingMaterialNotWrittenRules, body, response);
            }

            private static boolean matches(List<OutcomeRule> rules, JSONObject body,
                                           Response response) {
                if (body == null || response == null) return false;
                for (OutcomeRule rule : rules) {
                    if (rule.matches(body, response)) return true;
                }
                return false;
            }
        }

        static final class OutcomeRule {
            private static final Set<String> KEYS = Collections.unmodifiableSet(
                new LinkedHashSet<>(java.util.Arrays.asList(
                    "codeValues", "messagePatterns")));

            final Set<String> codeValues;
            final List<String> messagePatterns;

            private OutcomeRule(Set<String> codeValues, List<String> messagePatterns) {
                this.codeValues = immutableSet(codeValues);
                this.messagePatterns = immutableList(messagePatterns);
            }

            static List<OutcomeRule> list(Object raw, String path, List<String> errors) {
                if (!(raw instanceof JSONArray)) {
                    errors.add(path);
                    return Collections.emptyList();
                }
                JSONArray array = (JSONArray) raw;
                List<OutcomeRule> out = new ArrayList<>();
                for (int index = 0; index < array.length(); index++) {
                    String rulePath = path + "[" + index + "]";
                    Object item = array.opt(index);
                    if (!(item instanceof JSONObject)) {
                        errors.add(rulePath);
                        continue;
                    }
                    JSONObject json = (JSONObject) item;
                    int errorCount = errors.size();
                    if (!hasExactKeys(json, KEYS)) errors.add(rulePath + ".fields");
                    Set<String> codes = codeValues(json.opt("codeValues"),
                        rulePath + ".codeValues", errors);
                    List<String> messages = messagePatterns(json.opt("messagePatterns"),
                        rulePath + ".messagePatterns", errors);
                    if (codes.isEmpty() && messages.isEmpty()) {
                        errors.add(rulePath + ".selectors");
                    }
                    if (errors.size() == errorCount) {
                        out.add(new OutcomeRule(codes, messages));
                    }
                }
                return out;
            }

            private static Set<String> codeValues(Object raw, String path,
                                                  List<String> errors) {
                if (!(raw instanceof JSONArray)) {
                    errors.add(path);
                    return Collections.emptySet();
                }
                JSONArray values = (JSONArray) raw;
                Set<String> out = new LinkedHashSet<>();
                for (int index = 0; index < values.length(); index++) {
                    Object value = values.opt(index);
                    String normalized = "";
                    if (value instanceof String) {
                        normalized = ((String) value).trim();
                    } else if (value instanceof Boolean || finiteNumber(value)) {
                        normalized = String.valueOf(value).trim();
                    }
                    if (normalized.isEmpty() || !out.add(normalized)) {
                        errors.add(path + "[" + index + "]");
                    }
                }
                return out;
            }

            private static List<String> messagePatterns(Object raw, String path,
                                                        List<String> errors) {
                if (!(raw instanceof JSONArray)) {
                    errors.add(path);
                    return Collections.emptyList();
                }
                JSONArray values = (JSONArray) raw;
                List<String> out = stringList(values);
                if (out.size() != values.length()) errors.add(path);
                if (new LinkedHashSet<>(out).size() != out.size()) {
                    errors.add(path);
                }
                return out;
            }

            boolean matches(JSONObject body, Response response) {
                if (!codeValues.isEmpty()) {
                    if (response.codeField.isEmpty()
                            || !valueMatches(response.code(body), codeValues)) return false;
                }
                if (!messagePatterns.isEmpty()
                        && !containsConfiguredSubstring(
                            response.configuredMessage(body), messagePatterns)) return false;
                return !codeValues.isEmpty() || !messagePatterns.isEmpty();
            }
        }
    }

    static final class MaterialItemMapping {
        final String codeField;
        final String nameField;
        final String quantityField;

        private MaterialItemMapping(String codeField, String nameField, String quantityField) {
            this.codeField = codeField;
            this.nameField = nameField;
            this.quantityField = quantityField;
        }

        static MaterialItemMapping invalid() { return new MaterialItemMapping("", "", ""); }

        static MaterialItemMapping from(JSONObject json, List<String> errors) {
            if (json == null) {
                errors.add("backendAdapter.operations.submit.materialItemMapping");
                return invalid();
            }
            String code = text(json, "codeField");
            String name = text(json, "nameField");
            String quantity = text(json, "quantityField");
            require(errors, code, "operations.submit.materialItemMapping.codeField");
            require(errors, name, "operations.submit.materialItemMapping.nameField");
            require(errors, quantity, "operations.submit.materialItemMapping.quantityField");
            requireDistinct(errors, "operations.submit.materialItemMapping.fieldNames",
                code, name, quantity);
            return new MaterialItemMapping(code, name, quantity);
        }

        JSONObject item(Object code, Object name, Object quantity) {
            if (!areDistinctNonEmpty(codeField, nameField, quantityField)) {
                throw new IllegalStateException(
                    "backendAdapter.operations.submit.materialItemMapping.fieldNames");
            }
            JSONObject out = new JSONObject();
            try {
                out.put(codeField, code);
                out.put(nameField, name);
                out.put(quantityField, quantity);
            } catch (Exception ignored) {}
            return out;
        }
    }

    static final class PreviousSteps {
        final Map<String, String> queryFields;
        final String itemsPath;
        final String itemDataPath;
        final String serialPath;
        final Set<String> missingResponseCodes;
        final List<String> missingMessagePatterns;
        final List<String> retryableMessagePatterns;
        final List<String> alreadyExistsMessagePatterns;
        final RecipeOutcomePolicy recipeOutcomePolicy;
        final Map<String, JSONObject> recipeResolvers;
        final Map<String, JSONObject> optionValueBuilders;
        final List<String> dynamicRecipeErrors;

        private PreviousSteps(Map<String, String> queryFields, String itemsPath,
                              String itemDataPath, String serialPath,
                              Set<String> missingResponseCodes,
                              List<String> missingMessagePatterns,
                              List<String> retryableMessagePatterns,
                              List<String> alreadyExistsMessagePatterns,
                              RecipeOutcomePolicy recipeOutcomePolicy,
                              Map<String, JSONObject> recipeResolvers,
                              Map<String, JSONObject> optionValueBuilders,
                              List<String> dynamicRecipeErrors) {
            this.queryFields = Collections.unmodifiableMap(new LinkedHashMap<>(queryFields));
            this.itemsPath = itemsPath;
            this.itemDataPath = itemDataPath;
            this.serialPath = serialPath;
            this.missingResponseCodes = immutableSet(missingResponseCodes);
            this.missingMessagePatterns = immutableList(missingMessagePatterns);
            this.retryableMessagePatterns = immutableList(retryableMessagePatterns);
            this.alreadyExistsMessagePatterns = immutableList(alreadyExistsMessagePatterns);
            this.recipeOutcomePolicy = recipeOutcomePolicy == null
                ? RecipeOutcomePolicy.absent() : recipeOutcomePolicy;
            this.recipeResolvers = immutableJsonMap(recipeResolvers);
            this.optionValueBuilders = immutableJsonMap(optionValueBuilders);
            this.dynamicRecipeErrors = immutableList(dynamicRecipeErrors);
        }

        static PreviousSteps invalid() {
            return new PreviousSteps(Collections.emptyMap(), "", "", "",
                Collections.emptySet(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), RecipeOutcomePolicy.absent(),
                Collections.emptyMap(), Collections.emptyMap(),
                java.util.Arrays.asList(
                    "backendAdapter.operations.previousSteps.recipeResolvers",
                    "backendAdapter.operations.previousSteps.optionValueBuilders"));
        }

        static PreviousSteps from(JSONObject json, List<String> errors) {
            if (json == null) {
                errors.add("backendAdapter.operations.previousSteps");
                return invalid();
            }
            Map<String, String> query = mappedFields(json.optJSONObject("queryFields"),
                new String[]{"templateId", "warehouseId", "sku", "serial"},
                "operations.previousSteps.queryFields", errors);
            String items = text(json, "itemsPath");
            String data = text(json, "itemDataPath");
            String serial = text(json, "serialPath");
            JSONArray missingCodeValues = json.optJSONArray("missingResponseCodes");
            JSONArray missingMessageValues = json.optJSONArray("missingMessagePatterns");
            JSONArray retryableValues = json.optJSONArray("retryableMessagePatterns");
            JSONArray alreadyExistsValues = json.optJSONArray("alreadyExistsMessagePatterns");
            Set<String> missingCodes = businessValueSet(missingCodeValues);
            List<String> missingMessages = stringList(missingMessageValues);
            List<String> retryable = stringList(retryableValues);
            List<String> alreadyExists = stringList(alreadyExistsValues);
            boolean recipeOutcomePolicyDeclared = json.has("recipeOutcomePolicy");
            RecipeOutcomePolicy recipeOutcomePolicy = RecipeOutcomePolicy.from(
                recipeOutcomePolicyDeclared ? json.opt("recipeOutcomePolicy") : null,
                recipeOutcomePolicyDeclared, errors);
            require(errors, items, "operations.previousSteps.itemsPath");
            require(errors, data, "operations.previousSteps.itemDataPath");
            require(errors, serial, "operations.previousSteps.serialPath");
            if (missingCodeValues == null
                    || missingCodes.size() != missingCodeValues.length()) {
                errors.add("backendAdapter.operations.previousSteps.missingResponseCodes");
            }
            if (missingMessageValues == null
                    || missingMessages.size() != missingMessageValues.length()) {
                errors.add("backendAdapter.operations.previousSteps.missingMessagePatterns");
            }
            if (retryableValues == null || retryable.size() != retryableValues.length()) {
                errors.add("backendAdapter.operations.previousSteps.retryableMessagePatterns");
            }
            if (alreadyExistsValues == null
                    || alreadyExists.size() != alreadyExistsValues.length()) {
                errors.add("backendAdapter.operations.previousSteps.alreadyExistsMessagePatterns");
            }
            List<String> dynamicErrors = new ArrayList<>();
            Map<String, JSONObject> builders = parseDynamicObjectMap(
                json.opt("optionValueBuilders"), "operations.previousSteps.optionValueBuilders",
                dynamicErrors);
            Map<String, JSONObject> resolvers = parseDynamicObjectMap(
                json.opt("recipeResolvers"), "operations.previousSteps.recipeResolvers",
                dynamicErrors);
            if (json.has("optionValueBuilders") || json.has("recipeResolvers")) {
                validateDynamicBuilders(builders, dynamicErrors);
                validateDynamicResolvers(resolvers, builders.keySet(), dynamicErrors);
            }
            return new PreviousSteps(query, items, data, serial, missingCodes, missingMessages,
                retryable, alreadyExists, recipeOutcomePolicy,
                resolvers, builders, dynamicErrors);
        }

        String queryField(String canonical) { return queryFields.get(canonical); }

        JSONArray items(Object apiData) {
            Object value = valueAt(apiData, itemsPath);
            return value instanceof JSONArray ? (JSONArray) value : null;
        }

        String serial(JSONObject item) {
            Object data = valueAt(item, itemDataPath);
            Object value = valueAt(data, serialPath);
            return value == null || value == JSONObject.NULL ? "" : String.valueOf(value);
        }

        /** A lookup is absent only when an exact code or configured message pattern says so. */
        boolean isMissingResponse(Object responseCode, String configuredMessage) {
            return valueMatches(responseCode, missingResponseCodes)
                || containsConfiguredSubstring(configuredMessage, missingMessagePatterns);
        }

        boolean isRetryableResponse(JSONObject body, Response response) {
            return containsConfiguredSubstring(configuredMessage(body, response),
                retryableMessagePatterns);
        }

        boolean isAlreadyExistsResponse(JSONObject body, Response response) {
            return containsConfiguredSubstring(configuredMessage(body, response),
                alreadyExistsMessagePatterns);
        }

        boolean hasRecipeRetryableNotWrittenRules() {
            return recipeOutcomePolicy.declared
                && !recipeOutcomePolicy.evidenceSha256.isEmpty()
                && !recipeOutcomePolicy.retryableNotWrittenRules.isEmpty();
        }

        boolean hasRecipeAlreadyExistsAcknowledgedRules() {
            return recipeOutcomePolicy.declared
                && !recipeOutcomePolicy.evidenceSha256.isEmpty()
                && !recipeOutcomePolicy.alreadyExistsAcknowledgedRules.isEmpty();
        }

        RecipeResponseDisposition recipeResponseDisposition(
                JSONObject body, Response response) {
            boolean retryable = recipeOutcomePolicy.matchesRetryable(body, response);
            boolean acknowledged = recipeOutcomePolicy.matchesAlreadyExists(body, response);
            if (retryable && acknowledged) return RecipeResponseDisposition.CONFLICT;
            if (retryable) return RecipeResponseDisposition.RETRYABLE_NOT_WRITTEN;
            if (acknowledged) {
                return RecipeResponseDisposition.ALREADY_EXISTS_ACKNOWLEDGED;
            }
            return RecipeResponseDisposition.UNCLASSIFIED;
        }

        enum RecipeResponseDisposition {
            RETRYABLE_NOT_WRITTEN,
            ALREADY_EXISTS_ACKNOWLEDGED,
            CONFLICT,
            UNCLASSIFIED
        }

        static final class RecipeOutcomePolicy {
            private static final Set<String> KEYS = Collections.unmodifiableSet(
                new LinkedHashSet<>(java.util.Arrays.asList(
                    "version", "evidenceSha256", "retryableNotWrittenRules",
                    "alreadyExistsAcknowledgedRules")));

            final boolean declared;
            final String evidenceSha256;
            final List<Submit.OutcomeRule> retryableNotWrittenRules;
            final List<Submit.OutcomeRule> alreadyExistsAcknowledgedRules;

            private RecipeOutcomePolicy(boolean declared, String evidenceSha256,
                                        List<Submit.OutcomeRule> retryableNotWrittenRules,
                                        List<Submit.OutcomeRule> alreadyExistsAcknowledgedRules) {
                this.declared = declared;
                this.evidenceSha256 = evidenceSha256 == null ? "" : evidenceSha256;
                this.retryableNotWrittenRules = immutableList(retryableNotWrittenRules);
                this.alreadyExistsAcknowledgedRules =
                    immutableList(alreadyExistsAcknowledgedRules);
            }

            static RecipeOutcomePolicy absent() {
                return new RecipeOutcomePolicy(false, "", Collections.emptyList(),
                    Collections.emptyList());
            }

            static RecipeOutcomePolicy from(Object raw, boolean declared,
                                            List<String> errors) {
                if (!declared) return absent();
                final String path =
                    "backendAdapter.operations.previousSteps.recipeOutcomePolicy";
                if (!(raw instanceof JSONObject)) {
                    errors.add(path);
                    return new RecipeOutcomePolicy(true, "", Collections.emptyList(),
                        Collections.emptyList());
                }
                JSONObject json = (JSONObject) raw;
                int errorCount = errors.size();
                if (!hasExactKeys(json, KEYS)) errors.add(path + ".fields");
                if (!exactDynamicInteger(json.opt("version"), 1L)) {
                    errors.add(path + ".version");
                }
                Object rawEvidence = json.opt("evidenceSha256");
                String evidence = rawEvidence instanceof String ? (String) rawEvidence : "";
                if (!evidence.matches("[0-9a-f]{64}")) {
                    errors.add(path + ".evidenceSha256");
                }
                List<Submit.OutcomeRule> retryable = Submit.OutcomeRule.list(
                    json.opt("retryableNotWrittenRules"),
                    path + ".retryableNotWrittenRules", errors);
                List<Submit.OutcomeRule> acknowledged = Submit.OutcomeRule.list(
                    json.opt("alreadyExistsAcknowledgedRules"),
                    path + ".alreadyExistsAcknowledgedRules", errors);
                if (errors.size() != errorCount) {
                    return new RecipeOutcomePolicy(true, evidence,
                        Collections.emptyList(), Collections.emptyList());
                }
                return new RecipeOutcomePolicy(true, evidence, retryable, acknowledged);
            }

            boolean matchesRetryable(JSONObject body, Response response) {
                return matches(retryableNotWrittenRules, body, response);
            }

            boolean matchesAlreadyExists(JSONObject body, Response response) {
                return matches(alreadyExistsAcknowledgedRules, body, response);
            }

            private static boolean matches(List<Submit.OutcomeRule> rules,
                                           JSONObject body, Response response) {
                if (body == null || response == null) return false;
                for (Submit.OutcomeRule rule : rules) {
                    if (rule.matches(body, response)) return true;
                }
                return false;
            }
        }

        private static String configuredMessage(JSONObject body, Response response) {
            return response == null ? "" : response.configuredMessage(body);
        }

        JSONObject resolver(String id) {
            return copyObject(recipeResolvers.get(id));
        }

        JSONObject optionValueBuilders() {
            JSONObject out = new JSONObject();
            try {
                for (Map.Entry<String, JSONObject> entry : optionValueBuilders.entrySet()) {
                    out.put(entry.getKey(), copyObject(entry.getValue()));
                }
            } catch (Exception ignored) {
                return new JSONObject();
            }
            return out;
        }
    }

    static final class DuplicateCheck {
        final Map<String, String> queryFields;
        final String itemsPath;
        final List<String> dateFields;
        final List<String> epochUnits;
        final List<String> dateTransforms;
        final List<String> dateFormats;
        final String timeZone;
        final DuplicateDateRules.ParsePolicy dateParsePolicy;

        private DuplicateCheck(Map<String, String> queryFields, String itemsPath,
                               List<String> dateFields, List<String> epochUnits,
                               List<String> dateTransforms, List<String> dateFormats,
                               String timeZone,
                               DuplicateDateRules.ParsePolicy dateParsePolicy) {
            this.queryFields = Collections.unmodifiableMap(new LinkedHashMap<>(queryFields));
            this.itemsPath = itemsPath;
            this.dateFields = immutableList(dateFields);
            this.epochUnits = immutableList(epochUnits);
            this.dateTransforms = immutableList(dateTransforms);
            this.dateFormats = immutableList(dateFormats);
            this.timeZone = timeZone;
            this.dateParsePolicy = dateParsePolicy;
        }

        static DuplicateCheck invalid() {
            return new DuplicateCheck(Collections.emptyMap(), "", Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), "UTC",
                null);
        }

        static DuplicateCheck from(JSONObject json, List<String> errors) {
            if (json == null) {
                errors.add("backendAdapter.operations.duplicateCheck");
                return invalid();
            }
            Map<String, String> query = mappedFields(json.optJSONObject("queryFields"),
                new String[]{"templateId", "serial"},
                "operations.duplicateCheck.queryFields", errors);
            String items = text(json, "itemsPath");
            List<String> dates = stringList(json.optJSONArray("dateFields"));
            JSONArray epochUnitValues = json.optJSONArray("epochUnits");
            JSONArray dateTransformValues = json.optJSONArray("dateTransforms");
            JSONArray dateFormatValues = json.optJSONArray("dateFormats");
            List<String> epochUnits = stringList(epochUnitValues);
            List<String> dateTransforms = new ArrayList<>();
            List<String> dateFormats = stringList(dateFormatValues);
            String timeZone = text(json, "timeZone");
            DuplicateDateRules.ParsePolicy dateParsePolicy = parseDatePolicy(json, errors);
            require(errors, items, "operations.duplicateCheck.itemsPath");
            require(errors, dates, "operations.duplicateCheck.dateFields");
            if (epochUnitValues == null || epochUnits.size() != epochUnitValues.length()) {
                errors.add("backendAdapter.operations.duplicateCheck.epochUnits");
            }
            if (dateFormatValues == null || dateFormats.size() != dateFormatValues.length()) {
                errors.add("backendAdapter.operations.duplicateCheck.dateFormats");
            }
            Set<String> seenEpochUnits = new LinkedHashSet<>();
            for (String epochUnit : epochUnits) {
                if (!("seconds".equals(epochUnit) || "milliseconds".equals(epochUnit))
                        || !seenEpochUnits.add(epochUnit)) {
                    errors.add("backendAdapter.operations.duplicateCheck.epochUnits");
                    break;
                }
            }
            boolean invalidDateTransforms = dateTransformValues == null;
            Set<String> seenDateTransforms = new LinkedHashSet<>();
            for (int i = 0; dateTransformValues != null
                    && i < dateTransformValues.length(); i++) {
                Object raw = dateTransformValues.opt(i);
                if (!(raw instanceof String)) {
                    invalidDateTransforms = true;
                    continue;
                }
                String transform = (String) raw;
                if (!DuplicateDateRules.isSupportedTransform(transform)
                        || !seenDateTransforms.add(transform)) {
                    invalidDateTransforms = true;
                    continue;
                }
                dateTransforms.add(transform);
            }
            if (invalidDateTransforms) {
                errors.add("backendAdapter.operations.duplicateCheck.dateTransforms");
            }
            if (epochUnits.isEmpty() && dateFormats.isEmpty()) {
                errors.add("backendAdapter.operations.duplicateCheck.dateFormats");
            }
            require(errors, timeZone, "operations.duplicateCheck.timeZone");
            if (!timeZone.isEmpty()) {
                boolean known = false;
                for (String id : java.util.TimeZone.getAvailableIDs()) {
                    if (id.equals(timeZone)) { known = true; break; }
                }
                if (!known) errors.add("backendAdapter.operations.duplicateCheck.timeZone");
            }
            return new DuplicateCheck(
                query, items, dates, epochUnits, dateTransforms, dateFormats, timeZone,
                dateParsePolicy);
        }

        String queryField(String canonical) { return queryFields.get(canonical); }

        JSONArray items(Object apiData) {
            Object value = valueAt(apiData, itemsPath);
            if (value instanceof JSONArray) return (JSONArray) value;
            JSONArray array = new JSONArray();
            if (value != null && value != JSONObject.NULL) array.put(value);
            return array;
        }

        java.util.TimeZone duplicateAgeTimeZone() {
            if (dateParsePolicy != null && dateParsePolicy.valid
                    && DuplicateDateRules.TIME_ZONE_DEVICE.equals(
                        dateParsePolicy.timeZoneSource)) {
                return java.util.TimeZone.getDefault();
            }
            return java.util.TimeZone.getTimeZone(timeZone);
        }

        private static DuplicateDateRules.ParsePolicy parseDatePolicy(
                JSONObject json, List<String> errors) {
            String[] keys = new String[]{
                "epochDigitLengths", "numericFractionPolicy", "textParseConsumption",
                "plausibilityScope", "timeZoneSource", "rootValueEnabled",
                "numericEpochPrecision"
            };
            boolean any = false;
            for (String key : keys) any |= json.has(key);
            if (!any) return null;

            List<Integer> lengths = new ArrayList<>();
            JSONArray rawLengths = json.optJSONArray("epochDigitLengths");
            boolean validLengths = rawLengths != null;
            Set<Integer> seenLengths = new LinkedHashSet<>();
            for (int i = 0; rawLengths != null && i < rawLengths.length(); i++) {
                Object raw = rawLengths.opt(i);
                if (!exactDynamicIntegerValue(raw)) {
                    validLengths = false;
                    continue;
                }
                long value = ((Number) raw).longValue();
                if (value < 1L || value > 19L || !seenLengths.add((int) value)) {
                    validLengths = false;
                    continue;
                }
                lengths.add((int) value);
            }
            if (!validLengths) {
                errors.add("operations.duplicateCheck.epochDigitLengths");
            }

            String numericFractionPolicy = strictString(json, "numericFractionPolicy");
            if (!(DuplicateDateRules.NUMERIC_FRACTION_REJECT.equals(numericFractionPolicy)
                    || DuplicateDateRules.NUMERIC_FRACTION_TRUNCATE.equals(
                        numericFractionPolicy))) {
                errors.add("operations.duplicateCheck.numericFractionPolicy");
            }
            String numericEpochPrecision = json.has("numericEpochPrecision")
                ? strictString(json, "numericEpochPrecision")
                : DuplicateDateRules.NUMERIC_EPOCH_PRECISION_EXACT;
            if (!(DuplicateDateRules.NUMERIC_EPOCH_PRECISION_EXACT.equals(
                        numericEpochPrecision)
                    || DuplicateDateRules.NUMERIC_EPOCH_PRECISION_MINUTE_FLOOR.equals(
                        numericEpochPrecision))) {
                errors.add("operations.duplicateCheck.numericEpochPrecision");
            }
            String textParseConsumption = strictString(json, "textParseConsumption");
            if (!(DuplicateDateRules.TEXT_PARSE_FULL.equals(textParseConsumption)
                    || DuplicateDateRules.TEXT_PARSE_PREFIX.equals(textParseConsumption))) {
                errors.add("operations.duplicateCheck.textParseConsumption");
            }
            String plausibilityScope = strictString(json, "plausibilityScope");
            if (!(DuplicateDateRules.PLAUSIBILITY_ALL.equals(plausibilityScope)
                    || DuplicateDateRules.PLAUSIBILITY_EPOCH_ONLY.equals(plausibilityScope))) {
                errors.add("operations.duplicateCheck.plausibilityScope");
            }
            String timeZoneSource = strictString(json, "timeZoneSource");
            if (!(DuplicateDateRules.TIME_ZONE_CONFIGURED.equals(timeZoneSource)
                    || DuplicateDateRules.TIME_ZONE_DEVICE.equals(timeZoneSource))) {
                errors.add("operations.duplicateCheck.timeZoneSource");
            }
            Object rawRoot = json.opt("rootValueEnabled");
            boolean rootValueEnabled = rawRoot instanceof Boolean && (Boolean) rawRoot;
            if (!(rawRoot instanceof Boolean)) {
                errors.add("operations.duplicateCheck.rootValueEnabled");
            }
            return new DuplicateDateRules.ParsePolicy(
                validLengths ? lengths : null,
                numericFractionPolicy,
                numericEpochPrecision,
                textParseConsumption,
                plausibilityScope,
                timeZoneSource,
                rootValueEnabled);
        }

        private static String strictString(JSONObject json, String key) {
            Object value = json.opt(key);
            return value instanceof String ? ((String) value).trim() : "";
        }
    }

    /**
     * Mapping used by the optional pre-submit item refresh.
     *
     * <p>Every path and item-kind value comes from the Panel adapter. The App does not know any
     * deployment field name or business item type. Errors are capability-scoped: profiles that do
     * not enable {@code workflow.materials.refreshBeforeSubmit} do not need this authoring subset.
     */
    static final class MaterialRefresh {
        static final String EXISTING_QUANTITY_STRICT = "strict_live_match";
        static final String EXISTING_QUANTITY_PROFILE = "profile_authoritative";

        final String idParam;
        final String fieldListPath;
        final String fieldIdPath;
        final String fieldTypePath;
        final String fieldParentTypePath;
        final String fieldTypeNamePath;
        final String fieldTitlePath;
        final String fieldEnglishTitlePath;
        final String fieldOptionsPath;
        final String optionCodePath;
        final String optionLabelPath;
        final String optionEnglishLabelPath;
        final String optionQuantityPath;
        final String existingQuantityPolicy;
        final Set<String> itemKindValues;
        final List<String> errors;

        private MaterialRefresh(String idParam, String fieldListPath,
                                String fieldIdPath, String fieldTypePath,
                                String fieldParentTypePath, String fieldTypeNamePath,
                                String fieldTitlePath, String fieldEnglishTitlePath,
                                String fieldOptionsPath, String optionCodePath,
                                String optionLabelPath, String optionEnglishLabelPath,
                                String optionQuantityPath, String existingQuantityPolicy,
                                Set<String> itemKindValues, List<String> errors) {
            this.idParam = idParam;
            this.fieldListPath = fieldListPath;
            this.fieldIdPath = fieldIdPath;
            this.fieldTypePath = fieldTypePath;
            this.fieldParentTypePath = fieldParentTypePath;
            this.fieldTypeNamePath = fieldTypeNamePath;
            this.fieldTitlePath = fieldTitlePath;
            this.fieldEnglishTitlePath = fieldEnglishTitlePath;
            this.fieldOptionsPath = fieldOptionsPath;
            this.optionCodePath = optionCodePath;
            this.optionLabelPath = optionLabelPath;
            this.optionEnglishLabelPath = optionEnglishLabelPath;
            this.optionQuantityPath = optionQuantityPath;
            this.existingQuantityPolicy = existingQuantityPolicy;
            this.itemKindValues = immutableSet(itemKindValues);
            this.errors = immutableList(errors);
        }

        static MaterialRefresh invalid() {
            return new MaterialRefresh("", "", "", "", "", "", "", "", "", "", "",
                "", "", EXISTING_QUANTITY_STRICT, Collections.emptySet(),
                Collections.emptyList());
        }

        static MaterialRefresh from(JSONObject adapter) {
            List<String> errors = new ArrayList<>();
            JSONObject operations = adapter == null ? null : adapter.optJSONObject("operations");
            JSONObject detail = operations == null ? null : operations.optJSONObject("templateDetail");
            JSONObject fields = adapter == null ? null : adapter.optJSONObject("fields");
            JSONObject template = fields == null ? null : fields.optJSONObject("template");
            JSONObject formField = fields == null ? null : fields.optJSONObject("formField");
            JSONObject option = fields == null ? null : fields.optJSONObject("option");
            JSONObject conversion = adapter == null ? null : adapter.optJSONObject("conversion");
            JSONObject kinds = conversion == null ? null : conversion.optJSONObject("fieldKinds");

            String idParam = text(detail, "idParam");
            String fieldList = text(template, "fieldList");
            String fieldId = text(formField, "id");
            String fieldType = text(formField, "type");
            String fieldParentType = text(formField, "parentType");
            String fieldTypeName = text(formField, "typeName");
            String fieldTitle = text(formField, "title");
            String fieldEnglishTitle = text(formField, "englishTitle");
            String fieldOptions = text(formField, "options");
            String optionCode = text(option, "value");
            String optionLabel = text(option, "label");
            String optionEnglishLabel = text(option, "englishLabel");
            String optionQuantity = text(option, "quantity");
            String existingQuantityPolicy = EXISTING_QUANTITY_STRICT;
            if (detail != null && detail.has("existingQuantityPolicy")) {
                Object rawPolicy = detail.opt("existingQuantityPolicy");
                String configuredPolicy = rawPolicy instanceof String
                    ? ((String) rawPolicy).trim() : "";
                if (EXISTING_QUANTITY_STRICT.equals(configuredPolicy)
                        || EXISTING_QUANTITY_PROFILE.equals(configuredPolicy)) {
                    existingQuantityPolicy = configuredPolicy;
                } else {
                    errors.add("operations.templateDetail.existingQuantityPolicy");
                }
            }
            Set<String> itemKinds = stringSet(kinds == null ? null : kinds.optJSONArray("items"));

            require(errors, idParam, "operations.templateDetail.idParam");
            require(errors, fieldList, "fields.template.fieldList");
            require(errors, fieldId, "fields.formField.id");
            require(errors, fieldType, "fields.formField.type");
            require(errors, fieldParentType, "fields.formField.parentType");
            require(errors, fieldTypeName, "fields.formField.typeName");
            require(errors, fieldTitle, "fields.formField.title");
            require(errors, fieldEnglishTitle, "fields.formField.englishTitle");
            require(errors, fieldOptions, "fields.formField.options");
            require(errors, optionCode, "fields.option.value");
            require(errors, optionLabel, "fields.option.label");
            require(errors, optionEnglishLabel, "fields.option.englishLabel");
            require(errors, optionQuantity, "fields.option.quantity");
            require(errors, itemKinds, "conversion.fieldKinds.items");

            return new MaterialRefresh(idParam, fieldList, fieldId, fieldType,
                fieldParentType, fieldTypeName, fieldTitle, fieldEnglishTitle,
                fieldOptions, optionCode, optionLabel, optionEnglishLabel,
                optionQuantity, existingQuantityPolicy, itemKinds, errors);
        }

        boolean isItemField(JSONObject field) {
            return matches(valueAt(field, fieldTypePath))
                || matches(valueAt(field, fieldParentTypePath))
                || matches(valueAt(field, fieldTypeNamePath));
        }

        private boolean matches(Object value) {
            return value != null && value != JSONObject.NULL
                && itemKindValues.contains(String.valueOf(value).trim());
        }
    }

    static final class Printing {
        final boolean enabled;
        final boolean allowJobsArrayWhenCodeMissing;
        final String onlineStatusPath;
        final Set<String> onlineValues;
        final String jobsPath;
        final String serialQueryParam;
        final String pageQueryParam;
        final int firstPage;
        final String idField;
        final String serialField;
        final String typeField;
        final String statusField;
        final Set<String> acceptedTypeValues;
        final Set<String> printedValues;
        final Set<String> failedValues;
        final Set<String> ongoingValues;
        final String retryIdField;

        private Printing(boolean enabled, boolean allowJobsArrayWhenCodeMissing,
                         String onlineStatusPath, Set<String> onlineValues,
                         String jobsPath, String serialQueryParam, String pageQueryParam, int firstPage,
                         String idField, String serialField, String typeField, String statusField,
                         Set<String> acceptedTypeValues, Set<String> printedValues,
                         Set<String> failedValues, Set<String> ongoingValues, String retryIdField) {
            this.enabled = enabled;
            this.allowJobsArrayWhenCodeMissing = allowJobsArrayWhenCodeMissing;
            this.onlineStatusPath = onlineStatusPath;
            this.onlineValues = immutableSet(onlineValues);
            this.jobsPath = jobsPath;
            this.serialQueryParam = serialQueryParam;
            this.pageQueryParam = pageQueryParam;
            this.firstPage = firstPage;
            this.idField = idField;
            this.serialField = serialField;
            this.typeField = typeField;
            this.statusField = statusField;
            this.acceptedTypeValues = immutableSet(acceptedTypeValues);
            this.printedValues = immutableSet(printedValues);
            this.failedValues = immutableSet(failedValues);
            this.ongoingValues = immutableSet(ongoingValues);
            this.retryIdField = retryIdField;
        }

        static Printing disabled() {
            return new Printing(false, false, "", Collections.emptySet(), "", "", "", 1,
                "", "", "", "", Collections.emptySet(), Collections.emptySet(),
                Collections.emptySet(), Collections.emptySet(), "");
        }

        static Printing from(JSONObject json, List<String> errors) {
            if (json == null || !json.optBoolean("enabled", false)) return disabled();
            JSONObject onlineJson = json.optJSONObject("online");
            JSONObject queryJson = json.optJSONObject("query");
            JSONObject fieldsJson = json.optJSONObject("fields");
            JSONObject valuesJson = json.optJSONObject("values");
            Object rawCodeMissingJobs = json.opt("allowJobsArrayWhenCodeMissing");
            boolean allowCodeMissingJobs = rawCodeMissingJobs instanceof Boolean
                && (Boolean) rawCodeMissingJobs;
            if (rawCodeMissingJobs != null && rawCodeMissingJobs != JSONObject.NULL
                    && !(rawCodeMissingJobs instanceof Boolean)) {
                errors.add("backendAdapter.printing.allowJobsArrayWhenCodeMissing");
            }
            String onlinePath = text(onlineJson, "statusPath");
            String jobsPath = text(json, "jobsPath");
            String serialParam = text(queryJson, "serialParam");
            String pageParam = text(queryJson, "pageParam");
            String id = text(fieldsJson, "id");
            String serial = text(fieldsJson, "serial");
            String type = text(fieldsJson, "type");
            String status = text(fieldsJson, "status");
            String retryId = text(json, "retryIdField");
            Set<String> online = stringSet(onlineJson == null ? null : onlineJson.optJSONArray("values"));
            Set<String> acceptedTypes = stringSet(valuesJson == null ? null : valuesJson.optJSONArray("acceptedTypes"));
            Set<String> printed = stringSet(valuesJson == null ? null : valuesJson.optJSONArray("printed"));
            Set<String> failed = stringSet(valuesJson == null ? null : valuesJson.optJSONArray("failed"));
            Set<String> ongoing = stringSet(valuesJson == null ? null : valuesJson.optJSONArray("ongoing"));
            require(errors, onlinePath, "printing.online.statusPath");
            require(errors, jobsPath, "printing.jobsPath");
            require(errors, serialParam, "printing.query.serialParam");
            require(errors, pageParam, "printing.query.pageParam");
            require(errors, id, "printing.fields.id");
            require(errors, serial, "printing.fields.serial");
            require(errors, type, "printing.fields.type");
            require(errors, status, "printing.fields.status");
            require(errors, retryId, "printing.retryIdField");
            require(errors, online, "printing.online.values");
            require(errors, printed, "printing.values.printed");
            require(errors, failed, "printing.values.failed");
            require(errors, ongoing, "printing.values.ongoing");
            require(errors, acceptedTypes, "printing.values.acceptedTypes");
            Set<String> classified = new LinkedHashSet<>();
            for (Set<String> values : new Set[]{printed, failed, ongoing}) {
                for (String value : values) {
                    if (!classified.add(value)) {
                        errors.add("backendAdapter.printing.values.statusOverlap");
                    }
                }
            }
            return new Printing(true, allowCodeMissingJobs,
                onlinePath, online, jobsPath, serialParam, pageParam,
                Math.max(0, queryJson == null ? 1 : queryJson.optInt("pageStart", 1)),
                id, serial, type, status,
                acceptedTypes, printed, failed, ongoing, retryId);
        }

        boolean isOnline(JSONObject response) {
            return valueMatches(valueAt(response, onlineStatusPath), onlineValues);
        }

        JSONArray jobs(JSONObject response) {
            Object value = valueAt(response, jobsPath);
            return value instanceof JSONArray ? (JSONArray) value : null;
        }

        /**
         * Optional legacy compatibility for this read-only endpoint only. An explicit response
         * code always wins; a code-less response is accepted only when the Panel opted in, the
         * configured jobs path is an array, and no configured error-message path is populated.
         */
        boolean isJobsResponseSuccess(JSONObject body, Response response) {
            if (response == null) return false;
            if (response.isSuccess(body)) return true;
            if (!allowJobsArrayWhenCodeMissing || body == null) return false;
            Object code = response.code(body);
            return (code == null || code == JSONObject.NULL)
                && !response.hasConfiguredMessage(body)
                && jobs(body) != null;
        }

        boolean accepts(JSONObject job) {
            return !acceptedTypeValues.isEmpty()
                && valueMatches(valueAt(job, typeField), acceptedTypeValues);
        }

        boolean serialMatches(JSONObject job, String serial) {
            Object value = valueAt(job, serialField);
            return serial != null && serial.equals(value == null ? "" : String.valueOf(value));
        }

        long id(JSONObject job) {
            Object value = valueAt(job, idField);
            if (value instanceof Number) return ((Number) value).longValue();
            try { return Long.parseLong(value == null ? "" : String.valueOf(value)); }
            catch (Exception ignored) { return 0L; }
        }

        String status(JSONObject job) {
            Object value = valueAt(job, statusField);
            return value == null || value == JSONObject.NULL ? "" : String.valueOf(value).trim();
        }

        boolean isPrinted(JSONObject job) { return printedValues.contains(status(job)); }
        boolean isFailed(JSONObject job) { return failedValues.contains(status(job)); }
        boolean isOngoing(JSONObject job) { return ongoingValues.contains(status(job)); }

        String jobQuery(String serial) {
            return serialQueryParam + "=" + serial + "&" + pageQueryParam + "=" + firstPage;
        }

        JSONObject retryPayload(long id) {
            if (retryIdField.isEmpty()) {
                throw new IllegalStateException(
                    "backendAdapter.printing.retryIdField is required");
            }
            JSONObject payload = new JSONObject();
            try {
                payload.put(retryIdField, id);
            } catch (Exception error) {
                throw new IllegalStateException("Could not build print retry payload", error);
            }
            return payload;
        }
    }

    private static Map<String, JSONObject> parseDynamicObjectMap(
            Object raw, String path, List<String> errors) {
        Map<String, JSONObject> out = new LinkedHashMap<>();
        if (!(raw instanceof JSONObject)) {
            errors.add("backendAdapter." + path);
            return out;
        }
        JSONObject object = (JSONObject) raw;
        if (object.length() > 32) errors.add("backendAdapter." + path);
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject value = object.optJSONObject(key);
            if (!safeDynamicId(key, true) || value == null) {
                errors.add("backendAdapter." + path + "." + key);
                continue;
            }
            out.put(key, copyObject(value));
        }
        return out;
    }

    private static Map<String, JSONObject> immutableJsonMap(Map<String, JSONObject> values) {
        Map<String, JSONObject> out = new LinkedHashMap<>();
        if (values != null) {
            for (Map.Entry<String, JSONObject> entry : values.entrySet()) {
                out.put(entry.getKey(), copyObject(entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static void validateDynamicBuilders(Map<String, JSONObject> builders,
                                                List<String> errors) {
        for (Map.Entry<String, JSONObject> entry : builders.entrySet()) {
            validateDynamicBuilder(entry.getValue(),
                "operations.previousSteps.optionValueBuilders." + entry.getKey(),
                0, new int[]{0}, errors);
        }
    }

    private static void validateDynamicResolvers(Map<String, JSONObject> resolvers,
                                                 Set<String> builderIds,
                                                 List<String> errors) {
        for (Map.Entry<String, JSONObject> entry : resolvers.entrySet()) {
            String path = "operations.previousSteps.recipeResolvers." + entry.getKey();
            JSONObject resolver = entry.getValue();
            requireExactDynamicKeys(resolver, path, errors,
                "version", "identity", "searchTextAttributes", "optionSearchTextAttributes",
                "kindSelectors", "rules");
            if (!exactDynamicInteger(resolver.opt("version"), 1L)) {
                dynamicError(errors, path + ".version");
            }
            JSONObject identity = resolver.optJSONObject("identity");
            requireExactDynamicKeys(identity, path + ".identity", errors,
                "templateId", "expectedStep", "warehouseId", "sku");
            if (identity != null) {
                for (String key : new String[]{"templateId", "expectedStep", "warehouseId", "sku"}) {
                    validateDynamicBuilder(identity.optJSONObject(key),
                        path + ".identity." + key, 0, new int[]{0}, errors);
                }
            }
            validateDynamicSearchAttributes(resolver.opt("searchTextAttributes"),
                path + ".searchTextAttributes", errors);
            validateDynamicSearchAttributes(resolver.opt("optionSearchTextAttributes"),
                path + ".optionSearchTextAttributes", errors);

            Set<String> kinds = new LinkedHashSet<>();
            JSONArray kindRules = resolver.optJSONArray("kindSelectors");
            if (kindRules == null || kindRules.length() == 0 || kindRules.length() > 32) {
                dynamicError(errors, path + ".kindSelectors");
            }
            for (int i = 0; kindRules != null && i < Math.min(kindRules.length(), 32); i++) {
                String itemPath = path + ".kindSelectors[" + i + "]";
                JSONObject item = kindRules.optJSONObject(i);
                requireExactDynamicKeys(item, itemPath, errors, "kind", "selector");
                String kind = item == null ? "" : text(item, "kind");
                if (!safeDynamicId(kind, true) || !kinds.add(kind)) {
                    dynamicError(errors, itemPath + ".kind");
                }
                JSONObject selector = item == null ? null : item.optJSONObject("selector");
                validateDynamicSelector(selector, itemPath + ".selector", errors,
                    Collections.emptySet(), true);
            }

            JSONArray rules = resolver.optJSONArray("rules");
            if (rules == null || rules.length() == 0 || rules.length() > 32) {
                dynamicError(errors, path + ".rules");
            }
            for (int i = 0; rules != null && i < Math.min(rules.length(), 32); i++) {
                String rulePath = path + ".rules[" + i + "]";
                JSONObject rule = rules.optJSONObject(i);
                requireExactDynamicKeys(rule, rulePath, errors,
                    "selector", "cardinality", "action");
                validateDynamicSelector(rule == null ? null : rule.optJSONObject("selector"),
                    rulePath + ".selector", errors, kinds, false);
                if (rule == null || !dynamicCardinality(rule.opt("cardinality"))) {
                    dynamicError(errors, rulePath + ".cardinality");
                }
                validateDynamicAction(rule == null ? null : rule.optJSONObject("action"),
                    rulePath + ".action", builderIds, kinds, errors);
            }
        }
    }

    private static void validateDynamicAction(JSONObject action, String path,
                                              Set<String> builderIds, Set<String> kinds,
                                              List<String> errors) {
        if (action == null) {
            dynamicError(errors, path);
            return;
        }
        String type = text(action, "type");
        if ("serial".equals(type)) {
            requireExactDynamicKeys(action, path, errors, "type");
            return;
        }
        if ("photo".equals(type)) {
            requireExactDynamicKeys(action, path, errors, "type", "source", "joinWith");
            if (!safeDynamicId(text(action, "source"), false)) {
                dynamicError(errors, path + ".source");
            }
            Object joinWith = action.opt("joinWith");
            if (!(joinWith instanceof String) || ((String) joinWith).length() > 4096) {
                dynamicError(errors, path + ".joinWith");
            }
            return;
        }
        if ("omit".equals(type)) {
            requireExactDynamicKeys(action, path, errors, "type", "allowRequired");
            if (!(action.opt("allowRequired") instanceof Boolean)) {
                dynamicError(errors, path + ".allowRequired");
            }
            return;
        }
        if (!"fixedOption".equals(type)) {
            dynamicError(errors, path + ".type");
            return;
        }
        requireExactDynamicKeys(action, path, errors,
            "type", "optionSelectors", "valueBuilder", "onNoMatch");
        JSONArray selectors = action.optJSONArray("optionSelectors");
        if (selectors == null || selectors.length() == 0 || selectors.length() > 16) {
            dynamicError(errors, path + ".optionSelectors");
        }
        for (int i = 0; selectors != null && i < Math.min(selectors.length(), 16); i++) {
            String itemPath = path + ".optionSelectors[" + i + "]";
            JSONObject item = selectors.optJSONObject(i);
            requireAllowedDynamicKeys(item, itemPath, errors,
                new String[]{"selector", "cardinality", "literalOverride"},
                new String[]{"selector", "cardinality"});
            validateDynamicSelector(item == null ? null : item.optJSONObject("selector"),
                itemPath + ".selector", errors, kinds, false);
            if (item == null || !dynamicCardinality(item.opt("cardinality"))) {
                dynamicError(errors, itemPath + ".cardinality");
            }
            if (item != null && item.has("literalOverride")) {
                validateDynamicLiteral(item.opt("literalOverride"), itemPath + ".literalOverride",
                    0, new int[]{0}, errors);
            }
        }
        String builder = text(action, "valueBuilder");
        if (!safeDynamicId(builder, true) || !builderIds.contains(builder)) {
            dynamicError(errors, path + ".valueBuilder");
        }
        String onNoMatch = text(action, "onNoMatch");
        if (!"reject".equals(onNoMatch) && !"use_value_builder".equals(onNoMatch)) {
            dynamicError(errors, path + ".onNoMatch");
        }
    }

    private static void validateDynamicSelector(JSONObject selector, String path,
                                                List<String> errors, Set<String> kinds,
                                                boolean derivingKind) {
        requireAllowedDynamicKeys(selector, path, errors,
            new String[]{"allOf", "anyOf", "noneOf"}, new String[]{});
        if (selector == null) return;
        int total = 0;
        for (String group : new String[]{"allOf", "anyOf", "noneOf"}) {
            if (!selector.has(group)) continue;
            JSONArray predicates = selector.optJSONArray(group);
            if (predicates == null || predicates.length() == 0 || predicates.length() > 16) {
                dynamicError(errors, path + "." + group);
                continue;
            }
            total += predicates.length();
            for (int i = 0; i < Math.min(predicates.length(), 16); i++) {
                validateDynamicPredicate(predicates.optJSONObject(i),
                    path + "." + group + "[" + i + "]", kinds, derivingKind, errors);
            }
        }
        if (total == 0 || total > 32) dynamicError(errors, path);
    }

    private static void validateDynamicPredicate(JSONObject predicate, String path,
                                                 Set<String> kinds, boolean derivingKind,
                                                 List<String> errors) {
        requireAllowedDynamicKeys(predicate, path, errors,
            new String[]{"attribute", "caseSensitive", "equalsAny", "containsAny", "present"},
            new String[]{"attribute", "caseSensitive"});
        if (predicate == null) return;
        String attribute = text(predicate, "attribute");
        Set<String> allowedAttributes = new LinkedHashSet<>(java.util.Arrays.asList(
            "id", "kind", "type", "parentType", "typeName", "title", "englishTitle",
            "searchText", "required", "visible", "hasOptions"));
        if (!allowedAttributes.contains(attribute) || (derivingKind && "kind".equals(attribute))) {
            dynamicError(errors, path + ".attribute");
        }
        if (!(predicate.opt("caseSensitive") instanceof Boolean)) {
            dynamicError(errors, path + ".caseSensitive");
        }
        int operators = (predicate.has("equalsAny") ? 1 : 0)
            + (predicate.has("containsAny") ? 1 : 0) + (predicate.has("present") ? 1 : 0);
        if (operators != 1) {
            dynamicError(errors, path);
            return;
        }
        if (predicate.has("present")) {
            if (!(predicate.opt("present") instanceof Boolean)) {
                dynamicError(errors, path + ".present");
            }
            return;
        }
        String operator = predicate.has("equalsAny") ? "equalsAny" : "containsAny";
        JSONArray values = predicate.optJSONArray(operator);
        if (values == null || values.length() == 0 || values.length() > 16) {
            dynamicError(errors, path + "." + operator);
            return;
        }
        if ("kind".equals(attribute) && !"equalsAny".equals(operator)) {
            dynamicError(errors, path + ".kind");
        }
        for (int i = 0; i < values.length(); i++) {
            Object value = values.opt(i);
            boolean scalar = value == JSONObject.NULL || value instanceof String
                || value instanceof Boolean || finiteNumber(value);
            if (!scalar || (value instanceof String && (((String) value).isEmpty()
                    || ((String) value).length() > 4096))) {
                dynamicError(errors, path + "." + operator + "[" + i + "]");
            }
            if ("containsAny".equals(operator) && !(value instanceof String)) {
                dynamicError(errors, path + "." + operator + "[" + i + "]");
            }
            if ("kind".equals(attribute) && value instanceof String
                    && !kinds.contains(value)) {
                dynamicError(errors, path + ".equalsAny[" + i + "]");
            }
        }
    }

    private static void validateDynamicBuilder(JSONObject builder, String path, int depth,
                                               int[] count, List<String> errors) {
        if (builder == null || depth > 8 || ++count[0] > 512) {
            dynamicError(errors, path);
            return;
        }
        String type = text(builder, "type");
        if ("literal".equals(type)) {
            requireExactDynamicKeys(builder, path, errors, "type", "value");
            validateDynamicLiteral(builder.opt("value"), path + ".value", 0,
                new int[]{0}, errors);
            return;
        }
        if ("present".equals(type)) {
            requireExactDynamicKeys(builder, path, errors,
                "type", "path", "fallbackIfMissing");
            validateDynamicPath(builder.opt("path"), path + ".path", errors);
            validateDynamicLiteral(builder.opt("fallbackIfMissing"),
                path + ".fallbackIfMissing", 0, new int[]{0}, errors);
            return;
        }
        if ("firstNonEmpty".equals(type)) {
            requireExactDynamicKeys(builder, path, errors, "type", "paths");
            JSONArray paths = builder.optJSONArray("paths");
            if (paths == null || paths.length() == 0 || paths.length() > 16) {
                dynamicError(errors, path + ".paths");
            }
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; paths != null && i < Math.min(paths.length(), 16); i++) {
                Object value = paths.opt(i);
                validateDynamicPath(value, path + ".paths[" + i + "]", errors);
                if (value instanceof String && !seen.add((String) value)) {
                    dynamicError(errors, path + ".paths[" + i + "]");
                }
            }
            return;
        }
        if ("integer".equals(type)) {
            requireExactDynamicKeys(builder, path, errors, "type", "path", "default");
            validateDynamicPath(builder.opt("path"), path + ".path", errors);
            if (!exactDynamicIntegerValue(builder.opt("default"))) {
                dynamicError(errors, path + ".default");
            }
            return;
        }
        if (!"object".equals(type)) {
            dynamicError(errors, path + ".type");
            return;
        }
        requireExactDynamicKeys(builder, path, errors, "type", "members");
        JSONObject members = builder.optJSONObject("members");
        if (members == null || members.length() == 0 || members.length() > 32) {
            dynamicError(errors, path + ".members");
            return;
        }
        java.util.Iterator<String> keys = members.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!safeDynamicId(key, true)) dynamicError(errors, path + ".members." + key);
            validateDynamicBuilder(members.optJSONObject(key), path + ".members." + key,
                depth + 1, count, errors);
        }
    }

    private static void validateDynamicPath(Object raw, String path, List<String> errors) {
        if (!(raw instanceof String)) {
            dynamicError(errors, path);
            return;
        }
        String value = (String) raw;
        if (value.isEmpty() || value.length() > 512) {
            dynamicError(errors, path);
            return;
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length < 2 || parts.length > 16
                || !allowedDynamicPathKey(parts[0], parts[1])) {
            dynamicError(errors, path);
            return;
        }
        for (String part : parts) {
            if (!safeDynamicId(part, false)) {
                dynamicError(errors, path);
                return;
            }
        }
    }

    private static boolean allowedDynamicPathKey(String root, String key) {
        if ("template".equals(root)) return java.util.Arrays.asList(
            "id", "name", "sku", "step", "warehouseId", "fieldList").contains(key);
        if ("field".equals(root)) return java.util.Arrays.asList(
            "id", "type", "parentType", "typeName", "title", "englishTitle", "required",
            "visible", "maxCount", "options", "kind", "searchText", "hasOptions").contains(key);
        if ("option".equals(root)) return java.util.Arrays.asList(
            "id", "title", "englishTitle", "quantity", "searchText", "hasOptions").contains(key);
        if ("input".equals(root)) return "serial".equals(key);
        if ("identity".equals(root)) return java.util.Arrays.asList(
            "templateId", "expectedStep", "warehouseId", "sku").contains(key);
        return false;
    }

    private static void validateDynamicSearchAttributes(Object raw, String path,
                                                        List<String> errors) {
        if (!(raw instanceof JSONArray)) {
            dynamicError(errors, path);
            return;
        }
        JSONArray values = (JSONArray) raw;
        Set<String> allowed = new LinkedHashSet<>(java.util.Arrays.asList(
            "id", "type", "parentType", "typeName", "title", "englishTitle"));
        Set<String> seen = new LinkedHashSet<>();
        if (values.length() > allowed.size()) dynamicError(errors, path);
        for (int i = 0; i < values.length(); i++) {
            Object value = values.opt(i);
            if (!(value instanceof String) || !allowed.contains(value)
                    || !seen.add((String) value)) {
                dynamicError(errors, path + "[" + i + "]");
            }
        }
    }

    private static void validateDynamicLiteral(Object value, String path, int depth,
                                               int[] count, List<String> errors) {
        if (depth > 8 || ++count[0] > 256) {
            dynamicError(errors, path);
            return;
        }
        if (value == null || value == JSONObject.NULL || value instanceof Boolean
                || finiteNumber(value)) return;
        if (value instanceof String) {
            if (((String) value).length() > 4096) dynamicError(errors, path);
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.length() > 256) dynamicError(errors, path);
            for (int i = 0; i < Math.min(array.length(), 256); i++) {
                validateDynamicLiteral(array.opt(i), path + "[" + i + "]", depth + 1,
                    count, errors);
            }
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.length() > 256) dynamicError(errors, path);
            java.util.Iterator<String> keys = object.keys();
            int visited = 0;
            while (keys.hasNext() && visited++ < 256) {
                String key = keys.next();
                if (!safeDynamicId(key, true)) dynamicError(errors, path + "." + key);
                validateDynamicLiteral(object.opt(key), path + "." + key, depth + 1,
                    count, errors);
            }
            return;
        }
        dynamicError(errors, path);
    }

    private static void requireExactDynamicKeys(JSONObject object, String path,
                                                List<String> errors, String... keys) {
        requireAllowedDynamicKeys(object, path, errors, keys, keys);
    }

    private static void requireAllowedDynamicKeys(JSONObject object, String path,
                                                  List<String> errors, String[] allowed,
                                                  String[] required) {
        if (object == null) {
            dynamicError(errors, path);
            return;
        }
        Set<String> allowedSet = new LinkedHashSet<>(java.util.Arrays.asList(allowed));
        Set<String> present = new LinkedHashSet<>();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            present.add(key);
            if (!allowedSet.contains(key)) dynamicError(errors, path + "." + key);
        }
        for (String key : required) {
            if (!present.contains(key)) dynamicError(errors, path + "." + key);
        }
    }

    private static boolean dynamicCardinality(Object value) {
        return "exactly_one".equals(value) || "first_in_backend_order".equals(value);
    }

    private static boolean exactDynamicInteger(Object value, long expected) {
        return exactDynamicIntegerValue(value) && ((Number) value).longValue() == expected;
    }

    private static boolean exactDynamicIntegerValue(Object value) {
        if (!(value instanceof Number)) return false;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) && number == Math.rint(number)
            && number >= Long.MIN_VALUE && number <= Long.MAX_VALUE;
    }

    private static boolean finiteNumber(Object value) {
        return value instanceof Number && Double.isFinite(((Number) value).doubleValue());
    }

    private static boolean safeDynamicId(String value, boolean allowDot) {
        if (value == null || value.isEmpty() || value.length() > 128
                || "__proto__".equals(value) || "prototype".equals(value)
                || "constructor".equals(value)) return false;
        char first = value.charAt(0);
        if (!asciiLetter(first)) return false;
        for (int i = 1; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!asciiLetter(ch) && (ch < '0' || ch > '9') && ch != '_' && ch != '-'
                    && (!allowDot || ch != '.')) return false;
        }
        return true;
    }

    private static boolean asciiLetter(char ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
    }

    private static void dynamicError(List<String> errors, String path) {
        errors.add("backendAdapter." + path);
    }

    private static Set<String> dynamicPhotoAliases(JSONObject resolver) {
        Set<String> out = new LinkedHashSet<>();
        JSONArray rules = resolver == null ? null : resolver.optJSONArray("rules");
        for (int i = 0; rules != null && i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            JSONObject action = rule == null ? null : rule.optJSONObject("action");
            if (action == null || !"photo".equals(action.optString("type", ""))) continue;
            String source = action.optString("source", "").trim();
            if (!source.isEmpty()) out.add(source);
        }
        return out;
    }

    private static void requireMappedKeys(JSONObject mapping, String[] keys, String path,
                                          List<String> errors) {
        if (mapping == null) {
            errors.add("backendAdapter." + path);
            return;
        }
        for (String key : keys) {
            Object value = mapping.opt(key);
            if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
                errors.add("backendAdapter." + path + "." + key);
            }
        }
    }

    private static JSONObject copyObject(JSONObject value) {
        if (value == null) return new JSONObject();
        try {
            return new JSONObject(value.toString());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    static Object valueAt(Object root, String path) {
        if (root == null) return null;
        if (path == null || path.trim().isEmpty() || "$".equals(path.trim())) return root;
        Object current = root;
        for (String part : path.split("\\.")) {
            if (current instanceof JSONObject) current = ((JSONObject) current).opt(part);
            else return null;
            if (current == null || current == JSONObject.NULL) return current;
        }
        return current;
    }

    /** True when a configured dot path exists, even when its declared JSON value is null. */
    static boolean hasPath(Object root, String path) {
        if (root == null) return false;
        if (path == null || path.trim().isEmpty() || "$".equals(path.trim())) return true;
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof JSONObject)) return false;
            JSONObject object = (JSONObject) current;
            if (!object.has(part)) return false;
            current = object.opt(part);
            if (current == null && !object.has(part)) return false;
        }
        return true;
    }

    private static String text(JSONObject json, String key) {
        return json == null ? "" : json.optString(key, "").trim();
    }

    private static List<String> stringList(JSONArray array) {
        List<String> out = new ArrayList<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            Object raw = array.opt(i);
            if (!(raw instanceof String)) continue;
            String value = ((String) raw).trim();
            if (!value.isEmpty()) out.add(value);
        }
        return out;
    }

    private static List<Object> objectList(JSONArray array) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            Object value = array.opt(i);
            if (value != null && value != JSONObject.NULL) out.add(value);
        }
        return out;
    }

    private static <T> List<T> immutableList(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static Map<String, String> mappedFields(JSONObject json, String[] canonicalNames,
                                                     String prefix, List<String> errors) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null) {
            errors.add("backendAdapter." + prefix);
            return out;
        }
        for (String canonical : canonicalNames) {
            String value = text(json, canonical);
            require(errors, value, prefix + "." + canonical);
            if (!value.isEmpty()) out.put(canonical, value);
        }
        return out;
    }

    private static boolean containsConfiguredSubstring(String text, List<String> patterns) {
        String haystack = text == null ? "" : text.toLowerCase(java.util.Locale.US);
        if (haystack.isEmpty()) return false;
        for (String pattern : patterns) {
            if (haystack.contains(pattern.toLowerCase(java.util.Locale.US))) return true;
        }
        return false;
    }

    private static Set<String> stringSet(JSONArray array) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            Object value = array.opt(i);
            if (value != null && value != JSONObject.NULL) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) out.add(text);
            }
        }
        return out;
    }

    private static Set<String> businessValueSet(JSONArray array) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            Object value = array.opt(i);
            if (value instanceof String) {
                String text = ((String) value).trim();
                if (!text.isEmpty()) out.add(text);
            } else if (value instanceof Number || value instanceof Boolean) {
                out.add(String.valueOf(value).trim());
            }
        }
        return out;
    }

    private static Set<String> immutableSet(Set<String> values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static boolean valueMatches(Object value, Set<String> expected) {
        return value != null && value != JSONObject.NULL
            && expected.contains(String.valueOf(value).trim());
    }

    private static boolean hasExactKeys(JSONObject value, Set<String> expected) {
        if (value == null || expected == null || value.length() != expected.size()) return false;
        java.util.Iterator<String> keys = value.keys();
        while (keys.hasNext()) {
            if (!expected.contains(keys.next())) return false;
        }
        for (String key : expected) if (!value.has(key)) return false;
        return true;
    }

    private static int exactPositiveInteger(Object value) {
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) return 0;
        long number = ((Number) value).longValue();
        return number > 0L && number <= Integer.MAX_VALUE ? (int) number : 0;
    }

    private static void require(List<String> errors, String value, String suffix) {
        if (value == null || value.isEmpty()) errors.add("backendAdapter." + suffix);
    }

    private static void require(List<String> errors, Set<String> value, String suffix) {
        if (value == null || value.isEmpty()) errors.add("backendAdapter." + suffix);
    }

    private static void require(List<String> errors, List<String> value, String suffix) {
        if (value == null || value.isEmpty()) errors.add("backendAdapter." + suffix);
    }

    private static void requireDistinct(List<String> errors, String suffix, String... values) {
        if (!areDistinctNonEmpty(values)) errors.add("backendAdapter." + suffix);
    }

    private static boolean areDistinctNonEmpty(String... values) {
        Set<String> seen = new LinkedHashSet<>();
        if (values == null) return false;
        for (String value : values) {
            if (value == null || value.isEmpty() || !seen.add(value)) return false;
        }
        return true;
    }

    private static String stripTrailingSlash(String value) {
        String out = value == null ? "" : value;
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }
}
