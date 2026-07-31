package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guard for the shared, profile-owned spinner appearance. */
public class ProfileSpinnerWiringTest {
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
    public void mainAndAlternateSpinnersUseTheSameProfileRenderer() throws Exception {
        String source = mainActivitySource();
        String main = section(source,
            "private void showFormPage(boolean promptSavedDraft)",
            "private void bounceProfileSelection(int lockedIndex)");
        assertTrue(main.contains(
            "profileSpinner.setAdapter(new ProfileSpinnerAdapter(profiles));"));

        String alternate = section(source,
            "private void showAlternateEntryPage(String entryId)",
            "private void showLockedAlternateEntryDraftDialog(String requestedEntryId)");
        assertTrue(alternate.contains(
            "new ProfileSpinnerAdapter(alternateEntrySourceProfiles)"));
        assertFalse(alternate.contains("ArrayAdapter<String> sourceAdapter"));
        assertFalse(alternate.contains("simple_spinner_item"));
    }

    @Test
    public void adapterKeepsTheExactSubsetOrderForItemsAndBothViews() throws Exception {
        String adapter = section(mainActivitySource(),
            "private class ProfileSpinnerAdapter extends BaseAdapter",
            "private int templateId() throws JSONException");
        assertTrue(adapter.contains("private final JSONArray spinnerProfiles;"));
        assertTrue(adapter.contains("return spinnerProfiles.length();"));
        assertTrue(adapter.contains("return spinnerProfiles.getJSONObject(position);"));
        assertTrue(adapter.contains(
            "profileSpinnerView(spinnerProfiles, position, false)"));
        assertTrue(adapter.contains(
            "profileSpinnerView(spinnerProfiles, position, true)"));
    }

    @Test
    public void sharedRendererReadsDisplayNameAndUiColorFromItsOwnArray() throws Exception {
        String renderer = section(mainActivitySource(),
            "private View profileSpinnerView(JSONArray spinnerProfiles, int position,",
            "private Integer parseColor(String value)");
        assertTrue(renderer.contains("profileDotColor(spinnerProfiles, position)"));
        assertTrue(renderer.contains("profileName(spinnerProfiles, position)"));
        assertTrue(renderer.contains("spinnerProfiles.getJSONObject(position)"));
        assertTrue(renderer.contains("item.optString(\"uiColor\", \"\")"));
        assertTrue(renderer.contains("item.optString(\"displayName\""));
    }

    @Test
    public void alternateSourceListPreservesTheCompletePanelProfileObjects() throws Exception {
        String sources = section(mainActivitySource(),
            "private JSONArray alternateEntrySources(String entryId)",
            "private int alternateEntrySourceIndex(JSONArray sources, String profileId)");
        assertTrue(sources.contains("JSONObject source = profiles.optJSONObject(i)"));
        assertTrue(sources.contains("sources.put(source)"));
    }
}
