package com.mrdalse2.sbsplusproxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class SbsResolver {
    private static final String API = "https://apis.sbs.co.kr/play-api/1.0/onair/channel/S03";
    private static final String REFERER = "https://www.sbs.co.kr/live/S03";

    private SbsResolver() {}

    public static String resolve() throws Exception {
        String query = "v_type=2&platform=pcweb&protocol=hls&ssl=N&rscuse=&jwt-token=&sbsmain=";
        URL url = new URL(API + "?" + query);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "application/json,text/plain,*/*");
        c.setRequestProperty("Referer", REFERER);
        c.setRequestProperty("Origin", "https://www.sbs.co.kr");
        c.connect();
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("SBS API HTTP " + code);
        String body;
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            body = out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
        String media = findMediaUrl(new JSONObject(body));
        if (media == null) throw new IllegalStateException("S03 API returned no mediaurl");
        return media;
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
}
