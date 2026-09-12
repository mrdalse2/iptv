package com.mrdalse2.sbsplusproxy;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SbsResolver {
    private static final String ONAIR_API = "https://apis.sbs.co.kr/play-api/1.0/onair/channel/S03";
    private static final String PLUS_LIVESTREAM_API = "https://apis.sbs.co.kr/play-api/1.0/livestream/sbspluspc/sbsplus0";
    private static final String S03_LIVESTREAM_API = "https://apis.sbs.co.kr/play-api/1.0/livestream/S03/S03";
    private static final String REFERER = "https://www.sbs.co.kr/live/S03";
    private static final String ORIGIN = "https://www.sbs.co.kr";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36";
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36";

    private static final long PROACTIVE_REFRESH_MS = 10_000L;
    private static final long RECENT_PREFETCH_MS = 15_000L;
    private static final long HARD_STALE_MS = 90_000L;
    private static final int MAX_ROUNDS = 3;
    private static final long[] BACKOFF_MS = new long[]{700L, 1_400L, 2_800L};

    private static final Object INIT_LOCK = new Object();
    private static final AtomicBoolean REFRESHING = new AtomicBoolean(false);
    private static final ScheduledExecutorService REFRESHER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sbs-signed-root-refresher");
        t.setDaemon(true);
        return t;
    });

    private static volatile String lastDebug = "not probed yet";
    private static volatile String cachedMediaUrl;
    private static volatile long cachedAt;

    static {
        REFRESHER.scheduleAtFixedRate(() -> {
            if (cachedMediaUrl != null) warmAsync();
        }, PROACTIVE_REFRESH_MS, PROACTIVE_REFRESH_MS, TimeUnit.MILLISECONDS);
    }

    private SbsResolver() {}

    /**
     * Playback path: never re-probe just because the old 20s cache window elapsed.
     * Return the last validated root immediately and let the background refresher rotate it.
     */
    public static String resolve() throws Exception {
        long now = System.currentTimeMillis();
        String cached = cachedMediaUrl;
        if (cached != null && now - cachedAt < HARD_STALE_MS) {
            if (now - cachedAt >= PROACTIVE_REFRESH_MS) warmAsync();
            return cached;
        }

        synchronized (INIT_LOCK) {
            now = System.currentTimeMillis();
            cached = cachedMediaUrl;
            if (cached != null && now - cachedAt < HARD_STALE_MS) return cached;
            ProbeSet set = probeAll();
            if (set.mediaUrl == null) {
                throw new IllegalStateException("SBS Plus returned no validated HLS; " + set.summary);
            }
            cachedMediaUrl = set.mediaUrl;
            cachedAt = System.currentTimeMillis();
            return set.mediaUrl;
        }
    }

    /**
     * Called after a signed child request expires. Prefer a root already refreshed in the
     * background so the TiviMate request does not wait for the full SBS probe/validation path.
     */
    public static String resolveFresh() throws Exception {
        long now = System.currentTimeMillis();
        String cached = cachedMediaUrl;
        if (cached != null && now - cachedAt < RECENT_PREFETCH_MS) {
            warmAsync();
            return cached;
        }

        synchronized (INIT_LOCK) {
            now = System.currentTimeMillis();
            cached = cachedMediaUrl;
            if (cached != null && now - cachedAt < RECENT_PREFETCH_MS) return cached;
            ProbeSet set = probeAll();
            if (set.mediaUrl == null) {
                throw new IllegalStateException("SBS Plus fresh resolve failed; " + set.summary);
            }
            cachedMediaUrl = set.mediaUrl;
            cachedAt = System.currentTimeMillis();
            return set.mediaUrl;
        }
    }

    /** Queue a validated signed-root refresh without blocking the playback request thread. */
    public static void warmAsync() {
        if (!REFRESHING.compareAndSet(false, true)) return;
        REFRESHER.execute(() -> {
            try {
                ProbeSet set = probeAll();
                if (set.mediaUrl != null) {
                    cachedMediaUrl = set.mediaUrl;
                    cachedAt = System.currentTimeMillis();
                }
            } catch (Exception e) {
                lastDebug = "backgroundRefreshError=" + safe(e.getMessage()) + "; previous=" + lastDebug;
            } finally {
                REFRESHING.set(false);
            }
        });
    }

    public static String debugSnapshot() {
        long age = cachedMediaUrl == null ? -1L : System.currentTimeMillis() - cachedAt;
        return "cacheAgeMs=" + age + ",refreshing=" + REFRESHING.get() + "," + lastDebug;
    }

    private static ProbeSet probeAll() throws Exception {
        RequestProfile[] profiles = new RequestProfile[] {
                new RequestProfile("plus-live-N", PLUS_LIVESTREAM_API, "livestream", "pcweb", "N", DESKTOP_UA),
                new RequestProfile("plus-live-Y", PLUS_LIVESTREAM_API, "livestream", "pcweb", "Y", DESKTOP_UA),
                new RequestProfile("onair-pc-N", ONAIR_API, "onair", "pcweb", "N", DESKTOP_UA),
                new RequestProfile("onair-pc-Y", ONAIR_API, "onair", "pcweb", "Y", DESKTOP_UA),
                new RequestProfile("onair-mobile-N", ONAIR_API, "onair", "mobile", "N", MOBILE_UA),
                new RequestProfile("s03-live-N", S03_LIVESTREAM_API, "livestream", "pcweb", "N", DESKTOP_UA),
                new RequestProfile("s03-live-Y", S03_LIVESTREAM_API, "livestream", "pcweb", "Y", DESKTOP_UA)
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
                        Validation validation = validateHls(p.mediaUrl, profile.userAgent);
                        diagnostics.add(profile.name + "-hls{" + validation.summary + "}");
                        if (validation.valid) {
                            String summary = "selected=" + profile.name + ", round=" + (round + 1)
                                    + ", validated=true, selectedMedia=" + safeLocation(p.mediaUrl)
                                    + ", attempts=" + diagnostics;
                            lastDebug = summary;
                            return new ProbeSet(p.mediaUrl, summary);
                        }
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

        String summary = "selected=none, validated=false, attempts=" + diagnostics;
        if (lastError != null) summary += ", lastError=" + safe(lastError.getMessage());
        lastDebug = summary;
        return new ProbeSet(null, summary);
    }

    private static Probe request(RequestProfile profile) throws Exception {
        String query;
        if ("livestream".equals(profile.apiType)) {
            query = "protocol=hls&ssl=" + profile.ssl;
        } else {
            query = "v_type=2&platform=" + profile.platform
                    + "&protocol=hls&ssl=" + profile.ssl
                    + "&rscuse=&jwt-token=&sbsmain=";
        }

        HttpResponse response = httpGet(profile.api + "?" + query,
                profile.userAgent, "application/json,text/plain,*/*", false, 1024 * 1024);
        if (isTransientStatus(response.code)) {
            throw new TransientHttpException("HTTP " + response.code, response.retryAfterMs);
        }
        if (response.code < 200 || response.code >= 300) {
            throw new IllegalStateException("HTTP " + response.code);
        }

        ParsedMedia parsed = parseMediaResponse(response.bodyText());
        return new Probe(parsed.mediaUrl,
                "http=" + response.code + ",responseType=" + parsed.responseType
                        + ",media=" + mediaSummary(parsed.mediaUrl));
    }

    private static ParsedMedia parseMediaResponse(String rawBody) throws Exception {
        String body = rawBody == null ? "" : rawBody.trim();
        if (body.isEmpty()) return new ParsedMedia(null, "empty");

        if (isHttpUrl(body)) return new ParsedMedia(body, "plain-url");

        Object parsed = new JSONTokener(body).nextValue();
        if (parsed instanceof String) {
            String value = ((String) parsed).trim();
            return new ParsedMedia(isHttpUrl(value) ? value : null, "json-string");
        }
        if (parsed instanceof JSONObject || parsed instanceof JSONArray) {
            return new ParsedMedia(findMediaUrl(parsed),
                    parsed instanceof JSONObject ? "json-object" : "json-array");
        }
        return new ParsedMedia(null, parsed == null ? "null" : parsed.getClass().getSimpleName());
    }

    private static Validation validateHls(String mediaUrl, String userAgent) {
        try {
            HttpResponse master = httpGet(mediaUrl, userAgent, "*/*", false, 256 * 1024);
            String masterText = master.bodyText();
            if (master.code != 200 || !masterText.stripLeading().startsWith("#EXTM3U")) {
                return new Validation(false, "playlist=" + master.code + ",extm3u=false,master="
                        + safeLocation(master.finalUrl));
            }

            String masterFinal = master.finalUrl;
            List<String> lines = playlistLines(masterText);
            String variant = firstVariantUri(masterFinal, lines);
            String mediaPlaylistUrl = masterFinal;

            if (variant != null) {
                HttpResponse variantResponse = httpGet(variant, userAgent, "*/*", false, 256 * 1024);
                String variantText = variantResponse.bodyText();
                if (variantResponse.code != 200 || !variantText.stripLeading().startsWith("#EXTM3U")) {
                    return new Validation(false, "playlist=200,master=" + safeLocation(masterFinal)
                            + ",variant=" + variantResponse.code + ",variantPath="
                            + safeLocation(variantResponse.finalUrl) + ",extm3u=false");
                }
                lines = playlistLines(variantText);
                mediaPlaylistUrl = variantResponse.finalUrl;
            }

            String segment = firstMediaUri(mediaPlaylistUrl, lines);
            if (segment == null) {
                return new Validation(false, "playlist=200,master=" + safeLocation(masterFinal)
                        + ",variantPath=" + safeLocation(mediaPlaylistUrl) + ",segment=none");
            }

            HttpResponse segmentResponse = httpGet(segment, userAgent, "*/*", true, 4096);
            boolean ok = (segmentResponse.code == 200 || segmentResponse.code == 206)
                    && segmentResponse.body.length > 0;
            return new Validation(ok, "playlist=200,master=" + safeLocation(masterFinal)
                    + ",variant=" + (variant == null ? "direct" : "200")
                    + ",variantPath=" + safeLocation(mediaPlaylistUrl)
                    + ",segment=" + segmentResponse.code
                    + ",segmentPath=" + safeLocation(segmentResponse.finalUrl)
                    + ",bytes=" + segmentResponse.body.length);
        } catch (Exception e) {
            return new Validation(false, "validationError=" + safe(e.getMessage()));
        }
    }

    private static HttpResponse httpGet(String url, String userAgent, String accept,
                                        boolean rangeProbe, int limit) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8_000);
        c.setReadTimeout(10_000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", userAgent);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("Referer", REFERER);
        c.setRequestProperty("Origin", ORIGIN);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Pragma", "no-cache");
        if (rangeProbe) c.setRequestProperty("Range", "bytes=0-4095");

        int code = c.getResponseCode();
        long retryAfterMs = parseRetryAfterMs(c.getHeaderField("Retry-After"));
        String finalUrl = c.getURL().toString();
        InputStream stream = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        byte[] body = new byte[0];
        if (stream != null) {
            try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1 && out.size() < limit) {
                    int remaining = limit - out.size();
                    out.write(buf, 0, Math.min(n, remaining));
                    if (out.size() >= limit) break;
                }
                body = out.toByteArray();
            }
        }
        c.disconnect();
        return new HttpResponse(code, body, finalUrl, retryAfterMs);
    }

    private static List<String> playlistLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.isEmpty()) lines.add(line);
        }
        return lines;
    }

    private static String firstVariantUri(String base, List<String> lines) {
        for (int i = 0; i + 1 < lines.size(); i++) {
            if (lines.get(i).startsWith("#EXT-X-STREAM-INF")) {
                String next = lines.get(i + 1);
                if (!next.startsWith("#")) return resolveUrl(base, next);
            }
        }
        return null;
    }

    private static String firstMediaUri(String base, List<String> lines) {
        for (String line : lines) {
            if (!line.startsWith("#")) return resolveUrl(base, line);
        }
        return null;
    }

    private static String resolveUrl(String base, String ref) {
        try {
            return new URL(new URL(base), ref).toString();
        } catch (Exception e) {
            return ref;
        }
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
        if (value instanceof String) {
            String direct = ((String) value).trim();
            return isHttpUrl(direct) ? direct : null;
        }
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

    private static String mediaSummary(String media) {
        if (media == null) return "none";
        return safeLocation(media);
    }

    private static String safeLocation(String value) {
        if (value == null) return "none";
        try {
            URI u = URI.create(value);
            String host = u.getHost();
            String path = u.getPath();
            return "host=" + (host == null ? "unknown" : host)
                    + ",path=" + (path == null ? "" : path)
                    + ",query=" + (u.getRawQuery() != null);
        } catch (Exception ignored) {
            return "present";
        }
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

    private record RequestProfile(String name, String api, String apiType,
                                  String platform, String ssl, String userAgent) {}
    private record Probe(String mediaUrl, String summary) {}
    private record ProbeSet(String mediaUrl, String summary) {}
    private record Validation(boolean valid, String summary) {}
    private record ParsedMedia(String mediaUrl, String responseType) {}
    private record HttpResponse(int code, byte[] body, String finalUrl, long retryAfterMs) {
        String bodyText() { return new String(body, StandardCharsets.UTF_8); }
    }
}
