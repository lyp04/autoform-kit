package com.autoformkit.app.report;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Strips/obscures personally-identifying or password material before logs leave the
 * device. Anything user-typed (SSIDs, passwords) and anything device-unique (MAC,
 * BSSID) is either dropped or replaced with a short non-reversible hash.
 */
public final class Redactor {
    private Redactor() {}

    /** Truncate to a 6-char SHA-1 prefix. Stable across runs, not reversible. */
    public static String hashShort(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 3 && i < d.length; i++) {
                hex.append(String.format("%02x", d[i] & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ignored) {
            return Integer.toHexString(value.hashCode() & 0xffff);
        }
    }

    /** Show only the OUI (top 3 octets) so vendor stays visible, host-specific bits drop. */
    public static String maskMac(String mac) {
        if (mac == null || mac.isEmpty()) {
            return "";
        }
        String n = mac.replace("-", ":").trim();
        String[] parts = n.split(":");
        if (parts.length < 6) {
            return "??";
        }
        return (parts[0] + ":" + parts[1] + ":" + parts[2] + ":xx:xx:xx").toUpperCase();
    }

    /**
     * Strip likely secret material out of a free-form message: anything resembling
     * a password=..., token=..., or a long base64-looking blob.
     */
    public static String scrubMessage(String text) {
        if (text == null) {
            return "";
        }
        String s = text;
        s = s.replaceAll("(?i)(password|passwd|pwd|psk|token|secret|access_token|gtoken|user_center_token)\\s*[:=]\\s*\\S+",
                "$1=<redacted>");
        s = redactLongBase64(s);
        s = s.replaceAll("(?i)\\b([0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b", "<mac>");
        if (s.length() > 800) {
            s = s.substring(0, 800) + "…";
        }
        return s;
    }

    private static String redactLongBase64(String input) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([A-Za-z0-9+/]{32,}={0,2})").matcher(input);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            int len = m.group(1).length();
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("<b64:" + len + "B>"));
        }
        m.appendTail(out);
        return out.toString();
    }

    public static String scrubThrowable(Throwable t) {
        if (t == null) {
            return null;
        }
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        String trace = sw.toString();
        if (trace.length() > 4000) {
            trace = trace.substring(0, 4000) + "\n…(truncated)";
        }
        return scrubMessage(trace);
    }
}
