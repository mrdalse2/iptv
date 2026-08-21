package com.mrdalse2.sbsplusproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public class ProxyService extends Service {
    public static final String ACTION_START = "com.mrdalse2.sbsplusproxy.START";
    public static final String ACTION_STOP = "com.mrdalse2.sbsplusproxy.STOP";
    public static volatile boolean running = false;
    private static final String CHANNEL = "sbsplus_proxy";
    private static final long PUBLISH_INTERVAL_MS = 60_000L;
    private LocalHttpServer server;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable routePublisher = new Runnable() {
        @Override public void run() {
            if (running) {
                GitHubPublisher.publishIfConfiguredAsync(ProxyService.this);
                handler.postDelayed(this, PUBLISH_INTERVAL_MS);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "SBS Plus Proxy", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startAsForeground();
        if (!running) {
            try {
                server = new LocalHttpServer();
                server.start();
                running = true;
                handler.removeCallbacks(routePublisher);
                handler.postDelayed(routePublisher, 1500L);
            } catch (Exception e) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    private void startAsForeground() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String first = NetworkUtils.bestLocalProxyUrl();
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("SBS Plus Proxy 실행 중")
                .setContentText(first == null ? "포트 8787 대기 중" : first)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(8787, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(8787, n);
        }
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacks(routePublisher);
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
