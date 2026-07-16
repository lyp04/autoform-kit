package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

public class BackendSessionErrorsTest {
    @Test
    public void recognizesGenericAndPanelConfiguredSessionSignals() {
        BackendSessionErrors.Policy policy = new BackendSessionErrors.Policy(
                Arrays.asList(90001, "SESSION_REVOKED"),
                Arrays.asList("signed in on another workstation", "access revoked by administrator"));

        assertTrue(BackendSessionErrors.isInvalidMessage(
                "This account was signed in on another workstation.", policy));
        assertTrue(BackendSessionErrors.isInvalidMessage("Session expired; please log in again", policy));
        assertTrue(BackendSessionErrors.isInvalidHttpStatus(401));
        assertTrue(BackendSessionErrors.isInvalidHttpStatus(403));
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
        assertFalse(BackendSessionErrors.isInvalidHttpStatus(400));
        assertFalse(BackendSessionErrors.isInvalidHttpStatus(500));
        assertFalse(BackendSessionErrors.isInvalidApiCode(90001, policy));
    }

    @Test
    public void recognizesSessionExceptionThroughWrapper() {
        IOException wrapped = new IOException("upload failed",
                new BackendSessionErrors.SessionInvalidException("forced logout"));
        assertTrue(BackendSessionErrors.isSessionInvalid(wrapped));
        assertFalse(BackendSessionErrors.isSessionInvalid(new IOException("timeout")));
    }
}
