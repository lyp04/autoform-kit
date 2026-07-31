package com.autoformkit.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Source-level contract for the browser -> memory broker -> form-owner pairing boundary. */
public class PanelPairingWiringTest {
    @Test
    public void manifestKeepsTheLauncherIdentityButHidesTheRuntimeFormOwner() throws Exception {
        String manifest = source("app/AndroidManifest.xml", "AndroidManifest.xml");
        String entry = between(manifest,
            "android:name=\".PanelPairingEntryActivity\"",
            "android:name=\".InternalMainActivity\"");
        String internalMain = between(manifest,
            "android:name=\".InternalMainActivity\"", "<activity-alias");
        String launcherAlias = between(manifest, "<activity-alias", "</activity-alias>");
        String internalSource = source(
            "app/src/com/autoformkit/app/InternalMainActivity.java",
            "src/com/autoformkit/app/InternalMainActivity.java");

        assertTrue(entry.contains("android:exported=\"true\""));
        assertTrue(entry.contains("android:noHistory=\"true\""));
        assertTrue(entry.contains("android.intent.action.VIEW"));
        assertTrue(entry.contains("android.intent.category.DEFAULT"));
        assertTrue(entry.contains("android.intent.category.BROWSABLE"));
        assertTrue(entry.contains("android:scheme=\"${applicationId}\""));
        assertTrue(entry.contains("android:host=\"pair\""));
        assertTrue(entry.contains("android:path=\"/v1\""));
        assertFalse(entry.contains("android.intent.action.MAIN"));
        assertFalse(entry.contains("http://"));
        assertFalse(entry.contains("https://"));

        assertTrue(internalMain.contains("android:exported=\"false\""));
        assertFalse(internalMain.contains("<intent-filter>"));
        assertTrue(internalSource.contains(
            "final class InternalMainActivity extends MainActivity"));
        String internalCreate = between(internalSource,
            "protected void onCreate(Bundle savedInstanceState)",
            "protected void onNewIntent(Intent intent)");
        assertBefore(internalCreate, "scrubExternalIntent(getIntent())",
            "super.onCreate(savedInstanceState)");
        assertTrue(internalSource.contains("intent.setDataAndType(null, null)"));
        assertTrue(internalSource.contains("intent.setClipData(null)"));
        assertTrue(internalSource.contains("intent.replaceExtras((Bundle) null)"));
        assertTrue(internalSource.contains("intent.setSelector(null)"));

        assertTrue(launcherAlias.contains("android:name=\".MainActivity\""));
        assertTrue(launcherAlias.contains("android:targetActivity=\".InternalMainActivity\""));
        assertTrue(launcherAlias.contains("android:exported=\"true\""));
        assertTrue(launcherAlias.contains("android.intent.action.MAIN"));
        assertTrue(launcherAlias.contains("android.intent.category.LAUNCHER"));
        assertFalse(launcherAlias.contains("android.intent.action.VIEW"));
    }

    @Test
    public void exportedEntryScrubsTheIntentAndNeverForwardsTheTicketOrReordersTheTask()
            throws Exception {
        String entry = source(
            "app/src/com/autoformkit/app/PanelPairingEntryActivity.java",
            "src/com/autoformkit/app/PanelPairingEntryActivity.java");

        assertBefore(entry, "incoming.getData()", "incoming.setData(null)");
        assertTrue(entry.contains("incoming.setData(null)"));
        assertTrue(entry.contains("incoming.setClipData(null)"));
        assertTrue(entry.contains("incoming.replaceExtras((Bundle) null)"));
        assertTrue(entry.contains("PanelPairingLinkRules.parse("));
        assertTrue(entry.contains("PanelPairingBroker.offer(request, invalid)"));
        assertTrue(entry.contains(
            "decision == PanelPairingBroker.LaunchDecision.LAUNCH_MAIN"));
        assertTrue(entry.contains("new Intent(this, MainActivity.class)"));
        assertTrue(entry.contains(".setAction(Intent.ACTION_MAIN)"));
        assertTrue(entry.contains(".addCategory(Intent.CATEGORY_LAUNCHER)"));
        assertTrue(entry.contains("PanelPairingBroker.releaseLaunchReservation()"));
        assertTrue(entry.contains("finish()"));

        // The sole setData call erases the source URI. The clean launcher Intent carries no data,
        // ClipData, extras, browser flags or long-lived credential.
        assertEquals(1, count(entry, ".setData("));
        assertFalse(entry.contains("putExtra("));
        assertFalse(entry.contains("request.ticket"));
        assertFalse(entry.contains("Intent.FLAG_ACTIVITY_REORDER_TO_FRONT"));
        assertFalse(entry.contains("Intent.FLAG_ACTIVITY_CLEAR_TOP"));
        assertFalse(entry.contains("Intent.FLAG_ACTIVITY_SINGLE_TOP"));
        assertFalse(entry.contains("setFlags("));
        assertFalse(entry.contains("addFlags("));
        assertNoLogging(entry);
    }

    @Test
    public void mainOwnsTheBrokerAcrossCreateFocusResumeAndDestroy() throws Exception {
        String main = source("app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String broker = source(
            "app/src/com/autoformkit/app/PanelPairingBroker.java",
            "src/com/autoformkit/app/PanelPairingBroker.java");
        String onCreate = between(main, "protected void onCreate(Bundle savedInstanceState)",
            "public void onWindowFocusChanged(boolean hasFocus)");
        String onFocus = between(main,
            "public void onWindowFocusChanged(boolean hasFocus)",
            "protected void onSaveInstanceState(Bundle outState)");
        String onResume = between(main, "protected void onResume()", "protected void onPause()");
        String onDestroy = between(main, "protected void onDestroy()",
            "private void showSettingsPage()");
        String accept = between(main, "private void acceptPendingPanelPairingDelivery()",
            "private void maybeShowPanelPairingPrompt()");

        assertTrue(onCreate.contains(
            "panelPairingBrokerOwner = PanelPairingBroker.mainActivityCreated()"));
        assertTrue(onCreate.contains("if (!panelPairingBrokerOwner)"));
        assertBefore(onCreate, "if (!panelPairingBrokerOwner)", "finish()");
        assertTrue(onFocus.contains("if (!panelPairingBrokerOwner) return"));
        assertBefore(onFocus, "acceptPendingPanelPairingDelivery()",
            "maybeShowPanelPairingPrompt()");
        assertTrue(onResume.contains("acceptPendingPanelPairingDelivery()"));
        assertTrue(accept.contains("PanelPairingBroker.take()"));
        assertTrue(onDestroy.contains("panelPairingAttempt.cancel()"));
        assertTrue(onDestroy.contains("PanelPairingBroker.mainActivityDestroyed()"));
        assertBefore(onDestroy, "panelPairingGeneration++",
            "PanelPairingBroker.mainActivityDestroyed()");

        assertTrue(broker.contains("static synchronized boolean mainActivityCreated()"));
        assertTrue(broker.contains("static synchronized Delivery take()"));
        assertTrue(broker.contains("static synchronized void mainActivityDestroyed()"));
        assertTrue(broker.contains("static synchronized LaunchDecision offer("));
        assertFalse(broker.contains("SharedPreferences"));
        assertFalse(broker.contains("AtomicFile"));
        assertFalse(broker.contains("JSONObject"));
        assertFalse(broker.contains("Intent "));
        assertNoLogging(broker);
    }

    @Test
    public void pairingPromptExistsOnlyAtASettingsSafeBoundary() throws Exception {
        String main = source("app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String settings = between(main, "private void showSettingsPage()",
            "private void showFormPage()");
        String prompt = between(main, "private void maybeShowPanelPairingPrompt()",
            "private void beginPanelPairingRedemption(");

        assertTrue(settings.contains("settingsPageOpen = true"));
        assertTrue(settings.contains("scroll.post(this::maybeShowPanelPairingPrompt)"));
        assertTrue(prompt.contains("if (!settingsPageOpen) return"));
        assertTrue(prompt.contains("if (!safeToInstallBoundPanelSnapshot()) return"));
        assertBefore(prompt, "if (!settingsPageOpen) return", "new AlertDialog.Builder(this)");
        assertBefore(prompt, "if (!safeToInstallBoundPanelSnapshot()) return",
            "new AlertDialog.Builder(this)");
        assertTrue(prompt.contains("PanelPairingLinkRules.isUsableAt("));
    }

    @Test
    public void manualPanelConnectionRemainsVisibleAndPairingHasNoInAppAction()
            throws Exception {
        String main = source("app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String settings = between(main, "private void showSettingsPage()",
            "private void showFormPage()");

        assertTrue(settings.contains("final EditText panelBaseEdit = edit(t(\"panel_base_hint\"))"));
        assertTrue(settings.contains("panelBaseEdit.setText(AppConfig.panelBase(this))"));
        assertTrue(settings.contains("final EditText catalogKeyEdit = edit(t(\"catalog_key_hint\"))"));
        assertTrue(settings.contains("catalogKeyEdit.setText(AppConfig.catalogKey(this))"));
        assertTrue(settings.contains("button(t(\"panel_save\"), v -> savePanelConnection("));
        assertTrue(settings.contains(
            "panelBaseEdit.getText().toString(), catalogKeyEdit.getText().toString()"));

        // The external deep link may surface a pending prompt at the safe Settings boundary,
        // but Settings itself must not mint, discover, or start pairing through a button/menu.
        assertTrue(settings.contains("scroll.post(this::maybeShowPanelPairingPrompt)"));
        assertFalse(settings.contains("v -> maybeShowPanelPairingPrompt"));
        assertFalse(settings.contains("v -> beginPanelPairingRedemption"));
        assertFalse(settings.contains("PanelPairingBroker."));
        assertFalse(settings.contains("PanelPairingLinkRules."));
    }

    @Test
    public void pairingRechecksSafetyAndCommitsOnlyAgainstTheExactOldConnection()
            throws Exception {
        String main = source("app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String prompt = between(main, "private void maybeShowPanelPairingPrompt()",
            "private void beginPanelPairingRedemption(");
        String redemption = between(main, "private void beginPanelPairingRedemption(",
            "/** Persist the panel address + access key");
        String exactSave = between(main,
            "private void savePanelConnection(String panelBaseInput, String catalogKeyInput,",
            "/** A panel URL or key defines a security boundary");

        assertBefore(prompt, "if (!safeToInstallBoundPanelSnapshot())",
            "beginPanelPairingRedemption(request, dialog)");
        assertTrue(redemption.contains(
            "final String expectedOldBase = AppConfig.panelBase(this)"));
        assertTrue(redemption.contains(
            "final String expectedOldKey = AppConfig.catalogKey(this)"));
        assertBefore(redemption, "expectedOldBase =", "PanelPairingRedeemer.redeem(");
        assertBefore(redemption, "expectedOldKey =", "PanelPairingRedeemer.redeem(");
        assertTrue(redemption.contains(
            "AppConfig.connectionMatches(this, expectedOldBase, expectedOldKey)"));
        assertBefore(redemption,
            "AppConfig.connectionMatches(this, expectedOldBase, expectedOldKey)",
            "if (!safeToInstallBoundPanelSnapshot())");
        assertBefore(redemption, "PanelPairingRedeemer.redeem(",
            "if (!safeToInstallBoundPanelSnapshot())");
        assertTrue(redemption.contains(
            "savePanelConnection(request.panelBase, result.accessKey,"));
        assertTrue(redemption.contains("expectedOldBase, expectedOldKey)"));
        assertFalse(redemption.contains(
            "savePanelConnection(request.panelBase, result.accessKey);"));

        assertTrue(exactSave.contains("String expectedOldBase, String expectedOldKey"));
        assertTrue(exactSave.contains("exactOldConnectionRequired"));
        assertTrue(exactSave.contains("!oldBase.equals(expectedOldBase)"));
        assertTrue(exactSave.contains("!oldKey.equals(expectedOldKey)"));
        assertTrue(exactSave.contains("synchronized (UpdateInstallRules.HANDOFF_LOCK)"));
        assertTrue(exactSave.contains(
            "!expectedOldBase.equals(AppConfig.panelBase(this))"));
        assertTrue(exactSave.contains(
            "!expectedOldKey.equals(AppConfig.catalogKey(this))"));
    }

    @Test
    public void redeemerIsHttpsOnlyNonRedirectingBoundedCancelableAndSilent()
            throws Exception {
        String redeemer = source(
            "app/src/com/autoformkit/app/PanelPairingRedeemer.java",
            "src/com/autoformkit/app/PanelPairingRedeemer.java");
        String timeout = between(redeemer, "private void timeout()", "private void abortConnection()");
        String main = source("app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String onDestroy = between(main, "protected void onDestroy()",
            "private void showSettingsPage()");

        assertTrue(redeemer.contains("HttpsURLConnection"));
        assertTrue(redeemer.contains("new URL(request.redeemUrl())"));
        assertTrue(redeemer.contains("\"https\".equalsIgnoreCase(endpoint.getProtocol())"));
        assertTrue(redeemer.contains("setInstanceFollowRedirects(false)"));
        assertTrue(redeemer.contains("setConnectTimeout(CONNECT_TIMEOUT_MS)"));
        assertTrue(redeemer.contains("setReadTimeout(READ_TIMEOUT_MS)"));
        assertTrue(redeemer.contains("TOTAL_TIMEOUT_MS"));
        assertTrue(redeemer.contains("WATCHDOG.schedule("));
        assertTrue(redeemer.contains("attempt::timeout, TOTAL_TIMEOUT_MS"));
        assertTrue(redeemer.contains("private enum State { ACTIVE, CANCELLED, DELIVERED }"));
        assertTrue(redeemer.contains("private final AtomicReference<Callback> callback"));
        assertTrue(timeout.contains("deliverClaimed(Result.failure(Error.NETWORK))"));
        assertBefore(timeout, "state.compareAndSet(State.ACTIVE, State.DELIVERED)",
            "deliverClaimed(Result.failure(Error.NETWORK))");
        assertBefore(timeout, "deliverClaimed(Result.failure(Error.NETWORK))",
            "abortConnection()");
        assertTrue(redeemer.contains("callback.getAndSet(null)"));
        assertTrue(redeemer.contains("static final class Attempt"));
        assertTrue(redeemer.contains("void cancel()"));
        assertTrue(redeemer.contains("abortConnection()"));
        assertTrue(redeemer.contains("active.disconnect()"));
        assertTrue(redeemer.contains("activeWorker.interrupt()"));
        assertTrue(redeemer.contains("cleanup.setDaemon(true)"));
        assertTrue(redeemer.contains("cleanup.start()"));
        assertTrue(redeemer.contains(
            "attempt.connection.compareAndSet(connection, null)"));
        assertTrue(redeemer.contains("static Attempt redeem("));
        assertTrue(onDestroy.contains("panelPairingAttempt.cancel()"));
        assertFalse(redeemer.contains("Authorization"));
        assertFalse(redeemer.contains("(HttpURLConnection)"));
        assertNoLogging(redeemer);
    }

    @Test
    public void trustGrantingButtonRejectsObscuredAndPartiallyObscuredTouches()
            throws Exception {
        String main = source("app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String prompt = between(main, "private void maybeShowPanelPairingPrompt()",
            "private void beginPanelPairingRedemption(");
        String touchRules = source(
            "app/src/com/autoformkit/app/PanelPairingTouchRules.java",
            "src/com/autoformkit/app/PanelPairingTouchRules.java");

        assertTrue(prompt.contains("connect.setFilterTouchesWhenObscured(true)"));
        assertTrue(prompt.contains("connect.setOnTouchListener"));
        assertTrue(prompt.contains(
            "PanelPairingTouchRules.reject(event.getFlags(), Build.VERSION.SDK_INT)"));
        assertBefore(prompt, "PanelPairingTouchRules.reject(",
            "beginPanelPairingRedemption(request, dialog)");
        assertTrue(touchRules.contains("WINDOW_IS_OBSCURED"));
        assertTrue(touchRules.contains("WINDOW_IS_PARTIALLY_OBSCURED"));
        assertTrue(touchRules.contains("sdkInt >= 29"));
    }

    @Test
    public void oneTimeTicketNeverEntersSavedStatePreferencesOrLogs() throws Exception {
        String main = source("app/src/com/autoformkit/app/MainActivity.java",
            "src/com/autoformkit/app/MainActivity.java");
        String entry = source(
            "app/src/com/autoformkit/app/PanelPairingEntryActivity.java",
            "src/com/autoformkit/app/PanelPairingEntryActivity.java");
        String broker = source(
            "app/src/com/autoformkit/app/PanelPairingBroker.java",
            "src/com/autoformkit/app/PanelPairingBroker.java");
        String redeemer = source(
            "app/src/com/autoformkit/app/PanelPairingRedeemer.java",
            "src/com/autoformkit/app/PanelPairingRedeemer.java");
        String savedState = between(main,
            "protected void onSaveInstanceState(Bundle outState)",
            "public void onConfigurationChanged");
        String pairing = between(main,
            "private void acceptPendingPanelPairingDelivery()",
            "/** Persist the panel address + access key");

        assertFalse(savedState.contains("pendingPanelPairing"));
        assertFalse(savedState.contains("panelPairingRequest"));
        assertFalse(savedState.contains("panelPairingAttempt"));
        assertFalse(savedState.contains("ticket"));
        assertFalse(pairing.contains("request.ticket"));
        assertFalse(pairing.contains("prefs.edit("));
        assertFalse(pairing.contains("getSharedPreferences("));
        assertFalse(pairing.contains("outState.put"));
        assertNoLogging(pairing);

        assertFalse(entry.contains("request.ticket"));
        assertFalse(entry.contains("SharedPreferences"));
        assertFalse(entry.contains("outState.put"));
        assertNoLogging(entry);
        assertFalse(broker.contains("SharedPreferences"));
        assertFalse(broker.contains("AtomicFile"));
        assertFalse(broker.contains("JSONObject"));
        assertNoLogging(broker);
        assertNoLogging(redeemer);
        assertTrue(entry.contains("encodedLink = null"));
    }

    private static String source(String... candidates) throws Exception {
        Path cwd = Paths.get("").toAbsolutePath();
        for (String candidate : candidates) {
            Path path = cwd.resolve(candidate);
            if (Files.isRegularFile(path)) {
                return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("Source file not found");
    }

    /** Returns a marker-inclusive slice so callers can assert the declaration itself. */
    private static String between(String value, String startMarker, String endMarker) {
        int start = value.indexOf(startMarker);
        int end = value.indexOf(endMarker, start + startMarker.length());
        if (start < 0 || end < 0 || end <= start) {
            throw new AssertionError("Expected source markers not found: "
                + startMarker + " -> " + endMarker);
        }
        return value.substring(start, end);
    }

    private static void assertBefore(String value, String first, String second) {
        int firstIndex = value.indexOf(first);
        int secondIndex = value.indexOf(second);
        assertTrue("Expected marker: " + first, firstIndex >= 0);
        assertTrue("Expected marker: " + second, secondIndex >= 0);
        assertTrue("Expected ordering: " + first + " before " + second,
            firstIndex < secondIndex);
    }

    private static void assertNoLogging(String value) {
        assertFalse(value.contains("Diagnostics."));
        assertFalse(value.contains("FailureReporter"));
        assertFalse(value.contains("android.util.Log"));
        assertFalse(value.contains("Log."));
        assertFalse(value.contains("appendLog("));
        assertFalse(value.contains("System.out"));
        assertFalse(value.contains("System.err"));
        assertFalse(value.contains("printStackTrace("));
    }

    private static int count(String value, String needle) {
        int count = 0;
        for (int offset = 0; (offset = value.indexOf(needle, offset)) >= 0;
                offset += needle.length()) {
            count++;
        }
        return count;
    }
}
