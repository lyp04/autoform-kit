package com.autoformkit.app;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Strict, durable target identity for one main-form camera or scanner round trip. */
final class PendingFormOperationRules {
    static final int VERSION = 1;

    static final String SCAN = OperationBindingRules.MAIN_SCAN;
    static final String RESCAN = OperationBindingRules.MAIN_RESCAN;
    static final String PHOTO = OperationBindingRules.MAIN_PHOTO;
    static final String OCR_PHOTO = OperationBindingRules.MAIN_OCR_PHOTO;

    static final String ROLE_PRIMARY = "primary";
    static final String ROLE_SECONDARY = "secondary";
    static final String ROLE_PHOTO = "photo";

    private static final Set<String> KINDS = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList(SCAN, RESCAN, PHOTO, OCR_PHOTO)));
    private static final Set<String> ROLES = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList(ROLE_PRIMARY, ROLE_SECONDARY, ROLE_PHOTO)));
    private static final Set<String> PHOTO_SIDES = Collections.unmodifiableSet(new HashSet<>(
        Arrays.asList("front", "back", "supplemental", "slot", "artifact")));

    static final class Target {
        final String kind;
        final String operationId;
        final String connectionNamespace;
        final int catalogVersion;
        final String pairSha256;
        final String draftSemanticsSha256;
        final String profileId;
        final int unitSequence;
        final String role;
        final String side;
        final String field;
        final String outputPath;
        final String grade;
        final OperationBindingRules.Binding operationBinding;

        private Target(String kind, String operationId, String connectionNamespace,
                       int catalogVersion, String pairSha256, String draftSemanticsSha256,
                       String profileId, int unitSequence, String role, String side,
                       String field, String outputPath, String grade,
                       OperationBindingRules.Binding operationBinding) {
            this.kind = requiredKind(kind);
            this.operationId = requiredOperationId(operationId);
            this.connectionNamespace = requiredNamespace(connectionNamespace);
            if (catalogVersion <= 0) throw invalid("catalogVersion must be positive");
            this.catalogVersion = catalogVersion;
            this.pairSha256 = requiredDigest(pairSha256, "pairSha256");
            this.draftSemanticsSha256 = requiredDigest(
                draftSemanticsSha256, "draftSemanticsSha256");
            this.profileId = requiredText(profileId, "profileId", 256);
            if (unitSequence <= 0) throw invalid("unitSequence must be positive");
            this.unitSequence = unitSequence;
            this.role = requiredRole(role);
            this.side = optionalText(side, "side", 64);
            this.field = optionalText(field, "field", 256);
            this.outputPath = optionalText(outputPath, "outputPath", 4096);
            this.grade = optionalText(grade, "grade", 256);
            if (operationBinding == null || !operationId.equals(operationBinding.nonce)
                    || !this.kind.equals(operationBinding.kind)
                    || !this.connectionNamespace.equals(operationBinding.connectionNamespace)
                    || this.catalogVersion != operationBinding.catalogVersion
                    || !this.pairSha256.equals(operationBinding.pairSha256)) {
                throw invalid("operation binding does not match target");
            }
            this.operationBinding = operationBinding;
            validateShape();
        }

        private void validateShape() {
            if (PHOTO.equals(kind)) {
                if (!ROLE_PHOTO.equals(role) || !PHOTO_SIDES.contains(side)
                        || outputPath.isEmpty()) {
                    throw invalid("photo target shape is invalid");
                }
                boolean fieldRequired = "slot".equals(side) || "artifact".equals(side);
                if (fieldRequired == field.isEmpty()) {
                    throw invalid("photo field does not match side");
                }
                return;
            }
            if (!side.isEmpty() || !field.isEmpty()) {
                throw invalid("non-photo target cannot have side or field");
            }
            if (OCR_PHOTO.equals(kind)) {
                if (ROLE_PHOTO.equals(role) || outputPath.isEmpty()) {
                    throw invalid("OCR photo target shape is invalid");
                }
                return;
            }
            if (!outputPath.isEmpty() || ROLE_PHOTO.equals(role)) {
                throw invalid("scanner target shape is invalid");
            }
        }

        JSONObject toJson() {
            try {
                return new JSONObject()
                    .put("version", VERSION)
                    .put("kind", kind)
                    .put("operationId", operationId)
                    .put("connectionNamespace", connectionNamespace)
                    .put("catalogVersion", catalogVersion)
                    .put("pairSha256", pairSha256)
                    .put("draftSemanticsSha256", draftSemanticsSha256)
                    .put("profileId", profileId)
                    .put("unitSequence", unitSequence)
                    .put("role", role)
                    .put("side", side)
                    .put("field", field)
                    .put("outputPath", outputPath)
                    .put("grade", grade)
                    .put("operationBinding", operationBinding.toJson());
            } catch (Exception impossible) {
                throw invalid("cannot serialize pending target");
            }
        }

        boolean matches(MainDraftSnapshotRules.Binding draftBinding, String pairSha256,
                        String webFingerprint, String token) {
            return draftBinding != null
                && connectionNamespace.equals(draftBinding.connectionNamespace)
                && catalogVersion == draftBinding.catalogVersion
                && profileId.equals(draftBinding.profileId)
                && draftSemanticsSha256.equals(draftBinding.semanticsSha256)
                && this.pairSha256.equals(pairSha256)
                && operationBinding.matchesContext(connectionNamespace, catalogVersion,
                    pairSha256, webFingerprint, token, kind);
        }
    }

    private PendingFormOperationRules() {}

    static Target create(String kind, String operationId,
                         MainDraftSnapshotRules.Binding draftBinding,
                         String pairSha256, int unitSequence, String role,
                         String side, String field, String outputPath, String grade,
                         OperationBindingRules.Binding operationBinding) {
        if (draftBinding == null) throw invalid("draft binding is required");
        return new Target(kind, operationId, draftBinding.connectionNamespace,
            draftBinding.catalogVersion, pairSha256, draftBinding.semanticsSha256,
            draftBinding.profileId, unitSequence, role, side, field, outputPath,
            grade, operationBinding);
    }

    static Target parse(String raw) {
        try {
            JSONObject value = new JSONObject(requiredText(raw, "pending target", 32768));
            if (value.length() != 15 || exactInteger(value.opt("version")) != VERSION
                    || !(value.opt("operationBinding") instanceof JSONObject)) {
                throw invalid("invalid pending target fields");
            }
            return new Target(requiredString(value, "kind"),
                requiredString(value, "operationId"),
                requiredString(value, "connectionNamespace"),
                positiveInteger(value.opt("catalogVersion"), "catalogVersion"),
                requiredString(value, "pairSha256"),
                requiredString(value, "draftSemanticsSha256"),
                requiredString(value, "profileId"),
                positiveInteger(value.opt("unitSequence"), "unitSequence"),
                requiredString(value, "role"), requiredString(value, "side"),
                requiredString(value, "field"), requiredString(value, "outputPath"),
                requiredString(value, "grade"),
                OperationBindingRules.parse(value.getJSONObject("operationBinding")));
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (Exception error) {
            throw invalid("pending target is malformed");
        }
    }

    private static int exactInteger(Object value) {
        if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) return 0;
        long number = ((Number) value).longValue();
        return number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE ? (int) number : 0;
    }

    private static int positiveInteger(Object value, String field) {
        int number = exactInteger(value);
        if (number <= 0) throw invalid(field + " must be a positive integer");
        return number;
    }

    private static String requiredString(JSONObject value, String key) {
        Object raw = value == null ? null : value.opt(key);
        if (!(raw instanceof String)) throw invalid(key + " must be a string");
        return (String) raw;
    }

    private static String requiredKind(String value) {
        String safe = requiredText(value, "kind", 64);
        if (!KINDS.contains(safe)) throw invalid("kind is invalid");
        return safe;
    }

    private static String requiredRole(String value) {
        String safe = requiredText(value, "role", 64);
        if (!ROLES.contains(safe)) throw invalid("role is invalid");
        return safe;
    }

    private static String requiredOperationId(String value) {
        String safe = requiredText(value, "operationId", 128);
        if (safe.length() < 16 || !safe.matches("[A-Za-z0-9_-]+")) {
            throw invalid("operationId is invalid");
        }
        return safe;
    }

    private static String requiredNamespace(String value) {
        String safe = requiredText(value, "connectionNamespace", 20);
        if (!safe.matches("[0-9a-f]{20}")) throw invalid("connectionNamespace is invalid");
        return safe;
    }

    private static String requiredDigest(String value, String field) {
        String safe = requiredText(value, field, 64);
        if (!safe.matches("[0-9a-f]{64}")) throw invalid(field + " is invalid");
        return safe;
    }

    private static String optionalText(String value, String field, int maxLength) {
        String safe = value == null ? "" : value;
        if (!safe.equals(safe.trim()) || safe.length() > maxLength) {
            throw invalid(field + " is invalid");
        }
        return safe;
    }

    private static String requiredText(String value, String field, int maxLength) {
        String safe = optionalText(value, field, maxLength);
        if (safe.isEmpty()) throw invalid(field + " is required");
        return safe;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
