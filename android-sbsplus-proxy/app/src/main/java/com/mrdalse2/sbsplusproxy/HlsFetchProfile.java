package com.mrdalse2.sbsplusproxy;

final class HlsFetchProfile {
    final String name;
    final String userAgent;
    final boolean referer;
    final boolean origin;

    HlsFetchProfile(String name, String userAgent, boolean referer, boolean origin) {
        this.name = name;
        this.userAgent = userAgent;
        this.referer = referer;
        this.origin = origin;
    }
}
