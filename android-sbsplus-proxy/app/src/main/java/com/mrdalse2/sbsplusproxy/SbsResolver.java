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
    private static final String ONAIR = "https://apis.sbs.co.kr/play-api/1.0/onair/channel/S03";
    private static final String LIVESTREAM = "https://apis.sbs.co.kr/play-api/1.0/livestream/S03/S03?protocol=hls&ssl=Y";
    private static final String REFERER = "https://www.sbs.co.kr/live/S03";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36";
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36";
    private static final String PLAYER_UA = "TiviMate/Android";

    private static final long GOOD_CACHE_MS = 20_000L;
    private static final long LAST_GOOD_GRACE_MS = 50_000L;
    private static final long FAILED_SCAN_COOLDOWN_MS = 4_000L;

    private static volatile String cachedMediaUrl;
    private static volatile long cachedAt;
    private static volatile long lastFailedScanAt;
    private static volatile String lastDebug = "not probed yet";

    private SbsResolver() {}

    public static synchronized String resolve() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedMediaUrl != null && now - cachedAt < GOOD_CACHE_MS) return cachedMediaUrl;

        // Avoid hammering every official endpoint when multiple local player requests arrive together.
        if (now - lastFailedScanAt < FAILED_SCAN_COOLDOWN_MS && cachedMediaUrl != null
                && now - cachedAt < LAST_GOOD_GRACE_MS) {
            return cachedMediaUrl;
        }

        ScanResult result = scanAll();
        if (result.mediaUrl != null) {
            cachedMediaUrl = result.mediaUrl;
            cachedAt = System.currentTimeMillis();
            return result.mediaUrl;
        }

        lastFailedScanAt = System.currentTimeMillis();
        now = System.currentTimeMillis();
        if (cachedMediaUrl != null && now - cachedAt < LAST_GOOD_GRACE_MS) {
            lastDebug = result.summary + ", fallback=recent-validated-last-good ageMs=" + (now - cachedAt);
            return cachedMediaUrl;
        }

        throw new IllegalStateException("No playable SBS Plus source; " + result.summary);
    }

    public static synchronized String debugSnapshot() {
        try {
            ScanResult result = scanAll();
            if (result.mediaUrl != null) {
                cachedMediaUrl = result.mediaUrl;
                cachedAt = System.currentTimeMillis();
            }
            return result.summary;
        } catch (Exception e) {
            return "probe error=" + safe(e.getMessage()) + "; previous=" + lastDebug;
        }
    }

    private static ScanResult scanAll() {
        List<String> attempts = new ArrayList<>();

        Candidate[] candidates = new Candidate[] {
                // Current SBS web-player shape discovered from onair-player.min.js.
                new Candidate("onair-player-Y", onairQuery("Y", "", false), DESKTOP_UA),
                // Existing stable shapes kept intact as fallbacks.
                new Candidate("onair-legacy-N", onairQuery("N", null, true), DESKTOP_UA),
                new Candidate("onair-legacy-Y", onairQuery("Y", null, true), DESKTOP_UA),
                // SBS low-latency flag used by the official player when enabled.
                new Candidate("onair-player-LL", onairQuery("Y", "LL", false), DESKTOP_UA),
                // Same official endpoint with mobile browser identity.
                new Candidate("onair-mobile-Y", onairQuery("Y", "", false), MOBILE_UA),
        };

        for (Candidate candidate : candidates) {
            try {
                ApiResult api = fetchApi(candidate.url, candidate.userAgent);
                if (api.mediaUrl == null) {
                    attempts.add(candidate.name + "{api=" + api.httpCode + ",media=none}");
                    continue;
                }
                Validation v = validateDirect(api.mediaUrl);
                attempts.add(candidate.name + "{api=" + api.httpCode + ",media=present,direct=" + v.summary + "}");
                if (v.playable) {
                    String summary = "selected=" + candidate.name + ", source=onair, attempts=" + attempts;
                    lastDebug = summary;
                    return new ScanResult(api.mediaUrl, summary);
                }
            } catch (Exception e) {
                attempts.add(candidate.name + "{error=" + safe(e.getMessage()) + "}");
            }
        }

        // Secondary official API. It is only selected when the returned HLS is genuinely
        // reachable without browser cookies/origin; a 403 URL is deliberately rejected.
        try {
            ApiResult api = fetchApi(LIVESTREAM, DESKTOP_UA);
            if (api.mediaUrl == null) {
                attempts.add("livestream-s03{api=" + api.httpCode + ",media=none}");
            } else {
                Validation v = validateDirect(api.mediaUrl);
                attempts.add("livestream-s03{api=" + api.httpCode + ",media=present,direct=" + v.summary + "}");
                if (v.playable) {
                    String summary = "selected=livestream-s03, source=livestream, attempts=" + attempts;
                    lastDebug = summary;
                    return new ScanResult(api.mediaUrl, summary);
                }
            }
        } catch (Exception e) {
            attempts.add("livestream-s03{error=" + safe(e.getMessage()) + "}");
        }

        String summary = "selected=none, attempts=" + attempts;
        lastDebug = summary;
        return new ScanResult(null, summary);
    }

    private static String onairQuery(String ssl, String extra, boolean sbsmain) {
        StringBuilder q = new StringBuilder(ONAIR)
                .append("?v_type=2&platform=pcweb&protocol=hls&ssl=").append(ssl)
                .append("&rscuse=");
        if (extra != null) q.append("&extra=").append(extra);
        q.append("&jwt-token=");
        if (sbsmain) q.append("&sbsmain=");
        return q.toString();
    }

    private static ApiResult fetchApi(String url, String userAgent) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(7_000);
        c.setReadTimeout(9_000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", userAgent);
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        c.setRequestProperty("Referer", REFERER);
        c.setRequestProperty("Origin", "https://www.sbs.co.kr");
        c.setRequestProperty("Cache-Control", "no-cache");

        try {
            int code = c.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            String body = stream == null ? "" : readAll(stream, 1024 * 1024);
            if (code < 200 || code >= 300) return new ApiResult(code, null);
            String trimmed = body.trim();
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return new ApiResult(code, trimmed);
            }
            Object json = trimmed.startsWith("{") ? new JSONObject(trimmed)
                    : trimmed.startsWith("[") ? new JSONArray(trimmed) : null;
            return new ApiResult(code, findMediaUrl(json));
        } finally {
            c.disconnect();
        }
    }

    private static Validation validateDirect(String mediaUrl) {
        // First profile is intentionally player-like/minimal because LocalHttpServer 3.2
        // returns a 302 and TiviMate then connects to SBS directly.
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
                    InputStream in = c.getInputStream();
                    byte[] prefix = readPrefix(in, 128);
                    boolean m3u = new String(prefix, StandardCharsets.US_ASCII).trim().startsWith("#EXTM3U");
                    results.add(names[i] + "=" + code + "/m3u=" + m3u);
                    if (m3u && !referers[i]) return new Validation(true, String.join("|", results));
                    // Referer-only success is diagnostic, not safe for direct 302 playback.
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
            while ((n = in.read(buf, 0, Math.min(buf.length, limit - total))) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (total >= limit) break;
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String findMediaUrl(Object value) {
        if (value instanceof JSONObject obj) {
            for (String key : new String[]{"mediaurl", "mediaUrl", "media_url"}) {
                String direct = obj.optString(key, "");
                if (isHttpUrl(direct)) return direct;
            }
            JSONArray names = obj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String found = findMediaUrl(obj.opt(names.optString(i)));
                    if (found != null) return found;
                }
            }
        } else if (value instanceof JSONArray arr) {
            for (int i = 0; i < arr.length(); i++) {
                String found = findMediaUrl(arr.opt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isHttpUrl(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s.replace('\n', ' ').replace('\r', ' ');
    }

    private record Candidate(String name, String url, String userAgent) {}
    private record ApiResult(int httpCode, String mediaUrl) {}
    private record Validation(boolean playable, String summary) {}
    private record ScanResult(String mediaUrl, String summary) {}
}
