package com.mrdalse2.sbsplusproxy;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private TextView statusText;
    private TextView errorText;
    private TextView urlText;
    private TextView sbsUrlText;
    private TextView healthText;
    private Button toggleButton;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.statusText);
        errorText = findViewById(R.id.errorText);
        urlText = findViewById(R.id.urlText);
        sbsUrlText = findViewById(R.id.sbsUrlText);
        healthText = findViewById(R.id.healthText);
        toggleButton = findViewById(R.id.serverToggleButton);
        Button copy = findViewById(R.id.copyButton);
        CheckBox auto = findViewById(R.id.autoStartCheck);

        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        boolean initialized = prefs.getBoolean("initialized", false);
        if (!initialized) {
            prefs.edit().putBoolean("initialized", true).putBoolean("auto_start", true).apply();
        }
        auto.setChecked(prefs.getBoolean("auto_start", true));
        auto.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("auto_start", checked).apply());

        toggleButton.setOnClickListener(v -> {
            if (ProxyService.running) stopProxy(); else startProxy();
        });
        copy.setOnClickListener(v -> copyPlaylist());

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }

        if (!ProxyService.running) startProxy();
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override protected void onPause() {
        ui.removeCallbacks(ticker);
        super.onPause();
    }

    @Override protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void startProxy() {
        ProxyService.lastError = null;
        toggleButton.setEnabled(false);
        statusText.setText("서버 시작 중...");
        Intent i = new Intent(this, ProxyService.class).setAction(ProxyService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception e) {
            ProxyService.lastError = "서비스 호출 실패: " + safeMessage(e);
        }
        ui.postDelayed(() -> toggleButton.setEnabled(true), 800);
    }

    private void stopProxy() {
        toggleButton.setEnabled(false);
        statusText.setText("서버 중지 중...");
        Intent i = new Intent(this, ProxyService.class).setAction(ProxyService.ACTION_STOP);
        try { startService(i); }
        catch (Exception e) { ProxyService.lastError = "서비스 중지 실패: " + safeMessage(e); }
        ui.postDelayed(() -> toggleButton.setEnabled(true), 500);
    }

    private void refresh() {
        boolean running = ProxyService.running;
        statusText.setText(running ? "서버 실행 중 · HTTP 포트 8787" : "서버 중지됨");
        toggleButton.setText(running ? "서버 중지" : "서버 시작");
        errorText.setText(ProxyService.lastError == null ? "" : ProxyService.lastError);

        List<String> fallbacks = NetworkUtils.localPlaylistUrls();
        StringBuilder playlist = new StringBuilder(NetworkUtils.STABLE_PLAYLIST_URL);
        playlist.append("\n같은 기기: http://127.0.0.1:8787/playlist.m3u");
        if (!fallbacks.isEmpty()) {
            playlist.append("\n\nIP 직접접속(대체):\n").append(String.join("\n", fallbacks));
        }
        urlText.setText(playlist.toString());

        List<String> sbsUrls = NetworkUtils.localProxyUrls();
        StringBuilder sbs = new StringBuilder(NetworkUtils.STABLE_SBS_URL);
        if (!sbsUrls.isEmpty()) sbs.append("\n").append(String.join("\n", sbsUrls));
        sbsUrlText.setText(sbs.toString());

        if (running) checkHealth(fallbacks);
        else healthText.setText("HTTP 상태: 서버 중지");
    }

    private void checkHealth(List<String> fallbacks) {
        String healthUrl = "http://127.0.0.1:8787/health";
        io.execute(() -> {
            String result;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(healthUrl).openConnection();
                c.setConnectTimeout(1500);
                c.setReadTimeout(1500);
                int code = c.getResponseCode();
                String line;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                    line = r.readLine();
                }
                result = code == 200 ? "HTTP 상태: 정상 · " + (line == null ? "OK" : line) : "HTTP 상태: 오류 " + code;
            } catch (Exception e) {
                result = "HTTP 상태: 접속 실패 · " + safeMessage(e);
            } finally {
                if (c != null) c.disconnect();
            }
            String finalResult = result;
            ui.post(() -> healthText.setText(finalResult));
        });
    }

    private void copyPlaylist() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Local IPTV Playlist", NetworkUtils.STABLE_PLAYLIST_URL));
        Toast.makeText(this, "고정 통합 플레이리스트 주소를 복사했습니다.", Toast.LENGTH_SHORT).show();
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }
}
