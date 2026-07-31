package com.autoformkit.app;

import android.content.Context;

import org.json.JSONObject;

/** One-time, fail-closed migration of a Panel-first prewarmed legacy config/catalog pair. */
final class LegacyPanelCacheMigration {
    private LegacyPanelCacheMigration() {}

    static boolean migrate(Context context) {
        if (context == null) return false;
        Context app = context.getApplicationContext();
        String panelBase = AppConfig.panelBase(app);
        String catalogKey = AppConfig.catalogKey(app);
        if (panelBase.isEmpty()) return false;
        try {
            String configText = AppConfig.readRawCache(app);
            String catalogText = FormCatalog.readRawCache(app);
            JSONObject config = new JSONObject(configText);
            JSONObject catalog = new JSONObject(catalogText);
            boolean configBound = AppConfig.isBoundToConnection(config, panelBase, catalogKey);
            boolean catalogBound = AppConfig.isBoundToConnection(catalog, panelBase, catalogKey);
            if (configBound && catalogBound) return false;
            if (!unboundOrCurrent(config, panelBase, catalogKey)
                    || !unboundOrCurrent(catalog, panelBase, catalogKey)
                    || !AppConfig.hasUsablePayload(config)
                    || !LegacyPanelCacheMigrationRules.canMigrate(
                        panelBase, catalogKey, config, catalogText)) return false;

            String connectionNamespace = AppConfig.connectionNamespaceId(
                panelBase, catalogKey);
            String pairSha256 = MainDraftSnapshotRules.panelPairSha256(config, catalog);
            JSONObject receipt = MainDraftSnapshotRules.newLegacyMigrationReceipt(
                connectionNamespace, AppConfig.catalogVersion(config),
                BuildConfig.VERSION_CODE, pairSha256);
            // Persist the proof before changing either cache. If the process dies during the cache
            // swap, the receipt remains harmless until AppConfig/FormCatalog can load the exact
            // connection/version/pair hash again. Without this durable receipt, legacy drafts stay
            // untouched and locked rather than being guessed into the current profile.
            if (!app.getSharedPreferences(AppConfig.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(MainDraftSnapshotRules.legacyReceiptPreferenceKey(
                        connectionNamespace), receipt.toString())
                    .commit()) return false;

            // Stage both exact legacy roots, then use the same crash-safe pair transaction as a
            // normal Panel refresh. A provider or updater can see only the complete old pair, the
            // complete newly-bound pair, or a fail-closed absence — never one migrated half.
            PanelPairCacheCoordinator.stageConfigCandidate(
                app, panelBase, catalogKey, config);
            PanelPairCacheCoordinator.stageCatalogCandidate(
                app, panelBase, catalogKey, catalog,
                LegacyPanelCacheMigrationRules.sha256(catalogText));
            if (PanelPairCacheCoordinator.promoteCandidates(
                    app, connectionNamespace)
                    != PanelPairCacheCoordinator.Promotion.PROMOTED) return false;

            PanelPairCacheCoordinator.ActivePair verified =
                PanelPairCacheCoordinator.loadActivePair(app);
            return verified != null && verified.version == AppConfig.catalogVersion(config)
                && verified.pairSha256.equals(pairSha256);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean unboundOrCurrent(JSONObject value, String panelBase,
                                            String catalogKey) {
        return value.optJSONObject(AppConfig.CACHE_BINDING_FIELD) == null
            || AppConfig.isBoundToConnection(value, panelBase, catalogKey);
    }
}
