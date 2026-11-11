package com.laiholdings.kioskadbkeeper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Wi-Fi came up (the network callback Keeper.register asks for). Android refuses to enable Wireless debugging
 *  until Wi-Fi is connected, so this is what makes the boot switch-on stick. */
public class NetReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        Keeper.i("NetReceiver fired (up " + Keeper.up() + "s)");
        if (!Keeper.wifiConnected(context)) return;
        Keeper.reconcileAsync(context.getApplicationContext(), Keeper.Why.NETWORK, goAsync());
    }
}
