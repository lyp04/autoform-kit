package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the boundary between side-effect-free UI validation and strict submit resolution. */
public class AlternateEntryUiPreflightWiringTest {
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
    public void uiRefreshUsesSyntheticPreflightButSubmissionRequiresLiveOverrides()
            throws Exception {
        String source = mainActivitySource();
        String preflight = section(source,
            "private AlternateEntryRules.Resolution preflightAlternateEntry(\n"
                + "            JSONObject sourceProfile, JSONArray catalog",
            "private JSONArray alternateEntrySources(");
        assertTrue(preflight.contains("AlternateEntryRules.resolveForUiPreflight("));
        assertFalse(preflight.contains("AlternateEntryRules.resolve("));

        String submit = section(source,
            "private void submitAlternateEntry()",
            "private JSONObject resolveAlternateEntryDynamicOverrides(");
        assertTrue(submit.contains("resolveAlternateEntryDynamicOverrides("));
        assertTrue(submit.contains(
            "toggleSnapshot,\n                    dynamicOverrides);"));
        assertFalse(submit.contains("resolveForUiPreflight("));
    }
}
