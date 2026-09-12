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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LocalHttpServer {
    private static final String REFERER = "https://www.sbs.co.kr/live/S03";
    private static final String ORIGIN = "https://www.sbs.co.kr";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36";
    private static final Pattern URI_ATTR = Pattern.compile("URI=\\\"([^\\\"]+)\\\"");
    private static final long ROOT_WARM_INTERVAL_MS = 30_000L;

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AtomicBoolean warmingRoot = new AtomicBoolean(false);
    private volatile long lastRootWarmAt;
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
                    sendText(out, 200, "OK Local IPTV Proxy 4.1 2.8-relay\n");
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
        Upstream remote = openSeamlessly(target, range);
        try {
            String contentType = remote.contentType;
            String lowerType = contentType == null ? "" : contentType.toLowerCase();
            boolean playlist = remote.finalUrl.toLowerCase().contains(".m3u8")
                    || lowerType.contains("mpegurl") || lowerType.contains("vnd.apple.mpegurl");

            if (playlist) {
                byte[] body = readAll(remote.stream);
                body = rewritePlaylist(new String(body, StandardCharsets.UTF_8), remote.finalUrl, localBase)
                        .getBytes(StandardCharsets.UTF_8);
                contentType = "application/vnd.apple.mpegurl; charset=utf-8";
                writeHeaders(out, remote.code, contentType, body.length,
                        "no-store, no-cache, must-revalidate", rangeHeaders(remote));
                out.write(body);
                out.flush();
                return;
            }

            if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";
            writeStreamingHeaders(out, remote.code, contentType, remote.contentLength,
                    "private, max-age=2", rangeHeaders(remote));

            byte[] buffer = new byte[32 * 1024];
            int n;
            while ((n = remote.stream.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                out.flush();
            }
        } finally {
            remote.close();
        }
    }

    private String rangeHeaders(Upstream remote) {
        StringBuilder extra = new StringBuilder();
        if (remote.contentRange != null) extra.append("Content-Range: ").append(remote.contentRange).append("\r\n");
        if (remote.acceptRanges != null) extra.append("Accept-Ranges: ").append(remote.acceptRanges).append("\r\n");
        return extra.toString();
    }

    /**
     * Restore the 2.8 behaviour: every child HLS request is remapped against the latest
     * already-known root before touching SBS. A fresh root is also warmed in the background,
     * so normal playback does not wait for a token refresh. If SBS still rejects the resource,
     * force a refresh and retry transparently.
     */
    private Upstream openSeamlessly(String target, String range) throws Exception {
        warmRootAsync();
        String currentRoot = SbsResolver.resolve();
        String prepared = refreshSignedResource(target, currentRoot);
        try {
            return open(prepared, range);
        } catch (SignedUrlExpiredException e) {
            String freshRoot = SbsResolver.resolveFresh();
            String refreshed = refreshSignedResource(target, freshRoot);
            return open(refreshed, range);
        } catch (TransientUpstreamException e) {
            try { Thread.sleep(150L); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            return open(prepared, range);
        }
    }

    private void warmRootAsync() {
        long now = System.currentTimeMillis();
        if (now - lastRootWarmAt < ROOT_WARM_INTERVAL_MS || !warmingRoot.compareAndSet(false, true)) return;
        lastRootWarmAt = now;
        pool.execute(() -> {
            try {
                SbsResolver.resolveFresh();
            } catch (Exception ignored) {
                // Keep playback on the current validated root. A real upstream rejection will retry synchronously.
            } finally {
                warmingRoot.set(false);
            }
        });
    }

    private Upstream open(String target, String range) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setConnectTimeout(6_000);
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
        long contentLength = c.getContentLengthLong();
        String contentRange = c.getHeaderField("Content-Range");
        String acceptRanges = c.getHeaderField("Accept-Ranges");
        InputStream stream = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        if (stream == null) stream = InputStream.nullInputStream();
        return new Upstream(c, code, stream, contentType, finalUrl, contentLength, contentRange, acceptRanges);
    }

    /**
     * Keep the child resource suffix and child-only session parameters (for example solsessionid),
     * while replacing host/stream root and current signing parameters from the fresh SBS root.
     */
    private String refreshSignedResource(String target, String freshRoot) throws Exception {
        URI old = URI.create(target);
        URI fresh = URI.create(freshRoot);
        String oldPath = old.getPath() == null ? "" : old.getPath();
        String freshPath = fresh.getPath() == null ? "" : fresh.getPath();

        String newPath;
        int oldStream = oldPath.indexOf(".stream/");
        int freshStream = freshPath.indexOf(".stream/");
        if (oldStream >= 0 && freshStream >= 0) {
            int oldSuffixStart = oldStream + ".stream/".length();
            int freshPrefixEnd = freshStream + ".stream/".length();
            newPath = freshPath.substring(0, freshPrefixEnd) + oldPath.substring(oldSuffixStart);
        } else if (samePath(old, fresh)) {
            newPath = freshPath;
        } else {
            newPath = oldPath;
        }

        LinkedHashMap<String, String> merged = parseRawQuery(old.getRawQuery());
        merged.putAll(parseRawQuery(fresh.getRawQuery()));
        String query = buildRawQuery(merged);

        return new URI(
                fresh.getScheme() != null ? fresh.getScheme() : old.getScheme(),
                fresh.getAuthority() != null ? fresh.getAuthority() : old.getAuthority(),
                newPath,
                query,
                old.getFragment()).toString();
    }

    private LinkedHashMap<String, String> parseRawQuery(String query) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (query == null || query.isBlank()) return out;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            out.put(key, value);
        }
        return out;
    }

    private String buildRawQuery(LinkedHashMap<String, String> query) {
        if (query.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (out.length() > 0) out.append('&');
            out.append(entry.getKey());
            if (entry.getValue() != null) out.append('=').append(entry.getValue());
        }
        return out.toString();
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
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
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
        writeStreamingHeaders(out, code, type, length, cache, extra);
    }

    private void writeStreamingHeaders(OutputStream out, int code, String type, long length, String cache, String extra) throws Exception {
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n");
        if (length >= 0) headers.append("Content-Length: ").append(length).append("\r\n");
        headers.append("Cache-Control: ").append(cache).append("\r\n")
                .append("Access-Control-Allow-Origin: *\r\n");
        if (extra != null) headers.append(extra);
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();
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

    private static final class Upstream implements AutoCloseable {
        final HttpURLConnection connection;
        final int code;
        final InputStream stream;
        final String contentType;
        final String finalUrl;
        final long contentLength;
        final String contentRange;
        final String acceptRanges;

        Upstream(HttpURLConnection connection, int code, InputStream stream, String contentType,
                 String finalUrl, long contentLength, String contentRange, String acceptRanges) {
            this.connection = connection;
            this.code = code;
            this.stream = stream;
            this.contentType = contentType;
            this.finalUrl = finalUrl;
            this.contentLength = contentLength;
            this.contentRange = contentRange;
            this.acceptRanges = acceptRanges;
        }

        @Override public void close() {
            try { stream.close(); } catch (Exception ignored) {}
            connection.disconnect();
        }
    }

    private static final class SignedUrlExpiredException extends Exception {
        SignedUrlExpiredException(String message) { super(message); }
    }

    private static final class TransientUpstreamException extends Exception {
        TransientUpstreamException(String message) { super(message); }
    }
}
