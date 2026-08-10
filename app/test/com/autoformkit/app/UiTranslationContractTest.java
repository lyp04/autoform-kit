package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Guards the hand-written App UI language tables against silent fallback to Chinese. */
public class UiTranslationContractTest {
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

    private static Set<String> keys(String source) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("case \\\"([^\\\"]+)\\\"").matcher(source);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    @Test
    public void englishAndSpanishTablesMatchEveryChineseUiKey() throws Exception {
        String source = mainActivitySource();
        String zh = section(source, "private String zh(String key)",
            "private String en(String key)");
        String en = section(source, "private String en(String key)",
            "private String es(String key)");
        String es = section(source, "private String es(String key)",
            "private static List<String> extractOcrCandidates");

        assertEquals(keys(zh), keys(en));
        assertEquals(keys(zh), keys(es));
        assertFalse("English UI table contains a Han character",
            Pattern.compile("[\\u3400-\\u9FFF]").matcher(en).find());
        assertFalse("Spanish UI table contains a Han character",
            Pattern.compile("[\\u3400-\\u9FFF]").matcher(es).find());
    }
}
