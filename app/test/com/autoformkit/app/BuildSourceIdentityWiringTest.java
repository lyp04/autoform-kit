package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Keeps the release diagnostic id independent from release-only provenance finalization. */
public class BuildSourceIdentityWiringTest {
    private static String buildScript() throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : new Path[]{
                cwd.resolve("app/build.gradle"), cwd.resolve("build.gradle")}) {
            if (!Files.isRegularFile(candidate)
                    || !candidate.getFileName().toString().equals("build.gradle")) {
                continue;
            }
            String source = new String(
                Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            if (source.contains("com.android.application")) {
                return source;
            }
        }
        throw new AssertionError("App build.gradle not found from " + cwd);
    }

    @Test
    public void diagnosticSourceIdUsesTheStableAppTreeInsteadOfCommitMetadata()
            throws Exception {
        String source = buildScript();
        assertTrue(source.contains(
            "[\"git\", \"rev-parse\", \"HEAD:app\"]"));
        assertFalse(source.contains(
            "[\"git\", \"rev-parse\", \"--short\", \"HEAD\"]"));
        assertFalse(source.contains("--short=12"));
        assertTrue(source.contains(
            "buildConfigField \"String\", \"GIT_HEAD\", \"\\\"\" + gitHead + \"\\\"\""));
    }
}
