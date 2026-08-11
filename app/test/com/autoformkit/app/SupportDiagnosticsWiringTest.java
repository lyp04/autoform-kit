package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source guard for formal-build diagnostics and explicit obsolete-draft recovery. */
public class SupportDiagnosticsWiringTest {
    @Test
    public void supportUiIsInsideTheExistingFiveTapGateAndMasksTheKey() throws Exception {
        String settings = section(mainActivitySource(),
            "private void showSettingsPage()", "private void showFormPage()");
        int gate = settings.indexOf("if (showAdvancedSettings)");
        int support = settings.indexOf("support_diagnostics", gate);
        int end = settings.indexOf("setPageContentView(scroll)", gate);
        assertTrue(gate >= 0 && support > gate && support < end);
        assertTrue(settings.contains("InputType.TYPE_TEXT_VARIATION_PASSWORD"));
    }

    @Test
    public void supportReportDoesNotAppendConnectionOrBusinessValues() throws Exception {
        String report = section(mainActivitySource(),
            "private String panelSupportReport()",
            "private PanelSyncRecoveryStatus panelSyncRecoveryStatus()");
        assertFalse(report.contains("AppConfig.panelBase"));
        assertFalse(report.contains("AppConfig.catalogKey"));
        assertFalse(report.contains("savedAccount"));
        assertFalse(report.contains("currentProfileId"));
        assertFalse(report.contains("units"));
        assertTrue(report.contains("Diagnostics.sanitizeForSupport"));
    }

    @Test
    public void recoveryRequiresTwoDialogsAndPromotesOnlyAfterDraftClear() throws Exception {
        String source = mainActivitySource();
        String first = section(source, "private void promptObsoletePanelDraftRecovery()",
            "private void confirmObsoletePanelDraftRecovery(int expectedCount)");
        String second = section(source,
            "private void confirmObsoletePanelDraftRecovery(int expectedCount)",
            "private void performObsoletePanelDraftRecovery(int expectedCount)");
        String perform = section(source,
            "private void performObsoletePanelDraftRecovery(int expectedCount)",
            "private void notifyMissing(");
        assertTrue(first.contains("confirmObsoletePanelDraftRecovery(count)"));
        assertTrue(second.contains("performObsoletePanelDraftRecovery(currentCount)"));
        int clear = perform.indexOf("clearAllDrafts()");
        int promote = perform.indexOf("PanelPairCacheCoordinator.promoteCandidates(");
        assertTrue(clear >= 0 && promote > clear);
        assertTrue(perform.contains("status.eligible"));
    }

    @Test
    public void harmlessCurrentJournalsResolveBeforeTheAppWideRemoteGate() throws Exception {
        String safeBoundary = section(mainActivitySource(),
            "private boolean safeToInstallBoundPanelSnapshot()",
            "/**\n     * UI state is checked before taking HANDOFF_LOCK");
        int main = safeBoundary.indexOf("blockingMainSubmissionAttempt() != null");
        int alternate = safeBoundary.indexOf("blockingAlternateSubmissionAttempt() != null");
        int remote = safeBoundary.indexOf("RemoteSideEffectGate.blockingStatePresent(this)");
        assertTrue(main >= 0 && alternate > main && remote > alternate);
    }

    @Test
    public void releaseBuildIsNeverMadeDebuggable() throws Exception {
        String gradle = read("app/build.gradle");
        assertFalse(gradle.contains("release {\n            debuggable true"));
        assertTrue(gradle.contains("versionCode = (project.findProperty(\"versionCode\") ?: \"19\")"));
        assertTrue(gradle.contains("versionName = (project.findProperty(\"versionName\") ?: \"1.0.15\")"));
    }

    private static String mainActivitySource() throws Exception {
        return read("app/src/com/autoformkit/app/MainActivity.java");
    }

    private static String read(String relative) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path path = cwd.resolve(relative);
        if (!Files.isRegularFile(path) && relative.startsWith("app/")) {
            path = cwd.resolve(relative.substring("app/".length()));
        }
        if (!Files.isRegularFile(path)) throw new AssertionError("file not found: " + path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String section(String source, String start, String end) {
        int startAt = source.indexOf(start);
        int endAt = source.indexOf(end, startAt + start.length());
        if (startAt < 0 || endAt <= startAt) throw new AssertionError("source markers missing");
        return source.substring(startAt, endAt);
    }
}
