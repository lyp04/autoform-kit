package com.autoformkit.app;

/** Pure four-way orientation rules shared by camera UI and local tests. */
final class CameraOrientationRules {
    private static final int ROTATION_0 = 0;
    private static final int ROTATION_90 = 1;
    private static final int ROTATION_180 = 2;
    private static final int ROTATION_270 = 3;
    private static final int HYSTERESIS_DEGREES = 10;

    private CameraOrientationRules() {
    }

    static int snapSurfaceRotation(int rawOrientation, int currentRotation) {
        if (rawOrientation < 0) return currentRotation;
        int raw = normalizeDegrees(rawOrientation);
        int candidate = raw < 45 || raw >= 315 ? ROTATION_0
            : raw < 135 ? ROTATION_270
            : raw < 225 ? ROTATION_180
            : ROTATION_90;
        if (!isSurfaceRotation(currentRotation) || candidate == currentRotation) {
            return candidate;
        }
        int currentCenter = currentRotation == ROTATION_0 ? 0
            : currentRotation == ROTATION_270 ? 90
            : currentRotation == ROTATION_180 ? 180 : 270;
        int distance = Math.abs(raw - currentCenter);
        distance = Math.min(distance, 360 - distance);
        return distance < 45 + HYSTERESIS_DEGREES ? currentRotation : candidate;
    }

    static int relativeDegrees(int targetRotation, int baseRotation) {
        if (!isSurfaceRotation(targetRotation) || !isSurfaceRotation(baseRotation)) return 0;
        return normalizeDegrees(
            surfaceRotationDegrees(targetRotation) - surfaceRotationDegrees(baseRotation));
    }

    static boolean isSurfaceRotation(int rotation) {
        return rotation == ROTATION_0 || rotation == ROTATION_90
            || rotation == ROTATION_180 || rotation == ROTATION_270;
    }

    private static int surfaceRotationDegrees(int rotation) {
        if (rotation == ROTATION_90) return 90;
        if (rotation == ROTATION_180) return 180;
        if (rotation == ROTATION_270) return 270;
        return 0;
    }

    private static int normalizeDegrees(int degrees) {
        int normalized = degrees % 360;
        return normalized < 0 ? normalized + 360 : normalized;
    }
}
