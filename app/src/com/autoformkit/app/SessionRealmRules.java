package com.autoformkit.app;

import org.json.JSONObject;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure projection of the Panel-owned transport realm that may receive a saved login session.
 *
 * <p>The projection deliberately excludes form/profile/catalog display data and ordinary outcome,
 * retry and printing policies. Those values can change without moving the credential boundary.
 * Conversely, every endpoint the current app can call with {@code Authorization} is represented by
 * its complete resolved URL, including an empty value when that optional capability is absent.
 */
final class SessionRealmRules {
    private static final String REALM_DOMAIN = "autoform-kit/session-realm/v1";
    private static final List<String> CREDENTIAL_VALUE_KINDS = Collections.unmodifiableList(
        Arrays.asList("account", "userName"));
    private static final List<String> ENCRYPTED_RECORD_TYPES = Collections.unmodifiableList(
        Arrays.asList("password", "token"));

    /** Every endpoint name currently reachable through {@code Api.addHeaders}. Dynamic OCR is not. */
    static final List<String> AUTHORIZATION_ENDPOINT_KEYS = Collections.unmodifiableList(
        Arrays.asList(
            BackendAdapter.ENDPOINT_CAPTCHA,
            BackendAdapter.ENDPOINT_DETECTION_DATA,
            BackendAdapter.ENDPOINT_LABEL_RETRY,
            BackendAdapter.ENDPOINT_LOGIN,
            BackendAdapter.ENDPOINT_LOGIN_VERIFY,
            BackendAdapter.ENDPOINT_MESSAGE_LIST,
            BackendAdapter.ENDPOINT_PRINTER_STATE,
            BackendAdapter.ENDPOINT_SN_REPETITION,
            BackendAdapter.ENDPOINT_SUBMIT_ENTRY,
            BackendAdapter.ENDPOINT_TEMPLATE_DETAIL,
            BackendAdapter.ENDPOINT_UPLOAD_FILE,
            BackendAdapter.ENDPOINT_USER_INFO));

    private SessionRealmRules() {}

    /** Returns a canonical SHA-256 realm, or empty when the transport projection is not provable. */
    static String fingerprint(String connectionNamespace, JSONObject appConfig,
                              JSONObject catalogSettings) {
        try {
            JSONObject projection = projection(
                connectionNamespace, appConfig, catalogSettings);
            String result = MainDraftSnapshotRules.semanticSha256(projection);
            return validDigest(result) ? result : "";
        } catch (Exception invalid) {
            return "";
        }
    }

    /** Package-visible so tests can pin the realm schema, including its adapter version. */
    static JSONObject projection(String connectionNamespace, JSONObject appConfig,
                                 JSONObject catalogSettings) throws Exception {
        String connection = clean(connectionNamespace).toLowerCase(Locale.US);
        if (!connection.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("connection namespace is invalid");
        }
        BackendAdapter adapter = BackendAdapter.from(appConfig, catalogSettings);
        if (!adapter.isSupported()) {
            throw new IllegalArgumentException("backend adapter is unsupported");
        }

        JSONObject endpoints = new JSONObject();
        for (String key : AUTHORIZATION_ENDPOINT_KEYS) {
            String configured = adapter.endpoint(key);
            endpoints.put(key, configured.isEmpty() ? ""
                : normalizedHttpUrl(BackendAdapter.resolveEndpointUrl(
                    adapter.baseUrl, configured)));
        }

        JSONObject request = new JSONObject()
            .put("authScheme", clean(adapter.request.authScheme))
            .put("bodyEncoding", clean(adapter.request.bodyEncoding))
            .put("fingerprintHeader", clean(adapter.request.fingerprintHeader))
            .put("webAcceptLanguage", clean(adapter.request.webAcceptLanguage))
            .put("webUserAgent", clean(adapter.request.webUserAgent));

        JSONObject loginFields = new JSONObject();
        for (Map.Entry<String, String> field
                : new TreeMap<>(adapter.auth.loginFields).entrySet()) {
            loginFields.put(field.getKey(), clean(field.getValue()));
        }

        return new JSONObject()
            .put("domain", REALM_DOMAIN)
            .put("connectionNamespace", connection)
            .put("adapterVersion", adapter.version)
            .put("baseUrl", normalizedHttpUrl(new URL(adapter.baseUrl)))
            .put("authorizationEndpoints", endpoints)
            .put("request", request)
            .put("webOrigin", appConfig == null ? ""
                : clean(appConfig.optString("webOrigin", "")))
            .put("webReferer", appConfig == null ? ""
                : clean(appConfig.optString("webReferer", "")))
            .put("loginFields", loginFields);
    }

    /** Domain-separated digest for non-secret account/display-name values only. */
    static String credentialValueBindingSha256(String kind, String realmSha256,
                                               String webFingerprint, String sessionId,
                                               String value) {
        String valueKind = clean(kind);
        String realm = clean(realmSha256).toLowerCase(Locale.US);
        String fingerprint = clean(webFingerprint);
        String session = clean(sessionId);
        String actualValue = value == null ? "" : value;
        if (!CREDENTIAL_VALUE_KINDS.contains(valueKind)
                || !validDigest(realm) || fingerprint.length() < 16
                || !validSessionId(session)) return "";
        try {
            String result = MainDraftSnapshotRules.semanticSha256(new JSONObject()
                .put("domain", "autoform-kit/session-credential-value/v1/" + valueKind)
                .put("realmSha256", realm)
                .put("webFingerprint", fingerprint)
                .put("sessionId", session)
                .put("value", actualValue));
            return validDigest(result) ? result : "";
        } catch (Exception impossible) {
            return "";
        }
    }

    /** AES-GCM associated data; password/token values are never persisted as offline verifiers. */
    static byte[] credentialAad(String recordType, String realmSha256,
                                String webFingerprint, String sessionId) {
        String type = clean(recordType);
        String realm = clean(realmSha256).toLowerCase(Locale.US);
        String fingerprint = clean(webFingerprint);
        String session = clean(sessionId);
        if (!ENCRYPTED_RECORD_TYPES.contains(type) || !validDigest(realm)
                || fingerprint.length() < 16 || !validSessionId(session)) {
            throw new IllegalArgumentException("credential AAD context is invalid");
        }
        String framed = "autoform-kit/session-secret-aad/v2\n"
            + type.length() + ":" + type + "\n"
            + realm.length() + ":" + realm + "\n"
            + fingerprint.length() + ":" + fingerprint + "\n"
            + session.length() + ":" + session + "\n";
        return framed.getBytes(StandardCharsets.UTF_8);
    }

    static boolean validSessionId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{16,128}");
    }

    static boolean validDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String normalizedHttpUrl(URL url) throws Exception {
        if (url == null) throw new IllegalArgumentException("URL is required");
        URI uri = url.toURI().normalize();
        String scheme = clean(uri.getScheme()).toLowerCase(Locale.US);
        String host = clean(uri.getHost()).toLowerCase(Locale.US);
        if (!("https".equals(scheme) || "http".equals(scheme)) || host.isEmpty()) {
            throw new IllegalArgumentException("HTTP(S) URL is required");
        }
        int port = uri.getPort();
        if (("https".equals(scheme) && port == 443)
                || ("http".equals(scheme) && port == 80)) {
            port = -1;
        }
        StringBuilder out = new StringBuilder(scheme).append("://");
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null && !userInfo.isEmpty()) out.append(userInfo).append('@');
        if (host.indexOf(':') >= 0) out.append('[').append(host).append(']');
        else out.append(host);
        if (port >= 0) out.append(':').append(port);
        String path = uri.getRawPath();
        out.append(path == null || path.isEmpty() ? "/" : path);
        String query = uri.getRawQuery();
        if (query != null && !query.isEmpty()) out.append('?').append(query);
        return out.toString();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
