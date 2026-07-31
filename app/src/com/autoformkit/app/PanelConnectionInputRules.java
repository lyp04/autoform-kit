package com.autoformkit.app;

/** Pure validation rules for installing one complete Panel address/read-key tuple. */
final class PanelConnectionInputRules {
    enum TupleState {
        EMPTY,
        PARTIAL,
        COMPLETE;

        boolean allowsPanelNetwork() {
            return this == COMPLETE;
        }
    }

    enum Source {
        MANUAL,
        PAIRING
    }

    enum Decision {
        ACCEPT,
        PARTIAL_TUPLE,
        REUSED_OLD_KEY;

        boolean allowed() {
            return this == ACCEPT;
        }
    }

    private PanelConnectionInputRules() {}

    /** Classifies the trimmed Panel address/access-key pair before any Panel network work starts. */
    static TupleState classify(String panelBase, String key) {
        boolean hasBase = !clean(panelBase).isEmpty();
        boolean hasKey = !clean(key).isEmpty();
        if (!hasBase && !hasKey) return TupleState.EMPTY;
        return hasBase && hasKey ? TupleState.COMPLETE : TupleState.PARTIAL;
    }

    /** Only a complete address/access-key tuple grants authority to contact a Panel. */
    static boolean allowsPanelNetwork(String panelBase, String key) {
        return classify(panelBase, key).allowsPanelNetwork();
    }

    /**
     * Validates already base-normalized inputs. The caller owns URL canonicalization, including
     * trailing-slash removal; this helper trims only surrounding whitespace.
     *
     * <p>A Panel connection is either fully absent or fully present. A manual edit also may not
     * carry an existing non-empty read key across a material base change. A pairing redemption is
     * fresh authority and may return the same bytes as the previous key.</p>
     */
    static Decision validate(Source source, String oldBase, String oldKey,
                             String candidateBase, String candidateKey) {
        if (source == null) throw new IllegalArgumentException("source is required");
        String previousBase = clean(oldBase);
        String previousKey = clean(oldKey);
        String nextBase = clean(candidateBase);
        String nextKey = clean(candidateKey);

        if (classify(nextBase, nextKey) == TupleState.PARTIAL) {
            return Decision.PARTIAL_TUPLE;
        }
        if (source == Source.MANUAL
                && !nextBase.equals(previousBase)
                && !previousKey.isEmpty()
                && nextKey.equals(previousKey)) {
            return Decision.REUSED_OLD_KEY;
        }
        return Decision.ACCEPT;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
