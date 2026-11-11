package com.laiholdings.kioskadbkeeper;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Android's log on the Echo Show is 256 KB and FreeKiosk fills it in minutes, so every line also goes to a file in
 *  device-protected storage (writable before unlock). Read it with
 *  adb shell run-as com.laiholdings.kioskadbkeeper cat /data/user_de/0/com.laiholdings.kioskadbkeeper/files/keeper.log */
class Log2 {
    static final long MAX_BYTES = 256 * 1024;  // then keeper.log becomes keeper.1.log
    private static File dir;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    static synchronized void init(Context c) {
        if (dir == null) dir = c.createDeviceProtectedStorageContext().getFilesDir();
    }

    static void i(String m) { Log.i(Keeper.TAG, m); write("I", m); }
    static void w(String m) { Log.w(Keeper.TAG, m); write("W", m); }

    private static synchronized void write(String level, String m) {
        if (dir == null) return;
        try {
            File f = new File(dir, "keeper.log");
            if (f.length() > MAX_BYTES) f.renameTo(new File(dir, "keeper.1.log"));
            try (FileOutputStream os = new FileOutputStream(f, true)) {
                os.write((TIME.format(new Date()) + " " + level + " [" + Thread.currentThread().getName() + "] " + m
                        + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) { }
    }
}
