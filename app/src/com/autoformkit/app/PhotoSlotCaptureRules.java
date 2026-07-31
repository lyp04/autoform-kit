package com.autoformkit.app;

import java.util.ArrayList;
import java.util.List;

/** Pure selection rules for required capture followed by operator-chosen additional photos. */
final class PhotoSlotCaptureRules {
    private PhotoSlotCaptureRules() {
    }

    static int nextBelowMinimum(int[] counts, int[] minimums) {
        int length = Math.min(counts == null ? 0 : counts.length,
            minimums == null ? 0 : minimums.length);
        for (int i = 0; i < length; i++) {
            if (counts[i] < Math.max(0, minimums[i])) return i;
        }
        return -1;
    }

    static List<Integer> slotsWithCapacity(int[] counts, int[] maximums) {
        List<Integer> out = new ArrayList<>();
        int length = Math.min(counts == null ? 0 : counts.length,
            maximums == null ? 0 : maximums.length);
        for (int i = 0; i < length; i++) {
            if (maximums[i] <= 0 || counts[i] < maximums[i]) out.add(i);
        }
        return out;
    }
}
