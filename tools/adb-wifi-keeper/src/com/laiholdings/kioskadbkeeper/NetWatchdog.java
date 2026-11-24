package com.laiholdings.kioskadbkeeper;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Catches a Wi-Fi link that is associated but passes no data, and kicks it.
 *
 * Seen on the Echo Show 8 (MTK driver): after a roam to another access point it stays associated with no
 * traffic for an hour, the AP's own ping gets nothing back, and Android never notices, so it never
 * reconnects by itself. Every 60 s this probes the Wi-Fi network (the default gateway, then a TCP connect to
 * Home Assistant). After 3 failures in a row (~3 min) it disconnects and reconnects, then waits 10 min; if the next 3
 * probes still fail it switches Wi-Fi off and on. At most 3 resets an hour; beyond that it only logs. Both calls only work for apps targeting API 28 or lower, hence the keeper's
 * targetSdkVersion.
 */
final class NetWatchdog {
    static final long PERIOD_MS = 60_000;
    static final int FAILS_BEFORE_RESET = 3;
    static final long SETTLE_MS = 10 * 60_000;  // after a reset, wait this long before probing again
    static final int MAX_RESETS_PER_HOUR = 3;  // beyond this, only log
    static final int TIMEOUT_MS = 3_000;
    static final String HA_HOST = "10.0.0.190";
    static final int HA_PORT = 80;

    private final Context c;
    private HandlerThread thread;
    private Handler h;
    private int fails = 0;
    private int resets = 0;  // resets since the link last answered
    private long settleUntil = 0;
    private final java.util.ArrayDeque<Long> resetTimes = new java.util.ArrayDeque<>();

    NetWatchdog(Context c) { this.c = c.getApplicationContext(); }

    void start() {
        if (thread != null) return;
        thread = new HandlerThread("keeper-netwatch");
        thread.start();
        h = new Handler(thread.getLooper());
        h.postDelayed(this::tick, PERIOD_MS);
        Keeper.i("net watchdog started (every " + PERIOD_MS / 1000 + "s, reset after " + FAILS_BEFORE_RESET + " fails)");
    }

    void stop() {
        if (thread == null) return;
        thread.quitSafely();
        thread = null;
        h = null;
    }

    private void tick() {
        try {
            check();
        } catch (Throwable t) {
            Keeper.w("net watchdog error: " + t);
        }
        Handler hh = h;
        if (hh != null) hh.postDelayed(this::tick, PERIOD_MS);
    }

    private void check() {
        if (SystemClock.elapsedRealtime() < settleUntil) return;
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network wifi = wifiNetwork(cm);
        if (wifi == null) {  // not on Wi-Fi at all: Android is already handling that, nothing to kick
            fails = 0;
            return;
        }
        String gw = gateway(cm.getLinkProperties(wifi));
        boolean ok = (gw != null && gatewayAnswers(wifi, gw)) || tcpAnswers(wifi, HA_HOST, HA_PORT);
        if (ok) {
            if (fails > 0 || resets > 0) Keeper.i("net watchdog: link answering again (after " + fails + " fails, " + resets + " resets)");
            fails = 0;
            resets = 0;
            // Backstop: after a Wi-Fi reset the network callback (NetReceiver) did not always fire, which left
            // Wireless debugging off. If the link works and it is off, switch it back on here.
            if (android.provider.Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) == 0) {
                Keeper.i("net watchdog: link up but Wireless debugging off; reconciling");
                Keeper.reconcile(c, Keeper.Why.NETWORK);
            }
            return;
        }
        fails++;
        Keeper.w("net watchdog: no answer from gateway " + gw + " or " + HA_HOST + ":" + HA_PORT + " (" + fails + "/" + FAILS_BEFORE_RESET + ")");
        if (fails < FAILS_BEFORE_RESET) return;
        fails = 0;
        long now = SystemClock.elapsedRealtime();
        while (!resetTimes.isEmpty() && now - resetTimes.peekFirst() > 3_600_000L) resetTimes.pollFirst();
        if (resetTimes.size() >= MAX_RESETS_PER_HOUR) {
            Keeper.w("net watchdog: reset skipped, already " + resetTimes.size() + " in the last hour");
            return;
        }
        resetTimes.addLast(now);
        reset();
    }

    /** Runs one reset of the given kind (1 = disconnect/reconnect, 2 = off/on); for testing over adb. */
    static void testReset(Context c, int kind) {
        NetWatchdog w = new NetWatchdog(c);
        w.resets = kind - 1;
        Keeper.i("net watchdog: test reset requested (kind " + kind + ")");
        w.reset();
    }

    @SuppressWarnings("deprecation")
    private void reset() {
        WifiManager wm = (WifiManager) c.getSystemService(Context.WIFI_SERVICE);
        resets++;
        if (resets == 1) {
            boolean d = wm.disconnect();
            SystemClock.sleep(2_000);
            boolean r = wm.reconnect();
            Keeper.w("net watchdog RESET: Wi-Fi disconnect=" + d + " reconnect=" + r);
        } else {
            boolean off = wm.setWifiEnabled(false);
            SystemClock.sleep(3_000);
            boolean on = wm.setWifiEnabled(true);
            Keeper.w("net watchdog RESET #" + resets + ": Wi-Fi off=" + off + " on=" + on);
        }
        settleUntil = SystemClock.elapsedRealtime() + SETTLE_MS;
    }

    private static Network wifiNetwork(ConnectivityManager cm) {
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    private static String gateway(LinkProperties lp) {
        if (lp == null) return null;
        for (RouteInfo r : lp.getRoutes()) {
            if (r.isDefaultRoute() && r.getGateway() instanceof java.net.Inet4Address) {
                return r.getGateway().getHostAddress();
            }
        }
        return null;
    }

    /** ICMP if the app may send it, else a TCP connect to port 7; a refused connection counts as an answer. */
    private static boolean gatewayAnswers(Network n, String host) {
        try {
            InetAddress a = n.getByName(host);
            return a.isReachable(TIMEOUT_MS);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean tcpAnswers(Network n, String host, int port) {
        try (Socket s = n.getSocketFactory().createSocket()) {
            s.connect(new InetSocketAddress(n.getByName(host), port), TIMEOUT_MS);
            return true;
        } catch (java.net.ConnectException e) {
            // A refusal (RST) means the link carries data; "unreachable" and the like do not.
            String m = String.valueOf(e.getMessage());
            return m.contains("ECONNREFUSED") || m.toLowerCase().contains("refused");
        } catch (Throwable t) {
            return false;
        }
    }
}
