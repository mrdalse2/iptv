package com.mrdalse2.sbsplusproxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SbsResolver {
    private static final String API = "https://apis.sbs.co.kr/play-api/1.0/onair/channel/S03";
    private static final String REFERER = "https://www.sbs.co.kr/live/S03";
    private static final long REFRESH_MS = 40_000L;
    private static String cachedRoot;
    private static long cachedAt;

    private SbsResolver() {}

    public static synchronized String resolve() throws Exception {
        return resolve(false);
    }

    public static synchronized String resolve(boolean force) throws Exception {
        long now = System.currentTimeMillis();
        if (!force && cachedRoot != null && now - cachedAt < REFRESH_MS) return cachedRoot;
        String query = "v_type=2&platform=pcweb&protocol=hls&ssl=N&rscuse=&jwt-token=&sbsmain=";
        HttpURLConnection c = (HttpURLConnection) new URL(API + "?" + query).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        c.setRequestProperty("Referer", REFERER);
        c.setRequestProperty("Origin", "https://www.sbs.co.kr");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("SBS API HTTP " + code);
        String body;
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            body = out.toString(StandardCharsets.UTF_8.name());
        } finally { c.disconnect(); }
        String media = findMediaUrl(new JSONObject(body));
        if (media == null) throw new IllegalStateException("S03 API returned no mediaurl");
        cachedRoot = media;
        cachedAt = now;
        return media;
    }

    public static String refreshTokenizedUrl(String target, boolean force) throws Exception {
        URI old = URI.create(target);
        Map<String,String> oldQ = parseQuery(old.getRawQuery());
        if (!oldQ.containsKey("token")) return target;
        URI fresh = URI.create(resolve(force));
        Map<String,String> freshQ = parseQuery(fresh.getRawQuery());
        String token = freshQ.get("token");
        if (token == null || token.isEmpty()) return target;
        oldQ.put("token", token);
        String query = buildQuery(oldQ);
        return new URI(old.getScheme(), old.getAuthority(), old.getPath(), query, old.getFragment()).toString();
    }

    private static Map<String,String> parseQuery(String query) throws Exception {
        Map<String,String> out = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return out;
        for (String p : query.split("&")) {
            int i = p.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? p : p.substring(0, i), StandardCharsets.UTF_8.name());
            String v = URLDecoder.decode(i < 0 ? "" : p.substring(i + 1), StandardCharsets.UTF_8.name());
            out.put(k, v);
        }
        return out;
    }

    private static String buildQuery(Map<String,String> q) throws Exception {
        StringBuilder s = new StringBuilder();
        for (Map.Entry<String,String> e : q.entrySet()) {
            if (s.length() > 0) s.append('&');
            s.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8.name()));
            s.append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8.name()));
        }
        return s.toString();
    }

    private static String findMediaUrl(Object value) {
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            String direct = obj.optString("mediaurl", "");
            if (direct.startsWith("http://") || direct.startsWith("https://")) return direct;
            JSONArray names = obj.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String found = findMediaUrl(obj.opt(names.optString(i)));
                if (found != null) return found;
            }
        } else if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                String found = findMediaUrl(arr.opt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}
