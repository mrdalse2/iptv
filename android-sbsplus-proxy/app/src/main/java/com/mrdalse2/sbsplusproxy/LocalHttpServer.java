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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalHttpServer {
    private static final String REFERER = "https://www.sbs.co.kr/live/S03";
    private static final String ORIGIN = "https://www.sbs.co.kr";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36";
    private static final Pattern URI_ATTR = Pattern.compile("URI=\\\"([^\\\"]+)\\\"");

    private final ExecutorService pool = Executors.newCachedThreadPool();
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
            Map<String, String> requestHeaders = new HashMap<>();
            while (true) {
                String header = reader.readLine();
                if (header == null || header.isEmpty()) break;
                int colon = header.indexOf(':');
                if (colon > 0) requestHeaders.put(header.substring(0, colon).trim().toLowerCase(), header.substring(colon + 1).trim());
            }

            try {
                URI uri = URI.create(rawPath);
                String path = uri.getPath();
                if ("/health".equals(path)) {
                    sendText(out, 200, "OK Local IPTV Proxy 4.0\n");
                    return;
                }
                if ("/debug/sbs".equals(path)) {
                    sendText(out, 200, SbsResolver.debugSnapshot() + "\n");
                    return;
                }
                if ("/playlist.m3u".equals(path) || "/playlist.m3u8".equals(path)) {
                    String authority = requestHeaders.get("host");
                    if (authority == null || authority.isBlank()) authority = socket.getLocalAddress().getHostAddress() + ":8787";
                    byte[] body = PlaylistAggregator.build("http://" + authority + "/sbsplus.m3u8");
                    writeHeaders(out, 200, "application/x-mpegURL; charset=utf-8", body.length,
                            "no-store, no-cache, must-revalidate", null);
                    out.write(body);
                    out.flush();
                    return;
                }
                if ("/sbsplus.m3u8".equals(path) || "/sbsplus".equals(path) || "/".equals(path)) {
                    proxyRemote(out, SbsResolver.resolve(), requestHeaders.get("range"), localBase(socket, requestHeaders));
                    return;
                }
                if ("/sbsproxy".equals(path)) {
                    String target = queryParam(uri.getRawQuery(), "u");
                    if (target == null || !(target.startsWith("https://") || target.startsWith("http://"))) {
                        sendText(out, 400, "Bad proxy target");
                        return;
                    }
                    proxyRemote(out, target, requestHeaders.get("range"), localBase(socket, requestHeaders));
                    return;
                }
                sendText(out, 404, "Not found");
            } catch (Exception e) {
                sendText(out, 502, "Local IPTV proxy error: " + safeMessage(e));
            }
        } catch (Exception ignored) {}
    }

    private void proxyRemote(OutputStream out, String target, String range, String localBase) throws Exception {
        Remote remote = fetchSeamlessly(target, range);
        byte[] body = remote.body;
        String contentType = remote.contentType;
        String lowerType = contentType == null ? "" : contentType.toLowerCase();
        boolean playlist = remote.finalUrl.toLowerCase().contains(".m3u8")
                || lowerType.contains("mpegurl") || lowerType.contains("vnd.apple.mpegurl");

        if (remote.code >= 200 && remote.code < 300 && playlist) {
            body = rewritePlaylist(new String(body, StandardCharsets.UTF_8), remote.finalUrl, localBase)
                    .getBytes(StandardCharsets.UTF_8);
            contentType = "application/vnd.apple.mpegurl; charset=utf-8";
        }
        if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";

        StringBuilder extra = new StringBuilder();
        if (remote.contentRange != null) extra.append("Content-Range: ").append(remote.contentRange).append("\r\n");
        if (remote.acceptRanges != null) extra.append("Accept-Ranges: ").append(remote.acceptRanges).append("\r\n");
        writeHeaders(out, remote.code, contentType, body.length,
                playlist ? "no-store, no-cache, must-revalidate" : "private, max-age=2", extra.toString());
        out.write(body);
        out.flush();
    }

    private Remote fetchSeamlessly(String target, String range) throws Exception {
        try {
            return fetch(target, range);
        } catch (SignedUrlExpiredException e) {
            String freshRoot = SbsResolver.resolveFresh();
            String refreshed = refreshSignedQuery(target, freshRoot);
            return fetch(refreshed, range);
        } catch (TransientUpstreamException e) {
            try { Thread.sleep(250L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            return fetch(target, range);
        }
    }

    private Remote fetch(String target, String range) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setConnectTimeout(8_000);
        c.setReadTimeout(15_000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", DESKTOP_UA);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Referer", REFERER);
        c.setRequestProperty("Origin", ORIGIN);
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("Pragma", "no-cache");
        if (range != null && !range.isBlank()) c.setRequestProperty("Range", range);

        int code = c.getResponseCode();
        if (code == 401 || code == 403 || code == 404 || code == 410) {
            c.disconnect();
            throw new SignedUrlExpiredException("HTTP " + code);
        }
        if (code == 408 || code == 425 || code == 429 || code == 500 || code == 502 || code == 503 || code == 504) {
            c.disconnect();
            throw new TransientUpstreamException("HTTP " + code);
        }

        String finalUrl = c.getURL().toString();
        String contentType = c.getContentType();
        InputStream stream = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        byte[] body = readAll(stream);
        String contentRange = c.getHeaderField("Content-Range");
        String acceptRanges = c.getHeaderField("Accept-Ranges");
        c.disconnect();
        return new Remote(code, body, contentType, finalUrl, contentRange, acceptRanges);
    }

    private String refreshSignedQuery(String target, String freshRoot) throws Exception {
        URI old = URI.create(target);
        URI fresh = URI.create(freshRoot);
        String freshQuery = fresh.getRawQuery();
        if (freshQuery == null || freshQuery.isBlank()) {
            if (samePath(old, fresh)) return fresh.toString();
            return target;
        }
        return new URI(old.getScheme(), old.getAuthority(), old.getPath(), freshQuery, old.getFragment()).toString();
    }

    private boolean samePath(URI a, URI b) {
        String ap = a.getPath() == null ? "" : a.getPath();
        String bp = b.getPath() == null ? "" : b.getPath();
        return ap.equals(bp);
    }

    private String rewritePlaylist(String text, String baseUrl, String localBase) {
        StringBuilder out = new StringBuilder();
        for (String raw : text.split("\\r?\\n", -1)) {
            String line = raw.trim();
            if (line.isEmpty()) {
                out.append('\n');
                continue;
            }
            if (line.startsWith("#")) {
                Matcher m = URI_ATTR.matcher(raw);
                StringBuffer sb = new StringBuffer();
                while (m.find()) {
                    String absolute = resolveUrl(baseUrl, m.group(1));
                    String replacement = "URI=\"" + proxyUrl(localBase, absolute) + "\"";
                    m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                }
                m.appendTail(sb);
                out.append(sb).append('\n');
            } else {
                out.append(proxyUrl(localBase, resolveUrl(baseUrl, line))).append('\n');
            }
        }
        return out.toString();
    }

    private static String proxyUrl(String localBase, String remote) {
        return localBase + "/sbsproxy?u=" + URLEncoder.encode(remote, StandardCharsets.UTF_8);
    }

    private static String resolveUrl(String base, String ref) {
        try { return new URL(new URL(base), ref).toString(); }
        catch (Exception e) { return ref; }
    }

    private static String queryParam(String rawQuery, String name) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            if (!name.equals(URLDecoder.decode(key, StandardCharsets.UTF_8))) continue;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        return null;
    }

    private static String localBase(Socket socket, Map<String, String> headers) {
        String authority = headers.get("host");
        if (authority == null || authority.isBlank()) authority = socket.getLocalAddress().getHostAddress() + ":8787";
        return "http://" + authority;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = input.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private void sendText(OutputStream out, int code, String text) throws Exception {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        writeHeaders(out, code, "text/plain; charset=utf-8", body.length, "no-store", null);
        out.write(body);
        out.flush();
    }

    private void writeHeaders(OutputStream out, int code, String type, int length, String cache, String extra) throws Exception {
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Content-Length: ").append(length).append("\r\n")
                .append("Cache-Control: ").append(cache).append("\r\n")
                .append("Access-Control-Allow-Origin: *\r\n");
        if (extra != null) headers.append(extra);
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static String reason(int code) {
        if (code == 200) return "OK";
        if (code == 206) return "Partial Content";
        if (code == 400) return "Bad Request";
        if (code == 401) return "Unauthorized";
        if (code == 403) return "Forbidden";
        if (code == 404) return "Not Found";
        if (code == 410) return "Gone";
        if (code == 502) return "Bad Gateway";
        return "Error";
    }

    private static String safeMessage(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    private static final class Remote {
        final int code;
        final byte[] body;
        final String contentType;
        final String finalUrl;
        final String contentRange;
        final String acceptRanges;
        Remote(int code, byte[] body, String contentType, String finalUrl, String contentRange, String acceptRanges) {
            this.code = code;
            this.body = body;
            this.contentType = contentType;
            this.finalUrl = finalUrl;
            this.contentRange = contentRange;
            this.acceptRanges = acceptRanges;
        }
    }

    private static final class SignedUrlExpiredException extends Exception {
        SignedUrlExpiredException(String message) { super(message); }
    }

    private static final class TransientUpstreamException extends Exception {
        TransientUpstreamException(String message) { super(message); }
    }
}
