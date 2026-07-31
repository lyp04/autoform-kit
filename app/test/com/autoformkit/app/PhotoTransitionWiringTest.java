package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guard for Panel-owned photo-box titles in both catalog generations. */
public class PhotoTransitionWiringTest {
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

    private static int count(String source, String needle) {
        int result = 0;
        for (int at = source.indexOf(needle); at >= 0;
                at = source.indexOf(needle, at + needle.length())) {
            result++;
        }
        return result;
    }

    @Test
    public void newCatalogTransitionUsesLocalizedPhotoSlotTitles() throws Exception {
        String source = mainActivitySource();
        String capture = section(source,
            "protected void onActivityResult(int requestCode, int resultCode, Intent data)",
            "private void handleOcrPhotoResult(int requestCode, int resultCode, Intent data)");
        assertTrue(capture.contains(
            "slotTitleForStep(slotStepBeforeSave),\n"
                + "                        slotTitleForStep(slotStepAfterSave)"));

        String stepTitle = section(source,
            "private String slotTitleForStep(int[] step)",
            "private String legacyPhotoSlotTitle(int slotIndex, String fallbackSide)");
        assertTrue(stepTitle.contains("JSONArray slots = photoSlots();"));
        assertTrue(stepTitle.contains(
            "slotTitleForField(slot.optString(\"field\"))"));

        String fieldTitle = section(source,
            "private String slotTitleForField(String field)",
            "private String workflowArtifactTitle(String key)");
        assertTrue(fieldTitle.contains(
            "String title = localized(slot, \"title\", \"titleI18n\");"));
        assertTrue(fieldTitle.contains("return title.isEmpty() ? field : title;"));
        assertFalse(fieldTitle.contains("sideName("));
    }

    @Test
    public void oldCatalogTransitionUsesLocalizedUploadFieldTitles() throws Exception {
        String source = mainActivitySource();
        String capture = section(source,
            "protected void onActivityResult(int requestCode, int resultCode, Intent data)",
            "private void handleOcrPhotoResult(int requestCode, int resultCode, Intent data)");
        assertTrue(capture.contains(
            "legacyPhotoSlotTitle(0, \"front\"),\n"
                + "                        legacyPhotoSlotTitle(1, \"back\")"));

        String legacyTitle = section(source,
            "private String legacyPhotoSlotTitle(int slotIndex, String fallbackSide)",
            "private String photoSlotTransitionNotice(String completedSlotTitle, String nextSlotTitle)");
        assertTrue(legacyTitle.contains("profile.optJSONArray(\"uploadFields\")"));
        assertTrue(legacyTitle.contains(
            "String title = localized(field, \"title\", \"titleI18n\");"));
        assertTrue(legacyTitle.contains(
            "return title.isEmpty() ? sideName(fallbackSide) : title;"));
        assertFalse(legacyTitle.contains("return \""));
    }

    @Test
    public void bothCatalogPathsUseOneGenericDynamicTemplate() throws Exception {
        String source = mainActivitySource();
        String capture = section(source,
            "protected void onActivityResult(int requestCode, int resultCode, Intent data)",
            "private void handleOcrPhotoResult(int requestCode, int resultCode, Intent data)");
        assertEquals(2, count(capture, "photoSlotTransitionNotice("));
        assertFalse(capture.contains("photoSlotTransitionNotice(\""));

        String notice = section(source,
            "private String photoSlotTransitionNotice(String completedSlotTitle, String nextSlotTitle)",
            "private int[] nextSlotStep()");
        assertTrue(notice.contains(
            "PhotoTransitionRules.formatSlotTransitionNotice(\n"
                + "            t(\"photo_slot_transition\"), completedSlotTitle, nextSlotTitle)"));
        assertEquals(3, count(source, "case \"photo_slot_transition\""));
        assertTrue(source.contains(
            "case \"photo_slot_transition\": return \"%1$s已拍完，开始拍%2$s。\";"));
        assertTrue(source.contains(
            "case \"photo_slot_transition\": return \"%1$s is complete. Start %2$s.\";"));
        assertTrue(source.contains(
            "case \"photo_slot_transition\": return \"%1$s completado. Empiece con %2$s.\";"));
        assertFalse(source.contains("正面已拍完，开始拍反面"));
    }
}
