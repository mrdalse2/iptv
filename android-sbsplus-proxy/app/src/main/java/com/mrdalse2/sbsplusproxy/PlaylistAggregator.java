package com.mrdalse2.sbsplusproxy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class PlaylistAggregator {
    private static final String REMOTE_M3U = "https://raw.githubusercontent.com/mrdalse2/iptv/main/kr-tivimate.m3u";
    private PlaylistAggregator() {}

    public static byte[] build(String localSbsUrl) throws Exception {
        return replaceSbsPlus(fetchText(REMOTE_M3U), localSbsUrl).getBytes(StandardCharsets.UTF_8);
    }

    private static String fetchText(String target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "SBSPlusProxy/2.1 Android");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("playlist upstream HTTP " + code);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[32768]; int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally { c.disconnect(); }
    }

    static String replaceSbsPlus(String m3u, String localSbsUrl) {
        String[] lines = m3u.replace("\r\n", "\n").replace('\r','\n').split("\n", -1);
        List<String> out = new ArrayList<>(lines.length + 3);
        boolean pending = false, found = false;
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("#EXTINF:")) {
                pending = isSbsPlus(t); found |= pending; out.add(line); continue;
            }
            if (pending && !t.isEmpty() && !t.startsWith("#")) {
                out.add(localSbsUrl); pending = false; continue;
            }
            out.add(line);
        }
        if (!found) {
            out.add("#EXTINF:-1 tvg-id=\"SBSPlus.kr@SD\" group-title=\"Entertainment;Official;LocalProxy\",SBS Plus");
            out.add(localSbsUrl);
        }
        return String.join("\n", out);
    }

    private static boolean isSbsPlus(String line) {
        String l = line.toLowerCase();
        if (l.contains("tvg-id=\"sbsplus.kr@sd\"") || l.contains("tvg-id=\"sbsplus.kr\"")) return true;
        int comma = line.lastIndexOf(',');
        if (comma < 0) return false;
        String name = line.substring(comma + 1).trim().toLowerCase();
        return name.equals("sbs plus") || name.equals("sbsplus") || name.equals("sbs 플러스");
    }
}
