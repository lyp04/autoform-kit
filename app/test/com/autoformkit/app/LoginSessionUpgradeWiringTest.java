package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the persisted login contract that an in-place production upgrade must retain. */
public class LoginSessionUpgradeWiringTest {
    private static String source(String repositoryPath, String modulePath) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : new Path[]{
                cwd.resolve(repositoryPath), cwd.resolve(modulePath)}) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("source not found: " + repositoryPath);
    }

    private static String method(String source, String signature, String nextSignature) {
        int start = source.indexOf(signature);
        int end = source.indexOf(nextSignature, start + signature.length());
        if (start < 0 || end <= start) {
            throw new AssertionError("method boundary not found: " + signature);
        }
        return source.substring(start, end);
    }

    @Test
    public void persistedLoginStorageNamesRemainUpgradeCompatible() throws Exception {
        String store = source(
            "app/src/com/autoformkit/app/SecureTokenStore.java",
            "src/com/autoformkit/app/SecureTokenStore.java");
        assertTrue(store.contains("KEY_ALIAS = \"autoform_token_key\""));
        assertTrue(store.contains("V1_TOKEN_CIPHER = \"tokenCipher\""));
        assertTrue(store.contains("V1_TOKEN_IV = \"tokenIv\""));
        assertTrue(store.contains("V1_PASSWORD_CIPHER = \"pwdCipher\""));
        assertTrue(store.contains("V1_PASSWORD_IV = \"pwdIv\""));
        assertTrue(store.contains("V1_PLAINTEXT_TOKEN = \"token\""));
        assertTrue(store.contains("V2_TOKEN_CIPHER = \"sessionV2TokenCipher\""));
        assertTrue(store.contains("V2_PASSWORD_CIPHER = \"sessionV2PasswordCipher\""));
        assertTrue(AppConfig.PREFS.equals("settings"));
    }

    @Test
    public void ordinaryColdStartNeverClearsTheSavedSession() throws Exception {
        String activity = source(
            "app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String application = source(
            "app/src/com/autoformkit/app/App.java",
            "src/com/autoformkit/app/App.java");
        String legacyMigration = source(
            "app/src/com/autoformkit/app/LegacyPanelCacheMigration.java",
            "src/com/autoformkit/app/LegacyPanelCacheMigration.java");
        String onCreate = method(activity,
            "protected void onCreate(Bundle savedInstanceState)",
            "protected void onSaveInstanceState(Bundle outState)");

        assertTrue(onCreate.contains("getSharedPreferences(\"settings\", MODE_PRIVATE)"));
        assertTrue(onCreate.contains("showSettingsPage()"));
        assertTrue(onCreate.contains("if (savedToken().isEmpty())"));
        assertFalse(onCreate.contains("SecureTokenStore.clear("));
        assertFalse(onCreate.contains("prefs.edit().clear("));
        assertFalse(application.contains("SecureTokenStore.clear("));
        assertFalse(legacyMigration.contains("SecureTokenStore.clear("));
    }

    @Test
    public void debugPreviewRemainsIsolatedFromTheProductionSession() throws Exception {
        String gradle = source("app/build.gradle", "build.gradle");
        String debugManifest = source(
            "app/src/debug/AndroidManifest.xml", "src/debug/AndroidManifest.xml");

        assertTrue(gradle.contains("applicationId \"com.autoformkit.app\""));
        assertTrue(gradle.contains("applicationIdSuffix \".debug\""));
        assertTrue(gradle.contains(
            "buildConfigField \"boolean\", \"CROSS_APP_SESSION_ENABLED\", \"true\""));
        assertTrue(gradle.contains(
            "buildConfigField \"boolean\", \"CROSS_APP_SESSION_ENABLED\", \"false\""));
        assertTrue(debugManifest.contains("android:name=\".SessionAuthProvider\""));
        assertTrue(debugManifest.contains("android:name=\".SessionEventReceiver\""));
        assertTrue(debugManifest.contains("tools:node=\"remove\""));
    }
}
