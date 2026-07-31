package com.autoformkit.app;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level guard that keeps empty/partial Panel tuples outside both network workers. */
public class PanelConnectionNetworkGateWiringTest {
    @Test
    public void appConfigRejectsNonCompleteTupleBeforeContextAndThreadCreation()
            throws Exception {
        String source = source("app/src/com/autoformkit/app/AppConfig.java",
            "src/com/autoformkit/app/AppConfig.java");
        String refresh = between(source,
            "static void refresh(Context context, String panelBase, String key, Listener listener)",
            "static boolean hasUsablePayload(JSONObject json)");

        assertBefore(refresh,
            "PanelConnectionInputRules.allowsPanelNetwork(base, token)",
            "context.getApplicationContext()");
        assertBefore(refresh,
            "PanelConnectionInputRules.allowsPanelNetwork(base, token)",
            "new Thread(");
        assertBefore(refresh,
            "PanelConnectionInputRules.allowsPanelNetwork(base, token)",
            "get(base + CONFIG_PATH, token)");
    }

    @Test
    public void catalogRejectsNonCompleteTupleBeforeWorkerAndEveryRequestPath()
            throws Exception {
        String source = source("app/src/com/autoformkit/app/FormCatalogManager.java",
            "src/com/autoformkit/app/FormCatalogManager.java");
        String check = between(source,
            "private boolean check(boolean force, Listener listener)",
            "private void sync(Config config)");
        assertBefore(check,
            "PanelConnectionInputRules.allowsPanelNetwork(",
            "checkedThisProcess = true");
        assertBefore(check,
            "PanelConnectionInputRules.allowsPanelNetwork(",
            "new Thread(");

        String sync = between(source,
            "private void sync(Config config)",
            "private static void notifyFinished(");
        assertBefore(sync,
            "PanelConnectionInputRules.allowsPanelNetwork(",
            "getText(");
        assertBefore(sync,
            "PanelConnectionInputRules.allowsPanelNetwork(",
            "getBytes(");
    }

    private static String source(String repositoryPath, String modulePath) throws Exception {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : new Path[]{cwd.resolve(repositoryPath), cwd.resolve(modulePath)}) {
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("source not found: " + repositoryPath);
    }

    private static String between(String value, String startMarker, String endMarker) {
        int start = value.indexOf(startMarker);
        int end = value.indexOf(endMarker, start + startMarker.length());
        if (start < 0 || end <= start) throw new AssertionError("source markers not found");
        return value.substring(start, end);
    }

    private static void assertBefore(String value, String first, String second) {
        int firstIndex = value.indexOf(first);
        int secondIndex = value.indexOf(second);
        assertTrue("missing marker: " + first, firstIndex >= 0);
        assertTrue("missing marker: " + second, secondIndex >= 0);
        assertTrue("expected ordering", firstIndex < secondIndex);
    }
}
