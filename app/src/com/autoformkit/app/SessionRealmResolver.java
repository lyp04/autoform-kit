package com.autoformkit.app;

import android.content.Context;

/** Resolves a session realm only from the exact coherent active pair currently on disk. */
final class SessionRealmResolver {
    private SessionRealmResolver() {}

    static String activeFingerprint(Context context) {
        if (context == null) return "";
        Context app = context.getApplicationContext();
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            String panelBase = AppConfig.panelBase(app);
            String catalogKey = AppConfig.catalogKey(app);
            if (panelBase.isEmpty() || catalogKey.isEmpty()) return "";
            PanelPairCacheCoordinator.ActivePair pair =
                PanelPairCacheCoordinator.loadActivePairOrNull(app);
            return pair == null ? "" : forPair(app, pair);
        }
    }

    static String forPair(Context context, PanelPairCacheCoordinator.ActivePair pair) {
        if (context == null || pair == null || pair.config == null || pair.catalog == null) {
            return "";
        }
        String panelBase = AppConfig.panelBase(context);
        String catalogKey = AppConfig.catalogKey(context);
        if (panelBase.isEmpty() || catalogKey.isEmpty()) return "";
        return SessionRealmRules.fingerprint(
            AppConfig.connectionSecurityId(panelBase, catalogKey),
            pair.config, pair.catalog.settings);
    }
}
