package com.mrdalse2.sbsplusproxy;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.net.InetAddress;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

public final class MdnsAdvertiser {
    private final Context context;
    private JmDNS jmDNS;
    private ServiceInfo serviceInfo;
    private WifiManager.MulticastLock multicastLock;
    private String boundIp;

    public MdnsAdvertiser(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void refresh() {
        String ip = NetworkUtils.bestLocalIp();
        if (ip == null || ip.equals(boundIp) && jmDNS != null) return;
        stop();
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock("LocalIPTVProxy-mDNS");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }

            InetAddress address = InetAddress.getByName(ip);
            // Explicit host name makes JmDNS publish an A record for iptvproxy.local.
            jmDNS = JmDNS.create(address, "iptvproxy");
            serviceInfo = ServiceInfo.create(
                    "_http._tcp.local.",
                    "Local IPTV Proxy",
                    8787,
                    0,
                    0,
                    "path=/playlist.m3u"
            );
            jmDNS.registerService(serviceInfo);
            boundIp = ip;
        } catch (Exception e) {
            stop();
        }
    }

    public synchronized String getBoundIp() {
        return boundIp;
    }

    public synchronized void stop() {
        boundIp = null;
        if (jmDNS != null) {
            try { if (serviceInfo != null) jmDNS.unregisterService(serviceInfo); } catch (Exception ignored) {}
            try { jmDNS.close(); } catch (Exception ignored) {}
        }
        jmDNS = null;
        serviceInfo = null;
        if (multicastLock != null && multicastLock.isHeld()) {
            try { multicastLock.release(); } catch (Exception ignored) {}
        }
        multicastLock = null;
    }
}
