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
    private static final long MDNS_REFRESH_MS = 15_000L;
    private LocalHttpServer server;
    private MdnsAdvertiser mdns;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable mdnsRefresh = new Runnable() {
        @Override public void run() {
            if (!running || mdns == null) return;
            mdns.refresh();
            updateNotification();
            handler.postDelayed(this, MDNS_REFRESH_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Local IPTV Proxy", NotificationManager.IMPORTANCE_LOW));
        mdns = new MdnsAdvertiser(this);
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
                mdns.refresh();
                handler.removeCallbacks(mdnsRefresh);
                handler.postDelayed(mdnsRefresh, MDNS_REFRESH_MS);
                updateNotification();
            } catch (Exception e) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String ip = mdns == null ? null : mdns.getBoundIp();
        String text = ip == null ? "포트 8787 대기 중" : NetworkUtils.STABLE_PLAYLIST_URL + " → " + ip;
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Local IPTV Proxy 실행 중")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void startAsForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(8787, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(8787, n);
        }
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(8787, buildNotification());
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacks(mdnsRefresh);
        if (mdns != null) mdns.stop();
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
