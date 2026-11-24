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
        // Test hook for the net watchdog's reset (adb only, like the rest of this receiver):
        //   --ei netreset 1   disconnect + reconnect      --ei netreset 2   Wi-Fi off + on
        int nr = intent == null ? 0 : intent.getIntExtra("netreset", 0);
        if (nr > 0) {
            final android.content.BroadcastReceiver.PendingResult pr = goAsync();
            new Thread(() -> { NetWatchdog.testReset(app, nr); pr.finish(); }, "keeper-netreset-test").start();
            return;
        }
        Keeper.register(app);
        Keeper.schedule(app);
        Keeper.reconcileAsync(app, Keeper.Why.START, goAsync());
    }
}
