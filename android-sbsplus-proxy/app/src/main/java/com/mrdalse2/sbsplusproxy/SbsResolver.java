package com.mrdalse2.sbsplusproxy;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SbsResolver {
    private static final String ONAIR_API = "https://apis.sbs.co.kr/play-api/1.0/onair/channel/S03";
    private static final String PLUS_LIVESTREAM_API = "https://apis.sbs.co.kr/play-api/1.0/livestream/sbspluspc/sbsplus0";
    private static final String S03_LIVESTREAM_API = "https://apis.sbs.co.kr/play-api/1.0/livestream/S03/S03";
    private static final String REFERER = "https://www.sbs.co.kr/live/S03";
    private static final String ORIGIN = "https://www.sbs.co.kr";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36";
    private static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36";

    private static final long PREFETCH_BEFORE_EXPIRY_MS = 15_000L;
    private static final long NO_JWT_PREFETCH_AGE_MS = 45_000L;
    private static final long PREFETCH_VALID_MS = 45_000L;

    private static final Object LOCK = new Object();
    private static final AtomicBoolean PREFETCHING = new AtomicBoolean(false);
    private static final ExecutorService PREFETCHER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sbs-token-prefetch");
        t.setDaemon(true);
        return t;
    });

    private static volatile String activeMediaUrl;
    private static volatile long activeAt;
    private static volatile String prefetchedMediaUrl;
    private static volatile long prefetchedAt;
    private static volatile String lastDebug = "not probed yet";

    private SbsResolver() {}

    public static String resolve() throws Exception {
        String active = activeMediaUrl;
        if (active == null) {
            synchronized (LOCK) {
                if (activeMediaUrl == null) promote(probeAll().mediaUrl);
                active = activeMediaUrl;
            }
        }
        maybePrefetch(active);
        return active;
    }

    /** Called only after an upstream signed resource is rejected. */
    public static String resolveFresh() throws Exception {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (prefetchedMediaUrl != null && now - prefetchedAt < PREFETCH_VALID_MS && tokenUsable(prefetchedMediaUrl, 3_000L)) {
                String ready = prefetchedMediaUrl;
                prefetchedMediaUrl = null;
                prefetchedAt = 0L;
                promote(ready);
                return ready;
            }

            ProbeSet set = probeAll();
            if (set.mediaUrl == null) throw new IllegalStateException("SBS Plus fresh resolve failed; " + set.summary);
            promote(set.mediaUrl);
            return set.mediaUrl;
        }
    }

    private static void promote(String url) {
        if (url == null) throw new IllegalStateException("No validated SBS HLS URL");
        activeMediaUrl = url;
        activeAt = System.currentTimeMillis();
    }

    private static void maybePrefetch(String active) {
        if (active == null || PREFETCHING.get()) return;
        long remaining = tokenRemainingMs(active);
        long age = System.currentTimeMillis() - activeAt;
        boolean needed = remaining >= 0 ? remaining <= PREFETCH_BEFORE_EXPIRY_MS : age >= NO_JWT_PREFETCH_AGE_MS;
        if (!needed) return;

        if (!PREFETCHING.compareAndSet(false, true)) return;
        PREFETCHER.execute(() -> {
            try {
                ProbeSet set = probeAll();
                if (set.mediaUrl != null && !set.mediaUrl.equals(activeMediaUrl)) {
                    prefetchedMediaUrl = set.mediaUrl;
                    prefetchedAt = System.currentTimeMillis();
                    lastDebug = set.summary + ", prefetched=true, tokenRemainingMs=" + tokenRemainingMs(activeMediaUrl);
                }
            } catch (Exception e) {
                lastDebug = "prefetchError=" + safe(e.getMessage()) + "; previous=" + lastDebug;
            } finally {
                PREFETCHING.set(false);
            }
        });
    }

    public static String debugSnapshot() {
        long remaining = tokenRemainingMs(activeMediaUrl);
        long age = activeMediaUrl == null ? -1L : System.currentTimeMillis() - activeAt;
        long prefetchAge = prefetchedMediaUrl == null ? -1L : System.currentTimeMillis() - prefetchedAt;
        return "activeAgeMs=" + age + ",tokenRemainingMs=" + remaining
                + ",prefetching=" + PREFETCHING.get() + ",prefetchedAgeMs=" + prefetchAge
                + ",active=" + safeLocation(activeMediaUrl) + ",prefetched=" + safeLocation(prefetchedMediaUrl)
                + ",last=" + lastDebug;
    }

    private static ProbeSet probeAll() throws Exception {
        // Old smooth path first; the proven SBS Plus alias is the fallback for special-program windows.
        RequestProfile[] profiles = new RequestProfile[] {
                new RequestProfile("onair-pc-N", ONAIR_API, false, "pcweb", "N", DESKTOP_UA),
                new RequestProfile("onair-pc-Y", ONAIR_API, false, "pcweb", "Y", DESKTOP_UA),
                new RequestProfile("onair-mobile-N", ONAIR_API, false, "mobile", "N", MOBILE_UA),
                new RequestProfile("plus-live-N", PLUS_LIVESTREAM_API, true, "pcweb", "N", DESKTOP_UA),
                new RequestProfile("plus-live-Y", PLUS_LIVESTREAM_API, true, "pcweb", "Y", DESKTOP_UA),
                new RequestProfile("s03-live-N", S03_LIVESTREAM_API, true, "pcweb", "N", DESKTOP_UA),
                new RequestProfile("s03-live-Y", S03_LIVESTREAM_API, true, "pcweb", "Y", DESKTOP_UA)
        };

        List<String> attempts = new ArrayList<>();
        Exception last = null;
        for (RequestProfile p : profiles) {
            try {
                Probe probe = request(p);
                attempts.add(p.name + "{" + probe.summary + "}");
                if (probe.mediaUrl == null) continue;
                Validation validation = validateHls(probe.mediaUrl, p.userAgent);
                attempts.add(p.name + "-hls{" + validation.summary + "}");
                if (validation.valid) {
                    String summary = "selected=" + p.name + ",validated=true,selectedMedia=" + safeLocation(probe.mediaUrl)
                            + ",attempts=" + attempts;
                    lastDebug = summary;
                    return new ProbeSet(probe.mediaUrl, summary);
                }
            } catch (Exception e) {
                last = e;
                attempts.add(p.name + "{error=" + safe(e.getMessage()) + "}");
            }
        }
        String summary = "selected=none,validated=false,attempts=" + attempts
                + (last == null ? "" : ",lastError=" + safe(last.getMessage()));
        lastDebug = summary;
        return new ProbeSet(null, summary);
    }

    private static Probe request(RequestProfile p) throws Exception {
        String query = p.livestream
                ? "protocol=hls&ssl=" + p.ssl
                : "v_type=2&platform=" + p.platform + "&protocol=hls&ssl=" + p.ssl + "&rscuse=&jwt-token=&sbsmain=";
        HttpResponse r = httpGet(p.api + "?" + query, p.userAgent, "application/json,text/plain,*/*", false, 1024 * 1024);
        if (r.code < 200 || r.code >= 300) throw new IllegalStateException("HTTP " + r.code);
        ParsedMedia parsed = parseMediaResponse(r.bodyText());
        return new Probe(parsed.mediaUrl, "http=" + r.code + ",responseType=" + parsed.responseType + ",media=" + safeLocation(parsed.mediaUrl));
    }

    private static ParsedMedia parseMediaResponse(String rawBody) throws Exception {
        String body = rawBody == null ? "" : rawBody.trim();
        if (body.isEmpty()) return new ParsedMedia(null, "empty");
        if (isHttpUrl(body)) return new ParsedMedia(body, "plain-url");
        Object parsed = new JSONTokener(body).nextValue();
        if (parsed instanceof String) {
            String s = ((String) parsed).trim();
            return new ParsedMedia(isHttpUrl(s) ? s : null, "json-string");
        }
        if (parsed instanceof JSONObject || parsed instanceof JSONArray) {
            return new ParsedMedia(findMediaUrl(parsed), parsed instanceof JSONObject ? "json-object" : "json-array");
        }
        return new ParsedMedia(null, "other");
    }

    private static Validation validateHls(String mediaUrl, String ua) {
        try {
            HttpResponse master = httpGet(mediaUrl, ua, "*/*", false, 256 * 1024);
            String text = master.bodyText();
            if (master.code != 200 || !text.stripLeading().startsWith("#EXTM3U")) return new Validation(false, "master=" + master.code);
            List<String> lines = playlistLines(text);
            String mediaPlaylist = master.finalUrl;
            String variant = firstVariantUri(master.finalUrl, lines);
            if (variant != null) {
                HttpResponse vr = httpGet(variant, ua, "*/*", false, 256 * 1024);
                if (vr.code != 200 || !vr.bodyText().stripLeading().startsWith("#EXTM3U")) return new Validation(false, "variant=" + vr.code);
                mediaPlaylist = vr.finalUrl;
                lines = playlistLines(vr.bodyText());
            }
            String segment = firstMediaUri(mediaPlaylist, lines);
            if (segment == null) return new Validation(false, "segment=none");
            HttpResponse sr = httpGet(segment, ua, "*/*", true, 4096);
            boolean ok = (sr.code == 200 || sr.code == 206) && sr.body.length > 0;
            return new Validation(ok, "master=200,variant=" + (variant == null ? "direct" : "200") + ",segment=" + sr.code);
        } catch (Exception e) {
            return new Validation(false, "error=" + safe(e.getMessage()));
        }
    }

    private static HttpResponse httpGet(String url, String ua, String accept, boolean range, int limit) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8_000);
        c.setReadTimeout(10_000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("Referer", REFERER);
        c.setRequestProperty("Origin", ORIGIN);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Pragma", "no-cache");
        if (range) c.setRequestProperty("Range", "bytes=0-4095");
        int code = c.getResponseCode();
        long retry = parseRetryAfterMs(c.getHeaderField("Retry-After"));
        String finalUrl = c.getURL().toString();
        InputStream stream = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        byte[] body = new byte[0];
        if (stream != null) {
            try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1 && out.size() < limit) {
                    out.write(buf, 0, Math.min(n, limit - out.size()));
                    if (out.size() >= limit) break;
                }
                body = out.toByteArray();
            }
        }
        c.disconnect();
        return new HttpResponse(code, body, finalUrl, retry);
    }

    private static long tokenRemainingMs(String url) {
        try {
            if (url == null) return -1L;
            URI u = URI.create(url);
            String token = queryValue(u.getRawQuery(), "token");
            if (token == null || token.isBlank()) return -1L;
            String[] parts = token.split("\\.");
            if (parts.length < 2) return -1L;
            String payload = parts[1];
            int pad = (4 - payload.length() % 4) % 4;
            payload += "=".repeat(pad);
            byte[] decoded = Base64.getUrlDecoder().decode(payload);
            JSONObject json = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            long exp = json.optLong("exp", 0L);
            return exp <= 0 ? -1L : exp * 1000L - System.currentTimeMillis();
        } catch (Exception e) {
            return -1L;
        }
    }

    private static boolean tokenUsable(String url, long minRemainingMs) {
        long remaining = tokenRemainingMs(url);
        return remaining < 0 || remaining > minRemainingMs;
    }

    private static String queryValue(String query, String key) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equals(k)) return eq < 0 ? "" : pair.substring(eq + 1);
        }
        return null;
    }

    private static List<String> playlistLines(String text) {
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String s = raw.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static String firstVariantUri(String base, List<String> lines) {
        for (int i = 0; i + 1 < lines.size(); i++) if (lines.get(i).startsWith("#EXT-X-STREAM-INF")) {
            String next = lines.get(i + 1);
            if (!next.startsWith("#")) return resolveUrl(base, next);
        }
        return null;
    }

    private static String firstMediaUri(String base, List<String> lines) {
        for (String s : lines) if (!s.startsWith("#")) return resolveUrl(base, s);
        return null;
    }

    private static String resolveUrl(String base, String ref) {
        try { return new URL(new URL(base), ref).toString(); }
        catch (Exception e) { return ref; }
    }

    private static String findMediaUrl(Object value) {
        if (value instanceof String) {
            String s = ((String) value).trim();
            return isHttpUrl(s) ? s : null;
        }
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            for (String key : new String[]{"mediaurl", "mediaUrl", "media_url"}) {
                String s = o.optString(key, "");
                if (isHttpUrl(s)) return s;
            }
            JSONArray names = o.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String found = findMediaUrl(o.opt(names.optString(i)));
                if (found != null) return found;
            }
        } else if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.length(); i++) {
                String found = findMediaUrl(a.opt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static long parseRetryAfterMs(String value) {
        if (value == null || value.isBlank()) return 0L;
        try { return Math.min(Long.parseLong(value.trim()) * 1000L, 10_000L); }
        catch (Exception ignored) {}
        try {
            long target = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            return Math.max(0L, Math.min(target - System.currentTimeMillis(), 10_000L));
        } catch (Exception ignored) { return 0L; }
    }

    private static String safeLocation(String value) {
        if (value == null) return "none";
        try {
            URI u = URI.create(value);
            return "host=" + u.getHost() + ",path=" + u.getPath() + ",query=" + (u.getRawQuery() != null);
        } catch (Exception e) { return "present"; }
    }

    private static boolean isHttpUrl(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s.replace('\n', ' ').replace('\r', ' ');
    }

    private record RequestProfile(String name, String api, boolean livestream, String platform, String ssl, String userAgent) {}
    private record Probe(String mediaUrl, String summary) {}
    private record ProbeSet(String mediaUrl, String summary) {}
    private record Validation(boolean valid, String summary) {}
    private record ParsedMedia(String mediaUrl, String responseType) {}
    private record HttpResponse(int code, byte[] body, String finalUrl, long retryAfterMs) {
        String bodyText() { return new String(body, StandardCharsets.UTF_8); }
    }
}
