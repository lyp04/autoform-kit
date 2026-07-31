package com.autoformkit.app;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/** Pure/file-only checks used before completing one durable Panel-switch cleanup receipt. */
final class PanelConnectionAlternateCleanupRecovery {
    interface PhotoDeleter {
        boolean delete(File photo);
    }

    private PanelConnectionAlternateCleanupRecovery() {}

    static boolean capturedSourcesAbsent(
            Map<String, ?> stored, PanelConnectionAlternateCleanupReceipt receipt) {
        if (stored == null || receipt == null) return false;
        String namespace = receipt.oldNamespace;
        return !stored.containsKey("pending_alternate_entry_draft_json_" + namespace)
            && !stored.containsKey(
                "pending_alternate_entry_continuation_proof_v1_json_" + namespace)
            && !stored.containsKey("pending_alternate_entry_scan_guard")
            && !stored.containsKey("pending_alternate_entry_scan_reservation_v1_json")
            && !stored.containsKey("pending_alternate_entry_photo_path")
            && !stored.containsKey("pending_alternate_entry_photo_guard")
            && !stored.containsKey("pending_alternate_entry_photo_reservation_v1_json");
    }

    static boolean matchesCurrentCompleteTuple(
            PanelConnectionAlternateCleanupReceipt receipt,
            String panelBase, String catalogKey) {
        if (receipt == null) return false;
        String base = panelBase == null ? "" : panelBase.trim();
        String key = catalogKey == null ? "" : catalogKey.trim();
        return base.isEmpty() == key.isEmpty()
            && receipt.newConnectionSecurityId.equals(
                AppConfig.connectionSecurityId(base, key));
    }

    static boolean deleteCapturedPhotos(
            Map<String, ?> stored, PanelConnectionAlternateCleanupReceipt receipt,
            String panelBase, String catalogKey, File photosDirectory) {
        return deleteCapturedPhotos(stored, receipt, panelBase, catalogKey,
            photosDirectory, File::delete);
    }

    static boolean deleteCapturedPhotos(
            Map<String, ?> stored, PanelConnectionAlternateCleanupReceipt receipt,
            String panelBase, String catalogKey, File photosDirectory,
            PhotoDeleter deleter) {
        if (!matchesCurrentCompleteTuple(receipt, panelBase, catalogKey)
                || !capturedSourcesAbsent(stored, receipt)
                || photosDirectory == null || deleter == null) return false;
        try {
            File root = photosDirectory.getCanonicalFile();
            String prefix = root.getPath() + File.separator;
            for (String relativePath : receipt.photos) {
                File photo = new File(root, relativePath).getCanonicalFile();
                if (!photo.getPath().startsWith(prefix)) return false;
                if (!photo.exists()) continue;
                if (!photo.isFile() || !deleter.delete(photo) || photo.exists()) return false;
            }
            return true;
        } catch (IOException invalidPath) {
            return false;
        }
    }
}
