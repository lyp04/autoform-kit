package com.autoformkit.app;

/** Pure decision rules for the two-phase cloud-label confirmation flow. */
final class PrintConfirmationRules {
    enum Result {
        PRINTED,
        FAILED,
        MISSING
    }

    private PrintConfirmationRules() {
    }

    static Result classify(boolean confirmedPrinted, boolean jobEverSeen) {
        if (confirmedPrinted) return Result.PRINTED;
        return jobEverSeen ? Result.FAILED : Result.MISSING;
    }

    static boolean shouldAlertAfterInline(Result result) {
        return result == Result.FAILED;
    }

    static boolean shouldDeferUntilBatchEnd(Result result) {
        return result == Result.MISSING;
    }

    static boolean shouldWaitForDelayedBatchCheck(Result result) {
        return result == Result.MISSING;
    }

    static boolean shouldAlertAfterFinalBatchCheck(Result result) {
        return result != Result.PRINTED;
    }

    static boolean isSubmittedButUnconfirmed(String submitStatus, String printedStatus) {
        return "ok".equals(submitStatus) && !"ok".equals(printedStatus);
    }

    static boolean shouldShowSessionExpiredNotice(boolean hadSession, boolean hasUnconfirmedPrints) {
        return hadSession || hasUnconfirmedPrints;
    }
}
