package com.autoformkit.app.report;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stable 8-hex-char hash of (stage + errCode + subphase + stable ctx keys). Identical
 * failures across devices/runs collapse to the same fingerprint so the reporter can
 * deduplicate and downstream receivers can group them as one failure family.
 *
 * <p>Volatile fields (timestamps, exact SSIDs, attempt counters, MAC tails) must NOT
 * be in the fingerprint — pass only the stable subset via {@code stableCtx}.
 */
public final class Fingerprint {
    private Fingerprint() {}

    public static String compute(String stage, String errCode, String subphase,
                                 LinkedHashMap<String, String> stableCtx) {
        StringBuilder seed = new StringBuilder();
        seed.append(stage == null ? "" : stage).append('|');
        seed.append(errCode == null ? "" : errCode).append('|');
        seed.append(subphase == null ? "" : subphase);
        if (stableCtx != null) {
            for (Map.Entry<String, String> e : stableCtx.entrySet()) {
                seed.append('|').append(e.getKey()).append('=').append(e.getValue());
            }
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(seed.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4 && i < digest.length; i++) {
                hex.append(String.format("%02x", digest[i] & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ignored) {
            return Integer.toHexString(seed.toString().hashCode() & 0xffffffff);
        }
    }

    /**
     * Fingerprint a runtime failure using only root-cause identity. App/device/build metadata stays
     * in the report context for diagnosis, but must not split one problem after an upgrade. DNS is
     * the sole contextual discriminator because different failing hosts are different incidents.
     */
    public static String computeFailure(String stage, String errCode, String subphase,
                                        String dnsTarget) {
        LinkedHashMap<String, String> stable = new LinkedHashMap<>();
        if (dnsTarget != null && !dnsTarget.isEmpty()) {
            stable.put("dns_target", dnsTarget);
        }
        return compute(stage, errCode, subphase, stable);
    }
}
