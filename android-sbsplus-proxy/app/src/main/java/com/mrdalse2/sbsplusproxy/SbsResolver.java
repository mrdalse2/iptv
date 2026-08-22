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
    private static final long REFRESH_MS = 30_000L;
    private static final int MAX_ROUNDS = 2;
    private static String cachedRoot;
    private static long cachedAt;

    private SbsResolver() {}

    public static synchronized String resolve() throws Exception { return resolve(false); }

    public static synchronized String resolve(boolean force) throws Exception {
        long now = System.currentTimeMillis();
        if (!force && cachedRoot != null && now - cachedAt < REFRESH_MS) return cachedRoot;

        Exception last = null;
        RequestProfile[] profiles = new RequestProfile[] {
                new RequestProfile("N", false, true),
                new RequestProfile("N", false, false),
                new RequestProfile("Y", false, true),
                new RequestProfile("N", true, true)
        };
        for (int round = 0; round < MAX_ROUNDS; round++) {
            for (RequestProfile profile : profiles) {
                try {
                    String media = requestMediaUrl(profile);
                    if (media != null && isHttpHls(media)) {
                        cachedRoot = media;
                        cachedAt = System.currentTimeMillis();
                        return media;
                    }
                    last = new IllegalStateException("S03 API returned no HLS URL");
                } catch (Exception e) {
                    last = e;
                }
            }
            if (round + 1 < MAX_ROUNDS) {
                try { Thread.sleep(300L); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }

        // Do not try to interpret SBS' token format locally. A recently fetched URL is usable
        // until the upstream itself rejects it; 401/403 causes the caller to force a refresh.
        if (!force && cachedRoot != null && now - cachedAt < 45_000L) return cachedRoot;
        throw last != null ? last : new IllegalStateException("S03 API unavailable");
    }

    private static String requestMediaUrl(RequestProfile profile) throws Exception {
        String query = "v_type=2&platform=pcweb&protocol=hls&ssl=" + profile.ssl
                + "&rscuse=&jwt-token=&sbsmain=&rnd=" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection) new URL(API + "?" + query).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setUseCaches(false);
        String ua = profile.mobile
                ? "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36"
                : "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36";
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Pragma", "no-cache");
        if (profile.fullHeaders) {
            c.setRequestProperty("Referer", REFERER);
            c.setRequestProperty("Origin", "https://www.sbs.co.kr");
        }
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            c.disconnect();
            throw new IllegalStateException("SBS API HTTP " + code);
        }
        String body;
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            body = out.toString(StandardCharsets.UTF_8.name());
        } finally { c.disconnect(); }

        JSONObject json = new JSONObject(body);
        String media = findMediaUrl(json);
        if (media != null) return media;
        return findAnyHlsUrl(json);
    }

    public static String refreshTokenizedUrl(String target, boolean force) throws Exception {
        URI old = URI.create(target);
        Map<String,String> oldQ = parseQuery(old.getRawQuery());
        if (!oldQ.containsKey("token")) return target;

        URI fresh = URI.create(resolve(force));
        Map<String,String> freshQ = parseQuery(fresh.getRawQuery());
        String token = freshQ.get("token");
        if (token == null || token.isEmpty()) {
            // If SBS changes token naming/shape, use the newly-issued root URL when refreshing the root;
            // otherwise let the original child URL reach upstream and rely on 401/403 retry behavior.
            return target;
        }
        oldQ.put("token", token);
        return new URI(old.getScheme(), old.getAuthority(), old.getPath(), buildQuery(oldQ), old.getFragment()).toString();
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
            for (String key : new String[]{"mediaurl", "mediaUrl", "media_url"}) {
                String direct = obj.optString(key, "");
                if (isHttpHls(direct)) return direct;
            }
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

    private static String findAnyHlsUrl(Object value) {
        if (value instanceof String) {
            String s = (String) value;
            return isHttpHls(s) ? s : null;
        }
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            JSONArray names = obj.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String found = findAnyHlsUrl(obj.opt(names.optString(i)));
                if (found != null) return found;
            }
        } else if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                String found = findAnyHlsUrl(arr.opt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isHttpHls(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return (lower.startsWith("http://") || lower.startsWith("https://")) && lower.contains(".m3u8");
    }

    private record RequestProfile(String ssl, boolean mobile, boolean fullHeaders) {}
}
