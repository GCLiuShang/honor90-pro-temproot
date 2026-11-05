package com.ghostlock.ksuapp;

import android.app.Activity;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuShellHelper;

/**
 * "安全补丁模块" settings: per-hook toggles + service restart buttons.
 *
 * The LSPosed manager is provided by the KernelSU LSPosed module; this screen
 * only reports whether the module is enabled and whether the required scopes
 * are selected (read from LSPosed's modules_config.db when root is available).
 */
public class PatchSettingsActivity extends Activity {

    private static final String MODULE = "com.ghostlock.ksuapp";
    private static final String[] REQUIRED = {
            "com.hihonor.trustspace",
            "com.hihonor.securityserver",
            "com.hihonor.parallelspace"
    };

    private SharedPreferences prefs;
    private TextView status;
    private LinearLayout rows;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PatchEntry.PREFS, MODE_PRIVATE);
        buildUi();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp((int) radiusDp));
        return g;
    }

    private void buildUi() {
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(getColor(R.color.bg));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(18), dp(18), dp(24));
        sc.addView(col);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        col.addView(head);

        Button back = new Button(this);
        back.setText("‹");
        back.setTextSize(22);
        back.setAllCaps(false);
        back.setStateListAnimator(null);
        back.setBackground(round(0xFFFFFFFF, 14));
        back.setOnClickListener(v -> finish());
        head.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = new TextView(this);
        title.setText("安全补丁模块");
        title.setTextSize(22);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF111827);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.leftMargin = dp(12);
        head.addView(title, tlp);

        TextView sub = new TextView(this);
        sub.setText("LSPosed 模块：在目标进程启动时注入，默认全开。修改开关后请点对应「重启」按钮。");
        sub.setTextSize(12);
        sub.setTextColor(0xFF6B7280);
        sub.setPadding(0, dp(10), 0, dp(6));
        col.addView(sub);

        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(0xFF374151);
        status.setBackground(round(0xFFFFFFFF, 12));
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(6);
        col.addView(status, slp);

        Button refresh = new Button(this);
        refresh.setText("刷新状态");
        refresh.setTextSize(13);
        refresh.setAllCaps(false);
        refresh.setStateListAnimator(null);
        refresh.setBackground(round(0xFFFFFFFF, 14));
        refresh.setOnClickListener(v -> refreshStatus());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(46));
        rlp.topMargin = dp(10);
        col.addView(refresh, rlp);

        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rowsLp = new LinearLayout.LayoutParams(-1, -2);
        rowsLp.topMargin = dp(8);
        col.addView(rows, rowsLp);

        addSwitch("总开关", "关闭后所有 hook 均不生效", PatchEntry.KEY_MASTER, true, null);
        addSwitch("支付保护中心 root 检测", "getRootStatusSync() → 0（“手机未 Root”）",
                PatchEntry.KEY_TRUSTSPACE, true, "com.hihonor.trustspace");
        addSwitch("平行空间 App root 闸门", "ApplicationUtil.isRoot() → false",
                PatchEntry.KEY_PARALLELSPACE, true, "com.hihonor.parallelspace");
        addSwitch("空间密码写入 root 检查", "HwKeystoreServiceImpl.isRoot() → false（创建/重设密码）",
                PatchEntry.KEY_SECURITYSERVER, true, "com.hihonor.securityserver");
        addSwitch("查找设备 root 上报", "SystemUtil.isRoot() → false（仅上报，默认关）",
                PatchEntry.KEY_FINDMY, false, "com.hihonor.findmydevice");

        TextView note = new TextView(this);
        note.setText("说明：msc.parallelspace.debug_mode=1 由「一键越狱」收尾自动写入（su），"
                + "作为本模块平行空间 hook 的兜底，无需手动设置。");
        note.setTextSize(11);
        note.setTextColor(0xFF6B7280);
        note.setPadding(0, dp(12), 0, 0);
        col.addView(note);

        setContentView(sc);
        refreshStatus();
    }

    private void addSwitch(String label, String desc, String key, boolean def, String pkg) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(0xFFFFFFFF, 14));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(10);
        rows.addView(card, clp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top);

        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(15);
        t.setTextColor(0xFF111827);
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            toast("已保存：" + label + (checked ? " 开" : " 关"));
        });
        top.addView(sw, new LinearLayout.LayoutParams(-2, -2));

        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextSize(11);
        d.setTextColor(0xFF6B7280);
        d.setPadding(0, dp(4), 0, 0);
        card.addView(d);

        if (pkg != null) {
            Button restart = new Button(this);
            restart.setText("重启 " + pkg);
            restart.setTextSize(12);
            restart.setAllCaps(false);
            restart.setStateListAnimator(null);
            restart.setBackground(round(0xFFEEF2FF, 12));
            restart.setTextColor(getColor(R.color.brand));
            restart.setOnClickListener(v -> restartService(pkg));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(40));
            rlp.topMargin = dp(8);
            card.addView(restart, rlp);
        }
    }

    private void restartService(String pkg) {
        toast("正在重启 " + pkg + " …");
        new Thread(() -> {
            String out = runAsRoot("am force-stop " + pkg + "; echo done").trim();
            runOnUiThread(() -> {
                toast(pkg + " 已停止: " + out);
                refreshStatus();
            });
        }, "restart").start();
    }

    /** Shizuku when online, otherwise the su fallback (temporary root). */
    private String runAsRoot(String cmd) {
        try {
            if (Shizuku.pingBinder()) {
                return ShizukuShellHelper.run(cmd);
            }
        } catch (Throwable ignored) {
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            byte[] out = readAll(p.getInputStream());
            p.waitFor();
            return new String(out).trim();
        } catch (Throwable t) {
            return "ERR: " + t;
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /** Pull one LSPosed DB file through Shizuku/su + base64; false if root is unavailable. */
    private boolean pullDb(String remote, File out) {
        try {
            String raw = runAsRoot("su -c 'base64 " + remote + "'");
            StringBuilder b64 = new StringBuilder();
            for (String line : raw.split("\n")) {
                String t = line.trim();
                if (t.matches("[A-Za-z0-9+/=]+")) b64.append(t);
            }
            if (b64.length() == 0) return false;
            byte[] data = android.util.Base64.decode(b64.toString(), android.util.Base64.DEFAULT);
            if (data.length == 0) return false;
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(data);
            fos.close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String checkScopes() {
        File dir = new File(getCacheDir(), "lsp");
        if (!dir.exists() && !dir.mkdirs()) return "作用域: 无法检查（缓存目录不可用）";
        boolean ok = pullDb("/data/adb/lspd/config/modules_config.db", new File(dir, "modules_config.db"))
                && pullDb("/data/adb/lspd/config/modules_config.db-wal", new File(dir, "modules_config.db-wal"))
                && pullDb("/data/adb/lspd/config/modules_config.db-shm", new File(dir, "modules_config.db-shm"));
        if (!ok) return "作用域: 无法自动检查（su 不可用），请确认 LSPosed 中已勾选 3 个包";
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(new File(dir, "modules_config.db").getPath(),
                    null, SQLiteDatabase.OPEN_READONLY);
            boolean enabled = false;
            Cursor c = db.rawQuery(
                    "select enabled from modules_state where module_pkg_name=? and user_id=0",
                    new String[]{MODULE});
            if (c.moveToFirst()) enabled = c.getInt(0) == 1;
            c.close();
            Set<String> scopes = new HashSet<>();
            Cursor c2 = db.rawQuery(
                    "select app_pkg_name from scope where module_pkg_name=? and user_id=0",
                    new String[]{MODULE});
            while (c2.moveToNext()) scopes.add(c2.getString(0));
            c2.close();
            StringBuilder missing = new StringBuilder();
            for (String r : REQUIRED) {
                if (!scopes.contains(r)) {
                    if (missing.length() > 0) missing.append(", ");
                    missing.append(r);
                }
            }
            String st = "模块: " + (enabled ? "已启用" : "未启用")
                    + "\n作用域: " + (missing.length() == 0 ? "齐全" : "缺少 " + missing);
            return st;
        } catch (Throwable t) {
            return "作用域: 无法自动检查（" + t.getClass().getSimpleName() + "）";
        } finally {
            if (db != null) db.close();
        }
    }

    private void refreshStatus() {
        boolean shizuku;
        try {
            shizuku = Shizuku.pingBinder();
        } catch (Throwable t) {
            shizuku = false;
        }
        String prop = getProp("msc.parallelspace.debug_mode");
        String scope = checkScopes();
        status.setText("Shizuku: " + (shizuku ? "在线" : "离线（重启服务需要 root/su）")
                + "\n" + scope
                + "\nmsc.parallelspace.debug_mode = " + (prop.isEmpty() ? "(未设置)" : prop));
    }

    private static String getProp(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            Object v = c.getMethod("get", String.class).invoke(null, key);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
