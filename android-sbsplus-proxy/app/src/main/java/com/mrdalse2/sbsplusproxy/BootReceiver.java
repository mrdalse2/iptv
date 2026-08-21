package com.mrdalse2.sbsplusproxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        boolean enabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("auto_start", false);
        if (!enabled) return;
        Intent service = new Intent(context, ProxyService.class).setAction(ProxyService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
        } catch (Exception ignored) {
            // Some OEMs restrict boot-time foreground starts; opening the app starts it normally.
        }
    }
}
