package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BackendSessionErrorsTest {
    @Test
    public void recognizesOnlyPanelConfiguredSessionSignals() {
        BackendSessionErrors.Policy policy = new BackendSessionErrors.Policy(
                Arrays.asList(401, 403),
                Arrays.asList(90001, "SESSION_REVOKED"),
                Arrays.asList("signed in on another workstation", "session expired",
                    "access revoked by administrator"));

        assertTrue(BackendSessionErrors.isInvalidMessage(
                "This account was signed in on another workstation.", policy));
        assertTrue(BackendSessionErrors.isInvalidMessage("Session expired; please log in again", policy));
        assertTrue(BackendSessionErrors.isInvalidHttpStatus(401, policy));
        assertTrue(BackendSessionErrors.isInvalidHttpStatus(403, policy));
        assertTrue(BackendSessionErrors.isInvalidApiCode(90001, policy));
        assertTrue(BackendSessionErrors.isInvalidApiCode("90001", policy));
        assertTrue(BackendSessionErrors.isInvalidApiCode("SESSION_REVOKED", policy));
    }

    @Test
    public void doesNotTreatOrdinaryLoginOrBusinessErrorsAsSessionKick() {
        BackendSessionErrors.Policy policy = BackendSessionErrors.Policy.empty();
        assertFalse(BackendSessionErrors.isInvalidMessage("Login failed: captcha is incorrect", policy));
        assertFalse(BackendSessionErrors.isInvalidMessage("Image upload failed: file too large", policy));
        assertFalse(BackendSessionErrors.isInvalidMessage(
                "This account was signed in on another workstation.", policy));
        assertFalse(BackendSessionErrors.isInvalidMessage("Session expired", policy));
        assertFalse(BackendSessionErrors.isInvalidHttpStatus(401, policy));
        assertFalse(BackendSessionErrors.isInvalidHttpStatus(403, policy));
        assertFalse(BackendSessionErrors.isInvalidHttpStatus(400, policy));
        assertFalse(BackendSessionErrors.isInvalidHttpStatus(500, policy));
        assertFalse(BackendSessionErrors.isInvalidApiCode(90001, policy));
    }

    @Test
    public void recognizesSessionExceptionThroughWrapper() {
        IOException wrapped = new IOException("upload failed",
                new BackendSessionErrors.SessionInvalidException("forced logout"));
        assertTrue(BackendSessionErrors.isSessionInvalid(wrapped));
        assertFalse(BackendSessionErrors.isSessionInvalid(new IOException("timeout")));
    }

    @Test
    public void structuredSessionSignalsIgnoreDataEchoesAndNestedMessageObjects()
            throws Exception {
        List<String> errors = new ArrayList<>();
        BackendAdapter.Response response = BackendAdapter.Response.from(new JSONObject()
            .put("codeField", "status")
            .put("dataField", "result")
            .put("messageFields", new JSONArray().put("message").put("error.message"))
            .put("successValues", new JSONArray().put("ok")), errors);
        assertTrue(errors.toString(), errors.isEmpty());
        BackendSessionErrors.Policy policy = new BackendSessionErrors.Policy(
            Arrays.asList("SESSION-DEMO"), Arrays.asList("sign in again"));

        JSONObject echoed = new JSONObject()
            .put("status", "BUSINESS-ERROR")
            .put("result", new JSONObject().put("requestEcho", "please sign in again"))
            .put("requestEcho", "please sign in again");
        assertFalse(BackendSessionErrors.isInvalidStructuredResponse(
            echoed, response, policy));

        JSONObject nestedConfiguredPath = new JSONObject()
            .put("status", "BUSINESS-ERROR")
            .put("message", new JSONObject().put("requestEcho", "please sign in again"));
        assertTrue(response.hasConfiguredMessage(nestedConfiguredPath));
        assertTrue(response.configuredMessage(nestedConfiguredPath).isEmpty());
        assertFalse(BackendSessionErrors.isInvalidStructuredResponse(
            nestedConfiguredPath, response, policy));

        assertTrue(BackendSessionErrors.isInvalidStructuredResponse(
            new JSONObject().put("status", "BUSINESS-ERROR")
                .put("message", "Please sign in again"),
            response, policy));
        assertTrue(BackendSessionErrors.isInvalidStructuredResponse(
            new JSONObject().put("status", "BUSINESS-ERROR")
                .put("error", new JSONObject().put("message", "Please sign in again")),
            response, policy));
        assertTrue(BackendSessionErrors.isInvalidStructuredResponse(
            new JSONObject().put("status", "SESSION-DEMO"), response, policy));
    }
}
