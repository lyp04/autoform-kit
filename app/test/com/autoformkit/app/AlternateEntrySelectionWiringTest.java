package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guard that the durable preference is wired only into fresh UI selection. */
public class AlternateEntrySelectionWiringTest {
    private static String mainActivitySource() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path[] candidates = new Path[]{
            cwd.resolve("app/src/com/autoformkit/app/MainActivity.java"),
            cwd.resolve("src/com/autoformkit/app/MainActivity.java")
        };
        for (Path path : candidates) {
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("MainActivity source not found from " + cwd);
    }

    private static String section(String source, String start, String end) {
        int startAt = source.indexOf(start);
        int endAt = source.indexOf(end, startAt + start.length());
        assertTrue("missing start marker: " + start, startAt >= 0);
        assertTrue("missing end marker: " + end, endAt > startAt);
        return source.substring(startAt, endAt);
    }

    @Test
    public void exactDraftRestoreRunsBeforeFreshPresentationPreference() throws Exception {
        String page = section(mainActivitySource(),
            "private void showAlternateEntryPage(String entryId)",
            "private void showLockedAlternateEntryDraftDialog(String requestedEntryId)");
        int draft = page.indexOf("restoreStoredAlternateEntryDraft(requestedId)");
        int preference = page.indexOf("storedAlternateEntrySelection(requestedId)");
        assertTrue(draft >= 0);
        assertTrue(preference > draft);
        assertTrue(page.contains(
            "AlternateEntrySelectionState.pageSourceIndex("));
    }

    @Test
    public void successfulInitialAndSpinnerBindingsRememberBeforeRebuild() throws Exception {
        String page = section(mainActivitySource(),
            "private void showAlternateEntryPage(String entryId)",
            "private void showLockedAlternateEntryDraftDialog(String requestedEntryId)");
        assertTrue(page.contains(
            "rememberAlternateEntrySelection(requestedId,"));
        int spinnerBind = page.indexOf(
            "bindAlternateEntryForNewWork(next, nextEntry,");
        int spinnerRemember = page.indexOf(
            "rememberAlternateEntrySelection(alternateEntryId,", spinnerBind);
        int spinnerRebuild = page.indexOf(
            "showAlternateEntryPage(alternateEntryId);", spinnerRemember);
        assertTrue(spinnerBind >= 0);
        assertTrue(spinnerRemember > spinnerBind);
        assertTrue(spinnerRebuild > spinnerRemember);
    }

    @Test
    public void logoutAndSessionClearDoNotErasePresentationPreference() throws Exception {
        String source = mainActivitySource();
        String clear = section(source,
            "private void clearAlternateEntrySession(boolean deletePhotos)",
            "private JSONArray configuredAlternateEntries(JSONObject sourceProfile)");
        String logout = section(source,
            "private void logoutToSettings(boolean propagate)",
            "private void handleRemoteLogout(boolean firstHand)");
        assertFalse(clear.contains("AlternateEntrySelectionState.PREFERENCE_PREFIX"));
        assertFalse(clear.contains("last_alternate_entry_source"));
        assertFalse(logout.contains("AlternateEntrySelectionState.PREFERENCE_PREFIX"));
        assertFalse(logout.contains("last_alternate_entry_source"));
    }

    @Test
    public void leavingPageKeepsExactResultChoiceWithAnUnfinishedDraft() throws Exception {
        String exit = section(mainActivitySource(),
            "private void exitAlternateEntryPage()",
            "private void applyTypedAlternateEntrySerial()");
        int pending = exit.indexOf(
            "boolean keepBoundPendingData = hasAlternateEntryPendingData()");
        int durableSave = exit.indexOf(
            "keepBoundPendingData && !saveAlternateEntryDraft(true)", pending);
        int clearBranch = exit.indexOf("if (!keepBoundPendingData) {", durableSave);
        int clearToggles = exit.indexOf("alternateEntryToggleStates.clear()", clearBranch);

        assertTrue("pending draft state must be classified before exit", pending >= 0);
        assertTrue("SN/photos and their exact result choice must be durably saved",
            durableSave > pending);
        assertTrue("only an empty entry may clear the in-memory result choice",
            clearBranch > durableSave && clearToggles > clearBranch);
    }

    @Test
    public void resultPresetButtonsUseLocalizedBoundedResponsiveText() throws Exception {
        String ui = section(mainActivitySource(),
            "private void refreshAlternateEntryUi()",
            "private void exitAlternateEntryPage()");
        assertTrue(ui.contains("policy.localizedLabel(lang)"));
        assertTrue(ui.contains("resolution.showResultPresetCodes"));
        assertTrue(ui.contains("resolution.splitResultPresetLabelsOnPlus"));
        assertTrue(ui.contains("formatResultPresetLabel("));
        assertTrue(ui.contains("codePointCount("));
        assertTrue(ui.contains("preset.setMaxLines(3)"));
        assertTrue(ui.contains("preset.setEllipsize(TextUtils.TruncateAt.END)"));
        assertTrue(ui.contains("resolution.resultPresetPolicies.size() > 3"));
        assertTrue(ui.contains("presets.setBaselineAligned(false)"));
        assertTrue(ui.contains("RadioGroup.LayoutParams(0, dp(88), 1f)"));
    }

    @Test
    public void allIdentifierInputsUsePanelOwnedLocalizedPlaceholders() throws Exception {
        String source = mainActivitySource();
        String helper = section(source,
            "private String inputPlaceholder(boolean secondary)",
            "private String requiredInputMessage(boolean secondary)");
        assertTrue(helper.contains(
            "localized(plugin, \"placeholder\", \"placeholderI18n\")"));
        assertTrue(helper.contains("hasCurrentTranslation"));
        assertTrue(helper.contains("t(\"input_placeholder\")"));

        String mainPage = section(source,
            "private void showFormPage()",
            "private void showAlternateEntryPage(String entryId)");
        assertTrue(mainPage.contains("edit(inputPlaceholder(false))"));
        assertTrue(mainPage.contains("edit(inputPlaceholder(true))"));
        assertTrue(mainPage.contains("edit(inputPlaceholder(pl, pLabel))"));

        String alternatePage = section(source,
            "private void showAlternateEntryPage(String entryId)",
            "private void showLockedAlternateEntryDraftDialog(String requestedEntryId)");
        assertTrue(alternatePage.contains("edit(inputPlaceholder(false))"));
    }
}
