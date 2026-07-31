package com.autoformkit.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PanelBootstrapRulesTest {
    private static final String CONNECTION_A = "connection-a";
    private static final String CONNECTION_B = "connection-b";

    @Test
    public void configuredPanelBlocksWhenEitherBoundCacheIsMissing() {
        for (boolean configReady : new boolean[]{false, true}) {
            for (boolean catalogReady : new boolean[]{false, true}) {
                PanelBootstrapRules.State state = PanelBootstrapRules.begin(
                    CONNECTION_A, true, configReady, 7, catalogReady, 7);
                boolean both = configReady && catalogReady;
                assertTrue(both || state.blocksConfiguredUse(CONNECTION_A));
                assertTrue(both == state.allowsRemoteOperations(CONNECTION_A));
            }
        }
    }

    @Test
    public void missingCacheUnlocksOnlyAfterBothCurrentConnectionListenersFinish() {
        PanelBootstrapRules.State state = PanelBootstrapRules.begin(
            CONNECTION_A, true, false, 0, false, 0);

        state = PanelBootstrapRules.onRefreshFinished(state,
            PanelBootstrapRules.Source.CATALOG,
            CONNECTION_A, CONNECTION_A, false, 0, true, 7);
        assertTrue(state.blocksConfiguredUse(CONNECTION_A));
        assertFalse(state.allRefreshesFinished());

        state = PanelBootstrapRules.onRefreshFinished(state,
            PanelBootstrapRules.Source.CONFIG,
            CONNECTION_A, CONNECTION_A, true, 7, true, 7);
        assertTrue(state.allRefreshesFinished());
        assertFalse(state.blocksConfiguredUse(CONNECTION_A));
        assertTrue(state.allowsRemoteOperations(CONNECTION_A));
    }

    @Test
    public void failedOrIncompleteRefreshStaysLocked() {
        PanelBootstrapRules.State state = PanelBootstrapRules.begin(
            CONNECTION_A, true, false, 0, false, 0);
        state = PanelBootstrapRules.onRefreshFinished(state,
            PanelBootstrapRules.Source.CONFIG,
            CONNECTION_A, CONNECTION_A, false, 0, false, 0);
        state = PanelBootstrapRules.onRefreshFinished(state,
            PanelBootstrapRules.Source.CATALOG,
            CONNECTION_A, CONNECTION_A, false, 0, true, 7);

        assertTrue(state.allRefreshesFinished());
        assertTrue(state.blocksConfiguredUse(CONNECTION_A));
        assertFalse(state.allowsRemoteOperations(CONNECTION_A));
    }

    @Test
    public void responseFromPreviousPanelOrKeyCannotUnlockNewConnection() {
        PanelBootstrapRules.State state = PanelBootstrapRules.begin(
            CONNECTION_B, true, false, 0, false, 0);
        PanelBootstrapRules.State stale = PanelBootstrapRules.onRefreshFinished(state,
            PanelBootstrapRules.Source.CONFIG,
            CONNECTION_A, CONNECTION_B, true, 7, true, 7);
        assertSame(state, stale);

        PanelBootstrapRules.State switchedAgain = PanelBootstrapRules.onRefreshFinished(state,
            PanelBootstrapRules.Source.CATALOG,
            CONNECTION_B, CONNECTION_A, true, 7, true, 7);
        assertSame(state, switchedAgain);
        assertTrue(state.blocksConfiguredUse(CONNECTION_B));
    }

    @Test
    public void unconfiguredInstallIsPreviewOnlyAndNeverRemoteEnabled() {
        PanelBootstrapRules.State state = PanelBootstrapRules.begin(
            "", false, false, 0, false, 0);

        assertFalse(state.blocksConfiguredUse(""));
        assertFalse(state.allowsRemoteOperations(""));
        assertTrue(state.mode == PanelBootstrapRules.Mode.PREVIEW_ONLY);
    }

    @Test
    public void validBoundCachesRemainUsableDuringBestEffortRefresh() {
        PanelBootstrapRules.State state = PanelBootstrapRules.begin(
            CONNECTION_A, true, true, 7, true, 7);
        assertTrue(state.allowsRemoteOperations(CONNECTION_A));

        state = PanelBootstrapRules.onRefreshFinished(state,
            PanelBootstrapRules.Source.CONFIG,
            CONNECTION_A, CONNECTION_A, true, 7, true, 7);
        assertTrue(state.allowsRemoteOperations(CONNECTION_A));
    }

    @Test
    public void stagedCandidateBlocksNewFlowButExactActiveWorkflowCanFinish() {
        PanelBootstrapRules.State ready = PanelBootstrapRules.begin(
            CONNECTION_A, true, true, 7, true, 7);

        PanelBootstrapRules.State pending =
            PanelBootstrapRules.awaitingCandidatePromotion(ready);

        assertTrue(pending.blocksConfiguredUse(CONNECTION_A));
        assertFalse(pending.allowsRemoteOperations(CONNECTION_A));
        assertTrue(PanelBootstrapRules.allowsActiveWorkflow(
            pending, CONNECTION_A, true, 7, true, 7));
    }

    @Test
    public void twoFinishedResponsesWithDifferentRevisionsStayLocked() {
        PanelBootstrapRules.State state = PanelBootstrapRules.begin(
            CONNECTION_A, true, false, 0, false, 0);
        state = PanelBootstrapRules.onRefreshFinished(state,
            PanelBootstrapRules.Source.CONFIG,
            CONNECTION_A, CONNECTION_A, true, 8, false, 0);
        state = PanelBootstrapRules.onRefreshFinished(state,
            PanelBootstrapRules.Source.CATALOG,
            CONNECTION_A, CONNECTION_A, true, 8, true, 7);

        assertTrue(state.allRefreshesFinished());
        assertFalse(state.pairCompatible);
        assertTrue(state.blocksConfiguredUse(CONNECTION_A));
    }

    @Test
    public void firstNewHalfLocksOldPairAndMatchingHalfUnlocksIt() {
        PanelBootstrapRules.State state = PanelBootstrapRules.begin(
            CONNECTION_A, true, true, 7, true, 7);
        assertTrue(state.allowsRemoteOperations(CONNECTION_A));

        state = PanelBootstrapRules.onRefreshFinished(state,
            PanelBootstrapRules.Source.CONFIG,
            CONNECTION_A, CONNECTION_A, true, 8, true, 7);
        assertFalse(state.pairCompatible);
        assertTrue(state.blocksConfiguredUse(CONNECTION_A));
        assertTrue(PanelBootstrapRules.allowsActiveWorkflow(state, CONNECTION_A,
            true, 7, true, 7));
        assertFalse(PanelBootstrapRules.allowsActiveWorkflow(state, CONNECTION_A,
            true, 8, true, 7));

        state = PanelBootstrapRules.onRefreshFinished(state,
            PanelBootstrapRules.Source.CATALOG,
            CONNECTION_A, CONNECTION_A, true, 8, true, 8);
        assertTrue(state.pairCompatible);
        assertTrue(state.allRefreshesFinished());
        assertTrue(state.allowsRemoteOperations(CONNECTION_A));
    }

    @Test
    public void nextPairedRefreshCanRecoverARevisionMismatch() {
        PanelBootstrapRules.State state = PanelBootstrapRules.begin(
            CONNECTION_A, true, true, 8, true, 7);
        assertTrue(state.blocksConfiguredUse(CONNECTION_A));

        state = PanelBootstrapRules.onRefreshFinished(state,
            PanelBootstrapRules.Source.CONFIG,
            CONNECTION_A, CONNECTION_A, true, 8, true, 7);
        state = PanelBootstrapRules.onRefreshFinished(state,
            PanelBootstrapRules.Source.CATALOG,
            CONNECTION_A, CONNECTION_A, true, 8, true, 8);
        assertTrue(state.allowsRemoteOperations(CONNECTION_A));
    }

    @Test
    public void completedRevisionMismatchGetsOnlyBoundedWholePairRetries() {
        PanelBootstrapRules.State mismatch = PanelBootstrapRules.begin(
            CONNECTION_A, true, true, 8, true, 7);
        mismatch = PanelBootstrapRules.onRefreshFinished(mismatch,
            PanelBootstrapRules.Source.CONFIG,
            CONNECTION_A, CONNECTION_A, true, 8, true, 7);
        mismatch = PanelBootstrapRules.onRefreshFinished(mismatch,
            PanelBootstrapRules.Source.CATALOG,
            CONNECTION_A, CONNECTION_A, true, 8, true, 7);

        assertTrue(PanelBootstrapRules.shouldRetryRevisionMismatch(
            mismatch, CONNECTION_A));
        assertFalse(PanelBootstrapRules.shouldRetryRevisionMismatch(
            mismatch, CONNECTION_B));
        assertTrue(PanelBootstrapRules.pairRetryDelayMillis(0) > 0L);
        assertTrue(PanelBootstrapRules.pairRetryDelayMillis(1)
            > PanelBootstrapRules.pairRetryDelayMillis(0));
        assertTrue(PanelBootstrapRules.pairRetryDelayMillis(2)
            > PanelBootstrapRules.pairRetryDelayMillis(1));
        assertTrue(PanelBootstrapRules.pairRetryDelayMillis(3) < 0L);

        // The retry state concerns disk only; an already-open complete v7 pair remains valid.
        assertTrue(PanelBootstrapRules.allowsActiveWorkflow(
            mismatch, CONNECTION_A, true, 7, true, 7));
    }
}
