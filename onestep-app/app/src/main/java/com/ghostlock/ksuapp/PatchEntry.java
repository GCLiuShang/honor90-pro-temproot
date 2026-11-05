package com.ghostlock.ksuapp;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * LSPosed entry point for the "安全补丁" module.
 *
 * Loaded by LSPosed into each scoped process (trustspace / securityserver /
 * parallelspace / findmydevice). Every hook is opt-in at runtime through the
 * patch_config SharedPreferences written by PatchSettingsActivity.
 */
public class PatchEntry implements IXposedHookLoadPackage {

    public static final String PREFS = "patch_config";
    public static final String KEY_MASTER = "master";
    public static final String KEY_TRUSTSPACE = "trustspace";
    public static final String KEY_SECURITYSERVER = "securityserver";
    public static final String KEY_PARALLELSPACE = "parallelspace";
    public static final String KEY_FINDMY = "findmy";

    private static final String TAG = "GLKS-Patch: ";

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        try {
            XSharedPreferences p = new XSharedPreferences("com.ghostlock.ksuapp", PREFS);
            p.reload();
            if (!p.getBoolean(KEY_MASTER, true)) return;

            switch (lp.packageName) {
                case "com.hihonor.trustspace":
                    if (p.getBoolean(KEY_TRUSTSPACE, true)) hookTrustSpace(lp);
                    break;
                case "com.hihonor.securityserver":
                    if (p.getBoolean(KEY_SECURITYSERVER, true)) hookSecurityServer(lp);
                    break;
                case "com.hihonor.parallelspace":
                    if (p.getBoolean(KEY_PARALLELSPACE, true)) hookParallelSpace(lp);
                    break;
                case "com.hihonor.findmydevice":
                    if (p.getBoolean(KEY_FINDMY, false)) hookFindMy(lp);
                    break;
                default:
                    break;
            }
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    /** Payment protection: HwSecurityDiagnoseManager#getRootStatusSync() -> 0. */
    private void hookTrustSpace(LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.hihonor.android.securitydiagnose.HwSecurityDiagnoseManager",
                    lp.classLoader, "getRootStatusSync", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(0);
                        }
                    });
            XposedBridge.log(TAG + "trustspace getRootStatusSync -> 0 installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "trustspace hook failed");
            XposedBridge.log(t);
        }
    }

    /** Asset write (set/reset space password): HwKeystoreServiceImpl#isRoot() -> false. */
    private void hookSecurityServer(LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.hihonor.securityserver.HwKeystoreServiceImpl",
                    lp.classLoader, "isRoot", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(false);
                        }
                    });
            XposedBridge.log(TAG + "securityserver isRoot -> false installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "securityserver hook failed");
            XposedBridge.log(t);
        }
    }

    /** Parallel space app-level root gate: ApplicationUtil#isRoot() -> false. */
    private void hookParallelSpace(LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.hihonor.parallelspace.common.util.ApplicationUtil",
                    lp.classLoader, "isRoot", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(false);
                        }
                    });
            XposedBridge.log(TAG + "parallelspace isRoot -> false installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "parallelspace hook failed");
            XposedBridge.log(t);
        }
    }

    /** Optional: report "not rooted" from Find Device (BI/cloud only). */
    private void hookFindMy(LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.hihonor.base.common.SystemUtil",
                    lp.classLoader, "isRoot", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(false);
                        }
                    });
            XposedBridge.log(TAG + "findmy isRoot -> false installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "findmy hook failed");
            XposedBridge.log(t);
        }
    }
}
