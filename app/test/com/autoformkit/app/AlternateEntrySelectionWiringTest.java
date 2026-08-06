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
}
