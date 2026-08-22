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
    private static volatile String lastDebug = "not probed yet";

    private SbsResolver() {}

    public static synchronized String resolve() throws Exception {
        Probe p = request();
        if (p.mediaUrl == null) throw new IllegalStateException("S03 API returned no mediaurl; " + p.summary);
        return p.mediaUrl;
    }

    public static synchronized String debugSnapshot() {
        try {
            return request().summary;
        } catch (Exception e) {
            return "probe error=" + safe(e.getMessage()) + "; previous=" + lastDebug;
        }
    }

    private static Probe request() throws Exception {
        String query = "v_type=2&platform=pcweb&protocol=hls&ssl=N&rscuse=&jwt-token=&sbsmain=";
        HttpURLConnection c = (HttpURLConnection) new URL(API + "?" + query).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        c.setRequestProperty("Referer", REFERER);
        c.setRequestProperty("Origin", "https://www.sbs.co.kr");

        int code = c.getResponseCode();
        String body;
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (stream == null) {
            c.disconnect();
            throw new IllegalStateException("SBS API HTTP " + code + " with empty body");
        }
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            body = out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }

        if (code < 200 || code >= 300) {
            lastDebug = "apiHttp=" + code;
            throw new IllegalStateException("SBS API HTTP " + code);
        }

        JSONObject json = new JSONObject(body);
        String media = findMediaUrl(json);
        String summary = summarize(json, media, code);
        lastDebug = summary;
        return new Probe(media, summary);
    }

    private static String findMediaUrl(Object value) {
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            String direct = obj.optString("mediaurl", "");
            if (direct.startsWith("http://") || direct.startsWith("https://")) return direct;
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
        List<String> top = new ArrayList<>();
        JSONArray names = json.names();
        if (names != null) for (int i = 0; i < names.length() && i < 12; i++) top.add(names.optString(i));
        String mediaSummary = "none";
        if (media != null) {
            try {
                URI u = URI.create(media);
                mediaSummary = "host=" + u.getHost() + ", path=" + u.getPath();
            } catch (Exception ignored) {
                mediaSummary = "present but unparsable";
            }
        }
        JSONObject onair = json.optJSONObject("onair");
        JSONObject source = onair == null ? null : onair.optJSONObject("source");
        JSONObject ms = source == null ? null : source.optJSONObject("mediasource");
        JSONArray msl = source == null ? null : source.optJSONArray("mediasourcelist");
        return "apiHttp=" + code + ", topKeys=" + top + ", onair=" + (onair != null)
                + ", source=" + (source != null) + ", mediasource=" + (ms != null)
                + ", mediasourcelist=" + (msl == null ? 0 : msl.length()) + ", media=" + mediaSummary;
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s.replace('\n', ' ');
    }

    private record Probe(String mediaUrl, String summary) {}
}
