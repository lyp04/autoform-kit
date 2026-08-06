package com.autoformkit.app;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the exact source-profile identity carried by an unfinished independent-entry draft. */
public class AlternateEntryDraftSourceRestoreWiringTest {
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
    public void durableDraftCommitAtomicallyCarriesItsExactSourceSelection() throws Exception {
        String save = section(mainActivitySource(),
            "private boolean saveAlternateEntryDraft(boolean durable)",
            "private void persistAlternateEntryDraftBestEffort()");
        int draftAt = save.indexOf("AlternateEntryDraftState draft =");
        int stageAt = save.indexOf(
            "stageAlternateEntrySelection(editor, draft.entryId, draft.sourceProfileId);");
        int commitAt = save.indexOf("editor.commit()", stageAt);

        assertTrue("draft must exist before its source selection is staged", draftAt >= 0);
        assertTrue("the same preference editor must carry the draft's exact source",
            stageAt > draftAt);
        assertTrue("source selection must be staged before the durable draft commit",
            commitAt > stageAt);
    }

    @Test
    public void scannerAndPhotoResultCommitCarriesTheSameExactSourceSelection()
            throws Exception {
        String commit = section(mainActivitySource(),
            "private boolean commitMaterializedAlternateEntryReservationLocked(",
            "private boolean materializeAlternateEntrySerial(");
        int draftAt = commit.indexOf("AlternateEntryDraftState draft =");
        int stageAt = commit.indexOf(
            "stageAlternateEntrySelection(editor, draft.entryId, draft.sourceProfileId);");
        int commitAt = commit.indexOf("editor.commit()", stageAt);

        assertTrue("materialized result must have a bound draft", draftAt >= 0);
        assertTrue("the result transaction must stage the exact source", stageAt > draftAt);
        assertTrue("the result, draft and source must share the durable commit",
            commitAt > stageAt);
    }

    @Test
    public void exactDraftRestoreOverwritesAnyOlderPresentationChoice() throws Exception {
        String restore = section(mainActivitySource(),
            "private int restoreStoredAlternateEntryDraft(String requestedEntryId)",
            "private void suspendAlternateEntrySession()");
        int exactBindAt = restore.indexOf("bindAlternateEntry(source, entry, allProfiles);");
        int rememberAt = restore.indexOf(
            "rememberAlternateEntrySelection(draft.entryId, draft.sourceProfileId);");
        int successAt = restore.lastIndexOf("return 1;");

        assertTrue("restore must bind the profile named by the draft", exactBindAt >= 0);
        assertTrue("a restored draft must replace a stale remembered/current/first choice",
            rememberAt > exactBindAt);
        assertTrue("the exact source must be durable before restore reports success",
            successAt > rememberAt);
    }

    @Test
    public void reusedDraftBindingDrivesTheActualSpinnerSelection() throws Exception {
        String page = section(mainActivitySource(),
            "private void showAlternateEntryPage(String entryId)",
            "private void showLockedAlternateEntryDraftDialog(String requestedEntryId)");
        String reuse = section(page, "if (reuseBinding) {", "} else {");
        int exactResolverAt = reuse.indexOf(
            "AlternateEntrySelectionState.pageSourceIndex(");
        int exactIdAt = reuse.indexOf("source.optString(\"id\", \"\")", exactResolverAt);
        int failClosedAt = reuse.indexOf("if (selected < 0)", exactIdAt);
        int spinnerAt = page.indexOf(
            "alternateEntryProfileSpinner.setSelection(selected);");

        assertTrue("reuse must resolve the selected row from the bound draft source",
            exactResolverAt >= 0 && exactIdAt > exactResolverAt);
        assertTrue("a missing exact source must not silently display row zero",
            failClosedAt > exactIdAt && !reuse.contains("selected = 0"));
        assertTrue("the resolved exact row must be the value applied to the real Spinner",
            spinnerAt > page.indexOf("if (reuseBinding) {"));
    }
}
