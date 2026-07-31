package com.autoformkit.app;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.autoformkit.app.report.FailureReporter;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        boolean panelPairRecovered = true;
        try {
            PanelPairCacheCoordinator.recover(this);
        } catch (Exception blocked) {
            // Keep every pair consumer fail-closed. MainActivity can still render Settings so an
            // operator may explicitly change/discard the affected Panel connection.
            panelPairRecovered = false;
            Log.e("PanelPairCache", "Panel pair recovery is blocked", blocked);
        }
        boolean legacyPanelCacheMigrated = panelPairRecovered
            && LegacyPanelCacheMigration.migrate(this);
        Diagnostics.append(this, "App started");
        if (!panelPairRecovered) {
            Diagnostics.append(this, "Panel pair recovery blocked; remote operations disabled");
        }
        if (legacyPanelCacheMigrated) {
            Diagnostics.append(this, "Verified legacy Panel cache pair migrated");
        }
        if (!panelConnectionAlternateCleanupPendingOrUnreadable()) {
            FailureReporter.init(this);
        } else {
            Diagnostics.append(this,
                "Panel alternate cleanup pending; failure reporter remains offline");
        }
        // Dump reporter state to logcat + diagnostic-log after start so adb users
        // can see whether the failure queue has unflushed events from a previous run.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (panelConnectionAlternateCleanupPendingOrUnreadable()) {
                Diagnostics.append(this,
                    "Panel alternate cleanup still pending; failure flush skipped");
                return;
            }
            // MainActivity may have completed a valid receipt after Application startup. init() is
            // idempotent, and only now may install its network listener/periodic flush machinery.
            FailureReporter.init(this);
            FailureReporter reporter = FailureReporter.get();
            String summary = "FailureReporter available=" + reporter.isAvailable()
                    + " enabled=" + reporter.isEnabled()
                    + " queueSize=" + reporter.queueSize()
                    + " lastUploadMs=" + reporter.lastUploadMs()
                    + " lastConfigError=" + reporter.lastConfigErrorStatus()
                    + " lastTransportErrorMs=" + reporter.lastTransportErrorMs();
            Log.i("FailureReporter", summary);
            Diagnostics.append(this, summary);
            reporter.requestFlush();
        }, 5000L);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Diagnostics.writeCrash(this, thread, throwable);
            try {
                String errCode = throwable == null ? "null_throwable" : throwable.getClass().getSimpleName();
                FailureReporter.get().report(
                        "uncaught", errCode, "process_default", throwable);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                System.exit(2);
            }
        });
    }

    private boolean panelConnectionAlternateCleanupPendingOrUnreadable() {
        try {
            return getSharedPreferences("settings", MODE_PRIVATE).getAll().containsKey(
                PanelConnectionAlternateCleanupReceipt.PREFERENCE_KEY);
        } catch (RuntimeException unreadable) {
            return true;
        }
    }
}
