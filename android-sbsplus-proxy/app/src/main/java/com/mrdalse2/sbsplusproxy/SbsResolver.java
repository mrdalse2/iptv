package com.mrdalse2.sbsplusproxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class SbsResolver {
    private static final String API = "https://apis.sbs.co.kr/play-api/1.0/onair/channel/S03";
    private static final String REFERER = "https://www.sbs.co.kr/live/S03";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36";
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36";

    private static final long GOOD_CACHE_MS = 20_000L;
    private static final long STALE_GRACE_MS = 55_000L;
    private static final int MAX_ROUNDS = 4;
    private static final long[] BACKOFF_MS = new long[]{700L, 1_400L, 2_800L, 4_000L};

    private static volatile String lastDebug = "not probed yet";
    private static volatile String cachedMediaUrl;
    private static volatile long cachedAt;

    private SbsResolver() {}

    public static synchronized String resolve() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedMediaUrl != null && now - cachedAt < GOOD_CACHE_MS) return cachedMediaUrl;
        return resolveInternal(true);
    }

    /** Force a new SBS API lookup. Used internally when a signed HLS URL is rejected. */
    public static synchronized String resolveFresh() throws Exception {
        return resolveInternal(false);
    }

    private static String resolveInternal(boolean allowRecentFallback) throws Exception {
        ProbeSet set = probeAll();
        if (set.mediaUrl != null) {
            cachedMediaUrl = set.mediaUrl;
            cachedAt = System.currentTimeMillis();
            return set.mediaUrl;
        }

        long now = System.currentTimeMillis();
        if (allowRecentFallback && cachedMediaUrl != null && now - cachedAt < STALE_GRACE_MS) {
            lastDebug = set.summary + ", fallback=recent-last-good ageMs=" + (now - cachedAt);
            return cachedMediaUrl;
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

        for (int round = 0; round < MAX_ROUNDS; round++) {
            long serverRetryAfterMs = 0L;
            boolean sawTransient = false;

            for (RequestProfile profile : profiles) {
                try {
                    Probe p = request(profile);
                    diagnostics.add(profile.name + "{" + p.summary + "}");
                    if (p.mediaUrl != null) {
                        String summary = "selected=" + profile.name + ", round=" + (round + 1)
                                + ", attempts=" + diagnostics;
                        lastDebug = summary;
                        return new ProbeSet(p.mediaUrl, summary);
                    }
                } catch (TransientHttpException e) {
                    lastError = e;
                    sawTransient = true;
                    serverRetryAfterMs = Math.max(serverRetryAfterMs, e.retryAfterMs);
                    diagnostics.add(profile.name + "{transient=" + safe(e.getMessage())
                            + (e.retryAfterMs > 0 ? ",retryAfterMs=" + e.retryAfterMs : "") + "}");
                } catch (SocketTimeoutException | ConnectException e) {
                    lastError = e;
                    sawTransient = true;
                    diagnostics.add(profile.name + "{transient=" + e.getClass().getSimpleName() + "}");
                } catch (Exception e) {
                    lastError = e;
                    diagnostics.add(profile.name + "{error=" + safe(e.getMessage()) + "}");
                }
            }

            if (round + 1 < MAX_ROUNDS) {
                long base = BACKOFF_MS[Math.min(round, BACKOFF_MS.length - 1)];
                long waitMs = sawTransient ? Math.max(base, serverRetryAfterMs) : Math.min(base, 1_000L);
                waitMs += ThreadLocalRandom.current().nextLong(0L, 251L);
                diagnostics.add("backoff{round=" + (round + 1) + ",waitMs=" + waitMs + "}");
                sleep(waitMs);
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
        c.setConnectTimeout(8_000);
        c.setReadTimeout(10_000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", profile.userAgent);
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        c.setRequestProperty("Referer", REFERER);
        c.setRequestProperty("Origin", "https://www.sbs.co.kr");
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Pragma", "no-cache");

        int code = c.getResponseCode();
        long retryAfterMs = parseRetryAfterMs(c.getHeaderField("Retry-After"));
        if (isTransientStatus(code)) {
            c.disconnect();
            throw new TransientHttpException("HTTP " + code, retryAfterMs);
        }

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

    private static boolean isTransientStatus(int code) {
        return code == 408 || code == 425 || code == 429 || code == 500
                || code == 502 || code == 503 || code == 504;
    }

    private static long parseRetryAfterMs(String value) {
        if (value == null || value.isBlank()) return 0L;
        String trimmed = value.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            return Math.max(0L, Math.min(seconds * 1_000L, 10_000L));
        } catch (NumberFormatException ignored) {}
        try {
            long target = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli();
            long delta = target - System.currentTimeMillis();
            return Math.max(0L, Math.min(delta, 10_000L));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static void sleep(long millis) throws InterruptedException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
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

    private static final class TransientHttpException extends Exception {
        final long retryAfterMs;
        TransientHttpException(String message, long retryAfterMs) {
            super(message);
            this.retryAfterMs = retryAfterMs;
        }
    }

    private record RequestProfile(String name, String platform, String ssl, String userAgent) {}
    private record Probe(String mediaUrl, String summary) {}
    private record ProbeSet(String mediaUrl, String summary) {}
}
