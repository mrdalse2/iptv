package com.mrdalse2.sbsplusproxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class SbsSessionBootstrap {
    private static final String ENDPOINT = "https://apis.sbs.co.kr/play-api/1.0/livestream/S03/S03?protocol=hls&ssl=Y";
    private static final String REFERER = "https://www.sbs.co.kr/live/S03";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36";

    private SbsSessionBootstrap() {}

    static Session open() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        c.setConnectTimeout(6_000);
        c.setReadTimeout(8_000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        c.setRequestProperty("Referer", REFERER);
        c.setRequestProperty("Origin", "https://www.sbs.co.kr");
        c.setRequestProperty("Cache-Control", "no-cache");

        try {
            int code = c.getResponseCode();
            String cookie = collectCookies(c);
            InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            String body = stream == null ? "" : readAll(stream);
            String media = extractMedia(body);
            return new Session(code, media, cookie);
        } finally {
            c.disconnect();
        }
    }

    private static String collectCookies(HttpURLConnection c) {
        List<String> pairs = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (e.getKey() == null || !"Set-Cookie".equalsIgnoreCase(e.getKey())) continue;
            for (String raw : e.getValue()) {
                if (raw == null || raw.isBlank()) continue;
                String pair = raw.split(";", 2)[0].trim();
                if (!pair.isBlank()) pairs.add(pair);
            }
        }
        return pairs.isEmpty() ? null : String.join("; ", pairs);
    }

    private static String readAll(InputStream stream) throws Exception {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String extractMedia(String body) {
        if (body == null) return null;
        String trimmed = body.trim();
        if (isHttp(trimmed)) return trimmed;
        try {
            Object json = trimmed.startsWith("{") ? new JSONObject(trimmed)
                    : trimmed.startsWith("[") ? new JSONArray(trimmed) : null;
            return findMedia(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String findMedia(Object value) {
        if (value instanceof JSONObject obj) {
            for (String key : new String[]{"mediaurl", "mediaUrl", "media_url"}) {
                String direct = obj.optString(key, "");
                if (isHttp(direct)) return direct;
            }
            JSONArray names = obj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String found = findMedia(obj.opt(names.optString(i)));
                    if (found != null) return found;
                }
            }
        } else if (value instanceof JSONArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                String found = findMedia(arr.opt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isHttp(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    static final class Session {
        final int httpCode;
        final String mediaUrl;
        final String cookie;
        Session(int httpCode, String mediaUrl, String cookie) {
            this.httpCode = httpCode;
            this.mediaUrl = mediaUrl;
            this.cookie = cookie;
        }
    }
}
