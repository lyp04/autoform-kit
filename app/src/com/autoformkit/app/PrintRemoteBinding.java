package com.autoformkit.app;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Immutable identity for one print-status read or reprint command.
 *
 * <p>The UI may outlive a profile or Panel refresh.  Keeping every value which can affect a
 * request in this object lets the caller reject a stale callback instead of combining an old job
 * or serial number with the newly-selected backend.</p>
 */
final class PrintRemoteBinding {
    final String connectionNamespace;
    final int catalogVersion;
    final String panelPairSha256;
    final String profileId;
    final String backendSemanticsSha256;
    final String tokenSha256;
    final String policySha256;
    final long jobId;
    final String serial;

    private PrintRemoteBinding(String connectionNamespace, int catalogVersion,
                               String panelPairSha256, String profileId,
                               String backendSemanticsSha256, String tokenSha256,
                               String policySha256, long jobId, String serial) {
        this.connectionNamespace = requiredConnection(connectionNamespace);
        if (catalogVersion <= 0) throw new IllegalArgumentException("catalogVersion is required");
        this.catalogVersion = catalogVersion;
        this.panelPairSha256 = requiredSha256(panelPairSha256, "panelPairSha256");
        this.profileId = requiredBounded(profileId, "profileId", 160);
        this.backendSemanticsSha256 = requiredSha256(
            backendSemanticsSha256, "backendSemanticsSha256");
        this.tokenSha256 = requiredSha256(tokenSha256, "tokenSha256");
        this.policySha256 = requiredSha256(policySha256, "policySha256");
        if (jobId < 0L) throw new IllegalArgumentException("jobId cannot be negative");
        this.jobId = jobId;
        this.serial = requiredOpaqueBounded(serial, "serial", 256);
    }

    static PrintRemoteBinding capture(String connectionNamespace, int catalogVersion,
                                      String panelPairSha256, String profileId,
                                      String backendSemanticsSha256, String webFingerprint,
                                      String token,
                                      String policySha256, long jobId, String serial) {
        return new PrintRemoteBinding(connectionNamespace, catalogVersion, panelPairSha256,
            profileId, backendSemanticsSha256,
            sessionSha256(webFingerprint, requiredOpaque(token, "token")),
            policySha256, jobId, serial);
    }

    PrintRemoteBinding forJob(long expectedJobId, String expectedSerial) {
        return new PrintRemoteBinding(connectionNamespace, catalogVersion, panelPairSha256,
            profileId, backendSemanticsSha256, tokenSha256, policySha256,
            expectedJobId, expectedSerial);
    }

    boolean sameExecutionContext(String connectionNamespace, int catalogVersion,
                                 String panelPairSha256, String profileId,
                                 String backendSemanticsSha256, String webFingerprint,
                                 String token,
                                 String policySha256) {
        if (token == null || token.isEmpty()) return false;
        return this.connectionNamespace.equals(trim(connectionNamespace))
            && this.catalogVersion == catalogVersion
            && this.panelPairSha256.equals(trim(panelPairSha256))
            && this.profileId.equals(trim(profileId))
            && this.backendSemanticsSha256.equals(trim(backendSemanticsSha256))
            && this.tokenSha256.equals(sessionSha256(webFingerprint, token))
            && this.policySha256.equals(trim(policySha256));
    }

    boolean identifies(long expectedJobId, String expectedSerial) {
        return jobId == expectedJobId && serial.equals(expectedSerial);
    }

    /** Every value which may affect one concrete remote print operation is identical. */
    boolean sameExactOperation(PrintRemoteBinding other) {
        return other != null
            && connectionNamespace.equals(other.connectionNamespace)
            && catalogVersion == other.catalogVersion
            && panelPairSha256.equals(other.panelPairSha256)
            && profileId.equals(other.profileId)
            && backendSemanticsSha256.equals(other.backendSemanticsSha256)
            && tokenSha256.equals(other.tokenSha256)
            && policySha256.equals(other.policySha256)
            && jobId == other.jobId
            && serial.equals(other.serial);
    }

    /**
     * Stable server-side target identity.  This is intentionally stronger than an exact-session
     * comparison for unresolved POSTs: logging in again or changing a local policy must not make an
     * uncertain POST to the same backend job eligible for replay.
     */
    boolean sameRemoteTarget(PrintRemoteBinding other) {
        return other != null
            && connectionNamespace.equals(other.connectionNamespace)
            && profileId.equals(other.profileId)
            && backendSemanticsSha256.equals(other.backendSemanticsSha256)
            && jobId == other.jobId
            && serial.equals(other.serial);
    }

    JSONObject toJson() {
        try {
            return new JSONObject()
                .put("connectionNamespace", connectionNamespace)
                .put("catalogVersion", catalogVersion)
                .put("panelPairSha256", panelPairSha256)
                .put("profileId", profileId)
                .put("backendSemanticsSha256", backendSemanticsSha256)
                .put("tokenSha256", tokenSha256)
                .put("policySha256", policySha256)
                .put("jobId", jobId)
                .put("serial", serial);
        } catch (Exception impossible) {
            throw new IllegalStateException("Cannot serialize print binding", impossible);
        }
    }

    static PrintRemoteBinding fromJson(JSONObject value) {
        if (value == null || value.length() != 9) {
            throw new IllegalArgumentException("print binding has invalid fields");
        }
        return new PrintRemoteBinding(
            requiredJsonString(value, "connectionNamespace"),
            exactPositiveInt(value.opt("catalogVersion"), "catalogVersion"),
            requiredJsonString(value, "panelPairSha256"),
            requiredJsonString(value, "profileId"),
            requiredJsonString(value, "backendSemanticsSha256"),
            requiredJsonString(value, "tokenSha256"),
            requiredJsonString(value, "policySha256"),
            exactNonNegativeLong(value.opt("jobId"), "jobId"),
            requiredOpaqueJsonString(value, "serial"));
    }

    static String policySha256(boolean printingEnabled, String preflightAction,
                               boolean manualReprintEnabled,
                               boolean manualReprintRequiresConfirmation,
                               Set<String> manualReprintStatuses,
                               int confirmationPolls, long confirmationPollIntervalMs,
                               int maxAutoReprints, long finalRecheckDelayMs,
                               String onUnconfirmed, String batchEndRecheckMode) {
        List<String> statuses = new ArrayList<>();
        if (manualReprintStatuses != null) {
            for (String value : manualReprintStatuses) {
                String normalized = trim(value);
                if (!normalized.isEmpty()) statuses.add(normalized);
            }
        }
        Collections.sort(statuses);
        StringBuilder canonical = new StringBuilder();
        append(canonical, printingEnabled ? "1" : "0");
        append(canonical, trim(preflightAction));
        append(canonical, manualReprintEnabled ? "1" : "0");
        append(canonical, manualReprintRequiresConfirmation ? "1" : "0");
        for (String status : statuses) append(canonical, status);
        append(canonical, String.valueOf(confirmationPolls));
        append(canonical, String.valueOf(confirmationPollIntervalMs));
        append(canonical, String.valueOf(maxAutoReprints));
        append(canonical, String.valueOf(finalRecheckDelayMs));
        append(canonical, trim(onUnconfirmed));
        append(canonical, trim(batchEndRecheckMode));
        return sha256(canonical.toString());
    }

    private static void append(StringBuilder out, String value) {
        String safe = value == null ? "" : value;
        out.append(safe.length()).append(':').append(safe).append(';');
    }

    private static String required(String value, String label) {
        String normalized = trim(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return normalized;
    }

    private static String requiredOpaque(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private static String requiredConnection(String value) {
        String normalized = trim(value);
        if (!normalized.matches("[0-9a-f]{20}")) {
            throw new IllegalArgumentException("connectionNamespace must be 20 hex characters");
        }
        return normalized;
    }

    private static String requiredBounded(String value, String label, int maxLength) {
        String normalized = required(value, label);
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + " is too long");
        }
        return normalized;
    }

    private static String requiredOpaqueBounded(String value, String label, int maxLength) {
        String exact = requiredOpaque(value, label);
        if (exact.length() > maxLength) {
            throw new IllegalArgumentException(label + " is too long");
        }
        return exact;
    }

    private static String requiredSha256(String value, String label) {
        String normalized = trim(value).toLowerCase(java.util.Locale.US);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " must be SHA-256");
        }
        return normalized;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String requiredJsonString(JSONObject value, String key) {
        Object raw = value == null ? null : value.opt(key);
        if (!(raw instanceof String)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return required((String) raw, key);
    }

    private static String requiredOpaqueJsonString(JSONObject value, String key) {
        Object raw = value == null ? null : value.opt(key);
        if (!(raw instanceof String)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return requiredOpaque((String) raw, key);
    }

    private static int exactPositiveInt(Object raw, String label) {
        long value = exactNonNegativeLong(raw, label);
        if (value <= 0L || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(label + " must be a positive integer");
        }
        return (int) value;
    }

    private static long exactNonNegativeLong(Object raw, String label) {
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer
                || raw instanceof Long)) {
            throw new IllegalArgumentException(label + " must be an integer");
        }
        long value = ((Number) raw).longValue();
        if (value < 0L) throw new IllegalArgumentException(label + " cannot be negative");
        return value;
    }

    static String sha256(String value) {
        return sha256Bytes((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] value) {
        return sha256Bytes(value == null ? new byte[0] : value);
    }

    private static String sha256Bytes(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value);
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String sessionSha256(String webFingerprint, String exactToken) {
        String fingerprint = webFingerprint == null ? "" : webFingerprint;
        String token = exactToken == null ? "" : exactToken;
        return sha256("autoform-kit/print-session/v1\n"
            + fingerprint.length() + ":" + fingerprint + "\n"
            + token.length() + ":" + token);
    }
}
