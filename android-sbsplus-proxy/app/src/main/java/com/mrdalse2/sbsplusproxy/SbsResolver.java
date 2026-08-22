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

public final class SbsResolver {
    private static final String API = "https://apis.sbs.co.kr/play-api/1.0/onair/channel/S03?v_type=2&platform=pcweb&protocol=hls&ssl=N&rscuse=&jwt-token=&sbsmain=";
    private static final String REFERER = "https://www.sbs.co.kr/";
    private static final long REFRESH_MS = 30_000L;
    private static String cachedRoot;
    private static long cachedAt;
    private static volatile String lastDebug = "not probed yet";

    private SbsResolver() {}

    public static synchronized String resolve() throws Exception { return resolve(false); }

    public static synchronized String resolve(boolean force) throws Exception {
        long now = System.currentTimeMillis();
        if (!force && cachedRoot != null && now - cachedAt < REFRESH_MS) return cachedRoot;

        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Probe p = request();
                if (p.mediaUrl != null) {
                    cachedRoot = p.mediaUrl;
                    cachedAt = System.currentTimeMillis();
                    return cachedRoot;
                }
                last = new IllegalStateException("S03 API returned no HLS URL; " + p.summary);
            } catch (Exception e) {
                last = e;
                lastDebug = "attempt=" + attempt + ", error=" + safe(e.getMessage());
            }
            if (attempt < 3) {
                try { Thread.sleep(300L * attempt); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
            }
        }

        // A URL fetched in the last 45 seconds is still worth trying; actual 401/403 is handled by caller.
        if (!force && cachedRoot != null && now - cachedAt < 45_000L) return cachedRoot;
        throw last != null ? last : new IllegalStateException("S03 API unavailable");
    }

    public static synchronized String debugSnapshot() {
        try {
            Probe p = request();
            return p.summary;
        } catch (Exception e) {
            return "probe error=" + safe(e.getMessage()) + "; previous=" + lastDebug;
        }
    }

    private static Probe request() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(7000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36");
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        c.setRequestProperty("Referer", REFERER);

        int code = c.getResponseCode();
        String body;
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (stream == null) {
            c.disconnect();
            throw new IllegalStateException("SBS API HTTP " + code + " with empty body");
        }
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            body = out.toString(StandardCharsets.UTF_8.name());
        } finally { c.disconnect(); }

        if (code < 200 || code >= 300) {
            lastDebug = "apiHttp=" + code + ", bodyPrefix=" + sanitizePrefix(body);
            throw new IllegalStateException("SBS API HTTP " + code);
        }

        JSONObject json = new JSONObject(body);
        String media = directMediaUrl(json);
        if (media == null) media = findAnyHlsUrl(json);
        String summary = summarize(json, media, code);
        lastDebug = summary;
        return new Probe(media, summary);
    }

    public static String refreshTokenizedUrl(String target, boolean force) throws Exception {
        URI old = URI.create(target);
        URI fresh = URI.create(resolve(force));
        String freshQuery = fresh.getRawQuery();
        if (freshQuery == null || freshQuery.isBlank()) return target;
        // Keep the exact child resource path, but replace the entire signed query with the latest root query.
        return new URI(old.getScheme(), old.getAuthority(), old.getPath(), freshQuery, old.getFragment()).toString();
    }

    private static String directMediaUrl(JSONObject json) {
        JSONObject onair = json.optJSONObject("onair");
        JSONObject source = onair == null ? null : onair.optJSONObject("source");
        JSONObject media = source == null ? null : source.optJSONObject("mediasource");
        String url = media == null ? null : media.optString("mediaurl", null);
        return isHttpHls(url) ? url : null;
    }

    private static String findAnyHlsUrl(Object value) {
        if (value instanceof String) return isHttpHls((String) value) ? (String) value : null;
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

    private static String summarize(JSONObject json, String media, int code) {
        List<String> top = new ArrayList<>();
        JSONArray names = json.names();
        if (names != null) for (int i = 0; i < names.length() && i < 12; i++) top.add(names.optString(i));
        String mediaSummary = "none";
        if (media != null) {
            try {
                URI u = URI.create(media);
                List<String> qkeys = new ArrayList<>();
                if (u.getRawQuery() != null) for (String p : u.getRawQuery().split("&")) {
                    int eq = p.indexOf('=');
                    qkeys.add(eq < 0 ? p : p.substring(0, eq));
                }
                mediaSummary = "host=" + u.getHost() + ", path=" + u.getPath() + ", queryKeys=" + qkeys;
            } catch (Exception ignored) { mediaSummary = "present but unparsable"; }
        }
        JSONObject onair = json.optJSONObject("onair");
        JSONObject source = onair == null ? null : onair.optJSONObject("source");
        JSONObject ms = source == null ? null : source.optJSONObject("mediasource");
        return "apiHttp=" + code + ", topKeys=" + top + ", onair=" + (onair != null)
                + ", source=" + (source != null) + ", mediasource=" + (ms != null)
                + ", media=" + mediaSummary;
    }

    private static String sanitizePrefix(String s) {
        if (s == null) return "";
        s = s.replaceAll("(?i)(token[=\\\": ]+)[^,&\\\" ]+", "$1<redacted>");
        return s.substring(0, Math.min(180, s.length())).replace('\n', ' ');
    }

    private static String safe(String s) { return s == null ? "unknown" : s.replace('\n', ' '); }

    private static boolean isHttpHls(String s) {
        if (s == null) return false;
        String lower = s.toLowerCase();
        return (lower.startsWith("http://") || lower.startsWith("https://")) && lower.contains(".m3u8");
    }

    private record Probe(String mediaUrl, String summary) {}
}
