package com.autoformkit.app;

/**
 * Pure state machine for the two independent Panel bootstrap downloads.
 *
 * <p>A configured Panel is usable only when a complete config cache and a catalog cache are both
 * bound to the exact current connection and advertise the same positive catalog revision. When
 * either cache was missing/mismatched at startup, both refresh listeners must finish before the
 * state can become ready. This prevents a publish race from mixing one revision's adapter with
 * another revision's profiles.
 */
final class PanelBootstrapRules {
    private static final long[] PAIR_RETRY_DELAYS_MS = { 1500L, 4000L, 10000L };
    enum Source { CONFIG, CATALOG }
    enum Mode { PREVIEW_ONLY, SYNCING, READY }

    static final class State {
        final String connectionNamespace;
        final boolean panelConfigured;
        final boolean configReady;
        final boolean catalogReady;
        final int configCatalogVersion;
        final int catalogVersion;
        final boolean pairCompatible;
        final boolean configRefreshFinished;
        final boolean catalogRefreshFinished;
        final boolean initiallyReady;
        final Mode mode;

        private State(String connectionNamespace, boolean panelConfigured,
                      boolean configReady, int configCatalogVersion,
                      boolean catalogReady, int catalogVersion,
                      boolean configRefreshFinished, boolean catalogRefreshFinished,
                      boolean initiallyReady) {
            this.connectionNamespace = clean(connectionNamespace);
            this.panelConfigured = panelConfigured;
            this.configReady = configReady;
            this.catalogReady = catalogReady;
            this.configCatalogVersion = configCatalogVersion;
            this.catalogVersion = catalogVersion;
            this.pairCompatible = pairCompatible(configReady, configCatalogVersion,
                catalogReady, catalogVersion);
            this.configRefreshFinished = configRefreshFinished;
            this.catalogRefreshFinished = catalogRefreshFinished;
            // A previously ready pair remains usable during a best-effort refresh only while the
            // current disk pair still matches. Candidate classification is performed by the
            // coordinator: valid strictly-newer halves may leave this old pair ready, while a
            // same-revision, malformed or cross-connection half moves the state to syncing.
            this.initiallyReady = initiallyReady && pairCompatible;
            this.mode = !panelConfigured
                ? Mode.PREVIEW_ONLY
                : (pairCompatible && (this.initiallyReady
                    || (configRefreshFinished && catalogRefreshFinished))
                    ? Mode.READY : Mode.SYNCING);
        }

        boolean accepts(String currentConnectionNamespace) {
            return connectionNamespace.equals(clean(currentConnectionNamespace));
        }

        boolean allRefreshesFinished() {
            return configRefreshFinished && catalogRefreshFinished;
        }

        boolean blocksConfiguredUse(String currentConnectionNamespace) {
            return panelConfigured
                && (!accepts(currentConnectionNamespace) || mode != Mode.READY);
        }

        boolean allowsRemoteOperations(String currentConnectionNamespace) {
            return panelConfigured && accepts(currentConnectionNamespace) && mode == Mode.READY;
        }
    }

    private PanelBootstrapRules() {}

    static State begin(String connectionNamespace, boolean panelConfigured,
                       boolean configReady, int configCatalogVersion,
                       boolean catalogReady, int catalogVersion) {
        boolean ready = panelConfigured && pairCompatible(configReady, configCatalogVersion,
            catalogReady, catalogVersion);
        return new State(connectionNamespace, panelConfigured,
            configReady, configCatalogVersion, catalogReady, catalogVersion,
            false, false, ready);
    }

    /**
     * Applies one refresh listener only when both the listener and the Activity still refer to the
     * state machine's connection. The cache booleans must come from a fresh bound-cache read after
     * that listener completed; a callback payload is never trusted as the other half of the pair.
     */
    static State onRefreshFinished(State state, Source source,
                                   String listenerConnectionNamespace,
                                   String currentConnectionNamespace,
                                   boolean configReady, int configCatalogVersion,
                                   boolean catalogReady, int catalogVersion) {
        if (state == null || source == null
                || !state.panelConfigured
                || !state.accepts(listenerConnectionNamespace)
                || !state.accepts(currentConnectionNamespace)
                || !clean(listenerConnectionNamespace).equals(
                    clean(currentConnectionNamespace))) {
            return state;
        }
        boolean configFinished = state.configRefreshFinished || source == Source.CONFIG;
        boolean catalogFinished = state.catalogRefreshFinished || source == Source.CATALOG;
        return new State(state.connectionNamespace, true,
            configReady, configCatalogVersion, catalogReady, catalogVersion,
            configFinished, catalogFinished, state.initiallyReady);
    }

    static boolean pairCompatible(boolean configReady, int configCatalogVersion,
                                  boolean catalogReady, int catalogVersion) {
        return configReady && catalogReady
            && configCatalogVersion > 0
            && configCatalogVersion == catalogVersion;
    }

    /**
     * An already-open workflow may finish with its immutable active pair while a background disk
     * refresh is between revisions. It still must belong to the exact current connection and be a
     * complete internally matching pair; this permission never installs or starts a new workflow.
     */
    static boolean allowsActiveWorkflow(State state, String currentConnectionNamespace,
                                        boolean activeConfigReady,
                                        int activeConfigCatalogVersion,
                                        boolean activeCatalogReady,
                                        int activeCatalogVersion) {
        return state != null && state.panelConfigured
            && state.accepts(currentConnectionNamespace)
            && pairCompatible(activeConfigReady, activeConfigCatalogVersion,
                activeCatalogReady, activeCatalogVersion);
    }

    /** A completed publish-raced v8/v7 round gets a short, bounded whole-pair retry. */
    static boolean shouldRetryRevisionMismatch(State state,
                                               String currentConnectionNamespace) {
        return state != null && state.panelConfigured
            && state.accepts(currentConnectionNamespace)
            && state.allRefreshesFinished()
            && state.configReady && state.catalogReady
            && !state.pairCompatible;
    }

    /**
     * A candidate is unsafe for active-cache fallback (same revision, malformed, wrong binding,
     * or recovery uncertainty). Block every newly-created workflow while allowing only an already
     * open exact active pair to finish through {@link #allowsActiveWorkflow}.
     */
    static State awaitingCandidatePromotion(State state) {
        if (state == null || !state.panelConfigured) return state;
        return new State(state.connectionNamespace, true,
            state.configReady, state.configCatalogVersion,
            false, 0, state.configRefreshFinished,
            state.catalogRefreshFinished, false);
    }

    /** Delay for the zero-based retry number, or -1 after the bounded retry budget is exhausted. */
    static long pairRetryDelayMillis(int retryNumber) {
        return retryNumber >= 0 && retryNumber < PAIR_RETRY_DELAYS_MS.length
            ? PAIR_RETRY_DELAYS_MS[retryNumber] : -1L;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
