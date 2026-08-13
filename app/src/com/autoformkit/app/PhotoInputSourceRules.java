package com.autoformkit.app;

import org.json.JSONObject;

/** Panel-owned source policy for an operator-supplied workflow photo. */
final class PhotoInputSourceRules {
    static final String KEY = "inputSource";
    static final String CAMERA = "camera";
    static final String GALLERY = "gallery";
    static final String FILE = "file";

    private PhotoInputSourceRules() {}

    /**
     * Old profiles did not carry this field and always opened the camera. Keep that exact default
     * while rejecting malformed new policy instead of silently changing an authored restriction.
     */
    static String from(JSONObject owner) {
        if (owner == null || !owner.has(KEY)) return CAMERA;
        Object raw = owner.opt(KEY);
        if (!(raw instanceof String)) throw invalid();
        String value = (String) raw;
        if (!value.equals(value.trim()) || !isAllowed(value)) throw invalid();
        return value;
    }

    static boolean isAllowed(String value) {
        return CAMERA.equals(value) || GALLERY.equals(value) || FILE.equals(value);
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
            "inputSource must be camera, gallery, or file");
    }
}
