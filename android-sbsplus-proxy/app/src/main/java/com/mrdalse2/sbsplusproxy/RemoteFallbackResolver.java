package com.mrdalse2.sbsplusproxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RemoteFallbackResolver {
    private static final String CONFIG_URL = "https://raw.githubusercontent.com/mrdalse2/iptv/exp/sbs-remote-fallback-3.9/sbs-source-candidates.json";
    private static final String REFERER = "https://www.sbs.co.kr/live/S03";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36";
    private static final String PLAYER_UA = "TiviMate/Android";
    private static final int MAX_CANDIDATES = 20;
    private static final int MAX_URL_LENGTH = 4096;

    private static volatile String lastDebug = "not probed yet";

    private RemoteFallbackResolver() {}

    public static synchronized String resolve() throws Exception {
        List<String> attempts = new ArrayList<>();
        JSONArray candidates = fetchCandidates(attempts);
        int limit = Math.min(candidates.length(), MAX_CANDIDATES);
        for (int i = 0; i < limit; i++) {
            JSONObject item = candidates.optJSONObject(i);
            if (item == null) continue;
            String name = safeName(item.optString("name", "remote-" + i));
            String url = item.optString("url", "").trim();
            if (!isAllowed(url)) {
                attempts.add(name + "{rejected=allowlist}");
                continue;
            }
            Validation v = validateDirect(url);
            attempts.add(name + "{" + v.summary + "}");
            if (v.playable) {
                lastDebug = "selected=" + name + ", source=remote-config, attempts=" + attempts;
                return url;
            }
        }
        lastDebug = "selected=none, source=remote-config, attempts=" + attempts;
        throw new IllegalStateException(lastDebug);
    }

    public static String debugSnapshot() {
        return lastDebug;
    }

    private static JSONArray fetchCandidates(List<String> attempts) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(CONFIG_URL).openConnection();
        c.setConnectTimeout(4_000);
        c.setReadTimeout(5_000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", DESKTOP_UA);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            String body = stream == null ? "" : readAll(stream, 128 * 1024);
            if (code < 200 || code >= 300) {
                attempts.add("config{http=" + code + "}");
                throw new IllegalStateException("remote config HTTP " + code);
            }
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.optJSONArray("candidates");
            if (arr == null) arr = new JSONArray();
            attempts.add("config{http=" + code + ",count=" + arr.length() + "}");
            return arr;
        } finally {
            c.disconnect();
        }
    }

    private static boolean isAllowed(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_URL_LENGTH) return false;
        try {
            URI u = URI.create(value);
            if (!"https".equalsIgnoreCase(u.getScheme())) return false;
            if (!"tvlive.sbs.co.kr".equalsIgnoreCase(u.getHost())) return false;
            String path = u.getPath();
            return path != null && path.toLowerCase().endsWith(".m3u8");
        } catch (Exception e) {
            return false;
        }
    }

    private static Validation validateDirect(String mediaUrl) {
        String[] names = {"player-minimal", "desktop-minimal", "desktop-referer"};
        String[] uas = {PLAYER_UA, DESKTOP_UA, DESKTOP_UA};
        boolean[] referers = {false, false, true};
        List<String> results = new ArrayList<>();

        for (int i = 0; i < names.length; i++) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(mediaUrl).openConnection();
                c.setConnectTimeout(6_000);
                c.setReadTimeout(7_000);
                c.setUseCaches(false);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", uas[i]);
                c.setRequestProperty("Accept", "*/*");
                c.setRequestProperty("Accept-Encoding", "identity");
                if (referers[i]) c.setRequestProperty("Referer", REFERER);
                int code = c.getResponseCode();
                if (code >= 200 && code < 300) {
                    byte[] prefix = readPrefix(c.getInputStream(), 128);
                    boolean m3u = new String(prefix, StandardCharsets.US_ASCII).trim().startsWith("#EXTM3U");
                    results.add(names[i] + "=" + code + "/m3u=" + m3u);
                    if (m3u && !referers[i]) return new Validation(true, String.join("|", results));
                } else {
                    results.add(names[i] + "=" + code);
                }
            } catch (Exception e) {
                results.add(names[i] + "=error:" + e.getClass().getSimpleName());
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return new Validation(false, String.join("|", results));
    }

    private static byte[] readPrefix(InputStream in, int max) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[Math.min(128, max)];
            int total = 0;
            while (total < max) {
                int n = input.read(buf, 0, Math.min(buf.length, max - total));
                if (n < 0) break;
                out.write(buf, 0, n);
                total += n;
            }
            return out.toByteArray();
        }
    }

    private static String readAll(InputStream stream, int limit) throws Exception {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int total = 0;
            int n;
            while (total < limit && (n = in.read(buf, 0, Math.min(buf.length, limit - total))) != -1) {
                out.write(buf, 0, n);
                total += n;
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String safeName(String s) {
        if (s == null || s.isBlank()) return "remote";
        return s.replaceAll("[^A-Za-z0-9._-]", "_").substring(0, Math.min(64, s.length()));
    }

    private record Validation(boolean playable, String summary) {}
}
