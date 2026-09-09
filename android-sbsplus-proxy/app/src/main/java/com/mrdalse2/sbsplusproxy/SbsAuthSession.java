package com.mrdalse2.sbsplusproxy;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public final class SbsAuthSession {
    private static final String PREFS = "sbs_auth";
    private static final String KEY_JWT = "login_jwt";
    private static volatile Context appContext;

    private SbsAuthSession() {}

    public static void init(Context context) {
        if (context != null) appContext = context.getApplicationContext();
    }

    public static String getToken() {
        Context c = appContext;
        if (c == null) return null;
        String token = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_JWT, null);
        return token == null || token.isBlank() ? null : token;
    }

    public static boolean hasToken() {
        return getToken() != null;
    }

    public static boolean captureCookieHeader(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank() || appContext == null) return false;
        String token = null;
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq <= 0) continue;
            String name = trimmed.substring(0, eq).trim();
            if (!"LOGIN_JWT".equals(name)) continue;
            String value = trimmed.substring(eq + 1).trim();
            try { value = URLDecoder.decode(value, StandardCharsets.UTF_8); }
            catch (Exception ignored) {}
            if (!value.isBlank()) token = value;
        }
        if (token == null) return false;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_JWT, token).apply();
        return true;
    }

    public static void clear() {
        Context c = appContext;
        if (c != null) c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_JWT).apply();
    }
}
