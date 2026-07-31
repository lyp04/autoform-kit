package com.autoformkit.app;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * Non-exported runtime target for the stable {@code .MainActivity} launcher alias.
 *
 * <p>The alias preserves existing home-screen component identity across upgrades. External Apps
 * cannot directly start the form-owning Activity, and data supplied through an explicit alias
 * Intent is scrubbed before the base Activity sees or saves it.
 */
public final class InternalMainActivity extends MainActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        scrubExternalIntent(getIntent());
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        scrubExternalIntent(intent);
        super.onNewIntent(intent);
    }

    private static void scrubExternalIntent(Intent intent) {
        if (intent == null) return;
        intent.setDataAndType(null, null);
        intent.setClipData(null);
        intent.replaceExtras((Bundle) null);
        intent.setSelector(null);
        if (Build.VERSION.SDK_INT >= 29) intent.setIdentifier(null);
    }
}
