package com.laiholdings.kioskadbkeeper;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Finds the wireless-debugging port: the one Android announces over mDNS (fromMdns, Android 14+), else by
 *  scanning localhost and identifying adbd. */
public class AdbPort {
    static final int CNXN = 0x4e584e43, STLS = 0x534c5453, AUTH = 0x48545541;

    private static boolean isAdbd(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 150);
            // Connected to itself: nothing listens on `port`, the kernel picked it as this socket's own port too,
            // and our CNXN would come straight back as if adbd answered.
            if (s.getLocalPort() == port) return false;
            s.setSoTimeout(500);
            byte[] pl = "host::\0".getBytes();
            ByteBuffer b = ByteBuffer.allocate(24 + pl.length).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(CNXN).putInt(0x01000001).putInt(256 * 1024).putInt(pl.length).putInt(0).putInt(CNXN ^ 0xffffffff);
            b.put(pl);
            OutputStream os = s.getOutputStream();
            os.write(b.array());
            os.flush();
            InputStream is = s.getInputStream();
            byte[] h = new byte[24];
            int n = 0;
            while (n < 4) {
                int r = is.read(h, n, h.length - n);
                if (r < 0) break;
                n += r;
            }
            if (n < 4) return false;
            int cmd = ByteBuffer.wrap(h, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            return cmd == STLS || cmd == CNXN || cmd == AUTH;
        } catch (Throwable t) {
            return false;
        }
    }

    static final long MDNS_BOUND_MS = 3_000;  // only a failure bound: the resolved, answering port is the signal
    // Where NsdManager delivers resolutions. Never shut down: Android may deliver a last callback after a lookup has
    // finished, and a shut-down executor rejecting it crashes the app.
    private static final ExecutorService NSD_THREAD = Executors.newSingleThreadExecutor();

    /** The port Android announces for Wireless debugging (`_adb-tls-connect._tcp`), kept only when its address is
     *  this device's Wi-Fi address and adbd answers there. -1 if none within MDNS_BOUND_MS, or before Android 14
     *  (registerServiceInfoCallback is API 34; find() then scans). Blocking. */
    static int fromMdns(Context c) {
        if (Build.VERSION.SDK_INT < 34) return -1;
        final String me = Keeper.wifiIpv4(c);
        if (me == null) return -1;
        final android.net.nsd.NsdManager nsd = c.getSystemService(android.net.nsd.NsdManager.class);
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger port = new java.util.concurrent.atomic.AtomicInteger(-1);
        final List<android.net.nsd.NsdManager.ServiceInfoCallback> watching = new ArrayList<>();
        android.net.nsd.NsdManager.DiscoveryListener dl = new android.net.nsd.NsdManager.DiscoveryListener() {
            @Override public void onServiceFound(android.net.nsd.NsdServiceInfo found) {
                android.net.nsd.NsdManager.ServiceInfoCallback cb = new android.net.nsd.NsdManager.ServiceInfoCallback() {
                    @Override public void onServiceUpdated(android.net.nsd.NsdServiceInfo info) {
                        for (java.net.InetAddress a : info.getHostAddresses()) {
                            if (me.equals(a.getHostAddress()) && isAdbd(info.getPort())) {
                                port.set(info.getPort());
                                done.countDown();
                            }
                        }
                    }
                    @Override public void onServiceInfoCallbackRegistrationFailed(int err) {}
                    @Override public void onServiceLost() {}
                    @Override public void onServiceInfoCallbackUnregistered() {}
                };
                try {
                    nsd.registerServiceInfoCallback(found, NSD_THREAD, cb);
                    synchronized (watching) { watching.add(cb); }
                } catch (Throwable t) {
                    Keeper.w("mDNS: couldn't resolve: " + t);
                }
            }
            @Override public void onStartDiscoveryFailed(String type, int err) {
                Keeper.w("mDNS: discovery failed (" + err + ")");
                done.countDown();
            }
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onStopDiscoveryFailed(String type, int err) {}
            @Override public void onServiceLost(android.net.nsd.NsdServiceInfo s) {}
        };
        try {
            nsd.discoverServices("_adb-tls-connect._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, dl);
            done.await(MDNS_BOUND_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Keeper.w("mDNS: " + t);
        } finally {
            try { nsd.stopServiceDiscovery(dl); } catch (Throwable ignored) { }
            synchronized (watching) {
                for (android.net.nsd.NsdManager.ServiceInfoCallback cb : watching) {
                    try { nsd.unregisterServiceInfoCallback(cb); } catch (Throwable ignored) { }
                }
            }
        }
        return port.get();
    }

    /** adbd publishes its TLS port as service.adb.tls.port (Android 11+). SELinux may not let an app read it (then
     *  "" and -1). */
    static int fromProp() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String v = (String) sp.getMethod("get", String.class).invoke(null, "service.adb.tls.port");
            int p = v == null || v.isEmpty() ? -1 : Integer.parseInt(v.trim());
            return p > 0 && isAdbd(p) ? p : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** fromMdns for Android 11–13: resolveService (one resolve at a time, so found services are resolved in turn).
     *  -1 if none within MDNS_BOUND_MS. Blocking. */
    @SuppressWarnings("deprecation")
    static int fromMdnsLegacy(Context c) {
        final String me = Keeper.wifiIpv4(c);
        if (me == null) return -1;
        final android.net.nsd.NsdManager nsd = c.getSystemService(android.net.nsd.NsdManager.class);
        final java.util.concurrent.LinkedBlockingQueue<android.net.nsd.NsdServiceInfo> found =
                new java.util.concurrent.LinkedBlockingQueue<>();
        android.net.nsd.NsdManager.DiscoveryListener dl = new android.net.nsd.NsdManager.DiscoveryListener() {
            @Override public void onServiceFound(android.net.nsd.NsdServiceInfo s) { found.add(s); }
            @Override public void onStartDiscoveryFailed(String type, int err) { Keeper.w("mDNS: discovery failed (" + err + ")"); }
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onStopDiscoveryFailed(String type, int err) {}
            @Override public void onServiceLost(android.net.nsd.NsdServiceInfo s) {}
        };
        long end = SystemClock.elapsedRealtime() + MDNS_BOUND_MS;
        try {
            nsd.discoverServices("_adb-tls-connect._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, dl);
            long left;
            while ((left = end - SystemClock.elapsedRealtime()) > 0) {
                android.net.nsd.NsdServiceInfo s = found.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (s == null) break;
                final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
                final java.util.concurrent.atomic.AtomicReference<android.net.nsd.NsdServiceInfo> got =
                        new java.util.concurrent.atomic.AtomicReference<>();
                nsd.resolveService(s, new android.net.nsd.NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(android.net.nsd.NsdServiceInfo i, int err) { done.countDown(); }
                    @Override public void onServiceResolved(android.net.nsd.NsdServiceInfo i) { got.set(i); done.countDown(); }
                });
                done.await(Math.max(left, 1), java.util.concurrent.TimeUnit.MILLISECONDS);
                android.net.nsd.NsdServiceInfo r = got.get();
                if (r != null && r.getHost() != null && me.equals(r.getHost().getHostAddress()) && isAdbd(r.getPort())) {
                    return r.getPort();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Keeper.w("mDNS (legacy): " + t);
        } finally {
            try { nsd.stopServiceDiscovery(dl); } catch (Throwable ignored) { }
        }
        return -1;
    }

    /** Blocking; call off the main thread. Returns the port or -1. Cached per boot while it still answers. */
    static synchronized int find(Context c) {
        SharedPreferences sp = Keeper.prefs(c);
        int boot = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        int cp = sp.getInt("adb_port", -1);
        if (cp > 0 && sp.getInt("adb_port_boot", -2) == boot && isAdbd(cp)) return cp;  // a toggle moves it
        if (Keeper.wifiIpv4(c) == null) return -1;  // no Wi-Fi: Wireless debugging isn't listening
        long t0 = SystemClock.elapsedRealtime();
        int prop = fromProp();
        if (prop > 0) {
            Keeper.i("adb port from service.adb.tls.port: " + prop + " in " + (SystemClock.elapsedRealtime() - t0) + " ms");
            sp.edit().putInt("adb_port", prop).putInt("adb_port_boot", boot).apply();
            return prop;
        }
        int announced = Build.VERSION.SDK_INT >= 34 ? fromMdns(c) : fromMdnsLegacy(c);
        if (announced > 0) {
            Keeper.i("adb port from mDNS: " + announced + " in " + (SystemClock.elapsedRealtime() - t0) + " ms");
            sp.edit().putInt("adb_port", announced).putInt("adb_port_boot", boot).apply();
            return announced;
        }
        Keeper.i("adb port: no property, nothing from mDNS in " + (SystemClock.elapsedRealtime() - t0) + " ms; scanning");
        t0 = SystemClock.elapsedRealtime();
        java.util.concurrent.ConcurrentSkipListSet<Integer> hits = new java.util.concurrent.ConcurrentSkipListSet<>();
        ExecutorService ex = Executors.newFixedThreadPool(64);
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (int p = 30000; p <= 65535; p++) {
                final int port = p;
                fs.add(ex.submit(() -> { if (isAdbd(port)) hits.add(port); }));
            }
            for (Future<?> f : fs) {
                try { f.get(); } catch (Throwable t) { /* ignore */ }
            }
        } finally {
            ex.shutdownNow();
        }
        int found = hits.isEmpty() ? -1 : hits.first();
        Keeper.i("adb port scan: " + hits + " in " + (SystemClock.elapsedRealtime() - t0) + " ms");
        if (found > 0) sp.edit().putInt("adb_port", found).putInt("adb_port_boot", boot).apply();
        return found;
    }
}
