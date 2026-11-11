package com.laiholdings.kioskadbkeeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            Keeper.i("BootReceiver " + a + " (up " + Keeper.up() + "s)");
            Context app = context.getApplicationContext();
            Keeper.register(app);
            Keeper.schedule(app);
            Keeper.reconcileAsync(app, Keeper.Why.BOOT, goAsync());
        }
    }
}
