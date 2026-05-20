package com.example.biliblocker;

import android.app.Activity;
import android.app.Application;
import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final int MAX_STATUS_CHARS = 4096;
    private static String sHookStatus = "";
    private static int sForceLogSeq = 0;
    private static boolean sAppInitHooked = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals("tv.danmaku.bili")
                && !lpparam.packageName.equals("com.bilibili.app.blue")) {
            return;
        }

        hookApplicationInit(lpparam);
        forceLog("loadPackage process=" + lpparam.processName);
        CommentHook.init(lpparam);
        hookAlwaysOnLog(lpparam);
        // hookStartupToast disabled per user request
    }

    static void logHookStatus(String msg) {
        ModuleLog.i(msg, true);
        if (sHookStatus.length() >= MAX_STATUS_CHARS) {
            return;
        }
        if (sHookStatus.length() + msg.length() + 1 > MAX_STATUS_CHARS) {
            sHookStatus += "...";
            return;
        }
        sHookStatus += msg + "\n";
    }

    static void forceLog(String msg) {
        String line = "FORCE#" + (++sForceLogSeq) + " " + msg
                + " pid=" + android.os.Process.myPid()
                + " thread=" + Thread.currentThread().getName();
        ModuleLog.w(line, true);
    }

    private static void hookApplicationInit(XC_LoadPackage.LoadPackageParam lpparam) {
        if (sAppInitHooked) return;
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Application application = (Application) param.thisObject;
                    boolean mainProcess = !lpparam.processName.contains(":");
                    ModuleLog.init(application, mainProcess);
                    ModuleLog.i("Application.onCreate " + application.getClass().getName()
                            + " process=" + lpparam.processName, true);
                }
            });
            sAppInitHooked = true;
            logHookStatus("✓ LogInit(Application.onCreate)");
        } catch (Throwable t) {
            logHookStatus("✗ LogInit(Application.onCreate): " + t);
        }
    }

    private static void hookAlwaysOnLog(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    String activityName = activity.getClass().getName();
                    forceLog("Activity.onResume " + activityName);

                    try {
                        View decorView = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
                        if (decorView != null) {
                            decorView.postDelayed(() ->
                                    forceLog("postResumeTick " + activityName), 1500L);
                        }
                    } catch (Throwable t) {
                        ModuleLog.e("Failed to post delayed force log", t);
                    }
                }
            });
            logHookStatus("✓ ForceLog(Activity.onResume)");
        } catch (Throwable t) {
            logHookStatus("✗ ForceLog(Activity.onResume): " + t);
        }
    }

}
