package com.mrdalse2.sbsplusproxy;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GitHubPublisher {
    private static final String API = "https://api.github.com/repos/mrdalse2/iptv/contents/sbs-plus-route.txt";
    private static final String BRANCH = "main";
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private GitHubPublisher() {}

    public static boolean isConfigured(Context context) {
        String token = SecretStore.getGithubToken(context);
        return token != null && !token.trim().isEmpty();
    }

    public static void publishIfConfiguredAsync(Context context) {
        Context app = context.getApplicationContext();
        if (!isConfigured(app)) return;
        EXEC.execute(() -> {
            try {
                publishNow(app, false);
            } catch (Exception e) {
                saveStatus(app, "자동 push 실패: " + e.getMessage());
            }
        });
    }

    public static void publishNowAsync(Context context, boolean force, Callback callback) {
        Context app = context.getApplicationContext();
        EXEC.execute(() -> {
            try {
                String result = publishNow(app, force);
                if (callback != null) callback.onDone(true, result);
            } catch (Exception e) {
                String message = "push 실패: " + e.getMessage();
                saveStatus(app, message);
                if (callback != null) callback.onDone(false, message);
            }
        });
    }

    private static String publishNow(Context context, boolean force) throws Exception {
        String token = SecretStore.getGithubToken(context);
        if (token == null || token.trim().isEmpty()) throw new IllegalStateException("GitHub PAT가 저장되지 않았습니다.");
        String route = NetworkUtils.bestLocalProxyUrl();
        if (route == null) throw new IllegalStateException("공유 가능한 로컬 IPv4 주소를 찾지 못했습니다.");

        SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        String last = prefs.getString("last_published_route", null);
        if (!force && route.equals(last)) return "주소 변경 없음 · " + route;

        RemoteFile remote = fetchRemote(token);
        String content = "mode=local-proxy\nurl=" + route + "\n";
        if (content.equals(remote.content)) {
            prefs.edit().putString("last_published_route", route).apply();
            String msg = "GitHub 이미 최신 · " + route;
            saveStatus(context, msg);
            return msg;
        }

        JSONObject body = new JSONObject();
        body.put("message", "chore: update SBS Plus Android proxy route");
        body.put("content", Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        body.put("branch", BRANCH);
        if (remote.sha != null) body.put("sha", remote.sha);

        HttpURLConnection conn = open(API, "PUT", token);
        conn.setDoOutput(true);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.getOutputStream().write(payload);
        int code = conn.getResponseCode();
        String response = read(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        conn.disconnect();
        if (code < 200 || code >= 300) throw new IllegalStateException("GitHub HTTP " + code + " " + response);

        prefs.edit().putString("last_published_route", route).apply();
        String msg = "GitHub push 완료 · " + route;
        saveStatus(context, msg);
        return msg;
    }

    private static RemoteFile fetchRemote(String token) throws Exception {
        HttpURLConnection conn = open(API + "?ref=" + BRANCH, "GET", token);
        int code = conn.getResponseCode();
        if (code == 404) {
            conn.disconnect();
            return new RemoteFile(null, "");
        }
        String response = read(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        conn.disconnect();
        if (code < 200 || code >= 300) throw new IllegalStateException("GitHub HTTP " + code + " " + response);
        JSONObject json = new JSONObject(response);
        String sha = json.optString("sha", null);
        String encoded = json.optString("content", "").replace("\n", "");
        String content = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
        return new RemoteFile(sha, content);
    }

    private static HttpURLConnection open(String url, String method, String token) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("Authorization", "Bearer " + token.trim());
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        conn.setRequestProperty("User-Agent", "SBSPlusProxy-Android");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        return conn;
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static void saveStatus(Context context, String status) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putString("publish_status", status)
                .putLong("publish_status_at", System.currentTimeMillis())
                .apply();
    }

    public interface Callback { void onDone(boolean ok, String message); }
    private record RemoteFile(String sha, String content) {}
}
