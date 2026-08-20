package com.autoformkit.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CameraOrientationRulesTest {
    @Test
    public void rawGravityAnglesMapToCameraXSurfaceRotations() {
        assertEquals(0, CameraOrientationRules.snapSurfaceRotation(0, -1));
        assertEquals(3, CameraOrientationRules.snapSurfaceRotation(90, -1));
        assertEquals(2, CameraOrientationRules.snapSurfaceRotation(180, -1));
        assertEquals(1, CameraOrientationRules.snapSurfaceRotation(270, -1));
        assertEquals(0, CameraOrientationRules.snapSurfaceRotation(359, -1));
    }

    @Test
    public void tenDegreeHysteresisPreventsBoundaryJitter() {
        assertEquals(0, CameraOrientationRules.snapSurfaceRotation(54, 0));
        assertEquals(3, CameraOrientationRules.snapSurfaceRotation(55, 0));
        assertEquals(3, CameraOrientationRules.snapSurfaceRotation(36, 3));
        assertEquals(0, CameraOrientationRules.snapSurfaceRotation(35, 3));
        assertEquals(3, CameraOrientationRules.snapSurfaceRotation(144, 3));
        assertEquals(2, CameraOrientationRules.snapSurfaceRotation(145, 3));
        assertEquals(2, CameraOrientationRules.snapSurfaceRotation(234, 2));
        assertEquals(1, CameraOrientationRules.snapSurfaceRotation(235, 2));
        assertEquals(1, CameraOrientationRules.snapSurfaceRotation(324, 1));
        assertEquals(0, CameraOrientationRules.snapSurfaceRotation(325, 1));
        assertEquals(0, CameraOrientationRules.snapSurfaceRotation(306, 0));
        assertEquals(1, CameraOrientationRules.snapSurfaceRotation(305, 0));
        assertEquals(2, CameraOrientationRules.snapSurfaceRotation(180, 0));
        assertEquals(2, CameraOrientationRules.snapSurfaceRotation(-1, 2));
    }

    @Test
    public void relativeRotationWorksForEveryLockedWindowBase() {
        int[] degrees = {0, 90, 180, 270};
        for (int target = 0; target < 4; target++) {
            for (int base = 0; base < 4; base++) {
                int expected = (degrees[target] - degrees[base] + 360) % 360;
                assertEquals(expected,
                    CameraOrientationRules.relativeDegrees(target, base));
            }
        }
    }
}
