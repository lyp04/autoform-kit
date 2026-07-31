package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guard for the Panel-owned, cross-profile login summary. */
public class DailyStatsWiringTest {
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
    public void configuredSummaryUsesAllPickerVisibleProfilesAndPanelCardAppearance()
            throws Exception {
        String method = section(mainActivitySource(),
            "private View dailyStatsView()",
            "private LinearLayout.LayoutParams statCardParams()");
        assertTrue(method.contains(
            "DailyStatsRules.allProfilesGroups(catalogSettings)"));
        assertTrue(method.contains(
            "stats, profiles, group.optJSONArray(\"resultKeys\")"));
        assertTrue(method.contains(
            "statLabels.add(localized(group, \"label\", \"labelI18n\"))"));
        assertTrue(method.contains(
            "parseColor(group.optString(\"uiColor\", \"\"))"));
    }

    @Test
    public void unconfiguredCatalogRetainsCurrentProfileFallback() throws Exception {
        String method = section(mainActivitySource(),
            "private View dailyStatsView()",
            "private LinearLayout.LayoutParams statCardParams()");
        assertTrue(method.contains("byProfile.optJSONObject(currentProfileId())"));
        assertTrue(method.contains("statLabels.add(resultLabel(key))"));
        assertTrue(method.contains("statColors.add(gradeColor(key))"));
    }

    @Test
    public void statCardRendersItsResolvedLabelWithoutRebindingToCurrentProfile()
            throws Exception {
        String method = section(mainActivitySource(),
            "private View statCard(String label, int count, int color, int bgColor)",
            "private int lightenColor(int color)");
        assertTrue(method.contains("text(label, 13, true)"));
        assertFalse(method.contains("resultLabel("));
    }

    @Test
    public void resultButtonsPreferPanelOperatorLabelWithoutChangingLegacyLabel()
            throws Exception {
        String method = section(mainActivitySource(),
            "private String resultLabel(String key)",
            "private void updateGradeButtons()");
        int operatorAt = method.indexOf(
            "localized(entry, \"operatorLabel\", \"operatorLabelI18n\")");
        int legacyAt = method.indexOf(
            "localized(entry, \"label\", \"labelI18n\")");
        assertTrue(operatorAt >= 0);
        assertTrue(legacyAt > operatorAt);
        assertTrue(method.contains("if (!operatorLabel.isEmpty()) return operatorLabel;"));
    }

    @Test
    public void resultButtonsKeepPanelResultColorsWithProfileColorAsFallback()
            throws Exception {
        String source = mainActivitySource();
        String buttons = section(source,
            "private void ensureResultButtons()",
            "private JSONObject resultEntry(String key)");
        assertTrue(buttons.contains("radio.setText(resultLabel(key))"));

        String styling = section(source,
            "private void styleGradeButton(RadioButton radio, boolean selected)",
            "private boolean requiresSecondSn()");
        assertTrue(styling.contains("int color = gradeColor(grade)"));
        assertTrue(styling.contains("bg.setColor(selected ? color : gradeBgColor(grade))"));
        assertTrue(styling.contains("bg.setStroke(dp(2), selected ? color : lightenColor(color))"));
        int resultColorAt = styling.indexOf(
            "entry == null ? \"\" : entry.optString(\"uiColor\", \"\")");
        int profileColorAt = styling.indexOf(
            "profile == null ? \"\" : profile.optString(\"uiColor\", \"\")");
        assertTrue(resultColorAt >= 0);
        assertTrue(profileColorAt > resultColorAt);
    }
}
