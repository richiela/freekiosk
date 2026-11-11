package com.laiholdings.kioskadbkeeper;

import android.app.Application;

/** Runs before any of this app's components: opens the log file first, so every line is kept. */
public class KeeperApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Log2.init(this);
        Log2.i("app started (up " + Keeper.up() + "s)");
    }
}
