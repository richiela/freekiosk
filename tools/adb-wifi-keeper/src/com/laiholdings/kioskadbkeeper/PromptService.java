package com.laiholdings.kioskadbkeeper;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/** Stays bound, so it (1) hears every switch of Wireless debugging and lets Keeper.reconcile switch it back on
 *  (Android switches it off when the device moves to another access point, which the Wi-Fi callback doesn't see),
 *  and (2) accepts the "Allow wireless debugging on this network?" prompt, only on a listed SSID. */
public class PromptService extends AccessibilityService {
    private long ignoreUntil = 0;
    private boolean loggedForeign = false;
    private android.database.ContentObserver adbWifiObserver;

    @Override public void onServiceConnected() {
        Keeper.i("PromptService connected (up " + Keeper.up() + "s)");
        adbWifiObserver = new android.database.ContentObserver(new android.os.Handler(getMainLooper())) {
            @Override public void onChange(boolean self) { onAdbWifiChanged(); }
        };
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor("adb_wifi_enabled"), false, adbWifiObserver);
    }

    @Override public void onDestroy() {
        if (adbWifiObserver != null) getContentResolver().unregisterContentObserver(adbWifiObserver);
        super.onDestroy();
    }

    private void onAdbWifiChanged() {
        final Context c = getApplicationContext();
        new Thread(() -> {
            boolean on = Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) == 1;
            Keeper.i("wireless debugging switched " + (on ? "on" : "off") + " (up " + Keeper.up() + "s)");
            Keeper.reconcile(c, on ? Keeper.Why.WIFI_DEBUG_ON : Keeper.Why.WIFI_DEBUG_OFF);
        }, "keeper-adbwifi-changed").start();
    }

    @Override public void onInterrupt() {}

    @Override public void onAccessibilityEvent(AccessibilityEvent ev) {
        if (SystemClock.elapsedRealtime() < ignoreUntil) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        // Only the system prompt itself, never Settings' Wireless debugging page.
        if (root == null || root.getPackageName() == null
                || !"com.android.systemui".contentEquals(root.getPackageName())) return;
        try {
            List<AccessibilityNodeInfo> nodes = new ArrayList<>();
            collect(root, nodes);
            boolean title = false;
            List<String> texts = new ArrayList<>();
            for (AccessibilityNodeInfo n : nodes) {
                CharSequence t = n.getText();
                if (t == null) continue;
                String s = t.toString();
                texts.add(s);
                if (s.toLowerCase().contains("allow wireless debugging")) title = true;
            }
            // The prompt is the dialog with that title and an Allow button; the "Wireless debugging connected"
            // notification has neither.
            AccessibilityNodeInfo btn = allowButton(nodes);
            if (!title) { loggedForeign = false; return; }
            if (btn == null) {
                if (!loggedForeign) Keeper.w("prompt title seen but no Allow button: " + String.join(" | ", texts));
                loggedForeign = true;
                return;
            }

            List<String> allowed = allowedSsids();
            String matched = null;
            // The SSID may be on its own line or inside a longer text, so compare every line of every text.
            for (String t : texts) {
                for (String line : t.split("\\R")) {
                    String u = strip(line);
                    for (String a : allowed) if (a.equals(u)) matched = a;
                }
            }
            if (matched == null) {
                if (!loggedForeign) {
                    Keeper.i("prompt on a network not in the list; left alone; dialog text: "
                            + String.join(" | ", texts).replace('\n', '/'));
                    loggedForeign = true;
                }
                return;
            }
            Keeper.i("prompt on allowed network " + matched + "; accepting (up " + Keeper.up() + "s)");
            for (AccessibilityNodeInfo n : nodes) {
                if (n.isCheckable() && !n.isChecked()) {
                    CharSequence t = n.getText();
                    CharSequence d = n.getContentDescription();
                    String label = ((t == null ? "" : t) + " " + (d == null ? "" : d)).toLowerCase();
                    if (label.contains("always allow") || n.getClassName().toString().contains("CheckBox")) {
                        Keeper.i("ticking Always allow");
                        click(n);
                        break;
                    }
                }
            }
            Keeper.i("clicking Allow");
            click(btn);
            ignoreUntil = SystemClock.elapsedRealtime() + 2000;
            loggedForeign = false;
        } catch (Throwable t) {
            Keeper.w("PromptService error: " + t);
        }
    }

    /** The dialog's positive button (android:id/button1), else a clickable "Allow". */
    private static AccessibilityNodeInfo allowButton(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo n : nodes) {
            if ("android:id/button1".equals(n.getViewIdResourceName())) return n;
        }
        for (AccessibilityNodeInfo n : nodes) {
            CharSequence t = n.getText();
            if (t != null && n.isClickable() && "allow".equalsIgnoreCase(t.toString().trim())) return n;
        }
        return null;
    }

    private static String strip(String s) {
        s = s.trim();
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '“')) s = s.substring(1, s.length() - 1);
        return s.trim();
    }

    private List<String> allowedSsids() {
        List<String> out = new ArrayList<>();
        try {
            for (String s : Keeper.prefs(this).getString("ssids", "").split(",")) {
                s = s.trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Throwable t) { Keeper.w("ssids read failed: " + t); }
        return out;
    }

    private static void collect(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n == null || out.size() >= 300) return;
        out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out);
    }

    private static void click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        while (c != null && !c.isClickable()) c = c.getParent();
        if (c != null) c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
}
