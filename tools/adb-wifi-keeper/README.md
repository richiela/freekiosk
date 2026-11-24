# Kiosk ADB Keeper

Keeps Android's Wireless debugging on for the dart kiosks (the 16" Meswao tablet and the Echo Show 8), so adb
keeps working after a reboot, a Wi-Fi drop or a move between access points. Android turns it off on each of those.

A cut-down copy of ticket-agent's `~/Projects/ticket-agent/android/adb-wifi-keeper` (only the keeping: no
check-ins, doorbell, messages or diary), under its own package `com.laiholdings.kioskadbkeeper` so both can exist.

Triggers, all calling `Keeper.reconcile` (idempotent):
- **Boot / app update** (`BootReceiver`), **Wi-Fi up** (`NetReceiver`, a network callback), **every 15 min** (`KeepJob`).
- **Every switch of Wireless debugging** (`PromptService`, an accessibility service that stays bound): Android
  switching it off on an access-point move is not a new network to the Wi-Fi callback, so this catches it.
- `PromptService` also accepts "Allow wireless debugging on this network?", only on a listed SSID.

It never toggles a Wireless debugging that answers (every toggle moves the port), restarts a dead one at most once
per boot, and stops switching on if Android turns it straight off again, until adbd has answered once.
The port still changes per boot; this Mac finds it with `adb mdns services` (paired hosts reconnect).
Port lookup uses mDNS on Android 14+, a localhost scan before that (the Echo Show is Android 11).

## Keeping the device reachable (added 2026-11-23)
Two Echo Show 8 problems made the device look online while nothing could reach it (no ping, no adb):
- **Wi-Fi power save.** The MTK chip ran with `iw dev wlan0 get power_save` = on. `PromptService` now holds a
  `WIFI_MODE_FULL_HIGH_PERF` WifiLock for as long as it is bound, which turns power save off.
  Check: `dumpsys wifi` lists `WifiLock{kioskadbkeeper type=3 ...}` under "Locks held".
  The lock is taken once. After a roam the MTK driver can come back with power save on even though the lock is
  still held (Android 11 sends no event on a roam, so there is nothing to re-apply it on). That is accepted: power
  save only adds latency; the outage itself was the dead link below, and the cure for that is fewer roams.
- **Dead link after a roam.** After a move to another access point the driver stayed associated with no data
  flowing, for an hour, and Android never noticed. `NetWatchdog` (started by `PromptService`) probes every 60 s:
  the default gateway (`isReachable`), then a TCP connect to Home Assistant at 10.0.0.190:80 (a refusal counts as an
  answer); nothing else is sent. After 3 misses in a row (~3 min dead) it calls `WifiManager.disconnect()` +
  `reconnect()`; if the link still doesn't answer after the next 3, Wi-Fi off + on. After any reset it waits 10 min
  before probing again, and it does at most 3 resets an hour (then only logs "reset skipped"). Every miss and reset
  is a `W` line in keeper.log ("net watchdog: no answer", "net watchdog RESET"). While the link answers it also switches Wireless debugging back on if it is off
  (after a reset the Wi-Fi callback didn't always fire).
- **Why it roams (kitchen unit, 2026-11-23).** Android's ThroughputScorer left a -30 dBm AP (ch 36, predicted
  310 Mbps) for a -58 dBm one (ch 161, predicted 349 Mbps), then came back 20-40 s later, in bursts every few
  minutes. `dumpsys wifi` shows it under WifiConnectivityManager ("Current connected network is not sufficient",
  then the Candidate lines). The fix belongs on the access points, not the device.
- Those Wi-Fi calls only work for apps targeting API 28 or lower, so the keeper now targets and requires API 28.
  Every API it uses still exists at runtime on Android 11 (Echo Show) and 16 (tablet).
- Test a reset over adb (drops Wi-Fi for a few seconds; do it over USB):
  `am broadcast -n com.laiholdings.kioskadbkeeper/.SetupReceiver -f 0x20 --ei netreset 1` (disconnect/reconnect)
  or `--ei netreset 2` (off/on).
- Also exempt both apps from Doze after installing: `dumpsys deviceidle whitelist +com.freekiosk` and
  `+com.laiholdings.kioskadbkeeper`.

## Build
`./build.sh` (JDK 17 + Android command-line tools from Homebrew; no Gradle). Output: `build/kiosk-adb-keeper.apk`.

## Install (per device, over adb)
```
adb -s <serial> install -r build/kiosk-adb-keeper.apk
adb -s <serial> shell pm grant com.laiholdings.kioskadbkeeper android.permission.WRITE_SECURE_SETTINGS
adb -s <serial> shell am broadcast -n com.laiholdings.kioskadbkeeper/.SetupReceiver -f 0x20 --es ssids "Prospect"
```
Use the broadcast on a screen-pinned kiosk: Android refuses to start `StartActivity` there ("Lock Task Mode
violation"). `SetupReceiver` needs DUMP, which only adb's shell holds. On an unpinned device
`am start -n com.laiholdings.kioskadbkeeper/.StartActivity --es ssids "Prospect"` does the same.
Verify: the log file, which outlives Android's 256 KB log (FreeKiosk fills that in minutes):
`adb shell run-as com.laiholdings.kioskadbkeeper cat /data/user_de/0/com.laiholdings.kioskadbkeeper/files/keeper.log`
(the app is debuggable only so `run-as` can read it). Undo: `adb uninstall com.laiholdings.kioskadbkeeper`.

Port lookup, in order: `service.adb.tls.port` (SELinux usually stops apps reading it), mDNS (API 34+
`registerServiceInfoCallback`, older `resolveService`), then a localhost scan (~20 s on the Echo Show).

Tested 2026-11-03 by `settings put global adb_wifi_enabled 0` over adb: back in 2-10 s on the Echo Show (3 runs)
and 2-3 s on the tablet (2 runs). Run that test only with someone next to the device: switching it off drops every
adb connection, including a legacy `adb tcpip` 5555 one, so a failure leaves no way in.
The Echo Show belongs to Prospect: ask before installing there and log it in ~/Projects/prospect/INSTALLED.md.
