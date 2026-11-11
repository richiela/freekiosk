package com.laiholdings.kioskadbkeeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** One-time setup for a screen-pinned kiosk, where Android refuses to start StartActivity (a lock task violation):
 *  am broadcast -n com.laiholdings.kioskadbkeeper/.SetupReceiver -f 0x20 --es ssids "Prospect"
 *  (0x20 = FLAG_INCLUDE_STOPPED_PACKAGES; delivering it also takes the app out of the "stopped" state). The manifest
 *  guards it with DUMP, which adb's shell holds and apps can't get, so no app can add an SSID to the allowed list. */
public class SetupReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        String ss = intent == null ? null : intent.getStringExtra("ssids");
        if (ss != null) {
            Keeper.prefs(app).edit().putString("ssids", ss).apply();
            int n = 0;
            for (String x : ss.split(",")) if (!x.trim().isEmpty()) n++;
            Keeper.i("saved " + n + " allowed SSID(s)");
        }
        Keeper.register(app);
        Keeper.schedule(app);
        Keeper.reconcileAsync(app, Keeper.Why.START, goAsync());
    }
}
