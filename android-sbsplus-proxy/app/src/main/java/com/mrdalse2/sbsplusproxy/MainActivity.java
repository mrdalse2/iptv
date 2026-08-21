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
import android.text.InputType;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {
    private TextView statusText;
    private TextView urlText;
    private TextView publishText;
    private EditText tokenInput;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.statusText);
        urlText = findViewById(R.id.urlText);
        publishText = findViewById(R.id.publishText);
        tokenInput = findViewById(R.id.tokenInput);
        Button start = findViewById(R.id.startButton);
        Button stop = findViewById(R.id.stopButton);
        Button copy = findViewById(R.id.copyButton);
        Button saveToken = findViewById(R.id.saveTokenButton);
        Button publishNow = findViewById(R.id.publishNowButton);
        Button clearToken = findViewById(R.id.clearTokenButton);
        CheckBox auto = findViewById(R.id.autoStartCheck);

        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
        boolean initialized = prefs.getBoolean("initialized", false);
        if (!initialized) {
            prefs.edit().putBoolean("initialized", true).putBoolean("auto_start", true).apply();
        }
        auto.setChecked(prefs.getBoolean("auto_start", true));
        auto.setOnCheckedChangeListener((button, checked) -> prefs.edit().putBoolean("auto_start", checked).apply());

        start.setOnClickListener(v -> startProxy());
        stop.setOnClickListener(v -> stopProxy());
        copy.setOnClickListener(v -> copyFirst());
        saveToken.setOnClickListener(v -> saveTokenAndPublish());
        publishNow.setOnClickListener(v -> publishNow());
        clearToken.setOnClickListener(v -> clearToken());

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }

        // First app launch starts the local server immediately. Android does not run newly-installed apps before first launch.
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

    private void saveTokenAndPublish() {
        String token = tokenInput.getText().toString().trim();
        if (token.isEmpty()) {
            Toast.makeText(this, "GitHub PAT를 입력하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            SecretStore.saveGithubToken(this, token);
            tokenInput.setText("");
            publishText.setText("PAT 저장됨 · GitHub push 중...");
            publishNow();
        } catch (Exception e) {
            publishText.setText("PAT 저장 실패: " + e.getMessage());
        }
    }

    private void publishNow() {
        if (!GitHubPublisher.isConfigured(this)) {
            Toast.makeText(this, "먼저 GitHub PAT를 저장하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        publishText.setText("GitHub push 중...");
        GitHubPublisher.publishNowAsync(this, true, (ok, message) -> runOnUiThread(() -> {
            publishText.setText(message);
            Toast.makeText(this, ok ? "GitHub 반영 완료" : "GitHub 반영 실패", Toast.LENGTH_SHORT).show();
        }));
    }

    private void clearToken() {
        SecretStore.clearGithubToken(this);
        getSharedPreferences("settings", MODE_PRIVATE).edit().remove("last_published_route").apply();
        publishText.setText("GitHub 자동 push 미설정");
        Toast.makeText(this, "저장된 GitHub PAT를 삭제했습니다.", Toast.LENGTH_SHORT).show();
    }

    private void refresh() {
        statusText.setText(ProxyService.running ? "서버 실행 중 · 포트 8787" : "서버 중지됨");
        List<String> urls = NetworkUtils.localProxyUrls();
        urlText.setText(urls.isEmpty() ? "사용 가능한 IPv4 주소를 찾지 못했습니다." : String.join("\n", urls));
        String current = getSharedPreferences("settings", MODE_PRIVATE).getString("publish_status", null);
        if (current != null) publishText.setText(current);
        else publishText.setText(GitHubPublisher.isConfigured(this) ? "GitHub 자동 push 설정됨" : "GitHub 자동 push 미설정");
    }

    private void copyFirst() {
        List<String> urls = NetworkUtils.localProxyUrls();
        if (urls.isEmpty()) {
            Toast.makeText(this, "복사할 주소가 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("SBS Plus Proxy", urls.get(0)));
        Toast.makeText(this, "주소를 복사했습니다.", Toast.LENGTH_SHORT).show();
    }
}
