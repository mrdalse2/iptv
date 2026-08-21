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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProxyService extends Service {
    public static final String ACTION_START = "com.mrdalse2.sbsplusproxy.START";
    public static final String ACTION_STOP = "com.mrdalse2.sbsplusproxy.STOP";
    public static volatile boolean running = false;
    public static volatile String lastError = null;
    private static final String CHANNEL = "sbsplus_proxy";
    private static final long MDNS_REFRESH_MS = 15_000L;
    private LocalHttpServer server;
    private MdnsAdvertiser mdns;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();

    private final Runnable mdnsRefresh = new Runnable() {
        @Override public void run() {
            if (!running || mdns == null) return;
            refreshMdnsAsync();
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
                lastError = null;
                updateNotification();
                refreshMdnsAsync();
                handler.removeCallbacks(mdnsRefresh);
                handler.postDelayed(mdnsRefresh, MDNS_REFRESH_MS);
            } catch (Exception e) {
                running = false;
                lastError = "서버 시작 실패: " + safeMessage(e);
                updateNotification();
                stopSelf();
                return START_NOT_STICKY;
            }
        }
        return START_STICKY;
    }

    private void refreshMdnsAsync() {
        background.execute(() -> {
            try {
                mdns.refresh();
            } catch (Throwable t) {
                // mDNS failure must never stop the HTTP server. Direct-IP access remains valid.
                lastError = "mDNS 오류(직접 IP 사용 가능): " + safeMessage(t);
            }
            handler.post(this::updateNotification);
        });
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String ip = mdns == null ? null : mdns.getBoundIp();
        String text;
        if (!running) text = lastError == null ? "서버 중지됨" : lastError;
        else if (ip == null) text = "HTTP :8787 실행 중 · mDNS 준비 중";
        else text = NetworkUtils.STABLE_PLAYLIST_URL + " → " + ip;
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Local IPTV Proxy")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setOngoing(running)
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
        try { getSystemService(NotificationManager.class).notify(8787, buildNotification()); }
        catch (Exception ignored) {}
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacks(mdnsRefresh);
        background.shutdownNow();
        if (mdns != null) mdns.stop();
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
