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
 * There are two repair paths:
 *   1. If the app already has WRITE_SECURE_SETTINGS, write the secure setting.
 *   2. Otherwise grant it through Shizuku (uid 2000 has GRANT_RUNTIME_PERMISSIONS)
 *      and write the setting; if pm grant is refused, write the setting through
 *      the Shizuku shell directly (`settings put secure ...`).
 *
 * Path 2 is the bootstrap. It is only available after Shizuku has authorized
 * this app, which is why the app cannot repair accessibility before Shizuku is
 * set up.
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

    /**
     * Grants WRITE_SECURE_SETTINGS to this package through Shizuku's shell.
     * Blocking (binder + pm); call from a worker thread.
     */
    static boolean grantViaShizuku(Context c) {
        if (canWrite(c)) return true;
        try {
            String out = rikka.shizuku.ShizukuShellHelper.run(
                    "pm grant --user 0 " + c.getPackageName() + " " + PERM).trim();
            Log.i(TAG, "pm grant WRITE_SECURE_SETTINGS => " + out);
        } catch (Throwable t) {
            Log.w(TAG, "pm grant WRITE_SECURE_SETTINGS failed: " + t);
        }
        return canWrite(c);
    }

    /** Grant (if needed) then re-add our service to the secure setting. */
    static boolean ensure(Context c) {
        if (canWrite(c) && restore(c)) return true;
        if (grantViaShizuku(c) && restore(c)) return true;
        // On some MagicOS builds pm grant succeeds but the app process still
        // gets DENIED until it restarts. Shell `settings` has the permission
        // independently, so use it as a fallback instead of giving up.
        return shellRestore(c);
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
     * and turns accessibility back on. Returns true only if the writes were
     * accepted; the caller should still check listed()/ready() if it cares.
     */
    static boolean restore(Context c) {
        if (!canWrite(c)) return false;
        String me = self(c);
        try {
            ContentResolver cr = c.getContentResolver();
            String cur = Settings.Secure.getString(cr, KEY_SERVICES);
            String next;
            if (cur == null || cur.trim().isEmpty() || "null".equals(cur.trim())) next = me;
            else if (cur.contains(me)) next = cur;
            else next = cur + ":" + me;

            boolean wrote = next.equals(cur) || Settings.Secure.putString(cr, KEY_SERVICES, next);
            boolean enabled = Settings.Secure.putInt(cr, KEY_ENABLED, 1);
            Log.i(TAG, "a11y restore via app: wrote=" + wrote + " enabled=" + enabled
                    + " value=" + next);
            return wrote && enabled;
        } catch (Throwable t) {
            Log.w(TAG, "a11y restore via app failed: " + t);
            return false;
        }
    }

    /**
     * Fallback for devices where pm grant is ineffective: let the Shizuku
     * shell (uid 2000, which has WRITE_SECURE_SETTINGS) edit the setting.
     * Requires an authorized Shizuku; returns false on any shell error.
     */
    static boolean shellRestore(Context c) {
        String me = self(c);
        try {
            String cur = rikka.shizuku.ShizukuShellHelper.run(
                    "settings --user 0 get secure " + KEY_SERVICES).trim();
            if (cur.startsWith("ERR:")) {
                Log.w(TAG, "a11y shell get failed: " + cur);
                return false;
            }
            String next;
            if (cur.isEmpty() || "null".equals(cur)) next = me;
            else if (cur.contains(me)) next = cur;
            else next = cur + ":" + me;

            String out = rikka.shizuku.ShizukuShellHelper.run(
                    "settings --user 0 put secure " + KEY_SERVICES + " '" + next + "'; "
                            + "settings --user 0 put secure " + KEY_ENABLED + " 1").trim();
            if (out.startsWith("ERR:")) {
                Log.w(TAG, "a11y shell put failed: " + out);
                return false;
            }

            String got = rikka.shizuku.ShizukuShellHelper.run(
                    "settings --user 0 get secure " + KEY_ENABLED).trim();
            String services = rikka.shizuku.ShizukuShellHelper.run(
                    "settings --user 0 get secure " + KEY_SERVICES).trim();
            boolean ok = "1".equals(got) && services.contains(me);
            Log.i(TAG, "a11y restore via shell: ok=" + ok + " enabled=" + got
                    + " services=" + services + " output=" + out);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "a11y restore via shell failed: " + t);
            return false;
        }
    }
}
