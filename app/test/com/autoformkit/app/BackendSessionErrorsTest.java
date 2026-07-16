package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

public class BackendSessionErrorsTest {
    @Test
    public void recognizesForcedOfflineResponseFromIssue12() {
        assertTrue(BackendSessionErrors.isInvalidMessage(
                "您的帐号在另一个地点登录，您已被强制下线，如非您本人操作，建议您修改账号密码。"));
        assertTrue(BackendSessionErrors.isInvalidMessage("Session expired; please log in again"));
        assertTrue(BackendSessionErrors.isInvalidHttpStatus(401));
        assertTrue(BackendSessionErrors.isInvalidHttpStatus(403));
        assertTrue(BackendSessionErrors.isInvalidApiCode(40002));
        assertTrue(BackendSessionErrors.isInvalidApiCode("40002"));
    }

    @Test
    public void doesNotTreatOrdinaryLoginOrBusinessErrorsAsSessionKick() {
        assertFalse(BackendSessionErrors.isInvalidMessage("登录失败：验证码错误"));
        assertFalse(BackendSessionErrors.isInvalidMessage("Image upload failed: file too large"));
        assertFalse(BackendSessionErrors.isInvalidHttpStatus(400));
        assertFalse(BackendSessionErrors.isInvalidHttpStatus(500));
        assertFalse(BackendSessionErrors.isInvalidApiCode(30002));
    }

    @Test
    public void recognizesSessionExceptionThroughWrapper() {
        IOException wrapped = new IOException("upload failed",
                new BackendSessionErrors.SessionInvalidException("forced logout"));
        assertTrue(BackendSessionErrors.isSessionInvalid(wrapped));
        assertFalse(BackendSessionErrors.isSessionInvalid(new IOException("timeout")));
    }
}
