package com.mrdalse2.sbsplusproxy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class HlsFetcher {
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36";
    private static final String ANDROID_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36";
    private static volatile String lastDebug = "not fetched yet";

    private static final HlsFetchProfile[] PROFILES = new HlsFetchProfile[] {
            new HlsFetchProfile("desktop-referer", DESKTOP_UA, true, false),
            new HlsFetchProfile("desktop-minimal", DESKTOP_UA, false, false),
            new HlsFetchProfile("android-referer", ANDROID_UA, true, false)
    };

    private HlsFetcher() {}

    static Result fetch(String target) throws Exception {
        Exception last = null;
        StringBuilder attempts = new StringBuilder();
        for (HlsFetchProfile profile : PROFILES) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(target).openConnection();
                c.setConnectTimeout(8_000);
                c.setReadTimeout(15_000);
                c.setInstanceFollowRedirects(true);
                c.setUseCaches(false);
                c.setRequestProperty("User-Agent", profile.userAgent);
                c.setRequestProperty("Accept", "*/*");
                c.setRequestProperty("Accept-Encoding", "identity");
                if (profile.referer) c.setRequestProperty("Referer", "https://www.sbs.co.kr/live/S03");
                if (profile.origin) c.setRequestProperty("Origin", "https://www.sbs.co.kr");

                int code = c.getResponseCode();
                if (attempts.length() > 0) attempts.append(',');
                attempts.append(profile.name).append("{http=").append(code).append('}');

                if (code == 400) {
                    last = new BadRequestException("HTTP 400 profile=" + profile.name);
                    continue;
                }
                if (code == 401 || code == 403 || code == 404 || code == 410) {
                    lastDebug = "selected=" + profile.name + ", attempts=[" + attempts + "]";
                    throw new SignedUrlExpiredException("HTTP " + code);
                }
                if (code == 408 || code == 425 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504) {
                    lastDebug = "selected=" + profile.name + ", attempts=[" + attempts + "]";
                    throw new TransientUpstreamException("HTTP " + code);
                }
                if (code < 200 || code >= 300) {
                    last = new IllegalStateException("upstream HTTP " + code + " profile=" + profile.name);
                    continue;
                }

                try (InputStream in = c.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) >= 0) bytes.write(buf, 0, n);
                    String type = c.getContentType();
                    if (type == null) type = "application/octet-stream";
                    String finalUrl = c.getURL().toString();
                    lastDebug = "selected=" + profile.name + ", attempts=[" + attempts + "], finalHost=" + new URL(finalUrl).getHost();
                    return new Result(bytes.toByteArray(), type, finalUrl);
                }
            } catch (SignedUrlExpiredException | TransientUpstreamException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                if (attempts.length() > 0) attempts.append(',');
                attempts.append(profile.name).append("{error=").append(safe(e.getMessage())).append('}');
            } finally {
                if (c != null) c.disconnect();
            }
        }
        lastDebug = "selected=none, attempts=[" + attempts + "]";
        if (last != null) throw last;
        throw new IllegalStateException("HLS fetch failed");
    }

    static String debugSnapshot() {
        return lastDebug;
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s.replace('\n', ' ').replace('\r', ' ');
    }

    static final class Result {
        final byte[] body;
        final String contentType;
        final String finalUrl;
        Result(byte[] body, String contentType, String finalUrl) {
            this.body = body;
            this.contentType = contentType;
            this.finalUrl = finalUrl;
        }
    }

    static final class SignedUrlExpiredException extends Exception {
        SignedUrlExpiredException(String message) { super(message); }
    }

    static final class TransientUpstreamException extends Exception {
        TransientUpstreamException(String message) { super(message); }
    }

    private static final class BadRequestException extends Exception {
        BadRequestException(String message) { super(message); }
    }
}
