package yuki.biliblocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;

import de.robv.android.xposed.XposedBridge;

public final class ModuleLog {

    private static final String TAG = "BiliBlocker";
    private static final String PREFS_NAME = "bili_blocker_debug";
    private static final String KEY_SHOW_INFO = "show_info";
    private static final String KEY_SAVE_LOG = "save_log";
    private static final String LOG_FILE_NAME = "bili_blocker_log.txt";
    private static final String OLD_LOG_FILE_NAME = "bili_blocker_old_log.txt";
    private static final int MAX_LENGTH = 3000;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static Context sAppContext;
    private static Toast sToast;
    private static boolean sLogCaptureStarted = false;
    private static boolean sMainProcess = false;

    private ModuleLog() {
    }

    public static synchronized void init(Context context, boolean isMainProcess) {
        if (context == null) return;
        if (sAppContext == null) {
            sAppContext = context.getApplicationContext();
        }
        if (!sMainProcess && isMainProcess) {
            sMainProcess = true;
        }
        if (sMainProcess && shouldSaveLog() && !sLogCaptureStarted) {
            startLogCapture();
        }
    }

    public static void toast(String msg) {
        toast(msg, false, true);
    }

    public static void toast(String msg, boolean force, boolean alsoLog) {
        if (!force && !shouldShowInfo()) return;
        Context context = sAppContext;
        if (context != null) {
            MAIN_HANDLER.post(() -> {
                try {
                    if (sToast != null) {
                        sToast.cancel();
                    }
                    sToast = Toast.makeText(context, "BiliBlocker: " + msg, Toast.LENGTH_SHORT);
                    sToast.show();
                } catch (Throwable t) {
                    e("toast failed", t);
                }
            });
        }
        if (alsoLog) {
            w(msg);
        }
    }

    public static void d(String msg) {
        d(msg, false);
    }

    public static void d(String msg, boolean toXposed) {
        log(android.util.Log.DEBUG, msg, toXposed);
    }

    public static void i(String msg) {
        i(msg, false);
    }

    public static void i(String msg, boolean toXposed) {
        log(android.util.Log.INFO, msg, toXposed);
    }

    public static void w(String msg) {
        w(msg, false);
    }

    public static void w(String msg, boolean toXposed) {
        log(android.util.Log.WARN, msg, toXposed);
    }

    public static void e(String msg) {
        e(msg, true);
    }

    public static void e(String msg, boolean toXposed) {
        log(android.util.Log.ERROR, msg, toXposed);
    }

    public static void e(String msg, Throwable throwable) {
        String detail = msg;
        if (throwable != null) {
            detail += "\n" + android.util.Log.getStackTraceString(throwable);
        }
        e(detail, true);
    }

    public static String getLogFilePath() {
        File file = getLogFile();
        return file == null ? null : file.getAbsolutePath();
    }

    public static String getOldLogFilePath() {
        File file = getOldLogFile();
        return file == null ? null : file.getAbsolutePath();
    }

    private static void log(int priority, String msg, boolean toXposed) {
        String text = msg == null ? "null" : msg;
        if (text.length() > MAX_LENGTH) {
            for (int start = 0; start < text.length(); start += MAX_LENGTH) {
                int end = Math.min(text.length(), start + MAX_LENGTH);
                log(priority, text.substring(start, end), toXposed);
            }
            return;
        }
        android.util.Log.println(priority, TAG, text);
        if (toXposed) {
            XposedBridge.log(TAG + ": " + text);
        }
    }

    private static synchronized void startLogCapture() {
        if (sLogCaptureStarted) return;
        File logFile = getLogFile();
        File oldLogFile = getOldLogFile();
        if (logFile == null || oldLogFile == null) {
            w("skip log capture: log path unavailable", true);
            return;
        }
        try {
            if (logFile.exists()) {
                if (oldLogFile.exists() && !oldLogFile.delete()) {
                    w("failed to delete old log file: " + oldLogFile.getAbsolutePath(), true);
                }
                if (!logFile.renameTo(oldLogFile)) {
                    w("failed to rotate log file: " + logFile.getAbsolutePath(), true);
                }
            }
            if (logFile.exists() && !logFile.delete()) {
                w("failed to clear log file: " + logFile.getAbsolutePath(), true);
            }
            File parent = logFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (!logFile.exists()) {
                logFile.createNewFile();
            }
            Runtime.getRuntime().exec(new String[]{
                    "logcat",
                    "-T",
                    "100",
                    "-f",
                    logFile.getAbsolutePath()
                    });
            sLogCaptureStarted = true;
            i("log capture started -> " + logFile.getAbsolutePath(), true);
        } catch (Throwable t) {
            e("startLogCapture failed", t);
        }
    }

    private static boolean shouldShowInfo() {
        SharedPreferences prefs = getPrefs();
        return prefs == null || prefs.getBoolean(KEY_SHOW_INFO, true);
    }

    private static boolean shouldSaveLog() {
        SharedPreferences prefs = getPrefs();
        return prefs == null || prefs.getBoolean(KEY_SAVE_LOG, true);
    }

    private static SharedPreferences getPrefs() {
        Context context = sAppContext;
        if (context == null) return null;
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static File getLogFile() {
        Context context = sAppContext;
        if (context == null) return null;
        File dir = context.getExternalCacheDir();
        if (dir == null) {
            dir = context.getCacheDir();
        }
        return dir == null ? null : new File(dir, LOG_FILE_NAME);
    }

    private static File getOldLogFile() {
        Context context = sAppContext;
        if (context == null) return null;
        File dir = context.getExternalCacheDir();
        if (dir == null) {
            dir = context.getCacheDir();
        }
        return dir == null ? null : new File(dir, OLD_LOG_FILE_NAME);
    }
}
