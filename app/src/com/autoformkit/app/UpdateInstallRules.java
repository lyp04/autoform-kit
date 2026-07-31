package com.autoformkit.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;

/** Pure source/pending-install binding rules; Android I/O stays in {@link UpdateManager}. */
final class UpdateInstallRules {
    static final int PENDING_SCHEMA = 2;
    /** Serializes handoff-token issuance and provider consumption inside this app process. */
    static final Object HANDOFF_LOCK = new Object();

    private UpdateInstallRules() {
    }

    static final class SourceBinding {
        final String connectionNamespace;
        final int catalogVersion;
        final String panelPairSha256;
        final String channel;
        final String owner;
        final String repo;
        final String manifestAsset;
        final String releaseTag;
        final String sha256;

        private SourceBinding(String connectionNamespace, int catalogVersion,
                              String panelPairSha256, String channel,
                              String owner, String repo, String manifestAsset,
                              String releaseTag) {
            this.connectionNamespace = requiredConnection(connectionNamespace);
            if (catalogVersion <= 0) throw new IllegalArgumentException("catalogVersion is required");
            this.catalogVersion = catalogVersion;
            this.panelPairSha256 = requiredSha256(panelPairSha256, "panelPairSha256");
            this.channel = requiredChannel(channel);
            this.owner = requiredOwner(owner);
            this.repo = requiredRepo(repo);
            this.manifestAsset = safeAssetName(manifestAsset, "manifestAsset", false);
            this.releaseTag = safeReleaseTag(releaseTag);
            this.sha256 = sha256(canonical(connectionNamespace,
                String.valueOf(catalogVersion), this.panelPairSha256, channel, owner, repo,
                this.manifestAsset, this.releaseTag));
        }

        static SourceBinding capture(String connectionNamespace, int catalogVersion,
                                     String panelPairSha256, String channel,
                                     String owner, String repo,
                                     String manifestAsset, String releaseTag) {
            return new SourceBinding(connectionNamespace, catalogVersion, panelPairSha256,
                channel, owner, repo, manifestAsset, releaseTag);
        }

        boolean sameAs(SourceBinding other) {
            return other != null && sha256.equals(other.sha256)
                && connectionNamespace.equals(other.connectionNamespace)
                && catalogVersion == other.catalogVersion
                && panelPairSha256.equals(other.panelPairSha256);
        }
    }

    static final class PendingMetadata {
        final String apkName;
        final String manifestSha256;
        final String apkSha256;
        final String packageName;
        final long versionCode;
        final String versionName;
        final String sourceSha256;
        final String signerSetSha256;
        final long apkLength;
        final long apkLastModified;

        PendingMetadata(String apkName, String manifestSha256, String apkSha256,
                        String packageName, long versionCode, String versionName,
                        String sourceSha256, String signerSetSha256, long apkLength,
                        long apkLastModified) {
            this.apkName = safeAssetName(apkName, "apkName", true);
            this.manifestSha256 = requiredSha256(manifestSha256, "manifestSha256");
            this.apkSha256 = requiredSha256(apkSha256, "apkSha256");
            this.packageName = required(packageName, "packageName");
            if (versionCode <= 0L) throw new IllegalArgumentException("versionCode is required");
            this.versionCode = versionCode;
            this.versionName = required(versionName, "versionName");
            this.sourceSha256 = requiredSha256(sourceSha256, "sourceSha256");
            this.signerSetSha256 = requiredSha256(signerSetSha256, "signerSetSha256");
            if (apkLength <= 0L) throw new IllegalArgumentException("apkLength is required");
            this.apkLength = apkLength;
            if (apkLastModified <= 0L) {
                throw new IllegalArgumentException("apkLastModified is required");
            }
            this.apkLastModified = apkLastModified;
        }

        boolean matchesSource(SourceBinding source) {
            return source != null && sourceSha256.equals(source.sha256);
        }

        boolean matchesValidated(String apkName, String manifestSha256, String apkSha256,
                                 String packageName, long versionCode, String versionName,
                                 String signerSetSha256, long apkLength,
                                 long apkLastModified) {
            return this.apkName.equals(trim(apkName))
                && this.manifestSha256.equals(trim(manifestSha256).toLowerCase(Locale.US))
                && this.apkSha256.equals(trim(apkSha256).toLowerCase(Locale.US))
                && this.packageName.equals(trim(packageName))
                && this.versionCode == versionCode
                && this.versionName.equals(trim(versionName))
                && this.signerSetSha256.equals(
                    trim(signerSetSha256).toLowerCase(Locale.US))
                && this.apkLength == apkLength
                && this.apkLastModified == apkLastModified;
        }
    }

    static String localApkName(long versionCode, String apkSha256) {
        String digest = requiredSha256(apkSha256, "apkSha256");
        if (versionCode <= 0L) throw new IllegalArgumentException("versionCode is required");
        return "update-" + versionCode + "-" + digest.substring(0, 20) + ".apk";
    }

    static String pendingIdentitySha256(SourceBinding source, PendingMetadata metadata) {
        if (source == null || metadata == null || !metadata.matchesSource(source)) {
            throw new IllegalArgumentException("pending source/metadata binding is invalid");
        }
        return sha256("autoform-kit/pending-install/v2\n" + canonical(
            source.sha256,
            metadata.apkName,
            metadata.manifestSha256,
            metadata.apkSha256,
            metadata.packageName,
            String.valueOf(metadata.versionCode),
            metadata.versionName,
            metadata.signerSetSha256,
            String.valueOf(metadata.apkLength),
            String.valueOf(metadata.apkLastModified)));
    }

    static String handoffBindingSha256(String token, SourceBinding source,
                                       PendingMetadata metadata) {
        String normalizedToken = requiredHandoffToken(token);
        return sha256("autoform-kit/update-handoff/v1\n" + canonical(
            normalizedToken, pendingIdentitySha256(source, metadata),
            metadata.apkName, source.sha256));
    }

    static boolean isValidHandoffToken(String value) {
        try {
            requiredHandoffToken(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static boolean digestEquals(String first, String second) {
        String a = trim(first).toLowerCase(Locale.US);
        String b = trim(second).toLowerCase(Locale.US);
        if (!a.matches("[0-9a-f]{64}") || !b.matches("[0-9a-f]{64}")) return false;
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.US_ASCII),
            b.getBytes(StandardCharsets.US_ASCII));
    }

    static boolean isSafeApkName(String value) {
        try {
            safeAssetName(value, "apkName", true);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static boolean hasSignerContinuity(Set<String> installedCurrent,
                                       Set<String> candidateHistory) {
        if (installedCurrent == null || installedCurrent.isEmpty()
                || candidateHistory == null || candidateHistory.isEmpty()) return false;
        java.util.LinkedHashSet<String> installedNormalized = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> candidateNormalized = new java.util.LinkedHashSet<>();
        for (String candidate : candidateHistory) {
            String digest = trim(candidate).toLowerCase(Locale.US);
            if (!digest.matches("[0-9a-f]{64}")) return false;
            candidateNormalized.add(digest);
        }
        for (String installed : installedCurrent) {
            String digest = trim(installed).toLowerCase(Locale.US);
            if (!digest.matches("[0-9a-f]{64}")) return false;
            installedNormalized.add(digest);
            if (!candidateNormalized.contains(digest)) return false;
        }
        // Android signer rotation applies to a single signer. Multi-signer packages must retain
        // the exact signer set; accepting an added signer here would make our preflight weaker
        // than the platform installer.
        if (installedNormalized.size() > 1) {
            return candidateNormalized.size() == installedNormalized.size()
                && candidateNormalized.containsAll(installedNormalized);
        }
        return true;
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(Locale.US, "%02x", b & 0xff));
            return out.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String safeAssetName(String value, String label, boolean requireApk) {
        String normalized = required(value, label);
        if (normalized.contains("/") || normalized.contains("\\")
                || normalized.contains("..") || normalized.length() > 180
                || !normalized.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(label + " is unsafe");
        }
        if (requireApk && !normalized.toLowerCase(Locale.US).endsWith(".apk")) {
            throw new IllegalArgumentException(label + " must be an APK");
        }
        return normalized;
    }

    private static String requiredConnection(String value) {
        String normalized = trim(value);
        if (!normalized.matches("[0-9a-f]{20}")) {
            throw new IllegalArgumentException("connectionNamespace must be 20 hex characters");
        }
        return normalized;
    }

    private static String requiredChannel(String value) {
        String normalized = trim(value);
        if (!"stable".equals(normalized) && !"beta".equals(normalized)) {
            throw new IllegalArgumentException("channel must be stable or beta");
        }
        return normalized;
    }

    private static String requiredOwner(String value) {
        String normalized = required(value, "owner");
        if (normalized.length() > 39 || normalized.contains("--")
                || !normalized.matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")) {
            throw new IllegalArgumentException("owner is not a GitHub login segment");
        }
        return normalized;
    }

    private static String requiredRepo(String value) {
        String normalized = required(value, "repo");
        if (normalized.length() > 100 || normalized.contains("..")
                || !normalized.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("repo is not a GitHub repository segment");
        }
        return normalized;
    }

    private static String safeReleaseTag(String value) {
        String normalized = trim(value);
        if (normalized.length() > 120
                || (!normalized.isEmpty()
                    && (!normalized.matches("[A-Za-z0-9._-]+")
                        || normalized.contains("..")))) {
            throw new IllegalArgumentException("releaseTag is unsafe");
        }
        return normalized;
    }

    private static String canonical(String... values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            String safe = value == null ? "" : value.trim();
            out.append(safe.length()).append(':').append(safe).append(';');
        }
        return out.toString();
    }

    private static String required(String value, String label) {
        String normalized = trim(value);
        if (normalized.isEmpty()) throw new IllegalArgumentException(label + " is required");
        return normalized;
    }

    private static String requiredSha256(String value, String label) {
        String normalized = trim(value).toLowerCase(Locale.US);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " must be SHA-256");
        }
        return normalized;
    }

    private static String requiredHandoffToken(String value) {
        String normalized = trim(value);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("handoff token must be 256-bit lowercase hex");
        }
        return normalized;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
