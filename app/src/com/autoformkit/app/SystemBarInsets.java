package com.autoformkit.app;

import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Applies Android 15+ edge-to-edge insets to full-page programmatic content. */
final class SystemBarInsets {
    private static final Map<View, InitialPadding> INITIAL_PADDING =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<View, CameraOverlayInsets> CAMERA_OVERLAY_INSETS =
        Collections.synchronizedMap(new WeakHashMap<>());

    private SystemBarInsets() {
    }

    static boolean shouldApply(int sdkInt) {
        return sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM;
    }

    static void reserveSystemBars(View root) {
        if (root == null
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return;

        InitialPadding initial = INITIAL_PADDING.get(root);
        if (initial == null) {
            initial = new InitialPadding(
                root.getPaddingLeft(), root.getPaddingTop(),
                root.getPaddingRight(), root.getPaddingBottom());
            INITIAL_PADDING.put(root, initial);
        }
        InitialPadding baseline = initial;
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets systemBars = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int left = padded(baseline.left, systemBars.left);
            int top = padded(baseline.top, systemBars.top);
            int right = padded(baseline.right, systemBars.right);
            int bottom = padded(baseline.bottom, systemBars.bottom);
            if (view.getPaddingLeft() != left || view.getPaddingTop() != top
                    || view.getPaddingRight() != right
                    || view.getPaddingBottom() != bottom) {
                view.setPadding(left, top, right, bottom);
            }
            // This full-page root owns spacing, while descendants may still inspect the insets.
            return insets;
        });
    }

    /** Keeps camera controls clear of Android 15 system bars while preview stays edge-to-edge. */
    static void reserveCameraBars(
            View root, View topOverlay, View bottomOverlay, View... rotatingOverlays) {
        if (root == null || topOverlay == null || bottomOverlay == null
                || rotatingOverlays == null || rotatingOverlays.length == 0
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return;
        for (View overlay : rotatingOverlays) {
            if (overlay == null) return;
            CAMERA_OVERLAY_INSETS.put(overlay, new CameraOverlayInsets(
                new InitialPadding(
                    overlay.getPaddingLeft(), overlay.getPaddingTop(),
                    overlay.getPaddingRight(), overlay.getPaddingBottom())));
        }

        InitialPadding topPadding = new InitialPadding(
            topOverlay.getPaddingLeft(), topOverlay.getPaddingTop(),
            topOverlay.getPaddingRight(), topOverlay.getPaddingBottom());
        InitialPadding bottomPadding = new InitialPadding(
            bottomOverlay.getPaddingLeft(), bottomOverlay.getPaddingTop(),
            bottomOverlay.getPaddingRight(), bottomOverlay.getPaddingBottom());
        int topHeight = topOverlay.getLayoutParams().height;
        int bottomHeight = bottomOverlay.getLayoutParams().height;
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets systemBars = insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            topOverlay.setPadding(
                padded(topPadding.left, systemBars.left),
                padded(topPadding.top, systemBars.top),
                padded(topPadding.right, systemBars.right),
                topPadding.bottom);
            bottomOverlay.setPadding(
                padded(bottomPadding.left, systemBars.left),
                bottomPadding.top,
                padded(bottomPadding.right, systemBars.right),
                padded(bottomPadding.bottom, systemBars.bottom));
            for (View overlay : rotatingOverlays) {
                CameraOverlayInsets state = CAMERA_OVERLAY_INSETS.get(overlay);
                if (state == null) continue;
                state.left = systemBars.left;
                state.top = systemBars.top;
                state.right = systemBars.right;
                state.bottom = systemBars.bottom;
                applyCameraOverlayInsets(overlay, state);
            }
            setHeight(topOverlay, padded(topHeight, systemBars.top));
            setHeight(bottomOverlay, padded(bottomHeight, systemBars.bottom));
            return insets;
        });
    }

    /** Maps physical window insets into a chrome layer's rotated local coordinates. */
    static void rotateCameraOverlayInsets(View overlay, int rotationDegrees) {
        CameraOverlayInsets state = CAMERA_OVERLAY_INSETS.get(overlay);
        if (state == null) return;
        state.rotationDegrees = rotationDegrees == 90 || rotationDegrees == 180
            || rotationDegrees == 270 ? rotationDegrees : 0;
        applyCameraOverlayInsets(overlay, state);
    }

    private static void applyCameraOverlayInsets(View overlay, CameraOverlayInsets state) {
        int left = state.left;
        int top = state.top;
        int right = state.right;
        int bottom = state.bottom;
        if (state.rotationDegrees == 90) {
            left = state.top;
            top = state.right;
            right = state.bottom;
            bottom = state.left;
        } else if (state.rotationDegrees == 180) {
            left = state.right;
            top = state.bottom;
            right = state.left;
            bottom = state.top;
        } else if (state.rotationDegrees == 270) {
            left = state.bottom;
            top = state.left;
            right = state.top;
            bottom = state.right;
        }
        InitialPadding baseline = state.baseline;
        overlay.setPadding(
            padded(baseline.left, left), padded(baseline.top, top),
            padded(baseline.right, right), padded(baseline.bottom, bottom));
    }

    private static void setHeight(View view, int height) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params.height == height) return;
        params.height = height;
        view.setLayoutParams(params);
    }

    /** Requests a fresh dispatch after the page has joined the window hierarchy. */
    static void requestWhenAttached(View root) {
        if (root == null
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return;
        root.post(root::requestApplyInsets);
    }

    static int padded(int initialPadding, int systemInset) {
        long result = (long) initialPadding + Math.max(0, systemInset);
        if (result > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (result < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) result;
    }

    private static final class InitialPadding {
        final int left;
        final int top;
        final int right;
        final int bottom;

        InitialPadding(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private static final class CameraOverlayInsets {
        final InitialPadding baseline;
        int left;
        int top;
        int right;
        int bottom;
        int rotationDegrees;

        CameraOverlayInsets(InitialPadding baseline) {
            this.baseline = baseline;
        }
    }
}
