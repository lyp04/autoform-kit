package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

/** Source guards for fail-closed backend and dynamic-OCR redirect handling. */
public class HttpRedirectWiringTest {
    @Test
    public void authenticatedTransportDisablesRedirectsBeforeHeadersOrIo() throws Exception {
        String main = mainSource();
        String headers = between(main,
            "void addHeaders(HttpURLConnection conn, boolean webLoginClient, int connectTimeoutMs, int readTimeoutMs)",
            "JSONObject readJson(HttpURLConnection conn)");

        assertBefore(headers, "conn.setInstanceFollowRedirects(false)",
            "conn.setRequestProperty(\"Accept\"");
        assertBefore(headers, "conn.setInstanceFollowRedirects(false)",
            "conn.setRequestProperty(\"Authorization\"");

        for (String method : new String[]{"AuthState checkAuth(",
                "private JSONObject getJson(String path, String query, boolean webLoginClient,",
                "JSONObject postJson(", "JSONObject postEndpointJsonExact(",
                "JSONObject postForm(String path, JSONObject form, boolean webLoginClient)",
                "String uploadImage("}) {
            String body = methodBody(main, method);
            assertBefore(body, "addHeaders(conn,", firstIoMarker(body));
        }
    }

    @Test
    public void dynamicOcrRejectsRedirectsWithoutAddingBackendCredentials() throws Exception {
        String main = mainSource();
        String ocr = between(main,
            "JSONObject recognizeText(String recognizeTextUrl, File file,",
            "/**\n         * Preserve the signed v1 transport experience");

        assertBefore(ocr, "conn.setInstanceFollowRedirects(false)",
            "conn.getOutputStream()");
        // The explanatory comment intentionally names addHeaders(); reject only an executable
        // invocation line so documentation wording cannot make this source guard flaky.
        assertFalse(Pattern.compile("(?m)^\\s*addHeaders\\s*\\(").matcher(ocr).find());
        assertFalse(ocr.contains("Authorization"));
        assertFalse(ocr.contains("webFingerprint"));
        assertFalse(ocr.contains("webOrigin"));
        assertFalse(ocr.contains("webReferer"));
    }

    @Test
    public void redirectActionIsRejectedBeforeBodyParsing() throws Exception {
        String readJson = between(mainSource(),
            "JSONObject readJson(HttpURLConnection conn)",
            "boolean isSuccess(JSONObject body)");

        assertBefore(readJson, "HttpResponseStatusRules.Action.REDIRECT",
            "ByteArrayOutputStream output");
        assertBefore(readJson, "redirect rejected", "new JSONObject(text)");
    }

    private static String firstIoMarker(String body) {
        int output = body.indexOf("conn.getOutputStream()");
        int response = body.indexOf("conn.getResponseCode()");
        int readJson = body.indexOf("readJson(conn");
        int best = Integer.MAX_VALUE;
        String marker = null;
        if (output >= 0 && output < best) {
            best = output;
            marker = "conn.getOutputStream()";
        }
        if (response >= 0 && response < best) {
            best = response;
            marker = "conn.getResponseCode()";
        }
        if (readJson >= 0 && readJson < best) {
            marker = "readJson(conn";
        }
        if (marker == null) throw new AssertionError("network I/O marker missing");
        return marker;
    }

    private static String methodBody(String source, String marker) {
        int start = source.indexOf(marker);
        if (start < 0) throw new AssertionError("method marker missing: " + marker);
        int next = source.indexOf("\n        }\n", start);
        if (next < 0) throw new AssertionError("method end missing: " + marker);
        return source.substring(start, next + 11);
    }

    private static String mainSource() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : new Path[]{
                cwd.resolve("app/src/com/autoformkit/app/MainActivity.java"),
                cwd.resolve("src/com/autoformkit/app/MainActivity.java")}) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("MainActivity source not found");
    }

    private static String between(String value, String startMarker, String endMarker) {
        int start = value.indexOf(startMarker);
        int end = value.indexOf(endMarker, start + startMarker.length());
        if (start < 0 || end <= start) {
            throw new AssertionError("source markers not found: " + startMarker);
        }
        return value.substring(start, end);
    }

    private static void assertBefore(String value, String first, String second) {
        int firstIndex = value.indexOf(first);
        int secondIndex = value.indexOf(second);
        assertTrue("missing marker: " + first, firstIndex >= 0);
        assertTrue("missing marker: " + second, secondIndex >= 0);
        assertTrue("expected ordering: " + first + " before " + second,
            firstIndex < secondIndex);
    }
}
