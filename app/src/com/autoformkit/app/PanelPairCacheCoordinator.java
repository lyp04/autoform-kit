package com.autoformkit.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/**
 * The only runtime gateway for staging, recovering, promoting and reading a Panel config/catalog
 * pair. Every operation uses {@link UpdateInstallRules#HANDOFF_LOCK}, so a reader can never observe
 * the two independently atomic active files between replacements and a refresh cannot overwrite a
 * transaction's old-value candidate backups.
 */
final class PanelPairCacheCoordinator {
    private static final String RECEIPT_DIR = "panel-pair-transaction";
    private static final String RECEIPT_FILE = "promotion.json";

    enum Promotion {
        NONE,
        WAITING,
        INVALID,
        PROMOTED
    }

    enum CandidatePolicy {
        PERMIT_STRICTLY_NEWER,
        REQUIRE_NONE
    }

    /**
     * Storage seam for deterministic JVM race tests. Production supplies a view backed by the
     * coordinator's AtomicFiles; every callback is invoked while the caller-provided handoff lock
     * is held.
     */
    interface AtomicActiveUseSource {
        void recover() throws Exception;
        String currentConnection();
        ActivePair activePair() throws Exception;
        String configCandidateTextOrNull() throws Exception;
        String catalogCandidateTextOrNull() throws Exception;
        String panelBase();
        String catalogKey();
    }

    static final class ActivePair {
        final JSONObject config;
        final JSONObject catalogRoot;
        final FormCatalog.BoundSnapshot catalog;
        final int version;
        final String pairSha256;

        private ActivePair(JSONObject config, JSONObject catalogRoot,
                           FormCatalog.BoundSnapshot catalog, int version,
                           String pairSha256) {
            this.config = config;
            this.catalogRoot = catalogRoot;
            this.catalog = catalog;
            this.version = version;
            this.pairSha256 = pairSha256;
        }
    }

    private static volatile boolean recoveryBlocked;
    private static volatile String recoveryFailure = "";

    private PanelPairCacheCoordinator() {}

    static PanelPairCacheTransaction.Recovery recover(Context context) throws IOException {
        Context app = requireContext(context);
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            return recoverLocked(app);
        }
    }

    static boolean recoveryBlocked() {
        return recoveryBlocked;
    }

    static String recoveryFailure() {
        return recoveryFailure;
    }

    /** A coherent immutable-by-replacement active pair, or null when either half is unusable. */
    static ActivePair loadActivePair(Context context) throws IOException {
        Context app = requireContext(context);
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            recoverLocked(app);
            return loadActivePairLocked(app);
        }
    }

    /** Fail-closed convenience for callers whose public contract treats an invalid cache as absent. */
    static ActivePair loadActivePairOrNull(Context context) {
        try {
            return loadActivePair(context);
        } catch (Exception blockedOrInvalid) {
            return null;
        }
    }

    /**
     * One-lock active-use view for form/catalog/notification consumers.
     *
     * <p>The exact current connection, coherent active pair, and both candidate slots are observed
     * in one {@link UpdateInstallRules#HANDOFF_LOCK} critical section. No candidate returns the
     * active pair. Individually valid candidate halves that are strictly newer than that immutable
     * pair also return it; same/older/malformed/cross-connection candidates fail closed.
     */
    static ActivePair loadActivePairIfCandidatesPermit(
            Context context, String expectedConnection) {
        return atomicActivePairView(context, expectedConnection,
            CandidatePolicy.PERMIT_STRICTLY_NEWER);
    }

    /**
     * One-lock conservative view for update-source binding. Any candidate half suppresses the
     * active pair, even when it is a valid strictly-newer publish still downloading its peer.
     */
    static ActivePair loadActivePairIfNoCandidates(
            Context context, String expectedConnection) {
        return atomicActivePairView(context, expectedConnection,
            CandidatePolicy.REQUIRE_NONE);
    }

    /**
     * Stages one config only after rechecking the exact Panel/key while holding the pair lock.
     * A same-revision byte change is rejected: every behavior-affecting Panel publish must advance
     * catalogVersion, otherwise the catalog half cannot be proven to belong to that config.
     */
    static boolean stageConfigCandidate(Context context, String panelBase, String key,
                                        JSONObject downloaded) throws IOException {
        Context app = requireContext(context);
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            recoverLocked(app);
            requireExpectedConnection(app, panelBase, key);
            JSONObject candidate = copy(downloaded, "config candidate");
            AppConfig.stampConnection(candidate, panelBase, key);
            if (!validConfig(candidate, panelBase, key)) {
                throw new IOException("Panel config candidate is invalid");
            }
            ActivePair active = loadActivePairLocked(app);
            int version = AppConfig.catalogVersion(candidate);
            if (active != null && active.version == version) {
                if (!sameSemanticJson(active.config, candidate)) {
                    // Preserve the offending candidate as a visible fail-closed lock. Silently
                    // deleting it would keep new workflows on stale semantics even though the
                    // Panel changed behavior without publishing a new pair revision.
                    AtomicCacheFile.write(AppConfig.candidateCacheFile(app),
                        candidate.toString().getBytes("UTF-8"));
                    throw new IOException(
                        "Panel config changed without advancing catalogVersion");
                }
                AtomicCacheFile.delete(AppConfig.candidateCacheFile(app));
                return false;
            }
            AtomicCacheFile.write(AppConfig.candidateCacheFile(app),
                candidate.toString().getBytes("UTF-8"));
            return true;
        }
    }

    /** Stages one catalog under the same connection/write lock used by promotion and recovery. */
    static boolean stageCatalogCandidate(Context context, String panelBase, String key,
                                         JSONObject downloaded, String sourceSha256)
            throws IOException {
        Context app = requireContext(context);
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            recoverLocked(app);
            requireExpectedConnection(app, panelBase, key);
            JSONObject candidate = copy(downloaded, "catalog candidate");
            AppConfig.stampConnection(candidate, panelBase, key);
            AppConfig.stampCatalogSource(candidate,
                clean(sourceSha256).toLowerCase(java.util.Locale.US));
            if (!validCatalog(candidate, panelBase, key)) {
                throw new IOException("Panel catalog candidate is invalid");
            }
            ActivePair active = loadActivePairLocked(app);
            int version = FormCatalog.catalogVersion(candidate);
            if (active != null && active.version == version) {
                if (!sameSemanticJson(active.catalogRoot, candidate)) {
                    AtomicCacheFile.write(FormCatalog.candidateCacheFile(app),
                        candidate.toString().getBytes("UTF-8"));
                    throw new IOException(
                        "Panel catalog changed without advancing its version");
                }
                AtomicCacheFile.delete(FormCatalog.candidateCacheFile(app));
                return false;
            }
            AtomicCacheFile.write(FormCatalog.candidateCacheFile(app),
                candidate.toString().getBytes("UTF-8"));
            return true;
        }
    }

    /**
     * Promotes only a complete, exact-current-connection candidate pair. The caller owns the UI
     * safe-boundary checks; this method rechecks connection identity under the handoff lock.
     */
    static Promotion promoteCandidates(Context context, String expectedConnection)
            throws IOException {
        Context app = requireContext(context);
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            recoverLocked(app);
            if (!currentConnection(app).equals(clean(expectedConnection))) {
                return Promotion.NONE;
            }
            boolean configPresent = AtomicCacheFile.hasRecoverableCopy(
                AppConfig.candidateCacheFile(app));
            boolean catalogPresent = AtomicCacheFile.hasRecoverableCopy(
                FormCatalog.candidateCacheFile(app));
            if (!configPresent && !catalogPresent) return Promotion.NONE;
            if (!configPresent || !catalogPresent) return Promotion.WAITING;

            String panelBase = AppConfig.panelBase(app);
            String key = AppConfig.catalogKey(app);
            String configText;
            String catalogText;
            try {
                configText = AtomicCacheFile.readUtf8(AppConfig.candidateCacheFile(app));
                catalogText = AtomicCacheFile.readUtf8(FormCatalog.candidateCacheFile(app));
                parsePair(configText, catalogText, panelBase, key);
            } catch (IOException invalid) {
                return Promotion.INVALID;
            }

            PanelPairCacheTransaction.promote(new PanelPairCacheTransaction.AtomicStorage(),
                validator(panelBase, key), transactionFiles(app));
            recoveryBlocked = false;
            recoveryFailure = "";
            return Promotion.PROMOTED;
        }
    }

    /** True when a bounded whole-pair retry can make an incomplete/mismatched publish converge. */
    static boolean needsPairedRetry(Context context, String expectedConnection) {
        Context app;
        try {
            app = requireContext(context);
        } catch (Exception invalid) {
            return false;
        }
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            try {
                recoverLocked(app);
                if (!currentConnection(app).equals(clean(expectedConnection))) return false;
                boolean configPresent = AtomicCacheFile.hasRecoverableCopy(
                    AppConfig.candidateCacheFile(app));
                boolean catalogPresent = AtomicCacheFile.hasRecoverableCopy(
                    FormCatalog.candidateCacheFile(app));
                if (!configPresent && !catalogPresent) return false;
                ActivePair active = loadActivePairLocked(app);
                String panelBase = AppConfig.panelBase(app);
                String key = AppConfig.catalogKey(app);
                if (configPresent && catalogPresent) {
                    try {
                        parsePair(
                            AtomicCacheFile.readUtf8(AppConfig.candidateCacheFile(app)),
                            AtomicCacheFile.readUtf8(FormCatalog.candidateCacheFile(app)),
                            panelBase, key);
                        return false; // complete and valid: wait for the UI safe boundary.
                    } catch (Exception mismatched) {
                        return true;
                    }
                }
                int candidateVersion = configPresent
                    ? configCandidateVersion(app, panelBase, key)
                    : catalogCandidateVersion(app, panelBase, key);
                return candidateVersion <= 0
                    || active == null || candidateVersion != active.version;
            } catch (Exception blocked) {
                return false; // unresolved recovery evidence is not repaired by a network retry.
            }
        }
    }

    /** True when any staged half exists; callers separately classify whether old active use is safe. */
    static boolean hasPendingCandidates(Context context, String expectedConnection) {
        Context app;
        try {
            app = requireContext(context);
        } catch (Exception invalid) {
            return true;
        }
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            try {
                recoverLocked(app);
                if (!currentConnection(app).equals(clean(expectedConnection))) return true;
                return AtomicCacheFile.hasRecoverableCopy(
                        AppConfig.candidateCacheFile(app))
                    || AtomicCacheFile.hasRecoverableCopy(
                        FormCatalog.candidateCacheFile(app));
            } catch (Exception blocked) {
                return true;
            }
        }
    }

    /**
     * A strictly newer, individually valid candidate half cannot reinterpret the immutable active
     * pair. Keep the old pair usable while the other half is downloading or a durable manual queue
     * still pins it, matching the legacy App's cache-fallback behavior. Same-revision mutations,
     * malformed/cross-connection candidates and recovery uncertainty remain fail-closed.
     */
    static boolean pendingCandidatesBlockActiveUse(Context context,
                                                   String expectedConnection) {
        Context app;
        try {
            app = requireContext(context);
        } catch (Exception invalid) {
            return true;
        }
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            try {
                recoverLocked(app);
                if (!currentConnection(app).equals(clean(expectedConnection))) return true;
                boolean configPresent = AtomicCacheFile.hasRecoverableCopy(
                    AppConfig.candidateCacheFile(app));
                boolean catalogPresent = AtomicCacheFile.hasRecoverableCopy(
                    FormCatalog.candidateCacheFile(app));
                if (!configPresent && !catalogPresent) return false;
                ActivePair active = loadActivePairLocked(app);
                String configText = configPresent
                    ? AtomicCacheFile.readUtf8(AppConfig.candidateCacheFile(app)) : null;
                String catalogText = catalogPresent
                    ? AtomicCacheFile.readUtf8(FormCatalog.candidateCacheFile(app)) : null;
                return !newerCandidatesPermitActiveUse(active, configText, catalogText,
                    AppConfig.panelBase(app), AppConfig.catalogKey(app));
            } catch (Exception blocked) {
                return true;
            }
        }
    }

    /** Pure candidate classification used by JVM tests and the locked runtime gateway above. */
    static boolean newerCandidatesPermitActiveUse(ActivePair active,
                                                   String configCandidateText,
                                                   String catalogCandidateText,
                                                   String panelBase, String key) {
        if (active == null
                || (configCandidateText == null && catalogCandidateText == null)) return false;
        try {
            if (configCandidateText != null) {
                JSONObject config = new JSONObject(configCandidateText);
                if (!validConfig(config, panelBase, key)
                        || AppConfig.catalogVersion(config) <= active.version) return false;
            }
            if (catalogCandidateText != null) {
                JSONObject catalog = new JSONObject(catalogCandidateText);
                if (!validCatalog(catalog, panelBase, key)
                        || FormCatalog.catalogVersion(catalog) <= active.version) return false;
            }
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }

    /**
     * Pure orchestration seam: tests can insert a candidate from {@code activePair()} before the
     * candidate callbacks run and prove it is classified in the same locked view. Production uses
     * the overload below with {@link UpdateInstallRules#HANDOFF_LOCK}.
     */
    static ActivePair atomicActivePairView(
            Object handoffLock, String expectedConnection,
            CandidatePolicy policy, AtomicActiveUseSource source) {
        if (handoffLock == null || source == null || policy == null) return null;
        synchronized (handoffLock) {
            try {
                source.recover();
                if (!clean(expectedConnection).equals(
                        clean(source.currentConnection()))) {
                    return null;
                }
                ActivePair active = source.activePair();
                if (active == null) return null;

                // These reads deliberately occur after the active-pair read while retaining the
                // same lock. A candidate staged before this point is therefore part of this view;
                // a normal coordinator writer cannot stage one after these reads until return.
                String configCandidate = source.configCandidateTextOrNull();
                String catalogCandidate = source.catalogCandidateTextOrNull();
                boolean candidatePresent =
                    configCandidate != null || catalogCandidate != null;
                if (!candidatePresent) return active;
                if (policy == CandidatePolicy.REQUIRE_NONE) return null;
                return newerCandidatesPermitActiveUse(active,
                    configCandidate, catalogCandidate,
                    source.panelBase(), source.catalogKey()) ? active : null;
            } catch (Exception blockedOrInvalid) {
                return null;
            }
        }
    }

    private static ActivePair atomicActivePairView(
            Context context, String expectedConnection, CandidatePolicy policy) {
        final Context app;
        try {
            app = requireContext(context);
        } catch (Exception invalid) {
            return null;
        }
        return atomicActivePairView(UpdateInstallRules.HANDOFF_LOCK,
            expectedConnection, policy, new AtomicActiveUseSource() {
                @Override public void recover() throws Exception {
                    recoverLocked(app);
                }

                @Override public String currentConnection() {
                    return PanelPairCacheCoordinator.currentConnection(app);
                }

                @Override public ActivePair activePair() {
                    return loadActivePairLocked(app);
                }

                @Override public String configCandidateTextOrNull()
                        throws Exception {
                    return candidateTextOrNull(AppConfig.candidateCacheFile(app));
                }

                @Override public String catalogCandidateTextOrNull()
                        throws Exception {
                    return candidateTextOrNull(FormCatalog.candidateCacheFile(app));
                }

                @Override public String panelBase() {
                    return AppConfig.panelBase(app);
                }

                @Override public String catalogKey() {
                    return AppConfig.catalogKey(app);
                }
            });
    }

    private static String candidateTextOrNull(File file) throws IOException {
        if (!AtomicCacheFile.hasRecoverableCopy(file)) return null;
        return AtomicCacheFile.readUtf8(file);
    }

    /**
     * Explicit Panel-security-boundary discard. The receipt is removed first so a crash during
     * deletion cannot make the next process try to reconstruct an old connection from intentionally
     * discarded backups; any remaining old file is rejected by its embedded connection binding.
     */
    static void discardForConnectionChange(Context context) throws IOException {
        Context app = requireContext(context);
        synchronized (UpdateInstallRules.HANDOFF_LOCK) {
            PanelPairCacheTransaction.AtomicStorage storage =
                new PanelPairCacheTransaction.AtomicStorage();
            PanelPairCacheTransaction.Files files = transactionFiles(app);
            try {
                storage.delete(files.receipt);
                storage.delete(files.candidateFirst);
                storage.delete(files.candidateSecond);
                storage.delete(files.activeFirst);
                storage.delete(files.activeSecond);
                recoveryBlocked = false;
                recoveryFailure = "";
            } catch (IOException | RuntimeException failure) {
                recoveryBlocked = true;
                recoveryFailure = concise(failure);
                if (failure instanceof IOException) throw (IOException) failure;
                throw new IOException("Panel pair cache discard failed", failure);
            }
        }
    }

    private static PanelPairCacheTransaction.Recovery recoverLocked(Context app)
            throws IOException {
        String panelBase = AppConfig.panelBase(app);
        String key = AppConfig.catalogKey(app);
        try {
            PanelPairCacheTransaction.Recovery result = PanelPairCacheTransaction.recover(
                new PanelPairCacheTransaction.AtomicStorage(), validator(panelBase, key),
                transactionFiles(app));
            recoveryBlocked = false;
            recoveryFailure = "";
            return result;
        } catch (IOException | RuntimeException failure) {
            recoveryBlocked = true;
            recoveryFailure = concise(failure);
            if (failure instanceof IOException) throw (IOException) failure;
            throw new IOException("Panel pair recovery failed", failure);
        }
    }

    private static ActivePair loadActivePairLocked(Context app) {
        File configFile = AppConfig.cacheFile(app);
        File catalogFile = FormCatalog.cacheFile(app);
        if (!AtomicCacheFile.hasRecoverableCopy(configFile)
                || !AtomicCacheFile.hasRecoverableCopy(catalogFile)) return null;
        try {
            return parsePair(AtomicCacheFile.readUtf8(configFile),
                AtomicCacheFile.readUtf8(catalogFile), AppConfig.panelBase(app),
                AppConfig.catalogKey(app));
        } catch (Exception invalid) {
            return null;
        }
    }

    static ActivePair parsePair(String configText, String catalogText,
                                String panelBase, String key) throws IOException {
        try {
            JSONObject config = new JSONObject(configText);
            JSONObject catalogRoot = new JSONObject(catalogText);
            if (!validConfig(config, panelBase, key)
                    || !validCatalog(catalogRoot, panelBase, key)) {
                throw new IOException("Panel cache pair shape or binding is invalid");
            }
            int configVersion = AppConfig.catalogVersion(config);
            int catalogVersion = FormCatalog.catalogVersion(catalogRoot);
            if (configVersion <= 0 || configVersion != catalogVersion) {
                throw new IOException("Panel cache pair revision mismatch");
            }
            if (!validPairProof(config, catalogRoot, panelBase, key, configVersion)) {
                throw new IOException("Panel cache pair publication proof mismatch");
            }
            if (!CatalogPromotionValidator.isExecutableWithConfig(
                    catalogRoot, config)) {
                throw new IOException("Panel catalog is not executable with its bound config");
            }
            String pairSha256 = MainDraftSnapshotRules.panelPairSha256(
                config, catalogRoot);
            if (!pairSha256.matches("[0-9a-f]{64}")) {
                throw new IOException("Panel cache pair fingerprint is invalid");
            }
            JSONArray profiles = catalogRoot.getJSONArray("profiles");
            JSONObject settings = catalogRoot.optJSONObject("settings");
            JSONObject configCopy = new JSONObject(config.toString());
            JSONObject catalogCopy = new JSONObject(catalogRoot.toString());
            FormCatalog.BoundSnapshot snapshot = new FormCatalog.BoundSnapshot(
                catalogVersion, new JSONArray(profiles.toString()),
                settings == null ? new JSONObject()
                    : new JSONObject(settings.toString()));
            return new ActivePair(configCopy, catalogCopy, snapshot,
                catalogVersion, pairSha256);
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Panel cache pair is unreadable", failure);
        }
    }

    private static PanelPairCacheTransaction.Validator validator(
            String panelBase, String key) {
        final String capturedBase = panelBase == null ? "" : panelBase;
        final String capturedKey = key == null ? "" : key;
        return (configText, catalogText) -> {
            parsePair(configText, catalogText, capturedBase, capturedKey);
            return true;
        };
    }

    private static boolean validConfig(JSONObject config, String panelBase, String key) {
        return config != null && AppConfig.hasUsablePayload(config)
            && AppConfig.isBoundToConnection(config, panelBase, key)
            && validConfigProof(config, panelBase, key);
    }

    private static boolean validCatalog(JSONObject catalog, String panelBase, String key) {
        if (catalog == null
                || !AppConfig.isBoundToConnection(catalog, panelBase, key)) return false;
        Object schemaValue = catalog.opt("schemaVersion");
        int schema = schemaValue == null ? 1 : exactPositiveInteger(schemaValue);
        if (schema <= 0 || schema > FormCatalog.SUPPORTED_SCHEMA_VERSION
                || FormCatalog.catalogVersion(catalog) <= 0
                || AppConfig.catalogSourceSha256(catalog).isEmpty()) return false;
        JSONArray profiles = catalog.optJSONArray("profiles");
        return profiles != null && profiles.length() > 0
            && CatalogPromotionValidator.isStructurallyValid(catalog);
    }

    private static boolean validConfigProof(JSONObject config,
                                            String panelBase, String key) {
        try {
            JSONObject proof = config.optJSONObject(
                LegacyPanelCacheMigrationRules.PROOF_FIELD);
            int configVersion = AppConfig.catalogVersion(config);
            return proof != null
                && exactPositiveInteger(proof.opt("version")) == 1
                && cleanBase(panelBase).equals(cleanBase(
                    proof.optString("panelBase", "")))
                && LegacyPanelCacheMigrationRules.sha256(key == null ? "" : key)
                    .equals(proof.optString("keySha256", ""))
                && proof.optString("catalogSha256", "")
                    .matches("[0-9a-f]{64}")
                && exactPositiveInteger(proof.opt("catalogVersion")) == configVersion;
        } catch (Exception invalid) {
            return false;
        }
    }

    private static boolean validPairProof(JSONObject config, JSONObject catalog,
                                          String panelBase, String key, int version) {
        if (!validConfigProof(config, panelBase, key)
                || FormCatalog.catalogVersion(catalog) != version) return false;
        JSONObject proof = config.optJSONObject(
            LegacyPanelCacheMigrationRules.PROOF_FIELD);
        return proof != null && proof.optString("catalogSha256", "")
            .equals(AppConfig.catalogSourceSha256(catalog));
    }

    private static int configCandidateVersion(Context app, String panelBase, String key) {
        try {
            JSONObject value = new JSONObject(AtomicCacheFile.readUtf8(
                AppConfig.candidateCacheFile(app)));
            return validConfig(value, panelBase, key)
                ? AppConfig.catalogVersion(value) : 0;
        } catch (Exception invalid) {
            return 0;
        }
    }

    private static int catalogCandidateVersion(Context app, String panelBase, String key) {
        try {
            JSONObject value = new JSONObject(AtomicCacheFile.readUtf8(
                FormCatalog.candidateCacheFile(app)));
            return validCatalog(value, panelBase, key)
                ? FormCatalog.catalogVersion(value) : 0;
        } catch (Exception invalid) {
            return 0;
        }
    }

    private static void requireExpectedConnection(Context app, String panelBase, String key)
            throws IOException {
        String expected = AppConfig.connectionNamespaceId(panelBase, key);
        if (!expected.equals(currentConnection(app))) {
            throw new IOException("Panel connection changed before candidate write");
        }
    }

    private static String currentConnection(Context app) {
        return AppConfig.connectionNamespaceId(
            AppConfig.panelBase(app), AppConfig.catalogKey(app));
    }

    private static PanelPairCacheTransaction.Files transactionFiles(Context app) {
        File receipt = new File(new File(app.getFilesDir(), RECEIPT_DIR), RECEIPT_FILE);
        return new PanelPairCacheTransaction.Files(
            AppConfig.cacheFile(app), FormCatalog.cacheFile(app),
            AppConfig.candidateCacheFile(app), FormCatalog.candidateCacheFile(app),
            receipt);
    }

    private static boolean sameSemanticJson(JSONObject first, JSONObject second) {
        if (first == null || second == null) return false;
        try {
            JSONObject left = new JSONObject(first.toString());
            JSONObject right = new JSONObject(second.toString());
            left.remove(AppConfig.CACHE_BINDING_FIELD);
            right.remove(AppConfig.CACHE_BINDING_FIELD);
            String leftHash = MainDraftSnapshotRules.semanticSha256(left);
            String rightHash = MainDraftSnapshotRules.semanticSha256(right);
            return !leftHash.isEmpty() && leftHash.equals(rightHash);
        } catch (Exception invalid) {
            return false;
        }
    }

    private static JSONObject copy(JSONObject value, String label) throws IOException {
        if (value == null) throw new IOException(label + " is absent");
        try {
            return new JSONObject(value.toString());
        } catch (Exception invalid) {
            throw new IOException(label + " is unreadable", invalid);
        }
    }

    private static int exactPositiveInteger(Object value) {
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) return 0;
        long number = ((Number) value).longValue();
        return number > 0L && number <= Integer.MAX_VALUE ? (int) number : 0;
    }

    private static Context requireContext(Context context) {
        if (context == null) throw new IllegalArgumentException("context is required");
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String cleanBase(String value) {
        String result = clean(value);
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String concise(Throwable failure) {
        if (failure == null) return "unknown";
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
            + (message == null || message.trim().isEmpty() ? "" : ": " + message.trim());
    }
}
