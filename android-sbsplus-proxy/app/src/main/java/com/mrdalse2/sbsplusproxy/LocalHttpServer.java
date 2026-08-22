package com.mrdalse2.sbsplusproxy;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalHttpServer {
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final Set<String> allowed = ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private ServerSocket server;

    public synchronized void start() throws Exception {
        if (running) return;
        server = new ServerSocket(8787, 32, InetAddress.getByName("0.0.0.0"));
        server.setReuseAddress(true);
        running = true;
        pool.execute(() -> {
            while (running) {
                try {
                    Socket socket = server.accept();
                    pool.execute(() -> handle(socket));
                } catch (Exception e) {
                    if (running) e.printStackTrace();
                }
            }
        });
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
             OutputStream out = socket.getOutputStream()) {
            String request = reader.readLine();
            if (request == null) return;
            String[] parts = request.split(" ");
            String rawPath = parts.length > 1 ? parts[1] : "/";
            String host = null;
            while (true) {
                String header = reader.readLine();
                if (header == null || header.isEmpty()) break;
                int colon = header.indexOf(':');
                if (colon > 0 && "host".equalsIgnoreCase(header.substring(0, colon).trim())) host = header.substring(colon + 1).trim();
            }

            try {
                URI uri = URI.create(rawPath);
                String path = uri.getPath();
                if ("/health".equals(path)) { sendText(out, 200, "OK Local IPTV Proxy 2.4\n"); return; }
                if ("/playlist.m3u".equals(path) || "/playlist.m3u8".equals(path)) {
                    String authority = host;
                    if (authority == null || authority.isBlank()) authority = socket.getLocalAddress().getHostAddress() + ":8787";
                    byte[] body = PlaylistAggregator.build("http://" + authority + "/sbsplus.m3u8");
                    writeHeaders(out, 200, "application/x-mpegURL; charset=utf-8", body.length, "no-store, no-cache, must-revalidate");
                    out.write(body); out.flush(); return;
                }
                if ("/sbsplus.m3u8".equals(path) || "/sbsplus".equals(path) || "/".equals(path)) {
                    String target = SbsResolver.resolve();
                    allowed.add(target);
                    proxy(out, target, true);
                    return;
                }
                if ("/hls".equals(path)) {
                    String target = queryValue(uri.getRawQuery(), "u");
                    if (target == null || !target.startsWith("http") || !allowed.contains(target)) { sendText(out, 403, "Unknown HLS resource"); return; }
                    proxy(out, target, false);
                    return;
                }
                sendText(out, 404, "Not found");
            } catch (Exception e) {
                sendText(out, 502, "Local IPTV proxy error: " + safeMessage(e));
            }
        } catch (Exception ignored) {}
    }

    private void proxy(OutputStream out, String target, boolean root) throws Exception {
        Remote remote = fetchWithTokenRefresh(target);
        byte[] body = remote.body;
        String contentType = remote.contentType;
        if (looksLikePlaylist(remote.finalUrl, contentType, body)) {
            body = rewritePlaylist(new String(body, StandardCharsets.UTF_8), remote.finalUrl).getBytes(StandardCharsets.UTF_8);
            contentType = "application/vnd.apple.mpegurl";
        }
        writeHeaders(out, 200, contentType, body.length,
                root || contentType.toLowerCase().contains("mpegurl") ? "no-store, no-cache, must-revalidate" : "private, max-age=2");
        out.write(body); out.flush();
    }

    private Remote fetchWithTokenRefresh(String target) throws Exception {
        String refreshed = SbsResolver.refreshTokenizedUrl(target, false);
        try { return fetch(refreshed); }
        catch (UpstreamAuthException e) {
            String forced = SbsResolver.refreshTokenizedUrl(target, true);
            return fetch(forced);
        }
    }

    private Remote fetch(String target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) LocalIPTVProxy/2.4");
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Referer", "https://www.sbs.co.kr/live/S03");
        c.setRequestProperty("Origin", "https://www.sbs.co.kr");
        int code = c.getResponseCode();
        if (code == 401 || code == 403) { c.disconnect(); throw new UpstreamAuthException(); }
        if (code < 200 || code >= 300) { c.disconnect(); throw new IllegalStateException("upstream HTTP " + code + " for " + new URL(target).getHost()); }
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024]; int n;
            while ((n = in.read(buf)) >= 0) bytes.write(buf, 0, n);
            String type = c.getContentType();
            if (type == null) type = "application/octet-stream";
            return new Remote(bytes.toByteArray(), type, c.getURL().toString());
        } finally { c.disconnect(); }
    }

    private String rewritePlaylist(String text, String baseUrl) throws Exception {
        Pattern uriAttr = Pattern.compile("(URI=\\\")([^\\\"]+)(\\\")");
        StringBuilder result = new StringBuilder();
        for (String raw : text.split("\\r?\\n")) {
            Matcher m = uriAttr.matcher(raw);
            StringBuffer lineBuffer = new StringBuffer();
            while (m.find()) {
                String absolute = new URL(new URL(baseUrl), m.group(2)).toString();
                m.appendReplacement(lineBuffer, Matcher.quoteReplacement(m.group(1) + localize(absolute) + m.group(3)));
            }
            m.appendTail(lineBuffer);
            String line = lineBuffer.toString();
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) line = localize(new URL(new URL(baseUrl), trimmed).toString());
            result.append(line).append('\n');
        }
        return result.toString();
    }

    private String localize(String absolute) throws Exception {
        allowed.add(absolute);
        return "/hls?u=" + URLEncoder.encode(absolute, StandardCharsets.UTF_8.name());
    }

    private boolean looksLikePlaylist(String url, String contentType, byte[] body) {
        String type = contentType == null ? "" : contentType.toLowerCase();
        if (type.contains("mpegurl")) return true;
        try { if (new URL(url).getPath().toLowerCase().endsWith(".m3u8")) return true; } catch (Exception ignored) {}
        String prefix = new String(body, 0, Math.min(body.length, 16), StandardCharsets.US_ASCII).trim();
        return prefix.startsWith("#EXTM3U");
    }

    private String queryValue(String query, String key) throws Exception {
        if (query == null) return null;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            String k = eq >= 0 ? part.substring(0, eq) : part;
            if (key.equals(URLDecoder.decode(k, StandardCharsets.UTF_8.name()))) {
                String v = eq >= 0 ? part.substring(eq + 1) : "";
                return URLDecoder.decode(v, StandardCharsets.UTF_8.name());
            }
        }
        return null;
    }

    private void sendText(OutputStream out, int code, String text) throws Exception {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        writeHeaders(out, code, "text/plain; charset=utf-8", body.length, "no-store");
        out.write(body); out.flush();
    }

    private void writeHeaders(OutputStream out, int code, String type, int length, String cache) throws Exception {
        String reason = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : code == 502 ? "Bad Gateway" : "Error";
        String headers = "HTTP/1.1 " + code + " " + reason + "\r\n" +
                "Content-Type: " + type + "\r\n" + "Content-Length: " + length + "\r\n" +
                "Cache-Control: " + cache + "\r\n" + "Access-Control-Allow-Origin: *\r\n" + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    private static final class Remote {
        final byte[] body; final String contentType; final String finalUrl;
        Remote(byte[] body, String contentType, String finalUrl) { this.body = body; this.contentType = contentType; this.finalUrl = finalUrl; }
    }
    private static final class UpstreamAuthException extends Exception {}
}
