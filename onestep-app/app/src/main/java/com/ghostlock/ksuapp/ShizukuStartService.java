package com.ghostlock.ksuapp;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Exposes the live AccessibilityNodeInfo tree to a poller.
 *
 * We deliberately do NOT drive the flow from onAccessibilityEvent: event
 * delivery proved unreliable (window already open before binding, force-stop
 * dropping the binding, etc). Instead the activity polls {@link #pollAndAct()}
 * a few times a second, which reads the current window, decides what to do and
 * acts, and reports what it saw.
 *
 * Screens handled (all inside moe.shizuku.privileged.api):
 *   1. Shizuku's permission dialog  -> "始终允许" / "Allow all the time"
 *   2. Shizuku's main screen        -> "启动" (start the server)
 *   3. systemui wireless-ADB dialog -> "允许"
 */
public class ShizukuStartService extends AccessibilityService {

    private static volatile ShizukuStartService instance;

    // One-shot guards. Without them the poller re-clicks 启动 every time the
    // starter's log page auto-closes (it closes after ~3s and the main screen
    // comes back), which kills the server it just started and loops forever.
    private static boolean sClickedStart;
    private static boolean sClickedAllow;
    private static boolean sClickedGrant;
    private static int sSawStartTicks;

    public static void resetGuards() {
        sClickedStart = false;
        sClickedAllow = false;
        sClickedGrant = false;
        sSawStartTicks = 0;
    }

    private static final String SHIZUKU = "moe.shizuku.privileged.api";
    private static final String SYSTEMUI = "com.android.systemui";

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    public static final String STARTED = "STARTED";

    public static boolean ready() { return instance != null; }

    /** Global BACK: used to leave a stuck starter page and retry. */
    public static boolean back() {
        ShizukuStartService svc = instance;
        if (svc == null) return false;
        try { return svc.performGlobalAction(GLOBAL_ACTION_BACK); } catch (Throwable t) { return false; }
    }

    /** One monitor tick: read the window, click what we recognise, report. */
    public static String pollAndAct() {
        ShizukuStartService svc = instance;
        if (svc == null) return "无障碍服务未就绪";
        AccessibilityNodeInfo root;
        try { root = svc.getRootInActiveWindow(); } catch (Throwable t) { return "root 异常: " + t; }
        if (root == null) return "root=null";

        String pkg = root.getPackageName() == null ? "?" : root.getPackageName().toString();
        List<String> texts = new ArrayList<>();
        collectTexts(root, texts, 0);

        if (SHIZUKU.equals(pkg)) {
            // The permission dialog sits in the same package; check it first.
            if (texts.contains("始终允许") || texts.contains("Allow all the time")) {
                if (!sClickedGrant) {
                    if (clickExact(root, "始终允许") || clickExact(root, "Allow all the time")) {
                        sClickedGrant = true;
                        return "pkg=" + pkg + " 点击:始终允许";
                    }
                }
                return "pkg=" + pkg + " 授权弹窗(已处理/未找到按钮)";
            }
            // Shizuku is scanning for the wireless-debugging service. Clicking
            // anything here only makes it worse; it resolves by itself.
            if (hasTextContaining(texts, "正在搜索无线调试服务"))
                return "等待无线调试扫描";

            boolean starter = texts.contains("启动程序")
                    || hasTextContaining(texts, "Starting with wireless adb")
                    || hasTextContaining(texts, "Waiting for service");
            if (starter) {
                // The starter prints this the instant the server is up; it is
                // the earliest UI-level confirmation we can get (3 s before the
                // page closes and the main screen re-appears).
                if (hasTextContaining(texts, "Service started")) {
                    sSawStartTicks = 0;
                    return "STARTER_OK";
                }
                // Starter reports an outright failure.
                if (hasTextContaining(texts, "failed") || hasTextContaining(texts, "error")) {
                    sSawStartTicks = 0;
                    return "启动失败(启动器报错)";
                }
                return "启动器等待中";
            }

            boolean running = texts.contains("Shizuku 正在运行")
                    || texts.contains("在终端应用中使用 Shizuku");
            if (running) {
                sSawStartTicks = 0;
                return STARTED;
            }

            // Main screen, server not running.
            if (!sClickedStart) {
                boolean canClick = hasTextContaining(texts, "启动");
                if (canClick) {
                    if (++sSawStartTicks < 2) return "页面稳定中(等待启动按钮)";
                    if (clickExact(root, "启动")) { sClickedStart = true; return "点击:启动(仅一次)"; }
                } else {
                    sSawStartTicks = 0;
                }
            } else {
                // We already pressed 启动 and the starter window is gone, yet the
                // main screen still shows the button -> the start really failed.
                return "启动失败(已回到主页)";
            }
        } else if (SYSTEMUI.equals(pkg)) {
            if (texts.contains("是否允许通过此网络进行无线调试？")) {
                if (!sClickedAllow && clickExact(root, "允许")) { sClickedAllow = true; return "pkg=" + pkg + " 点击:允许(仅一次)"; }
                return "pkg=" + pkg + " 弹窗(已处理或未找到允许)";
            }
        }
        return "pkg=" + pkg + " texts=" + texts;
    }

    private static boolean hasTextContaining(List<String> texts, String sub) {
        for (String t : texts) if (t.contains(sub)) return true;
        return false;
    }

    private static void collectTexts(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (n == null || depth > 12 || out.size() > 24) return;
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) out.add(t.toString());
        for (int i = 0; i < n.getChildCount(); i++) collectTexts(n.getChild(i), out, depth + 1);
    }

    private static boolean clickExact(AccessibilityNodeInfo root, String label) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
        if (nodes == null) return false;
        for (AccessibilityNodeInfo n : nodes) {
            CharSequence txt = n.getText();
            if (txt == null || !label.contentEquals(txt)) continue;
            if (n.isClickable() && n.isEnabled()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            AccessibilityNodeInfo p = n.getParent();
            for (int d = 0; p != null && d < 4; d++) {
                if (p.isClickable() && p.isEnabled()) return p.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                p = p.getParent();
            }
        }
        return false;
    }
}
