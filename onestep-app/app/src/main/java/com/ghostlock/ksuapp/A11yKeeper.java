package com.ghostlock.ksuapp;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Keeps our accessibility service enabled.
 *
 * HONOR MagicOS (like HyperOS) clears third-party accessibility services on a
 * full power cycle and sometimes even while running. Losing it breaks the whole
 * flow, because the service is what taps 启动 / 始终允许 in Shizuku.
 *
 * With WRITE_SECURE_SETTINGS we can put the entry back ourselves. That
 * permission is not grantable by the user, but shell (uid 2000) has
 * GRANT_RUNTIME_PERMISSIONS, so the app grants it to itself through Shizuku on
 * the first successful run and is then self-healing forever.
 */
public final class A11yKeeper {

    private static final String TAG = "GLKSU";
    static final String PERM = android.Manifest.permission.WRITE_SECURE_SETTINGS;

    private static final String KEY_SERVICES = "enabled_accessibility_services";
    private static final String KEY_ENABLED = "accessibility_enabled";

    private A11yKeeper() { }

    static boolean canWrite(Context c) {
        try {
            return c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
    }

    private static String self(Context c) {
        return c.getPackageName() + "/" + ShizukuStartService.class.getName();
    }

    /** true if our own entry is present in the secure setting. */
    static boolean listed(Context c) {
        try {
            String v = Settings.Secure.getString(c.getContentResolver(), KEY_SERVICES);
            return v != null && v.contains(c.getPackageName() + "/");
        } catch (Throwable t) { return false; }
    }

    /**
     * Appends our service to the enabled list (never clobbers other services)
     * and turns accessibility back on. Returns true if a write happened.
     */
    static boolean restore(Context c) {
        if (!canWrite(c)) return false;
        String me = self(c);
        try {
            ContentResolver cr = c.getContentResolver();
            String cur = Settings.Secure.getString(cr, KEY_SERVICES);
            String next;
            if (cur == null || cur.trim().isEmpty()) next = me;
            else if (cur.contains(me)) next = cur;
            else next = cur + ":" + me;

            if (!next.equals(cur)) {
                Settings.Secure.putString(cr, KEY_SERVICES, next);
            }
            Settings.Secure.putInt(cr, KEY_ENABLED, 1);
            Log.i(TAG, "a11y setting restored: " + next);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "a11y restore failed: " + t);
            return false;
        }
    }
}
