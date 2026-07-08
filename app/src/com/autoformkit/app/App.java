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
        Diagnostics.append(this, "App started");
        FailureReporter.init(this);
        // Dump reporter state to logcat + diagnostic-log after start so adb users
        // can see whether the failure queue has unflushed events from a previous run.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
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
                FailureReporter.get().report("uncaught", errCode, "process_default",
                        throwable == null ? "uncaught throwable was null" : throwable.getMessage(),
                        FailureReporter.ctx("thread", thread == null ? "" : thread.getName()),
                        throwable);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            } else {
                System.exit(2);
            }
        });
    }
}
