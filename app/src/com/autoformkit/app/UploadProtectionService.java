package com.autoformkit.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Gives an already-running, user-initiated upload foreground process importance while its
 * journaled transaction remains owned by {@link MainActivity}. It deliberately cannot restart or
 * replay an upload after process death.
 */
public final class UploadProtectionService extends Service {
    private static final String CHANNEL_ID = "upload_in_progress";
    private static final int NOTIFICATION_ID = 4107;
    private static final long MAX_GUARD_LIFETIME_MS = 60L * 60L * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable lifetimeLimit = this::stopSelf;

    static void start(Context context) {
        Context app = context.getApplicationContext();
        Intent intent = new Intent(app, UploadProtectionService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            app.startForegroundService(intent);
        } else {
            app.startService(intent);
        }
    }

    static void stop(Context context) {
        Context app = context.getApplicationContext();
        app.stopService(new Intent(app, UploadProtectionService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification());
        handler.removeCallbacks(lifetimeLimit);
        handler.postDelayed(lifetimeLimit, MAX_GUARD_LIFETIME_MS);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(lifetimeLimit);
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        Intent openApp = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
            this, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL_ID)
            : new Notification.Builder(this);
        return builder
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(localizedProgressText())
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, localizedChannelName(), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(localizedProgressText());
        channel.setSound(null, null);
        channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private String localizedChannelName() {
        String lang = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("lang", "zh");
        if ("en".equals(lang)) return "Uploads";
        if ("es".equals(lang)) return "Cargas";
        return "上传任务";
    }

    private String localizedProgressText() {
        String lang = getSharedPreferences("settings", MODE_PRIVATE)
            .getString("lang", "zh");
        if ("en".equals(lang)) return "Upload continues while the screen is locked";
        if ("es".equals(lang)) return "La carga continúa con la pantalla bloqueada";
        return "正在上传，锁屏后仍会继续";
    }
}
