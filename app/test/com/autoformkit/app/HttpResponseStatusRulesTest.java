package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HttpResponseStatusRulesTest {
    @Test
    public void structuredBusinessErrorsStillReachTheJsonClassifier() {
        for (int status : new int[]{400, 403, 404, 409, 422, 429, 500, 501}) {
            assertEquals(HttpResponseStatusRules.Action.PARSE_JSON,
                HttpResponseStatusRules.beforeJson(status, false, true));
        }
    }

    @Test
    public void sessionAndGatewayStatusesRemainFailClosedBeforeParsing() {
        assertEquals(HttpResponseStatusRules.Action.SESSION_INVALID,
            HttpResponseStatusRules.beforeJson(401, true, true));
        for (int status : new int[]{502, 503, 504}) {
            assertTrue(HttpResponseStatusRules.isTransientGateway(status));
            assertEquals(HttpResponseStatusRules.Action.TRANSIENT_GATEWAY,
                HttpResponseStatusRules.beforeJson(status, false, true));
        }
        assertFalse(HttpResponseStatusRules.isTransientGateway(500));
    }

    @Test
    public void everyRedirectStatusIsRejectedBeforeSessionAndBodyRules() {
        for (int status = 300; status <= 399; status++) {
            assertTrue(HttpResponseStatusRules.isRedirect(status));
            assertEquals(HttpResponseStatusRules.Action.REDIRECT,
                HttpResponseStatusRules.beforeJson(status, false, true));
            assertEquals(HttpResponseStatusRules.Action.REDIRECT,
                HttpResponseStatusRules.beforeJson(status, false, false));
            assertEquals(HttpResponseStatusRules.Action.REDIRECT,
                HttpResponseStatusRules.beforeJson(status, true, true));
        }
        assertFalse(HttpResponseStatusRules.isRedirect(299));
        assertFalse(HttpResponseStatusRules.isRedirect(400));
    }

    @Test
    public void absentBodyRemainsAnExplicitTransportFailure() {
        assertEquals(HttpResponseStatusRules.Action.MISSING_BODY,
            HttpResponseStatusRules.beforeJson(200, false, false));
        assertEquals(HttpResponseStatusRules.Action.MISSING_BODY,
            HttpResponseStatusRules.beforeJson(400, false, false));
    }

    @Test
    public void httpErrorsCanBeClassifiedButNeverPromotedToSuccess() {
        assertFalse(HttpResponseStatusRules.allowsConfiguredSuccess(199));
        assertTrue(HttpResponseStatusRules.allowsConfiguredSuccess(200));
        assertTrue(HttpResponseStatusRules.allowsConfiguredSuccess(299));
        assertFalse(HttpResponseStatusRules.allowsConfiguredSuccess(300));
        assertFalse(HttpResponseStatusRules.allowsConfiguredSuccess(399));
        assertFalse(HttpResponseStatusRules.allowsConfiguredSuccess(400));
        assertFalse(HttpResponseStatusRules.allowsConfiguredSuccess(500));
    }
}
