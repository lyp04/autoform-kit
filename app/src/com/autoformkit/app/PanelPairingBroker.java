package com.autoformkit.app;

/**
 * Process-memory rendezvous between the exported browser entry and the existing form Activity.
 *
 * <p>No Activity reference and no raw URI is retained. If Capture/Scanner is above MainActivity,
 * the entry Activity simply finishes and this delivery waits until MainActivity naturally resumes;
 * it never reorders or clears the live result stack. Process death intentionally drops the ticket.
 */
final class PanelPairingBroker {
    enum LaunchDecision {
        MAIN_EXISTS,
        LAUNCH_MAIN,
        LAUNCH_PENDING
    }

    static final class Delivery {
        final PanelPairingLinkRules.Request request;
        final boolean invalid;

        private Delivery(PanelPairingLinkRules.Request request, boolean invalid) {
            this.request = request;
            this.invalid = invalid;
        }
    }

    private static int mainActivityCount;
    private static boolean mainLaunchReserved;
    private static Delivery pending;

    private PanelPairingBroker() {}

    /** Claims the one process-wide form owner; a duplicate Activity must finish immediately. */
    static synchronized boolean mainActivityCreated() {
        mainLaunchReserved = false;
        if (mainActivityCount > 0) return false;
        mainActivityCount = 1;
        return true;
    }

    static synchronized void mainActivityDestroyed() {
        mainActivityCount = 0;
    }

    /** Stores the latest browser action and atomically elects at most one cold-starting entry. */
    static synchronized LaunchDecision offer(
            PanelPairingLinkRules.Request request, boolean invalid) {
        if ((request == null) == !invalid) {
            throw new IllegalArgumentException("delivery must be either valid or invalid");
        }
        pending = new Delivery(request, invalid);
        if (mainActivityCount > 0) return LaunchDecision.MAIN_EXISTS;
        if (mainLaunchReserved) return LaunchDecision.LAUNCH_PENDING;
        mainLaunchReserved = true;
        return LaunchDecision.LAUNCH_MAIN;
    }

    static synchronized Delivery take() {
        Delivery result = pending;
        pending = null;
        return result;
    }

    static synchronized void releaseLaunchReservation() {
        mainLaunchReserved = false;
    }

    static synchronized void resetForTest() {
        mainActivityCount = 0;
        mainLaunchReserved = false;
        pending = null;
    }
}
