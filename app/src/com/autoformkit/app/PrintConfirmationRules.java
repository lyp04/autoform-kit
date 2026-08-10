package com.autoformkit.app;

/** Pure decision rules for the cloud-label confirmation flow and its optional batch-end phase. */
final class PrintConfirmationRules {
    enum Result {
        PRINTED,
        FAILED,
        MISSING,
        /** A status lookup failed, or a reprint POST may have reached the server. */
        UNCERTAIN
    }

    private PrintConfirmationRules() {
    }

    static Result classify(boolean confirmedPrinted, boolean jobEverSeen) {
        return classify(confirmedPrinted, jobEverSeen, false);
    }

    static Result classify(boolean confirmedPrinted, boolean jobEverSeen,
                           boolean outcomeUncertain) {
        if (confirmedPrinted) return Result.PRINTED;
        if (outcomeUncertain) return Result.UNCERTAIN;
        return jobEverSeen ? Result.FAILED : Result.MISSING;
    }

    static boolean shouldAlertAfterInline(Result result) {
        return result == Result.FAILED || result == Result.UNCERTAIN;
    }

    static boolean shouldDeferUntilBatchEnd(Result result) {
        return result == Result.MISSING;
    }

    static boolean shouldDeferUntilBatchEnd(Result result,
                                            boolean deferredMissingTwoPassEnabled) {
        return deferredMissingTwoPassEnabled && shouldDeferUntilBatchEnd(result);
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
