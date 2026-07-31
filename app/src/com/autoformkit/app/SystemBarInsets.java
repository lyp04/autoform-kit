package com.autoformkit.app;

import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Applies Android 15+ edge-to-edge insets to full-page programmatic content. */
final class SystemBarInsets {
    private static final Map<View, InitialPadding> INITIAL_PADDING =
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
}
