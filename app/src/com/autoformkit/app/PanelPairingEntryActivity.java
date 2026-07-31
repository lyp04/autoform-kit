package com.autoformkit.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Narrow exported entry point for the browser pairing link.
 *
 * <p>The URI is parsed and scrubbed here. If a MainActivity already exists, this Activity finishes
 * without changing the task stack; a Capture/Scanner result therefore returns to its original
 * owner. Only a cold process starts MainActivity, using a clean launcher Intent with no ticket.
 */
public final class PanelPairingEntryActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent incoming = getIntent();
        Uri data = incoming == null ? null : incoming.getData();
        String encodedLink = data == null ? null : data.toString();
        if (incoming != null) {
            incoming.setData(null);
            incoming.setClipData(null);
            incoming.replaceExtras((Bundle) null);
        }
        PanelPairingLinkRules.Request request = null;
        boolean invalid = false;
        try {
            request = PanelPairingLinkRules.parse(
                encodedLink, BuildConfig.APPLICATION_ID, System.currentTimeMillis() / 1000L);
            invalid = request == null;
        } catch (PanelPairingLinkRules.InvalidPairingLinkException failure) {
            invalid = true;
        } finally {
            encodedLink = null;
        }
        PanelPairingBroker.LaunchDecision decision =
            PanelPairingBroker.offer(request, invalid);
        if (decision == PanelPairingBroker.LaunchDecision.LAUNCH_MAIN) {
            try {
                startActivity(new Intent(this, MainActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER));
            } catch (RuntimeException launchFailed) {
                PanelPairingBroker.releaseLaunchReservation();
            }
        }
        finish();
    }
}
