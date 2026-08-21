package com.mrdalse2.sbsplusproxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public class ProxyService extends Service {
    public static final String ACTION_START = "com.mrdalse2.sbsplusproxy.START";
    public static final String ACTION_STOP = "com.mrdalse2.sbsplusproxy.STOP";
    public static volatile boolean running = false;
    private static final String CHANNEL = "sbsplus_proxy";
    private LocalHttpServer server;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Local IPTV Proxy", NotificationManager.IMPORTANCE_LOW));
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
        String first = NetworkUtils.bestLocalPlaylistUrl();
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Local IPTV Proxy 실행 중")
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
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
