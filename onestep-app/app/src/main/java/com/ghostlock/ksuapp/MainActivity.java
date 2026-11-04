package com.ghostlock.ksuapp;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import rikka.shizuku.Shizuku;

/**
 * 一键 GLKS —— 在手机上抢到临时 root。
 *
 * 两个可独立点击的动作（有意分开，避免后台调度把两件事搅在一起）：
 *   1) 启动 Shizuku：检查环境（无障碍 + Shizuku）→ 拉起 Shizuku → 等 binder → 取授权
 *   2) 一键越狱：检查 Shizuku → 设备状态门槛（开机≥60s、可用内存≥4GB）→ 跑套件 → 收尾
 *   3) 日志：查看运行记录
 *
 * 约束：
 *  1. manifest 必须声明 moe.shizuku.manager.permission.API_V23，provider authority
 *     必须是字面量 "<package>.shizuku"，否则服务器永远不推 binder。
 *  2. Shizuku 只在进程启动/切前台时推 binder，所以 poll 里可能要自我重启。
 *  3. 套件只从 APK 私有目录（/sdcard/Android/data/<pkg>/files/kit）落盘到
 *     /data/local/tmp，并按 kit.md5 清单逐文件校验；公有目录逻辑已删除。
 */
public class MainActivity extends Activity {

    private static final String TAG = "GLKSU";
    private static final int PERM_CODE = 4242;
    private static final int TICK_MS = 400;
    private static final int MAX_TICKS = 450;
    private static final String KIT_SCRIPT = "/data/local/tmp/run_ksu.sh";
    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";

    // 设备状态门槛（仅两项，实时判定）
    private static final long GATE_MIN_UPTIME_S = 60;
    private static final long GATE_MIN_MEM_KB = 4L * 1024 * 1024;   // 4 GB

    // ---- 状态机 ----
    private static final int S_IDLE = 0, S_CHECK = 1, S_START = 2, S_WAIT = 3,
            S_PERM = 4, S_RUN = 5, S_DONE = 6, S_FAIL = 7;

    private static final String[] TITLE = {
            "待机", "检查环境", "启动 Shizuku", "等待 Shizuku 就绪",
            "申请 Shizuku 授权", "执行套件", "完成", "失败"};

    // 两个动作各自一套步骤。一键越狱里“Shizuku 已就绪 / 获取授权”是
    // 「启动 Shizuku」那一步的产物，不属于本流程，故不再列出。
    private static final String[] STEPS_START = {
            "检查环境（无障碍 / Shizuku）",
            "启动并等待 Shizuku 就绪",
            "获取 Shizuku 授权",
            "完成"};
    // 按 run_ksu.sh / load_ksu.sh / run.log 的真实阶段拆分：
    //   gate -> 私有镜像校验落盘 -> exploit(5 个 sysctl 阶段) -> KSU 模块 ->
    //   等待 su -> 收尾(关无线调试/停 Shizuku) -> 完成
    private static final String[] STEPS_ROOT = {
            "检查 Shizuku",
            "设备状态门槛",
            "校验并落盘套件",
            "临时 root（GhostLock）",
            "加载 KernelSU 模块",
            "等待 su 可用",
            "收尾（关调试、停 Shizuku）",
            "完成"};

    private static final int PENDING = 0, ACTIVE = 1, DONE = 2, FAILED = 3;
    private static final int MODE_START = 0, MODE_ROOT = 1;

    // ---- 视图 ----
    private View pageHome, pageLog;
    private FrameLayout rootView;
    private TextView statusTitle, timerView, logView;
    private TextView stepCur, stepNext;
    private LinearLayout stepRows;
    private FrameLayout stepBox;
    private PulseView pulse;
    private ProgressBar progress;
    private Button startBtn, rootBtn;
    private TextView snackView;
    private int mode = MODE_START;
    private String[] currentSteps = STEPS_START;
    private int stepCount = STEPS_START.length;
    private final int[] stepState = new int[8];
    private int focusIdx = -1;
    private volatile boolean timerFrozen;
    private final TextView[] devValues = new TextView[5];   // 无障碍/Shizuku/KSU/内存/开机

    // ---- 状态 ----
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int state = S_IDLE;
    private long startedAt;
    private volatile boolean running;
    private volatile boolean busy;
    private boolean armed, askedPermission, binderHandled, polling;
    private int tickNo;
    private long serverSeenAt;
    private long starterSeenAt;
    private int startRetries;
    private boolean launchedShizuku;
    private boolean uiRunning;
    private boolean starterOk;
    private long permDoneAt;
    private String lastSeen = "";
    private File logFile;
    private final StringBuilder logBuf = new StringBuilder();

    private final Runnable POLL = () -> tick();

    // ===================================================================== UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        try { logFile = new File(getExternalFilesDir(null), "app.log"); } catch (Throwable ignored) { }

        try {
            Shizuku.addBinderReceivedListenerSticky(() -> ui.post(() -> {
                if (mode == MODE_START && armed && !binderHandled && pingBinderSafe()) {
                    binderHandled = true;
                    log("Shizuku 服务已就绪 (binder 推送)");
                    setStep(1, DONE);
                    onShizukuReady();
                }
            }));
        } catch (Throwable t) { log("注册 binder 监听失败: " + t); }

        try {
            Shizuku.addRequestPermissionResultListener((code, grant) -> ui.post(() -> {
                boolean ok = grant == PackageManager.PERMISSION_GRANTED;
                log("Shizuku 授权结果: " + (ok ? "已允许" : "被拒绝"));
                if (ok) {
                    setStep(2, DONE);
                    permDoneAt = SystemClock.elapsedRealtime();
                    awaitUiConfirm();
                } else {
                    setStep(2, FAILED);
                    setState(S_FAIL);
                    snack("Shizuku 授权被拒绝：请到 Shizuku 应用列表里手动允许本应用");
                }
            }), ui);
        } catch (Throwable t) { log("注册授权监听失败: " + t); }

        setState(S_IDLE);
        log("=== 一键 GLKS ===");
        new Thread(() -> { if (!syncPrivateKit()) log("私有套件镜像校验未通过（将影响一键越狱）"); }, "kit-sync").start();

        if (!ShizukuStartService.ready() && A11yKeeper.canWrite(this)) {
            log("检测到无障碍服务被系统关闭，正在自动恢复…");
            A11yKeeper.restore(this);
        }

        if (getSharedPreferences("gl", MODE_PRIVATE).getBoolean("armed", false)) {
            armed = true;
            ui.postDelayed(() -> {
                log("（检测到上次未完成的流程，继续）");
                resumeRun();
            }, 600);
        }

        ui.post(timerLoop);
        ui.post(deviceLoop);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private Drawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private void buildUi() {
        rootView = new FrameLayout(this);
        rootView.setBackgroundColor(getColor(R.color.bg));
        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets b = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                top = b.top;
                bottom = b.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        pageHome = buildHome();
        pageLog = buildLog();
        rootView.addView(pageHome);
        rootView.addView(pageLog);
        pageLog.setVisibility(View.GONE);
        setContentView(rootView);
    }

    private View buildHome() {
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(26), dp(18), dp(26));
        sc.addView(col);

        TextView title = new TextView(this);
        title.setText("一键GLKS");
        title.setTextSize(26);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTypeface(null, Typeface.BOLD);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("HONOR 90 Pro · GhostLock + KernelSU 临时 root");
        sub.setTextSize(12);
        sub.setTextColor(getColor(R.color.text_secondary));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(2);
        sp.bottomMargin = dp(16);
        col.addView(sub, sp);

        // ---- 状态卡片：只保留最上方状态 + 进度 + 两行任务单 + 计时 ----
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(round(getColor(R.color.card), 18));
        card.setElevation(dp(3));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        pulse = new PulseView(this, getColor(R.color.brand));
        head.addView(pulse, new LinearLayout.LayoutParams(dp(12), dp(12)));
        statusTitle = new TextView(this);
        statusTitle.setTextSize(19);
        statusTitle.setTextColor(getColor(R.color.text_primary));
        statusTitle.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.leftMargin = dp(10);
        head.addView(statusTitle, tlp);
        card.addView(head);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        GradientDrawable track = new GradientDrawable();
        track.setColor(0xFFE8EBF2);
        track.setCornerRadius(dp(3));
        GradientDrawable bar = new GradientDrawable();
        bar.setColor(getColor(R.color.brand));
        bar.setCornerRadius(dp(3));
        LayerDrawable ld = new LayerDrawable(new Drawable[]{
                track, new ClipDrawable(bar, Gravity.LEFT, ClipDrawable.HORIZONTAL)});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        progress.setProgressDrawable(ld);
        progress.setMax(100);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, dp(6));
        plp.topMargin = dp(14);
        card.addView(progress, plp);

        // 两行滚动任务单：当前步骤（深色、大字号）+ 下一步。
        stepBox = new FrameLayout(this);
        stepBox.setClipChildren(true);
        stepRows = new LinearLayout(this);
        stepRows.setOrientation(LinearLayout.VERTICAL);
        stepCur = makeRow();
        stepNext = makeRow();
        stepRows.addView(stepCur);
        stepRows.addView(stepNext);
        stepBox.addView(stepRows, new FrameLayout.LayoutParams(-1, dp(68)));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(68));
        slp.topMargin = dp(12);
        card.addView(stepBox, slp);

        timerView = new TextView(this);
        timerView.setTextSize(12);
        timerView.setTextColor(getColor(R.color.text_secondary));
        timerView.setTypeface(Typeface.MONOSPACE);
        timerView.setText("已用时 00:00.000");
        LinearLayout.LayoutParams tmlp = new LinearLayout.LayoutParams(-1, -2);
        tmlp.topMargin = dp(10);
        card.addView(timerView, tmlp);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = dp(6);
        col.addView(card, clp);

        // ---- 设备状态卡片：无障碍 / Shizuku / KernelSU / 可用内存 / 开机 ----
        LinearLayout devCard = new LinearLayout(this);
        devCard.setOrientation(LinearLayout.VERTICAL);
        devCard.setPadding(dp(18), dp(14), dp(18), dp(16));
        devCard.setBackground(round(getColor(R.color.card), 18));
        devCard.setElevation(dp(2));

        TextView devTitle = new TextView(this);
        devTitle.setText("设备状态");
        devTitle.setTextSize(13);
        devTitle.setTextColor(getColor(R.color.text_secondary));
        devCard.addView(devTitle);

        String[] labels = {"无障碍", "Shizuku", "KernelSU", "可用内存", "开机"};
        for (int r = 0; r < labels.length; r += 3) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(54));
            rlp.topMargin = dp(8);
            for (int c = 0; c < 3; c++) {
                int idx = r + c;
                if (idx >= labels.length) {
                    row.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1f));
                    continue;
                }
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                TextView lab = new TextView(this);
                lab.setText(labels[idx]);
                lab.setTextSize(11);
                lab.setTextColor(getColor(R.color.text_secondary));
                cell.addView(lab);
                TextView val = new TextView(this);
                val.setText("—");
                val.setTextSize(14);
                val.setTextColor(getColor(R.color.text_primary));
                val.setTypeface(null, Typeface.BOLD);
                cell.addView(val);
                devValues[idx] = val;
                row.addView(cell, new LinearLayout.LayoutParams(0, -1, 1f));
            }
            devCard.addView(row, rlp);
        }

        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = dp(14);
        col.addView(devCard, dlp);

        // ---- 按钮 ----
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(20);
        col.addView(btns, blp);

        startBtn = new Button(this);
        startBtn.setText("启动 Shizuku");
        startBtn.setTextSize(16);
        startBtn.setTypeface(null, Typeface.BOLD);
        startBtn.setAllCaps(false);
        startBtn.setStateListAnimator(null);
        startBtn.setOnClickListener(v -> {
            if (busy || running) { snack("正在执行中，请稍候"); return; }
            if (pingBinderSafe()) { snack("Shizuku 已在运行，无需再次启动"); return; }
            if (!ShizukuStartService.ready()) {
                snack("无障碍服务未开启：请在 设置 → 无障碍 中开启「一键GLKS」");
                openA11ySettings();
                return;
            }
            go(MODE_START);
        });
        btns.addView(startBtn, new LinearLayout.LayoutParams(0, dp(56), 1f));

        rootBtn = new Button(this);
        rootBtn.setText("一键越狱");
        rootBtn.setTextSize(17);
        rootBtn.setTypeface(null, Typeface.BOLD);
        rootBtn.setAllCaps(false);
        rootBtn.setStateListAnimator(null);
        rootBtn.setOnClickListener(v -> {
            if (busy || running) { snack("正在执行中，请稍候"); return; }
            if (!pingBinderSafe()) { snack("Shizuku 未运行：请先点「启动 Shizuku」"); return; }
            if (ksuLoaded()) { snack("KernelSU 已加载，无需再次越狱"); return; }
            go(MODE_ROOT);
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        rlp.leftMargin = dp(12);
        btns.addView(rootBtn, rlp);

        LinearLayout logRow = new LinearLayout(this);
        logRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lrlp = new LinearLayout.LayoutParams(-1, -2);
        lrlp.topMargin = dp(12);
        col.addView(logRow, lrlp);

        Button logBtn = new Button(this);
        logBtn.setText("日志");
        logBtn.setTextSize(14);
        logBtn.setTextColor(getColor(R.color.brand));
        logBtn.setAllCaps(false);
        logBtn.setBackground(round(0xFFFFFFFF, 16));
        logBtn.setStateListAnimator(null);
        logBtn.setOnClickListener(v -> showPage(true));
        logRow.addView(logBtn, new LinearLayout.LayoutParams(-1, dp(48)));

        // 底部浮出消息（白底黑字），用于“为什么不让我点”的即时提示。
        snackView = new TextView(this);
        snackView.setTextSize(13);
        snackView.setTextColor(0xFF111827);
        snackView.setBackground(round(0xFFFFFFFF, 14));
        snackView.setPadding(dp(16), dp(12), dp(16), dp(12));
        snackView.setElevation(dp(8));
        snackView.setVisibility(View.GONE);
        FrameLayout.LayoutParams snlp = new FrameLayout.LayoutParams(-2, -2);
        snlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        snlp.bottomMargin = dp(88);
        snlp.leftMargin = dp(18);
        snlp.rightMargin = dp(18);
        rootView.addView(snackView, snlp);

        setupSteps(STEPS_START);
        return sc;
    }

    private TextView makeRow() {
        TextView tv = new TextView(this);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(34)));
        return tv;
    }

    private void styleCur(TextView tv) {
        tv.setTextSize(19);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(34)));
    }

    private void styleNext(TextView tv) {
        tv.setTextSize(14);
        tv.setTypeface(null, Typeface.NORMAL);
        tv.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(34)));
    }

    private View buildLog() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(getColor(R.color.bg));
        col.setPadding(dp(18), dp(26), dp(18), dp(18));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(this);
        t.setText("运行日志");
        t.setTextSize(20);
        t.setTextColor(getColor(R.color.text_primary));
        t.setTypeface(null, Typeface.BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));

        Button copy = new Button(this);
        copy.setText("复制");
        copy.setTextSize(14);
        copy.setAllCaps(false);
        copy.setTextColor(Color.WHITE);
        copy.setBackground(round(getColor(R.color.brand), 14));
        copy.setStateListAnimator(null);
        copy.setOnClickListener(v -> copyLog());
        head.addView(copy, new LinearLayout.LayoutParams(dp(84), dp(44)));

        Button back = new Button(this);
        back.setText("返回");
        back.setTextSize(14);
        back.setAllCaps(false);
        back.setTextColor(getColor(R.color.brand));
        back.setBackground(round(0xFFFFFFFF, 14));
        back.setStateListAnimator(null);
        back.setOnClickListener(v -> showPage(false));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(84), dp(44));
        blp.leftMargin = dp(10);
        head.addView(back, blp);

        col.addView(head);

        TextView path = new TextView(this);
        path.setText(getString(R.string.log_path_hint));
        path.setTextSize(11);
        path.setTextColor(getColor(R.color.text_secondary));
        path.setTextIsSelectable(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = dp(6);
        plp.bottomMargin = dp(10);
        col.addView(path, plp);

        ScrollView sc = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(getColor(R.color.text_primary));
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        sc.addView(logView);
        col.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1f));
        return col;
    }

    private void showPage(boolean toLog) {
        View in = toLog ? pageLog : pageHome;
        View out = toLog ? pageHome : pageLog;
        if (in.getVisibility() == View.VISIBLE) return;
        if (toLog) logView.setText(logBuf.toString());
        in.setVisibility(View.VISIBLE);
        in.setAlpha(0f);
        in.setTranslationX(toLog ? dp(40) : -dp(40));
        in.animate().alpha(1f).translationX(0f).setDuration(200).start();
        out.animate().alpha(0f).translationX(toLog ? -dp(40) : dp(40)).setDuration(160)
                .withEndAction(() -> { out.setVisibility(View.GONE); out.setAlpha(1f); }).start();
    }

    private void copyLog() {
        try {
            String text = logBuf.length() == 0 ? logView.getText().toString() : logBuf.toString();
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("GLKS log", text));
            Toast.makeText(this, "日志已复制（" + text.length() + " 字）", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败: " + t, Toast.LENGTH_SHORT).show();
        }
    }

    /** 屏幕下部浮出白底黑字消息；错误提示统一带「详情请见日志」。 */
    private void snack(String msg) {
        log("提示：" + msg);
        final String text = msg.endsWith("）") ? msg : msg + "（详情请见日志）";
        ui.post(() -> {
            if (snackView == null) return;
            snackView.animate().cancel();
            snackView.setText(text);
            snackView.setVisibility(View.VISIBLE);
            snackView.setAlpha(0f);
            snackView.setTranslationY(dp(24));
            snackView.animate().alpha(1f).translationY(0f).setDuration(180).start();
            if (snackHide != null) ui.removeCallbacks(snackHide);
            snackHide = () -> snackView.animate().alpha(0f).translationY(dp(24))
                    .setDuration(180).withEndAction(() -> snackView.setVisibility(View.GONE)).start();
            ui.postDelayed(snackHide, 3800);
        });
    }

    private Runnable snackHide;

    // ============================================================== 状态与动效

    private int stateColor(int s) {
        if (s == S_DONE) return getColor(R.color.ok);
        if (s == S_FAIL) return getColor(R.color.err);
        if (s == S_IDLE) return getColor(R.color.text_secondary);
        return getColor(R.color.brand);
    }

    private void setState(int s) {
        state = s;
        ui.post(() -> {
            if (s == S_DONE) {
                ObjectAnimator.ofInt(progress, "progress", 100).setDuration(400).start();
            }
            if (s == S_DONE || s == S_FAIL) {
                timerFrozen = true;
                busy = false;
                if (!running) stopPolling();
                armed = false;
                getSharedPreferences("gl", MODE_PRIVATE).edit().putBoolean("armed", false).apply();
                setBusyUi(false);   // 重新允许点击（否则上一轮的 setEnabled(false) 会一直留着）
            }
            statusTitle.setText(TITLE[s]);
            pulse.setColor(stateColor(s));
            pulse.setActive(s == S_CHECK || s == S_START || s == S_WAIT || s == S_PERM);
            statusTitle.setAlpha(0.3f);
            statusTitle.animate().alpha(1f).setDuration(240).start();
            updateButtons();
        });
    }

    // ------------------------------------------------------------ 两行任务单

    private void setupSteps(String[] names) {
        currentSteps = names;
        stepCount = names.length;
        // 状态重置必须同步：go() 会在同一调用栈里紧接着 setStep(0,ACTIVE/DONE)、
        // setStep(1,ACTIVE)，若把重置放进 ui.post，队列里的重置会把这些推进抹回
        // PENDING，导致任务单永远停在第一步、不再滚动。
        for (int i = 0; i < stepState.length; i++) stepState[i] = i < stepCount ? PENDING : -1;
        focusIdx = -1;
        ui.post(() -> {
            progress.setProgress(0);
            stepRows.setTranslationY(0);
            stepRows.removeAllViews();
            stepCur = makeRow();
            stepNext = makeRow();
            styleCur(stepCur);
            styleNext(stepNext);
            stepRows.addView(stepCur);
            stepRows.addView(stepNext);
            bindRow(stepCur, 0, true);
            bindRow(stepNext, 1, false);
            focusIdx = 0;
        });
    }

    private void bindRow(TextView tv, int idx, boolean current) {
        if (idx < 0 || idx >= stepCount) { tv.setText(""); return; }
        int st = stepState[idx];
        String mark = st == DONE ? "✓ " : st == FAILED ? "✗ " : st == ACTIVE ? "▶ " : "○ ";
        tv.setText(mark + currentSteps[idx]);
        if (current) {
            tv.setTextColor(st == FAILED ? getColor(R.color.err) : getColor(R.color.text_primary));
        } else {
            tv.setTextColor(st == FAILED ? getColor(R.color.err) : getColor(R.color.text_secondary));
        }
    }

    private void rebindRows() {
        bindRow(stepCur, focusIdx, true);
        bindRow(stepNext, focusIdx + 1, false);
    }

    /** 当前步骤向上滚出、下一步滚到“当前”位置。 */
    private int animGen;

    private void animateFocus(int newIdx) {
        if (newIdx == focusIdx) { rebindRows(); return; }
        if (newIdx != focusIdx + 1) {
            // 跳步：直接重排，不做位移动画。
            animGen++;
            stepRows.animate().cancel();
            stepRows.setTranslationY(0);
            focusIdx = newIdx;
            rebindRows();
            return;
        }
        focusIdx = newIdx;
        final int gen = ++animGen;
        stepRows.animate().cancel();   // 旧动画的 end action 会因 gen 不符而跳过
        stepRows.animate().translationY(-dp(34)).setDuration(220).withEndAction(() -> {
            if (gen != animGen) return;
            stepRows.removeView(stepCur);
            stepCur = stepNext;
            styleCur(stepCur);
            bindRow(stepCur, focusIdx, true);
            stepNext = makeRow();
            styleNext(stepNext);
            bindRow(stepNext, focusIdx + 1, false);
            stepRows.addView(stepNext);
            stepRows.setTranslationY(0);
        }).start();
    }

    /** 运行中动态更新某一步的文案（例如「临时 root（3/5）」）。 */
    private void setStepLabel(int i, String text) {
        if (i < 0 || i >= stepCount) return;
        currentSteps[i] = text;
        ui.post(this::rebindRows);
    }

    /** 把当前（第一个未完成）步骤标记为失败。 */
    private void failCurrentStep() {
        int i = 0;
        while (i < stepCount && stepState[i] == DONE) i++;
        if (i < stepCount) setStep(i, FAILED);
    }

    private void setStep(int i, int st) {
        if (i < 0 || i >= stepCount || stepState[i] == st) return;
        stepState[i] = st;
        ui.post(() -> {
            int idx = 0;
            while (idx < stepCount && stepState[idx] == DONE) idx++;
            if (idx >= stepCount) idx = stepCount - 1;
            int done = 0, active = 0;
            for (int k = 0; k < stepCount; k++) {
                if (stepState[k] == DONE) done++;
                else if (stepState[k] == ACTIVE) active++;
            }
            int pct = (done * 100 + active * 50) / Math.max(1, stepCount);
            ObjectAnimator.ofInt(progress, "progress", Math.max(0, Math.min(100, pct)))
                    .setDuration(350).start();
            if (idx != focusIdx) animateFocus(idx);
            else rebindRows();
        });
    }

    // ---------------------------------------------------------------- 计时器

    /** 每次运行开始：重置并启动毫秒级计时。冻结由 setState(DONE/FAIL) 负责。 */
    private void startTimer() {
        startedAt = SystemClock.elapsedRealtime();
        timerFrozen = false;
        ui.post(() -> timerView.setText("已用时 00:00.000"));
        ui.removeCallbacks(timerLoop);
        ui.post(timerLoop);
    }

    private final Runnable timerLoop = new Runnable() {
        @Override public void run() {
            if (!timerFrozen && startedAt > 0) {
                long ms = SystemClock.elapsedRealtime() - startedAt;
                timerView.setText(String.format(Locale.US, "已用时 %02d:%02d.%03d",
                        ms / 60000, (ms / 1000) % 60, ms % 1000));
            }
            ui.postDelayed(this, state == S_RUN ? 100 : 50);
        }
    };

    private final Runnable deviceLoop = new Runnable() {
        @Override public void run() {
            updateDeviceInfo();
            ui.postDelayed(this, 500);   // 实时刷新（500ms）
        }
    };

    private boolean ksuLoaded() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/modules"))) {
            String l;
            while ((l = r.readLine()) != null) if (l.startsWith("kernelsu")) return true;
        } catch (Throwable ignored) { }
        return new File("/system/bin/su").exists();
    }

    private String memAvailableGb() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("MemAvailable:")) {
                    long kb = Long.parseLong(l.replaceAll("[^0-9]", ""));
                    return String.format(Locale.US, "%.1f GB", kb / 1048576f);
                }
            }
        } catch (Throwable ignored) { }
        return "—";
    }

    private void updateDeviceInfo() {
        final boolean a11y = ShizukuStartService.ready();
        final boolean sb = pingBinderSafe();
        final boolean ksu = ksuLoaded();
        final String mem = memAvailableGb();
        long secs = SystemClock.elapsedRealtime() / 1000;
        final String up = String.format(Locale.US, "%d:%02d", secs / 3600, (secs % 3600) / 60);
        ui.post(() -> {
            devValues[0].setText(a11y ? "已开启" : "未开启");
            devValues[0].setTextColor(a11y ? getColor(R.color.ok) : getColor(R.color.warn));
            devValues[1].setText(sb ? "运行中" : "未运行");
            devValues[1].setTextColor(sb ? getColor(R.color.ok) : getColor(R.color.text_secondary));
            devValues[2].setText(ksu ? "已加载" : "未加载");
            devValues[2].setTextColor(ksu ? getColor(R.color.ok) : getColor(R.color.text_secondary));
            devValues[3].setText(mem);
            devValues[4].setText(up);
            updateButtons();
        });
    }

    // ------------------------------------------------------------ 按钮样式/状态

    private void styleBtn(Button b, boolean on) {
        b.setBackground(round(on ? getColor(R.color.brand) : 0xFFE5E7EB, 16));
        b.setTextColor(on ? Color.WHITE : 0xFF9CA3AF);
    }

    /** 实时更新两个按钮的可点状态：启动=无障碍已开且 Shizuku 未运行；越狱=Shizuku 运行且 KSU 未加载。 */
    private void updateButtons() {
        boolean a11y = ShizukuStartService.ready();
        boolean sb = pingBinderSafe();
        boolean ksu = ksuLoaded();
        boolean idle = !busy && !running;
        styleBtn(startBtn, idle && a11y && !sb);
        styleBtn(rootBtn, idle && sb && !ksu);
        startBtn.setText("启动 Shizuku");
        rootBtn.setText("一键越狱");
    }

    // ================================================================= 日志

    private static final String[] KIT_FILES = {
            "exploit_static", "run_ksu.sh", "load_ksu.sh", "kernelsu.ko", "ksudbin",
            "magiskpolicy", "ksu_rules", "kmsg_dumper", "stage_kit.sh"};
    private static final String KSU_ALIAS = "kernelsu.new.ko";

    private String md5Of(java.io.InputStream in) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Throwable t) { return null; }
    }

    private String md5Asset(String name) {
        try (java.io.InputStream in = getAssets().open(name)) { return md5Of(in); }
        catch (Throwable t) { return null; }
    }

    private String md5File(File f) {
        if (f == null || !f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) { return md5Of(in); }
        catch (Throwable t) { return null; }
    }

    private boolean copyFile(File a, File b) {
        try (FileInputStream in = new FileInputStream(a); FileOutputStream out = new FileOutputStream(b)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.getFD().sync();
            return true;
        } catch (Throwable t) { log("复制失败 " + a + " → " + b + ": " + t); return false; }
    }

    /**
     * 把 APK 内置套件同步到应用私有外部目录：
     *   /sdcard/Android/data/<pkg>/files/kit (getExternalFilesDir)
     * 其他应用写不了；shell(2000) 可读。同步时逐个 md5 比对并 fsync，
     * 并写出 kit.md5 清单，供 stage_kit.sh 在落盘到 /data/local/tmp 时复核。
     */
    private synchronized boolean syncPrivateKit() {
        try {
            File dir = new File(getExternalFilesDir(null), "kit");
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            StringBuilder man = new StringBuilder();
            boolean ok = true;
            for (String n : KIT_FILES) {
                File out = new File(dir, n);
                String want = md5Asset(n);
                if (want == null) { log("资产缺失: " + n); ok = false; continue; }
                if (!want.equals(md5File(out))) {
                    try (java.io.InputStream in = getAssets().open(n);
                         FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
                        fos.getFD().sync();
                    }
                }
                if (!want.equals(md5File(out))) { log("私有镜像校验失败: " + n); ok = false; continue; }
                man.append(want).append(' ').append(n).append('\n');
            }
            File ko = new File(dir, "kernelsu.ko"), ko2 = new File(dir, KSU_ALIAS);
            if (ko.isFile()) {
                String m = md5File(ko);
                if (m != null) {
                    if (!m.equals(md5File(ko2))) copyFile(ko, ko2);
                    if (m.equals(md5File(ko2))) man.append(m).append(' ').append(KSU_ALIAS).append('\n');
                    else ok = false;
                }
            }
            if (ok) {
                File mf = new File(dir, "kit.md5");
                try (FileOutputStream fos = new FileOutputStream(mf)) {
                    fos.write(man.toString().getBytes("UTF-8"));
                    fos.getFD().sync();
                }
                log("私有套件镜像已校验 → " + dir.getAbsolutePath());
            }
            return ok;
        } catch (Throwable t) { log("私有套件镜像同步失败: " + t); return false; }
    }

    private String readOneLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String l = r.readLine();
            return l == null ? null : l.trim();
        } catch (Throwable t) { return null; }
    }

    private void log(String s) {
        Log.i(TAG, s);
        ui.post(() -> {
            logBuf.append(s).append('\n');
            if (logBuf.length() > 200000) logBuf.delete(0, 50000);
            if (pageLog.getVisibility() == View.VISIBLE) logView.setText(logBuf.toString());
        });
        if (logFile != null) {
            try (FileOutputStream fo = new FileOutputStream(logFile, true)) {
                String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
                fo.write((ts + " " + s + "\n").getBytes("UTF-8"));
            } catch (Throwable ignored) { }
        }
    }

    // ================================================================== 流程

    private void resetPerRunState() {
        tickNo = 0;
        serverSeenAt = 0;
        starterSeenAt = 0;
        startRetries = 0;
        binderHandled = false;
        askedPermission = false;
        uiRunning = false;
        starterOk = false;
        launchedShizuku = false;
        permDoneAt = 0;
    }

    private void go(int m) {
        if (busy || running) { snack("正在执行中，已忽略重复点击"); return; }
        mode = m;
        busy = true;
        setBusyUi(true);
        resetPerRunState();
        setupSteps(m == MODE_ROOT ? STEPS_ROOT : STEPS_START);
        startTimer();
        log(m == MODE_ROOT ? "---- 一键越狱 ----" : "---- 启动 Shizuku ----");
        armed = true;
        setState(S_CHECK);
        setStep(0, ACTIVE);
        getSharedPreferences("gl", MODE_PRIVATE).edit()
                .putBoolean("armed", true).putInt("mode", m).commit();

        if (m == MODE_ROOT) {
            // 一键越狱：只要求 Shizuku 已就绪（无障碍不参与本流程）。
            if (!pingBinderSafe()) {
                setStep(0, FAILED);
                setState(S_FAIL);
                snack("Shizuku 未运行：请先点「启动 Shizuku」");
                return;
            }
            setStep(0, DONE);
            runKit();
            return;
        }

        // 启动 Shizuku：需要无障碍服务来点「启动」。
        if (!ShizukuStartService.ready()) {
            if (A11yKeeper.canWrite(this)) {
                log("无障碍服务未运行（系统关闭），正在自动恢复…");
                A11yKeeper.restore(this);
                waitA11y(0);
                return;
            }
            setStep(0, FAILED);
            setState(S_FAIL);
            snack("无障碍服务未开启：请在 设置 → 无障碍 中开启「一键GLKS」");
            openA11ySettings();
            return;
        }
        startFlow();
    }

    private void resumeRun() {
        if (running || busy) return;
        mode = getSharedPreferences("gl", MODE_PRIVATE).getInt("mode", MODE_ROOT);
        busy = true;
        setBusyUi(true);
        setupSteps(mode == MODE_ROOT ? STEPS_ROOT : STEPS_START);
        if (mode == MODE_ROOT) {
            if (!pingBinderSafe()) { setState(S_FAIL); return; }
            setStep(0, DONE);
            runKit();
            return;
        }
        if (!ShizukuStartService.ready() && A11yKeeper.canWrite(this)) {
            A11yKeeper.restore(this);
            waitA11y(0);
            return;
        }
        startFlow();
    }

    private void waitA11y(final int n) {
        if (ShizukuStartService.ready()) {
            log("无障碍服务已自动恢复");
            startFlow();
            return;
        }
        if (n > 12) {
            setStep(0, FAILED);
            setState(S_FAIL);
            snack("自动恢复无障碍失败：请手动到 设置 → 无障碍 开启 一键GLKS");
            openA11ySettings();
            return;
        }
        if (n % 5 == 0) A11yKeeper.restore(this);
        ui.postDelayed(() -> waitA11y(n + 1), TICK_MS);
    }

    private void openA11ySettings() {
        try {
            Intent st = new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
            st.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(st);
        } catch (Throwable t) { log("打开无障碍设置失败: " + t); }
    }

    private void startFlow() {
        setStep(0, DONE);
        startPolling();

        if (pingBinderSafe()) {
            setStep(1, DONE);
            setState(S_WAIT);
            log("Shizuku 服务已在运行");
            if (!binderHandled) { binderHandled = true; onShizukuReady(); }
            return;
        }

        setState(S_START);
        setStep(1, ACTIVE);
        launchedShizuku = true;
        log("Shizuku 未运行：打开它，并由监控自动点“启动”…");
        try {
            Intent i = new Intent();
            i.setClassName(SHIZUKU_PKG, "moe.shizuku.manager.MainActivity");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable t) { log("打开 Shizuku 失败: " + t); }
        ShizukuStartService.resetGuards();
    }

    private boolean pingBinderSafe() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    private void startPolling() {
        if (polling || running) return;
        polling = true;
        ui.post(POLL);
    }

    private void stopPolling() {
        polling = false;
        ui.removeCallbacks(POLL);
    }

    private void tick() {
        if (!polling) return;
        if (running) { stopPolling(); return; }
        if (tickNo > MAX_TICKS) {
            log("监控超时(" + (MAX_TICKS * TICK_MS / 1000) + "s)：请手动启动 Shizuku 后重试");
            setState(S_FAIL);
            getSharedPreferences("gl", MODE_PRIVATE).edit().putBoolean("armed", false).commit();
            stopPolling();
            snack("等待 Shizuku 就绪超时");
            return;
        }
        int n = tickNo++;

        if (n % 10 == 0 && !ShizukuStartService.ready() && A11yKeeper.canWrite(this)) {
            log("无障碍服务掉线，自动恢复…");
            A11yKeeper.restore(this);
        }

        String seen = ShizukuStartService.pollAndAct();
        String sig = seen.length() > 220 ? seen.substring(0, 220) + "…" : seen;
        if (!sig.equals(lastSeen) || n % 15 == 0) log("[" + n + "] " + sig);
        lastSeen = sig;
        if (seen.contains("点击:启动")) { setStep(1, DONE); setState(S_WAIT); }
        if (seen.equals(ShizukuStartService.STARTED)) uiRunning = true;
        if (seen.startsWith("STARTER_OK") && !starterOk) {
            starterOk = true;
            log("启动器报告 Service started —— 直接判定就绪（省掉回首页那 3 秒）");
        }
        if (seen.contains("启动失败") && startRetries < 3) {
            startRetries++;
            starterSeenAt = 0;
            log("Shizuku 启动失败，重试(" + startRetries + "/3)： " + seen);
            ShizukuStartService.resetGuards();
            ui.postDelayed(POLL, 600);
            return;
        }

        if (pingBinderSafe()) {
            if (!binderHandled) {
                binderHandled = true;
                log("Shizuku 服务已就绪 (binder)");
                setStep(1, DONE);
                onShizukuReady();
            }
        } else if (seen.contains("STARTED")) {
            long now = System.currentTimeMillis();
            if (serverSeenAt == 0) serverSeenAt = now;
            if (now - serverSeenAt > 12000) { restartSelf(); return; }
        } else if (seen.contains("启动器等待中")) {
            long now = System.currentTimeMillis();
            if (starterSeenAt == 0) starterSeenAt = now;
            if (now - starterSeenAt > 12000) {
                starterSeenAt = 0;
                serverSeenAt = 0;
                if (startRetries >= 3) {
                    log("Shizuku 启动器连续 3 次失败，请手动在 Shizuku 里点「启动」");
                    setState(S_FAIL);
                    stopPolling();
                    snack("Shizuku 启动器连续失败：请手动在 Shizuku 里点「启动」");
                    return;
                }
                startRetries++;
                log("启动器卡住 20s，重试点「启动」(" + startRetries + "/3)");
                ShizukuStartService.back();
                ShizukuStartService.resetGuards();
            }
        }
        ui.postDelayed(POLL, TICK_MS);
    }

    private void restartSelf() {
        long now = System.currentTimeMillis();
        SharedPreferences sp = getSharedPreferences("gl", MODE_PRIVATE);
        int count = sp.getInt("restarts", 0);
        long last = sp.getLong("lastRestart", 0);
        if (count >= 3 || (last != 0 && now - last < 15000)) {
            log("已重启 " + count + " 次仍未拿到 binder。请手动关掉再打开本应用。");
            setState(S_FAIL);
            stopPolling();
            snack("Shizuku 已运行但未收到 binder：请手动重开本应用");
            return;
        }
        sp.edit().putInt("restarts", count + 1).putLong("lastRestart", now).commit();
        log("Shizuku 已运行但未收到 binder —— 重启本应用以重新触发推送(" + (count + 1) + "/3)");
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        } catch (Throwable t) { log("重启自身失败: " + t); return; }
        ui.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 500);
    }

    private void onShizukuReady() {
        if (running) return;
        getSharedPreferences("gl", MODE_PRIVATE).edit().remove("restarts").remove("lastRestart").apply();
        if (mode == MODE_START) {
            setState(S_PERM);
            setStep(2, ACTIVE);
        }

        try {
            log("Shizuku 版本=" + Shizuku.getVersion() + " preV11=" + Shizuku.isPreV11());
        } catch (Throwable t) {
            log("读取版本失败: " + t);
            binderHandled = false;
            return;
        }

        boolean granted = false;
        try {
            granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            log("checkSelfPermission 异常: " + t);
            binderHandled = false;
            return;
        }

        if (granted) {
            log("已获得 Shizuku 授权");
            if (mode == MODE_START) setStep(2, DONE);
            permDoneAt = SystemClock.elapsedRealtime();
            awaitUiConfirm();
            return;
        }
        if (askedPermission) return;
        askedPermission = true;

        try {
            log("申请 Shizuku 授权 …（监控会自动点“始终允许”）");
            Shizuku.requestPermission(PERM_CODE);
        } catch (Throwable t) {
            log("requestPermission 异常: " + t);
            askedPermission = false;
            binderHandled = false;
            return;
        }
        startPolling();
    }

    private void runKit() {
        if (running) return;
        running = true;
        getSharedPreferences("gl", MODE_PRIVATE).edit().putBoolean("armed", false).commit();
        stopPolling();
        mode = MODE_ROOT;
        setState(S_RUN);
        setStep(0, DONE);
        setStep(1, ACTIVE);

        new Thread(() -> {
            try {
                if (!syncPrivateKit()) {
                    log("私有套件镜像校验失败，已中止");
                    setStep(2, FAILED);
                    setState(S_FAIL);
                    snack("套件镜像校验失败，请重装应用后重试");
                    return;
                }

                // ---- 设备状态门槛：仅开机>=60s + 可用内存>=4GB ----
                String gate = rikka.shizuku.ShizukuShellHelper.run(
                        "u=$(cut -d. -f1 /proc/uptime 2>/dev/null); " +
                        "m=$(grep MemAvailable /proc/meminfo 2>/dev/null | awk '{print $2}'); " +
                        "echo \"$u $m\"").trim();
                log("设备门槛: " + gate);
                String why = null;
                try {
                    String[] gp = gate.split("\\s+");
                    long u = Long.parseLong(gp[0]), m = Long.parseLong(gp[1]);
                    if (u < GATE_MIN_UPTIME_S) why = "开机仅 " + u + "s（需 ≥ " + GATE_MIN_UPTIME_S + "s）";
                    else if (m < GATE_MIN_MEM_KB) why = String.format(Locale.US,
                            "可用内存仅 %.1f GB（需 ≥ 4 GB）", m / 1048576f);
                } catch (Throwable e) { why = "读取设备状态失败: " + e; }
                if (why != null) {
                    log("拒绝执行临时 root：" + why);
                    setStep(1, FAILED);
                    setState(S_FAIL);
                    snack("设备状态不合格：" + why);
                    return;
                }
                setStep(1, DONE);
                setStep(2, ACTIVE);

                // ---- 套件只来自 APK 私有目录：按 kit.md5 校验后落到 /data/local/tmp ----
                File kit = new File(getExternalFilesDir(null), "kit");
                String kd = kit.getAbsolutePath();
                String prep = rikka.shizuku.ShizukuShellHelper.run(
                        "sh '" + kd + "/stage_kit.sh' '" + kd + "' 2>&1; echo ST_RC=$?");
                log(prep.trim());
                if (!prep.contains("KIT_OK")) {
                    log("套件落盘校验失败（私有镜像与 APK 不一致或文件损坏），已中止");
                    setStep(2, FAILED);
                    setState(S_FAIL);
                    snack("套件落盘校验失败，请重装应用后重试");
                    return;
                }
                setStep(2, DONE);
                setStep(3, ACTIVE);

                log("以 shell(2000) 身份执行套件 …");
                final boolean[] done = {false};
                final boolean[] sawModule = {false};
                rikka.shizuku.ShizukuShellHelper.runStreaming("sh " + KIT_SCRIPT + " 2>&1", line -> {
                    log(line);
                    int at = line.indexOf("exploit stage ");
                    if (at >= 0) {
                        String frac = line.substring(at + "exploit stage ".length()).trim();
                        setStepLabel(3, "临时 root（" + frac + "）");
                    }
                    if (line.contains("waiting for the KSU module") && !sawModule[0]) {
                        sawModule[0] = true;
                        setStep(3, DONE);
                        setStep(4, ACTIVE);
                    }
                    if (line.contains("[+] kernelsu loaded")) {
                        if (!sawModule[0]) { sawModule[0] = true; setStep(3, DONE); }
                        setStep(4, DONE);
                        setStep(5, ACTIVE);
                    }
                    if (line.contains("uid=0(root)") && !done[0]) {
                        done[0] = true;
                        setStep(5, DONE);
                        setStep(6, ACTIVE);
                    }
                });

                if (!done[0]) {
                    failCurrentStep();
                    setState(S_FAIL);
                    snack("越狱未完成：请查看日志");
                    return;
                }
                teardownAfterRootBlocking();
                setStep(6, DONE);
                setStep(7, DONE);
                setState(S_DONE);
                snack("越狱完成：已关闭无线调试并停止 Shizuku");
            } catch (Throwable t) {
                log("执行异常: " + t);
                setState(S_FAIL);
                snack("执行异常，请查看日志");
            } finally {
                log("---- 流程结束 ----");
                running = false;
                if (state != S_DONE && state != S_FAIL) setState(S_FAIL);
                updateButtons();
            }
        }, "kit").start();
    }

    /** 一键越狱成功后收尾：关闭无线调试、停掉 Shizuku 服务与 App。 */
    private void teardownAfterRootBlocking() {
        try {
            String wifi = rikka.shizuku.ShizukuShellHelper.run(
                    "settings put global adb_wifi_enabled 0; echo wifi_debug_off").trim();
            log("收尾 · 关闭无线调试: " + wifi);
            if (pingBinderSafe()) {
                String stop = rikka.shizuku.ShizukuShellHelper.run(
                        "am force-stop " + SHIZUKU_PKG + "; pkill -f shizuku_server 2>/dev/null; echo shizuku_stopped").trim();
                log("收尾 · 停止 Shizuku: " + stop);
            } else {
                log("收尾 · Shizuku 已不可用（视为已停止）");
            }
        } catch (Throwable t) { log("收尾异常: " + t); }
    }

    private void bringSelfToFront() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            log("已切回本应用（直接拉起）");
        } catch (Throwable t) { log("回到前台失败: " + t); }

        new Thread(() -> {
            try {
                String r = rikka.shizuku.ShizukuShellHelper.run(
                        "am start -n " + getPackageName() + "/" + MainActivity.class.getName()
                                + " -f 0x20000000 2>&1 | head -2");
                log("shell 兜底拉起: " + r.trim());
            } catch (Throwable t) { log("shell 兜底失败: " + t); }
        }, "front").start();
    }

    private void setBusyUi(boolean b) {
        ui.post(() -> {
            if (startBtn != null) startBtn.setEnabled(!b);
            if (rootBtn != null) rootBtn.setEnabled(!b);
            updateButtons();
        });
    }

    private void awaitUiConfirm() {
        if (!launchedShizuku) { finishRun(); return; }
        setState(S_WAIT);
        long waited = SystemClock.elapsedRealtime() - permDoneAt;
        if (starterOk) {
            log("UI 已确认（启动器 Service started，用时 " + waited + "ms）");
            finishRun();
            return;
        }
        if (uiRunning) {
            log("UI 已确认 Shizuku 正在运行（用时 " + waited + "ms）");
            finishRun();
            return;
        }
        if (waited > 8000) {
            log("8s 内未等到 UI 确认，按 binder 继续（服务已可用的判据）");
            finishRun();
            return;
        }
        ui.postDelayed(this::awaitUiConfirm, 200);
    }

    private void finishRun() {
        bringSelfToFront();
        if (getIntent() != null && getIntent().getBooleanExtra("dryrun", false)) {
            log("[dryrun] 停在 Shizuku 阶段，不执行套件");
            setState(S_DONE);
            return;
        }
        if (mode == MODE_START) {
            setStep(0, DONE);
            setStep(1, DONE);
            setStep(2, DONE);
            setStep(3, DONE);
            setState(S_DONE);
            snack("Shizuku 已就绪：现在可以点「一键越狱」");
            log("Shizuku 已就绪（本步不执行套件）");
            return;
        }
        runKit();
    }
}
