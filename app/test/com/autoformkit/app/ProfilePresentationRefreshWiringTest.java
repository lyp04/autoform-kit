package com.autoformkit.app;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Guards profile changes that must rebuild controls whose labels, hints, scanners and extra
 * identifier rows are all owned by the selected Panel profile.
 */
public class ProfilePresentationRefreshWiringTest {
    private static String mainActivitySource() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : new Path[]{
                cwd.resolve("app/src/com/autoformkit/app/MainActivity.java"),
                cwd.resolve("src/com/autoformkit/app/MainActivity.java")}) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("MainActivity.java not found from " + cwd);
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing start marker " + start, from >= 0);
        assertTrue("missing end marker " + end, to > from);
        return source.substring(from, to);
    }

    @Test
    public void interactiveMainProfileChangeRebuildsThenRestoresTheProfileDraft()
            throws Exception {
        String showForm = section(mainActivitySource(),
            "private void showFormPage(boolean promptSavedDraft)",
            "private void bounceProfileSelection(int lockedIndex)");
        int listenerAt = showForm.indexOf(
            "profileSpinner.setOnItemSelectedListener(");
        int selectedAt = showForm.indexOf(
            "profile = profiles.getJSONObject(position);", listenerAt);
        int rebuildAt = showForm.indexOf("showFormPage(false, false);", selectedAt);
        int restoreAt = showForm.indexOf(
            "restoreCurrentProfileDraftOrEmpty();", rebuildAt);

        assertTrue("main profile listener is missing", listenerAt >= 0);
        assertTrue("selected profile must be bound before rebuilding its presentation",
            selectedAt > listenerAt && rebuildAt > selectedAt);
        assertTrue("draft restore must run after the new controls exist so its photo order wins",
            restoreAt > rebuildAt);
    }

    @Test
    public void crossProfileDraftRestoreRebuildsButSameProfileRestoreDoesNotNeedTo()
            throws Exception {
        String restore = section(mainActivitySource(),
            "private void restoreDraft(JSONObject draft) throws JSONException",
            "private int restoreDraftContents(JSONObject draft) throws JSONException");
        int currentIdAt = restore.indexOf("currentProfileId()");
        int targetIdAt = restore.indexOf(
            "String profileId = prepared.optString(\"profileId\", \"\");");
        int bindAt = restore.indexOf(
            "profile = profiles.getJSONObject(profileIndex);");
        int saveSelectionAt = restore.indexOf("saveLastProfile();", bindAt);
        int changedAt = restore.indexOf("profileChanged");
        int rebuildAt = restore.indexOf("showFormPage(false, false);", bindAt);
        int contentsAt = restore.indexOf(
            "restorePreparedDraftContents(prepared);", rebuildAt);

        assertTrue("restore must snapshot whether the rendered profile changes",
            currentIdAt >= 0 && targetIdAt >= 0 && changedAt >= 0
                && currentIdAt < bindAt && changedAt < bindAt);
        assertTrue("target selection must be saved before rebuilding its controls",
            bindAt > targetIdAt && saveSelectionAt > bindAt && rebuildAt > saveSelectionAt);
        assertTrue("snapshot contents must restore after rebuild so saved photo order wins",
            contentsAt > rebuildAt);
        String rebuildGuard = restore.substring(bindAt, rebuildAt);
        assertTrue("same-profile restore must not rebuild needlessly",
            rebuildGuard.contains("profileChanged"));
    }

    @Test
    public void internalPresentationRebuildSkipsCatalogReloadButNormalEntryKeepsIt()
            throws Exception {
        String source = mainActivitySource();
        String overloads = section(source,
            "private void showFormPage()",
            "private void bounceProfileSelection(int lockedIndex)");
        assertTrue("normal form entry must retain catalog reload semantics",
            overloads.contains("showFormPage(promptSavedDraft, true);"));
        assertTrue("the internal rebuild overload must make reload intent explicit",
            overloads.contains(
                "private void showFormPage(boolean promptSavedDraft, boolean reloadCatalog)"));
        assertTrue("catalog reload must be guarded for internal presentation-only rebuilds",
            overloads.contains("if (reloadCatalog) reloadCatalogProfiles();"));
    }

    @Test
    public void alternateEntryProfileChangeStillRebindsThenRebuildsItsWholePage()
            throws Exception {
        String alternate = section(mainActivitySource(),
            "alternateEntryProfileSpinner.setOnItemSelectedListener(",
            "alternateEntryProfileSpinner.post(() -> selectionReady[0] = true);");
        int bindAt = alternate.indexOf(
            "bindAlternateEntryForNewWork(next, nextEntry,");
        int rebuildAt = alternate.indexOf(
            "showAlternateEntryPage(alternateEntryId);", bindAt);

        assertTrue("alternate entry must rebind the newly selected source profile",
            bindAt >= 0);
        assertTrue("alternate entry labels/controls require a full page rebuild",
            rebuildAt > bindAt);
    }

    @Test
    public void bothMainIdentifierRowsAreBuiltFromPanelOwnedPresentation()
            throws Exception {
        String showForm = section(mainActivitySource(),
            "private void showFormPage(boolean promptSavedDraft)",
            "private void bounceProfileSelection(int lockedIndex)");
        assertTrue(showForm.contains(
            "capturePanel.addView(compactLabel(primaryInputLabel()));"));
        assertTrue(showForm.contains("snEdit = edit(inputPlaceholder(false));"));
        assertTrue(showForm.contains(
            "baseLabel = compactLabel(secondaryInputLabel());"));
        assertTrue(showForm.contains("baseSnEdit = edit(inputPlaceholder(true));"));
        assertTrue(showForm.contains("scanPrompt(false)"));
        assertTrue(showForm.contains("scanPrompt(true)"));
    }

    @Test
    public void savedDraftSummariesResolveLabelsFromTheDraftsOwnProfile()
            throws Exception {
        String summary = section(mainActivitySource(),
            "private String draftUnitSummary(JSONObject draft)",
            "private void deletePhoto(UnitRecord unit, String side)");
        assertTrue(summary.contains(
            "JSONObject draftProfile = uniqueProfile(allProfiles, draftProfileId);"));
        assertTrue(summary.contains(
            "String draftPrimaryLabel = inputLabel(draftProfile, false);"));
        assertTrue(summary.contains(
            "String draftSecondaryLabel = inputLabel(draftProfile, true);"));
        assertTrue(summary.contains("+ draftPrimaryLabel + \"=\" + sn"));
        assertTrue(summary.contains("draftSecondaryLabel + \"=\" + base"));
        assertTrue("other-profile drafts must not borrow the active profile label",
            !summary.contains("secondaryInputLabel()"));
        assertTrue("operator summaries must not restore a hard-coded primary label",
            !summary.contains("\" SN=\""));
    }
}
