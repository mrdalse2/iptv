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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    private TextView statusText;
    private TextView urlText;
    private TextView sbsUrlText;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.statusText);
        urlText = findViewById(R.id.urlText);
        sbsUrlText = findViewById(R.id.sbsUrlText);
        Button start = findViewById(R.id.startButton);
        Button stop = findViewById(R.id.stopButton);
        Button copy = findViewById(R.id.copyButton);
        CheckBox auto = findViewById(R.id.autoStartCheck);

        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        boolean initialized = prefs.getBoolean("initialized", false);
        if (!initialized) {
            prefs.edit().putBoolean("initialized", true).putBoolean("auto_start", true).apply();
        }
        auto.setChecked(prefs.getBoolean("auto_start", true));
        auto.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("auto_start", checked).apply());

        start.setOnClickListener(v -> startProxy());
        stop.setOnClickListener(v -> stopProxy());
        copy.setOnClickListener(v -> copyPlaylist());

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }

        if (!ProxyService.running) startProxy();
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void startProxy() {
        Intent i = new Intent(this, ProxyService.class).setAction(ProxyService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        urlText.postDelayed(this::refresh, 500);
    }

    private void stopProxy() {
        Intent i = new Intent(this, ProxyService.class).setAction(ProxyService.ACTION_STOP);
        startService(i);
        urlText.postDelayed(this::refresh, 300);
    }

    private void refresh() {
        statusText.setText(ProxyService.running ? "서버 실행 중 · 포트 8787" : "서버 중지됨");
        List<String> playlists = NetworkUtils.localPlaylistUrls();
        List<String> sbsUrls = NetworkUtils.localProxyUrls();
        urlText.setText(playlists.isEmpty() ? "사용 가능한 IPv4 주소를 찾지 못했습니다." : String.join("\n", playlists));
        sbsUrlText.setText(sbsUrls.isEmpty() ? "-" : String.join("\n", sbsUrls));
    }

    private void copyPlaylist() {
        String url = NetworkUtils.bestLocalPlaylistUrl();
        if (url == null) {
            Toast.makeText(this, "복사할 주소가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Local IPTV Playlist", url));
        Toast.makeText(this, "통합 플레이리스트 주소를 복사했습니다.", Toast.LENGTH_SHORT).show();
    }
}
