package com.mrdalse2.sbsplusproxy;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

public final class NetworkUtils {
    private NetworkUtils() {}
    private record Candidate(String ip, int score) {}

    public static List<String> localPlaylistUrls() { return urlsFor("/playlist.m3u"); }
    public static List<String> localProxyUrls() { return urlsFor("/sbsplus.m3u8"); }
    public static String bestLocalPlaylistUrl() {
        List<String> urls = localPlaylistUrls();
        return urls.isEmpty() ? null : urls.get(0);
    }
    public static String bestLocalProxyUrl() {
        List<String> urls = localProxyUrls();
        return urls.isEmpty() ? null : urls.get(0);
    }

    private static List<String> urlsFor(String path) {
        List<Candidate> candidates = candidates();
        candidates.sort(Comparator.comparingInt(Candidate::score).reversed());
        List<String> urls = new ArrayList<>();
        for (Candidate c : candidates) urls.add("http://" + c.ip() + ":8787" + path);
        return urls;
    }

    private static List<Candidate> candidates() {
        List<Candidate> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return out;
            for (NetworkInterface ni : Collections.list(interfaces)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.startsWith("169.254.")) continue;
                    int score = score(name, ip);
                    if (score < 0) continue;
                    out.add(new Candidate(ip, score));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static int score(String name, String ip) {
        int score = 0;
        if (name.contains("swlan") || name.contains("ap") || name.contains("wlan") || name.contains("wifi")) score += 100;
        if (name.contains("rndis") || name.contains("eth")) score += 80;
        if (name.contains("rmnet") || name.contains("ccmni") || name.contains("pdp") || name.contains("tun") || name.contains("vpn")) score -= 150;
        if (isPrivate(ip)) score += 30; else score -= 20;
        return score;
    }

    private static boolean isPrivate(String ip) {
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
        if (ip.startsWith("172.")) {
            String[] parts = ip.split("\\.");
            if (parts.length > 1) {
                try { int n = Integer.parseInt(parts[1]); return n >= 16 && n <= 31; }
                catch (NumberFormatException ignored) {}
            }
        }
        return false;
    }
}
