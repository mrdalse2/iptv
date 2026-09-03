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
    private static final String API = "https://apis.sbs.co.kr/play-api/1.0/onair/channel/S03";
    private static final String REFERER = "https://www.sbs.co.kr/live/S03";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36";
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36";
    private static final long GOOD_CACHE_MS = 20_000L;
    private static volatile String lastDebug = "not probed yet";
    private static volatile String cachedMediaUrl;
    private static volatile long cachedAt;

    private SbsResolver() {}

    public static synchronized String resolve() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedMediaUrl != null && now - cachedAt < GOOD_CACHE_MS) return cachedMediaUrl;

        ProbeSet set = probeAll();
        if (set.mediaUrl != null) {
            cachedMediaUrl = set.mediaUrl;
            cachedAt = System.currentTimeMillis();
            return set.mediaUrl;
        }

        throw new IllegalStateException("S03 API returned no mediaurl; " + set.summary);
    }

    public static synchronized String debugSnapshot() {
        try {
            ProbeSet set = probeAll();
            if (set.mediaUrl != null) {
                cachedMediaUrl = set.mediaUrl;
                cachedAt = System.currentTimeMillis();
            }
            return set.summary;
        } catch (Exception e) {
            return "probe error=" + safe(e.getMessage()) + "; previous=" + lastDebug;
        }
    }

    private static ProbeSet probeAll() throws Exception {
        RequestProfile[] profiles = new RequestProfile[] {
                new RequestProfile("pc-N-desktop", "pcweb", "N", DESKTOP_UA),
                new RequestProfile("pc-Y-desktop", "pcweb", "Y", DESKTOP_UA),
                new RequestProfile("pc-N-mobileUA", "pcweb", "N", MOBILE_UA)
        };

        List<String> diagnostics = new ArrayList<>();
        Exception lastError = null;
        for (int round = 0; round < 3; round++) {
            for (RequestProfile profile : profiles) {
                try {
                    Probe p = request(profile);
                    diagnostics.add(profile.name + "{" + p.summary + "}");
                    if (p.mediaUrl != null) {
                        String summary = "selected=" + profile.name + ", round=" + (round + 1) + ", attempts=" + diagnostics;
                        lastDebug = summary;
                        return new ProbeSet(p.mediaUrl, summary);
                    }
                } catch (Exception e) {
                    lastError = e;
                    diagnostics.add(profile.name + "{error=" + safe(e.getMessage()) + "}");
                }
            }
            if (round < 2) {
                try { Thread.sleep(350L * (round + 1)); }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }

        String summary = "selected=none, attempts=" + diagnostics;
        if (lastError != null) summary += ", lastError=" + safe(lastError.getMessage());
        lastDebug = summary;
        return new ProbeSet(null, summary);
    }

    private static Probe request(RequestProfile profile) throws Exception {
        String query = "v_type=2&platform=" + profile.platform
                + "&protocol=hls&ssl=" + profile.ssl
                + "&rscuse=&jwt-token=&sbsmain=";
        HttpURLConnection c = (HttpURLConnection) new URL(API + "?" + query).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", profile.userAgent);
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        c.setRequestProperty("Referer", REFERER);
        c.setRequestProperty("Origin", "https://www.sbs.co.kr");
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Pragma", "no-cache");

        int code = c.getResponseCode();
        String body;
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (stream == null) {
            c.disconnect();
            throw new IllegalStateException("HTTP " + code + " empty body");
        }
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            body = out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }

        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);

        JSONObject json = new JSONObject(body);
        String media = findMediaUrl(json);
        return new Probe(media, summarize(json, media, code));
    }

    private static String findMediaUrl(Object value) {
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
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
        } else if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            for (int i = 0; i < arr.length(); i++) {
                String found = findMediaUrl(arr.opt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String summarize(JSONObject json, String media, int code) {
        JSONObject onair = json.optJSONObject("onair");
        JSONObject source = onair == null ? null : onair.optJSONObject("source");
        JSONObject ms = source == null ? null : source.optJSONObject("mediasource");
        JSONArray msl = source == null ? null : source.optJSONArray("mediasourcelist");
        String mediaSummary = "none";
        if (media != null) {
            try {
                URI u = URI.create(media);
                mediaSummary = "host=" + u.getHost() + ",path=" + u.getPath();
            } catch (Exception ignored) {
                mediaSummary = "present";
            }
        }
        return "http=" + code + ",onair=" + (onair != null)
                + ",source=" + (source != null)
                + ",mediasource=" + (ms != null)
                + ",mediasourcelist=" + (msl == null ? 0 : msl.length())
                + ",media=" + mediaSummary;
    }

    private static boolean isHttpUrl(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s.replace('\n', ' ').replace('\r', ' ');
    }

    private record RequestProfile(String name, String platform, String ssl, String userAgent) {}
    private record Probe(String mediaUrl, String summary) {}
    private record ProbeSet(String mediaUrl, String summary) {}
}
