package com.laiholdings.kioskadbkeeper;

import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.net.Inet4Address;

/** Keeps Wireless debugging on so the kiosk stays reachable over adb. Cut down from ticket-agent's
 *  adb-wifi-keeper (~/Projects/ticket-agent/android/adb-wifi-keeper): only the keeping, no check-ins, doorbell,
 *  messages or diary. */
public class Keeper {
    static final String TAG = "KioskAdbKeeper";

    static void i(String m) { Log2.i(m); }
    static void w(String m) { Log2.w(m); }

    static SharedPreferences prefs(Context c) {
        return c.createDeviceProtectedStorageContext().getSharedPreferences("keeper", Context.MODE_PRIVATE);
    }

    static boolean wifiConnected(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Throwable t) {
            w("wifiConnected failed: " + t);
            return false;
        }
    }

    /** This device's IPv4 address on Wi-Fi, or null. */
    static String wifiIpv4(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (LinkAddress a : lp.getLinkAddresses()) {
                    if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
                }
            }
        } catch (Throwable t) {
            w("wifiIpv4 failed: " + t);
        }
        return null;
    }

    static long up() { return SystemClock.elapsedRealtime() / 1000; }

    /** What made reconcile run. Wireless debugging's own switches change what it does. */
    enum Why { BOOT, START, NETWORK, HEARTBEAT, WIFI_DEBUG_OFF, WIFI_DEBUG_ON }

    // A switch-on we made that adbd hasn't answered yet. If the switch goes off again before it answers, Android
    // refused it (e.g. it shows "Allow wireless debugging on this network?", which PromptService accepts on a listed
    // network and which switches it on itself). Then we wait before switching on again, longer each time (1 min,
    // doubling to 15), so we never fight Android in a loop but never give up either.
    private static final Object SWITCH = new Object();  // guards the switch-on state; never held while blocking
    private static boolean reenabling = false;
    private static long retryAfterMs = 0, backoffMs = 0;
    static final long FIRST_BACKOFF_MS = 60_000, MAX_BACKOFF_MS = 15 * 60_000;
    static final long ANSWER_BOUND_MS = 20_000;  // a switch-on: adbd answering is the signal; this only bounds it
    // The slow part (finding adbd, waiting for it, restarting it) runs one at a time; a trigger arriving meanwhile
    // skips it (the one running sees the same state) instead of queueing behind it.
    private static final java.util.concurrent.atomic.AtomicBoolean checking =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** The one place this app puts things right; every trigger calls it and it is idempotent:
     *  - Wireless debugging off (with Wi-Fi): switch it on at once, unless Android refused our last switch-on within
     *    the backoff. This never waits for the slow part.
     *  - On but no adbd answering: restart it, once per boot (Android sometimes ignores it at boot). A working
     *    Wireless debugging is never toggled, since every toggle moves the port.
     *  - Keep this app's accessibility service on.
     *  Blocking (port lookup, restart, waiting for adbd): call off the main thread. */
    static void reconcile(Context c, Why why) {
        i("reconcile: " + why + " (up " + up() + "s)");
        ContentResolver cr = c.getContentResolver();
        try {
            if (!wifiConnected(c)) i("no Wi-Fi: Wireless debugging left as it is");
            else if (!switchOnIfOff(c, cr, why)) check(c, cr, why);
        } catch (SecurityException e) {
            w("WRITE_SECURE_SETTINGS not granted: " + e.getMessage());
        }
        ensureA11y(c);
    }

    /** True when Wireless debugging was off (and is now switched on, or left off for the backoff). */
    private static boolean switchOnIfOff(Context c, ContentResolver cr, Why why) {
        synchronized (SWITCH) {
            if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) != 0) return false;
            long now = SystemClock.elapsedRealtime();
            if (why == Why.WIFI_DEBUG_OFF && reenabling) {  // off again before adbd answered: Android refused it
                backoffMs = backoffMs == 0 ? FIRST_BACKOFF_MS : Math.min(backoffMs * 2, MAX_BACKOFF_MS);
                retryAfterMs = now + backoffMs;
                i("Android switched Wireless debugging straight off again; next switch-on in " + backoffMs / 1000
                        + " s (or when its prompt is accepted)");
                final Context app = c.getApplicationContext();
                final long wait = backoffMs + 500;
                new Thread(() -> {
                    try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
                    reconcile(app, Why.HEARTBEAT);
                }, "keeper-retry").start();
            }
            reenabling = false;
            if (now < retryAfterMs) {
                i("Wireless debugging off; in backoff for " + (retryAfterMs - now) / 1000 + " s more");
                return true;
            }
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1);
            reenabling = true;
            i("Wireless debugging was off; switched it on");
            return true;  // the switch fires WIFI_DEBUG_ON (PromptService), which waits for adbd
        }
    }

    private static void answered() {
        synchronized (SWITCH) { reenabling = false; retryAfterMs = 0; backoffMs = 0; }
    }

    private static void check(Context c, ContentResolver cr, Why why) {
        if (!checking.compareAndSet(false, true)) {
            i("check already running; skipped for " + why);
            return;
        }
        try {
            if (why == Why.WIFI_DEBUG_ON) {
                long start = SystemClock.elapsedRealtime();
                boolean ok;
                while (!(ok = AdbPort.find(c) > 0) && SystemClock.elapsedRealtime() < start + ANSWER_BOUND_MS) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
                if (ok) { answered(); i("adbd answering on " + AdbPort.find(c) + " after the switch-on"); }
                else w("Wireless debugging on, but adbd didn't answer within " + ANSWER_BOUND_MS / 1000 + " s");
                return;
            }
            int port = AdbPort.find(c);
            if (port > 0) {
                answered();
                i("ok: adbd answering on " + port);
                return;
            }
            restartOncePerBoot(c, cr);
        } finally {
            checking.set(false);
        }
    }

    private static void restartOncePerBoot(Context c, ContentResolver cr) {
        SharedPreferences sp = prefs(c);
        int boot = Settings.Global.getInt(cr, Settings.Global.BOOT_COUNT, -1);
        if (sp.getInt("kick_boot", -2) == boot) {
            i("Wireless debugging on but adbd not answering; restarted it already this boot, so not again");
            return;
        }
        sp.edit().putInt("kick_boot", boot).apply();
        i("Wireless debugging on but adbd not answering; restarting it");
        restart(cr);
    }

    /** Wireless debugging off, then on: Android re-evaluates it and adbd listens on a new port. */
    private static void restart(ContentResolver cr) {
        try {
            Settings.Global.putInt(cr, "adb_wifi_enabled", 0);
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1);
        }
    }

    /** Idempotent: same PendingIntent replaces the old registration. */
    static void register(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, NetReceiver.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | 0x02000000 /* FLAG_MUTABLE */);
            cm.registerNetworkCallback(req, pi);
            i("network callback registered (up " + up() + "s)");
        } catch (Throwable t) {
            w("register failed: " + t);
        }
    }

    /** reconcile on a background thread, finishing the receiver's pending result if given. */
    static void reconcileAsync(final Context c, final Why why, final android.content.BroadcastReceiver.PendingResult pr) {
        new Thread(() -> {
            try { reconcile(c, why); } finally { if (pr != null) pr.finish(); }
        }, "keeper-reconcile").start();
    }

    static void schedule(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        try {
            js.schedule(new JobInfo.Builder(1, new ComponentName(c, KeepJob.class))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build());
            i("periodic job scheduled (up " + up() + "s)");
        } catch (Exception e) {
            w("schedule failed: " + e);
        }
    }

    static final String A11Y = "com.laiholdings.kioskadbkeeper/com.laiholdings.kioskadbkeeper.PromptService";

    /** This app's accessibility service on, keeping any others (FreeKiosk's) as they are. */
    static void ensureA11y(Context c) {
        try {
            ContentResolver cr = c.getContentResolver();
            String cur = Settings.Secure.getString(cr, "enabled_accessibility_services");
            java.util.LinkedHashSet<String> on = new java.util.LinkedHashSet<>();
            if (cur != null) for (String p : cur.split(":")) if (!p.isEmpty()) on.add(p);
            if (on.add(A11Y)) {
                Settings.Secure.putString(cr, "enabled_accessibility_services", String.join(":", on));
                i("a11y: switched on PromptService (" + on.size() + " service(s) now)");
            }
            if (Settings.Secure.getInt(cr, "accessibility_enabled", 0) != 1) {
                Settings.Secure.putInt(cr, "accessibility_enabled", 1);
                i("a11y: accessibility_enabled=1");
            }
        } catch (Throwable t) {
            w("ensureA11y failed: " + t);
        }
    }
}
