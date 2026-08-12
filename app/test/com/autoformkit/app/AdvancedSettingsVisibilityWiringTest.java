package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guard that keeps the maintenance UI behind the English five-tap rule. */
public class AdvancedSettingsVisibilityWiringTest {
    @Test
    public void englishButtonFeedsTheFiveTapHandlerBeforeChangingLanguage() throws Exception {
        String source = mainActivitySource();
        String languagePanel = section(source,
            "LinearLayout languagePanel = panel();", "LinearLayout loginPanel = panel();");
        assertTrue(languagePanel.contains(
            "languageLabel(\"en\"), v -> {\n"
                + "            handleEnglishLanguageTap();\n"
                + "            if (!\"en\".equals(lang)) switchLanguage(\"en\");"));
    }

    @Test
    public void completePanelHidesConnectionAndBothLogsTogether() throws Exception {
        String source = mainActivitySource();
        String settings = section(source,
            "private void showSettingsPage()", "private void showFormPage()");
        int gate = settings.indexOf("if (showAdvancedSettings)");
        int panel = settings.indexOf("LinearLayout panelPanel = panel()", gate);
        int liveLog = settings.indexOf("logText = text", gate);
        int crashLog = settings.indexOf("crashLogText = text", gate);
        int gateEnd = settings.indexOf("setPageContentView(scroll)", gate);

        assertTrue(gate >= 0);
        assertTrue(panel > gate && panel < gateEnd);
        assertTrue(liveLog > gate && liveLog < gateEnd);
        assertTrue(crashLog > gate && crashLog < gateEnd);
        assertFalse(settings.substring(0, gate).contains("LinearLayout panelPanel = panel()"));
    }

    @Test
    public void revealIsProcessLocalAndNeverWrittenToPreferences() throws Exception {
        String source = mainActivitySource();
        assertTrue(source.contains("private static boolean advancedSettingsRevealed = false;"));
        String handler = section(source,
            "private void handleEnglishLanguageTap()", "private void refreshUpdateChannelText()");
        assertTrue(handler.contains("advancedSettingsRevealed = true;"));
        assertFalse(handler.contains("prefs.edit()"));
    }

    @Test
    public void revealedFooterShowsSoftwareAndActivePanelVersionsAtTheBottom()
            throws Exception {
        String source = mainActivitySource();
        String settings = section(source,
            "private void showSettingsPage()", "private void showFormPage()");
        int gate = settings.indexOf("if (showAdvancedSettings)");
        int crashLog = settings.indexOf("root.addView(crashLogText)", gate);
        int footer = settings.indexOf("TextView versionFooter", gate);
        int gateEnd = settings.indexOf("setPageContentView(scroll)", gate);

        assertTrue(footer > crashLog && footer < gateEnd);
        assertTrue(settings.substring(footer, gateEnd).contains("BuildConfig.VERSION_NAME"));
        assertTrue(settings.substring(footer, gateEnd).contains("BuildConfig.VERSION_CODE"));
        assertTrue(settings.substring(footer, gateEnd).contains("activeCatalogVersion"));
    }

    private static String mainActivitySource() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : new Path[]{
                cwd.resolve("app/src/com/autoformkit/app/MainActivity.java"),
                cwd.resolve("src/com/autoformkit/app/MainActivity.java")}) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("MainActivity source not found from " + cwd);
    }

    private static String section(String source, String start, String end) {
        int startAt = source.indexOf(start);
        int endAt = source.indexOf(end, startAt + start.length());
        if (startAt < 0 || endAt <= startAt) throw new AssertionError("source markers not found");
        return source.substring(startAt, endAt);
    }
}
