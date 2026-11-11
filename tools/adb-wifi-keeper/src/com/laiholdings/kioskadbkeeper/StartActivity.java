package com.laiholdings.kioskadbkeeper;

import android.app.Activity;
import android.os.Bundle;

/** One-time setup (also takes the app out of the "stopped" state so it receives BOOT_COMPLETED):
 *  am start -n com.laiholdings.kioskadbkeeper/.StartActivity --es ssids "Prospect" */
public class StartActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        String ss = getIntent().getStringExtra("ssids");
        if (ss != null) {
            Keeper.prefs(this).edit().putString("ssids", ss).apply();
            int n = 0;
            for (String x : ss.split(",")) if (!x.trim().isEmpty()) n++;
            Keeper.i("saved " + n + " allowed SSID(s)");
        }
        Keeper.register(this);
        Keeper.schedule(this);
        Keeper.reconcileAsync(getApplicationContext(), Keeper.Why.START, null);
        finish();
    }
}
