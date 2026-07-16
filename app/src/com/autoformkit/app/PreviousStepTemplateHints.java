package com.autoformkit.app;

/**
 * Verified legacy BMS packaging-template to previous-step-template relationships.
 *
 * <p>Profiles may override these through the mapping-only {@code previousStepTemplates} or through
 * {@code autoCreatePreviousSteps}; this table is only the compatibility fallback for known
 * production templates that predate the config-driven catalog.
 */
final class PreviousStepTemplateHints {
    private PreviousStepTemplateHints() {
    }

    static int forProcessStep(int currentTemplateId, int processStep) {
        if (processStep != 1 && processStep != 2) return 0;
        switch (currentTemplateId) {
            case 769: return processStep == 1 ? 767 : 768;
            case 1900: return processStep == 1 ? 1897 : 1899;
            case 1482: return processStep == 1 ? 1480 : 1481;
            case 1902: return processStep == 1 ? 1898 : 1901;
            case 1786: return processStep == 1 ? 1784 : 1785;
            case 31973: return processStep == 1 ? 31971 : 31972;
            case 32017: return processStep == 1 ? 32015 : 32016;
            default: return 0;
        }
    }
}
