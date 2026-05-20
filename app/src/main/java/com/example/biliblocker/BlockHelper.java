package com.example.biliblocker;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

public class BlockHelper {

    private static final String TAG = "BiliBlocker";
    private static final String BLOCK_API = "https://api.bilibili.com/x/relation/modify";
    private static String sCachedCsrf = null;
    private static String sCachedCookieHeader = null;

    public interface BlockCallback {
        void onSuccess(String toast);
        void onError(String error);
    }

    public static boolean isAlreadyBlockedMessage(String message) {
        if (message == null) return false;
        return message.contains("已经拉黑")
                || message.contains("已拉黑")
                || message.contains("不能再拉黑")
                || message.toLowerCase().contains("already block");
    }

    public static void cacheCsrf(String csrf) {
        if (csrf != null && !csrf.isEmpty()) {
            sCachedCsrf = csrf;
        }
    }

    public static void cacheCookieHeader(String cookieHeader) {
        if (cookieHeader == null || cookieHeader.trim().isEmpty()) return;
        sCachedCookieHeader = normalizeCookieHeader(cookieHeader);
        String csrf = extractCookieValue(sCachedCookieHeader, "bili_jct");
        if (csrf != null && !csrf.isEmpty()) {
            sCachedCsrf = csrf;
        }
    }

    public static void blockUser(Context context, String mid, BlockCallback callback) {
        if (context == null || mid == null) {
            if (callback != null) callback.onError("参数错误");
            return;
        }

        String csrf = getCsrf(context);
        if (csrf == null || csrf.isEmpty()) {
            if (callback != null) callback.onError("无法获取CSRF Token");
            return;
        }

        String cookieHeader = getAuthCookieHeader();
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            if (callback != null) callback.onError("无法获取登录Cookie");
            return;
        }

        executeBlock(mid, csrf, cookieHeader, callback);
    }

    private static String getCsrf(Context context) {
        if (sCachedCsrf != null) return sCachedCsrf;
        if (sCachedCookieHeader != null) {
            String csrf = extractCookieValue(sCachedCookieHeader, "bili_jct");
            if (csrf != null && !csrf.isEmpty()) {
                sCachedCsrf = csrf;
                return csrf;
            }
        }

        // Try all known cookie database paths
        for (String path : ALL_COOKIE_PATHS) {
            String csrf = readCookieFromFile(path);
            if (csrf != null) { sCachedCsrf = csrf; return csrf; }
        }

        // Try system CookieManager as last resort
        String csrf = getCsrfFromCookieManager();
        if (csrf != null) { sCachedCsrf = csrf; return csrf; }

        ModuleLog.w("CSRF: all methods exhausted", true);
        return null;
    }

    private static String getAuthCookieHeader() {
        if (sCachedCookieHeader != null && !sCachedCookieHeader.isEmpty()) {
            return sCachedCookieHeader;
        }

        String cookieHeader = readAuthCookieHeaderFromDb();
        if (cookieHeader != null && !cookieHeader.isEmpty()) {
            cacheCookieHeader(cookieHeader);
            return sCachedCookieHeader;
        }

        return null;
    }

    /** Force early CSRF initialization at hook load time */
    public static void tryInitCsrf() {
        if (sCachedCsrf != null) return;
        for (String path : ALL_COOKIE_PATHS) {
            String csrf = readCookieFromFile(path);
            if (csrf != null) {
                sCachedCsrf = csrf;
                ModuleLog.i("CSRF loaded from " + path, true);
                return;
            }
        }
    }

    private static final String[] ALL_COOKIE_PATHS = {
        "/data/data/tv.danmaku.bili/app_webview_tv.danmaku.bili/Default/Cookies",
        "/data/data/tv.danmaku.bili/app_webview_tv.danmaku.bili_web/Default/Cookies",
        "/data/data/tv.danmaku.bili/app_webview/Default/Cookies",
        "/data/data/tv.danmaku.bili/cache/WebView/Cookies",
        "/data/data/tv.danmaku.bili/databases/webview.db",
        "/data/data/tv.danmaku.bili/databases/webviewCookiesChromium.db"
    };

    private static String readCookieFromFile(String dbPath) {
        java.io.File f = new java.io.File(dbPath);
        if (!f.exists() || !f.canRead()) {
            ModuleLog.d("CSRF-DBG: file not found/unreadable: " + dbPath);
            return null;
        }
        ModuleLog.d("CSRF-DBG: trying " + dbPath + " size=" + f.length());
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY);
            if (db == null) {
                ModuleLog.d("CSRF-DBG: openDatabase returned null: " + dbPath);
                return null;
            }

            // First check what tables exist
            cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
            boolean hasCookies = false;
            while (cursor != null && cursor.moveToNext()) {
                String tbl = cursor.getString(0);
                if ("cookies".equals(tbl)) hasCookies = true;
            }
            if (cursor != null) { cursor.close(); cursor = null; }
            if (!hasCookies) {
                ModuleLog.d("CSRF-DBG: no cookies table in " + dbPath);
                db.close(); db = null;
                return null;
            }

            // Try exact host match first
            cursor = db.rawQuery(
                    "SELECT value FROM cookies WHERE name=? AND (host_key=? OR host_key=?)",
                    new String[]{"bili_jct", ".bilibili.com", "bilibili.com"});

            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                if (value != null && !value.isEmpty()) {
                    ModuleLog.i("CSRF from " + dbPath, true);
                    return value;
                }
            }
            cursor.close(); cursor = null;

            // Fallback: LIKE
            cursor = db.rawQuery(
                    "SELECT value FROM cookies WHERE name=? AND host_key LIKE ?",
                    new String[]{"bili_jct", "%.bilibili.com%"});

            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                if (value != null && !value.isEmpty()) {
                    ModuleLog.i("CSRF from " + dbPath + " (LIKE fallback)", true);
                    return value;
                }
            }
        } catch (Throwable t) {
            ModuleLog.e("readCookie(" + dbPath + "): " + t, true);
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Throwable ignored) {}
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
        ModuleLog.d("CSRF-DBG: no bili_jct in " + dbPath);
        return null;
    }

    private static String getCsrfFromCookieManager() {
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);

            String[] domains = {
                ".bilibili.com", "https://.bilibili.com",
                "https://bilibili.com", "bilibili.com",
                "https://www.bilibili.com", "www.bilibili.com",
                "https://api.bilibili.com", "api.bilibili.com"
            };
            for (String domain : domains) {
                try {
                    String cookies = cm.getCookie(domain);
                    if (cookies != null && !cookies.isEmpty()) {
                        for (String c : cookies.split(";")) {
                            String[] parts = c.trim().split("=", 2);
                            if (parts.length == 2 && "bili_jct".equals(parts[0].trim())) {
                                sCachedCsrf = parts[1].trim();
                                ModuleLog.i("CSRF from CookieManager domain=" + domain, true);
                                return sCachedCsrf;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            ModuleLog.e("CookieManager error: " + t, true);
        }
        return null;
    }

    private static void executeBlock(String mid, String csrf, String cookieHeader, BlockCallback callback) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(BLOCK_API);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 BiliApp/8930400");
                conn.setRequestProperty("Referer", "https://www.bilibili.com");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("Cookie", cookieHeader);

                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                String params = "fid=" + URLEncoder.encode(mid, "UTF-8")
                        + "&act=5"
                        + "&csrf=" + URLEncoder.encode(csrf, "UTF-8");

                try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
                    dos.writeBytes(params);
                    dos.flush();
                }

                int responseCode = conn.getResponseCode();
                BufferedReader reader;
                if (responseCode >= 200 && responseCode < 300) {
                    reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                } else {
                    reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                }

                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject json = new JSONObject(sb.toString());
                int apiCode = json.optInt("code", -1);
                String message = json.optString("message", "");

                if (apiCode == 0) {
                    sCachedCsrf = null;
                    String toast = json.optString("toast", "");
                    if (callback != null) callback.onSuccess(toast);
                } else if (isAlreadyBlockedMessage(message)) {
                    ModuleLog.w("blockUser already blocked mid=" + mid + " msg=" + message, true);
                    if (callback != null) callback.onSuccess(message);
                } else {
                    if (callback != null) callback.onError(message);
                }
            } catch (Exception e) {
                ModuleLog.e("executeBlock error: " + e, true);
                if (callback != null) callback.onError(e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private static String readCookieValue(String name) {
        Map<String, String> cookies = new LinkedHashMap<>();
        fillCookieMapFromKnownPaths(cookies);
        return cookies.get(name);
    }

    private static String readAuthCookieHeaderFromDb() {
        Map<String, String> cookies = new LinkedHashMap<>();
        fillCookieMapFromKnownPaths(cookies);
        if (cookies.isEmpty()) return null;
        return buildCookieHeader(cookies);
    }

    private static void fillCookieMapFromKnownPaths(Map<String, String> cookies) {
        String[] paths = {
            "/data/data/tv.danmaku.bili/app_webview_tv.danmaku.bili/Default/Cookies",
            "/data/data/tv.danmaku.bili/app_webview_tv.danmaku.bili_web/Default/Cookies"
        };
        for (String dbPath : paths) {
            readCookiesIntoMap(dbPath, cookies);
        }
    }

    private static void readCookiesIntoMap(String dbPath, Map<String, String> out) {
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY);
            if (db == null) return;

            cursor = db.rawQuery(
                    "SELECT name, value, host_key FROM cookies WHERE host_key LIKE ? OR host_key LIKE ?",
                    new String[]{"%bilibili.com%", "%bilivideo.com%"});

            while (cursor != null && cursor.moveToNext()) {
                String name = cursor.getString(0);
                String value = cursor.getString(1);
                if (name == null || value == null || value.isEmpty()) continue;
                if (!isAuthCookieName(name)) continue;
                if (!out.containsKey(name)) {
                    out.put(name, value);
                }
            }
        } catch (Throwable t) {
            ModuleLog.e("readCookiesIntoMap(" + dbPath + "): " + t, true);
        } finally {
            if (cursor != null) try { cursor.close(); } catch (Throwable ignored) {}
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }
    }

    private static boolean isAuthCookieName(String name) {
        return "SESSDATA".equals(name)
                || "bili_jct".equals(name)
                || "DedeUserID".equals(name)
                || "DedeUserID__ckMd5".equals(name)
                || "sid".equals(name)
                || "buvid3".equals(name)
                || "buvid4".equals(name);
    }

    private static String buildCookieHeader(Map<String, String> cookies) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String normalizeCookieHeader(String cookieHeader) {
        StringBuilder sb = new StringBuilder();
        for (String part : cookieHeader.split(";")) {
            String trimmed = part == null ? "" : part.trim();
            if (trimmed.isEmpty()) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(trimmed);
        }
        return sb.toString();
    }

    private static String extractCookieValue(String cookieHeader, String name) {
        if (cookieHeader == null || name == null) return null;
        for (String part : cookieHeader.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && name.equals(kv[0].trim())) {
                return kv[1].trim();
            }
        }
        return null;
    }
}
