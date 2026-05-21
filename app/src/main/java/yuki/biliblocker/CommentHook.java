package yuki.biliblocker;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommentHook {

    private static final int TAG_COMMENT_ROOT_MARKER = 0x5f0a1005;
    private static final int TAG_BOUND_MID = 0x5f0a1006;

    private static final Pattern MID_TEXT_PATTERN = Pattern.compile(
            "(?:^|[,(\\s])(?:mid|uid|userId|memberId|authorMid|authorUid)\\s*[=:]\\s*'?([1-9]\\d{4,})",
            Pattern.CASE_INSENSITIVE);

    private static Class<?> sCommentItemClass;
    private static VersionMap sVM;
    private static int sCommentItemDebugCount = 0;
    private static final Map<View, Object[]> blockCallbackMap = new WeakHashMap<>();
    private static final Map<String, Object[]> blockCallbackMidMap =
            Collections.synchronizedMap(new HashMap<>());

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        String version = detectVersion(lpparam);
        sVM = new VersionMap(version);
        log("version=" + version + " map=" + sVM);

        try {
            sCommentItemClass = XposedHelpers.findClass(
                    "com.bilibili.app.comment3.data.model.CommentItem", lpparam.classLoader);
        } catch (Throwable t) {
            log("✗ CommentItem class not found: " + t);
        }

        hookCommentListAdapter(lpparam);
        hookCommentMoreMenu(lpparam);
        hookCookieInterceptor(lpparam);
        BlockHelper.tryInitCsrf();
    }

    private static String detectVersion(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> buildConfig = XposedHelpers.findClass(
                    lpparam.packageName + ".BuildConfig", lpparam.classLoader);
            Object vn = XposedHelpers.getStaticObjectField(buildConfig, "VERSION_NAME");
            if (vn instanceof String) return (String) vn;
        } catch (Throwable e) {
            log("detectVersion BuildConfig failed: " + e);
        }
        try {
            Class<?> cl = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object at = XposedHelpers.callStaticMethod(cl, "currentActivityThread");
            Context ctx = (Context) XposedHelpers.callMethod(at, "getSystemContext");
            android.content.pm.PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(lpparam.packageName, 0);
            if (pi.versionName != null) return pi.versionName;
        } catch (Throwable e) {
            log("detectVersion PackageManager failed: " + e);
        }
        log("detectVersion: all methods failed, returning unknown");
        return "unknown";
    }

    private static void hookCommentListAdapter(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> adapter = XposedHelpers.findClass(
                    "com.bilibili.app.comment3.ui.adapter.CommentListAdapter", lpparam.classLoader);
            XposedBridge.hookAllMethods(adapter, "onBindViewHolder", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    handleBind(param);
                }
            });
            log("✓ CommentListAdapter.onBindViewHolder");
        } catch (Throwable t) {
            log("✗ CommentListAdapter: " + t);
        }
    }

    private static void handleBind(XC_MethodHook.MethodHookParam param) {
        View itemView = null;
        try {
            Object vh = param.args[0];
            if (vh == null) return;

            itemView = readItemView(vh);
            CommentBlockFeature.prepareForBind(itemView);
            markCommentItemRoot(itemView);

            String vhName = vh.getClass().getName();
            if (!isCommentLikeHolder(vhName)) {
                CommentBlockFeature.clear(itemView, "non comment holder");
                return;
            }
            if (shouldSkipBlockFeatureForHolder(vhName)) {
                CommentBlockFeature.clear(itemView, "skip holder=" + shortClassName(vhName));
                return;
            }

            Object adapter = param.thisObject;
            int pos = param.args.length > 1 && param.args[1] instanceof Integer
                    ? (Integer) param.args[1] : -1;

            Object data = resolveCommentData(vh, adapter, pos);
            String scene = resolveCommentScene(vh, adapter, data, itemView);

            if (!isCommentItem(data)) {
                CommentBlockFeature.clear(itemView, "comment item not found");
                log("  no comment item for holder=" + shortClassName(vhName)
                        + " pos=" + pos + " scene=" + scene);
                maybeDumpHolderShape(vh, adapter);
                return;
            }

            CommentBlockFeature.Binding binding = buildBinding(vh, data, itemView, scene);
            if (!CommentBlockFeature.bind(itemView, binding)) {
                if (binding != null && !binding.hasValidMid()) {
                    log("  no mid for pos=" + pos + " scene=" + scene
                            + " item=" + summarize(safeToString(data), 200));
                }
            }
        } catch (Throwable t) {
            CommentBlockFeature.clear(itemView, "handleBind exception");
            log("handleBind error: " + t);
        }
    }

    private static void hookCommentMoreMenu(XC_LoadPackage.LoadPackageParam lpparam) {
        final Class<?> holder;
        try {
            holder = XposedHelpers.findClass(
                    "com.bilibili.app.comment3.ui.widget.menu.CommentMoreMenuItemHolder",
                    lpparam.classLoader);
        } catch (Throwable t) {
            log("✗ CommentMoreMenuItemHolder class: " + t);
            return;
        }

        String[] candidates = {"y3", "z3", "a3", "b3", "c3"};
        boolean hooked = false;
        for (String name : candidates) {
            try {
                XposedHelpers.findAndHookMethod(holder, name,
                        "kotlin.jvm.functions.Function1",
                        "com.bilibili.app.comment3.data.model.CommentItem$MenuItem",
                        View.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                captureBlockMenuCallback(name, param.args);
                            }
                        });
                log("✓ CommentMoreMenuItemHolder." + name);
                hooked = true;
                break;
            } catch (NoSuchMethodError ignored) {
            } catch (Throwable t) {
                log("  Error hooking " + name + ": " + t);
            }
        }

        if (hooked) {
            return;
        }

        for (Method method : holder.getDeclaredMethods()) {
            try {
                Class<?>[] pts = method.getParameterTypes();
                if (pts.length == 3 && View.class.isAssignableFrom(pts[2])) {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            captureBlockMenuCallback(method.getName(), param.args);
                        }
                    });
                    hooked = true;
                }
            } catch (Throwable ignored) {
            }
        }

        if (hooked) {
            log("✓ CommentMoreMenuItemHolder.<fallback>");
        } else {
            log("✗ CommentMoreMenuItemHolder methods not hooked");
        }
    }

    private static void captureBlockMenuCallback(String methodName, Object[] args) {
        if (args == null || args.length < 3) return;
        try {
            Object callback = args[0];
            Object menuItem = args[1];
            View parentView = args[2] instanceof View ? (View) args[2] : null;
            if (callback == null || menuItem == null || parentView == null) {
                return;
            }

            String menuDesc = describeMenuItem(menuItem);
            boolean isBlock = isBlockMenuItem(menuDesc);
            log("MenuHolder." + methodName
                    + " menu=" + menuItem.getClass().getSimpleName()
                    + " desc=" + summarize(menuDesc, 120)
                    + " isBlock=" + isBlock);
            if (!isBlock) {
                return;
            }

            String mid = extractMidFromMenuItem(menuItem);
            if (!CommentBlockFeature.isValidMid(mid)) {
                log("  ! BLOCK menu detected but no mid extracted");
                return;
            }

            View itemView = findCommentItemRoot(parentView);
            if (itemView == null) {
                itemView = parentView;
            }
            bindMidToItemView(itemView, mid);
            Object[] entry = new Object[]{callback, menuItem, mid, menuDesc};
            blockCallbackMap.put(itemView, entry);
            blockCallbackMidMap.put(mid, entry);
            log("  ✓ Store BLOCK callback mid=" + mid
                    + " root=" + itemView.getClass().getSimpleName());
        } catch (Throwable t) {
            log("  MenuHolder capture error: " + t);
        }
    }

    private static boolean invokeCachedBlockCallback(View itemRoot, String mid, Context context) {
        Object[] stored = blockCallbackMidMap.get(mid);
        View keyView = itemRoot;
        if (stored == null && keyView != null) {
            stored = blockCallbackMap.get(keyView);
        }
        if (stored == null && keyView != null) {
            View mappedRoot = findCommentItemRoot(keyView);
            if (mappedRoot != null) {
                stored = blockCallbackMap.get(mappedRoot);
            }
        }
        if (stored == null) {
            log("  No internal block callback cached for mid=" + mid);
            return false;
        }
        if (stored.length < 3) {
            return false;
        }

        String callbackMid = stored[2] == null ? null : stored[2].toString();
        if (!mid.equals(callbackMid)) {
            log("  Skip menu callback due to mid mismatch clickMid=" + mid
                    + " callbackMid=" + callbackMid);
            return false;
        }

        try {
            Object callback = stored[0];
            Object menuItem = stored[1];
            String menuDesc = stored.length >= 4 && stored[3] != null
                    ? stored[3].toString() : "";
            log("  invoke internal block callback mid=" + mid
                    + " desc=" + summarize(menuDesc, 120));
            XposedHelpers.callMethod(callback, "invoke", menuItem);
            if (context != null) {
                Toast.makeText(context, "已触发内置拉黑流程", Toast.LENGTH_SHORT).show();
            }
            return true;
        } catch (Throwable t) {
            log("  Menu callback failed: " + t);
            return false;
        }
    }

    private static View readItemView(Object vh) {
        if (vh == null) return null;
        try {
            return (View) XposedHelpers.getObjectField(vh, "itemView");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isCommentLikeHolder(String className) {
        if (className == null || className.isEmpty()) return false;
        return className.contains("Comment")
                || className.contains("comment")
                || className.contains("Reply")
                || className.contains("reply");
    }

    private static boolean shouldSkipBlockFeatureForHolder(String className) {
        if (className == null || className.isEmpty()) return false;
        return className.contains("CommentAnswerHolder");
    }

    private static Object resolveCommentData(Object vh, Object adapter, int pos) {
        Object data = sVM.callVhData(vh);
        if (isCommentItem(data)) {
            return data;
        }

        data = extractNestedCommentItem(vh);
        if (isCommentItem(data)) {
            return data;
        }

        data = discoverAdapterData(adapter, pos);
        if (isCommentItem(data)) {
            return data;
        }

        data = findCommentItemDeep(vh, 4, Collections.newSetFromMap(new IdentityHashMap<>()));
        if (isCommentItem(data)) {
            return data;
        }

        data = findCommentItemDeep(adapter, 3, Collections.newSetFromMap(new IdentityHashMap<>()));
        if (isCommentItem(data)) {
            return data;
        }
        return null;
    }

    private static Object extractNestedCommentItem(Object holder) {
        if (holder == null) return null;
        try {
            Object richTextHandler = readNamedField(holder, holder.getClass(), "m");
            if (isCommentItem(richTextHandler)) {
                return richTextHandler;
            }
            if (richTextHandler != null) {
                Object nested = readNamedField(richTextHandler, richTextHandler.getClass(), "i");
                if (isCommentItem(nested)) {
                    return nested;
                }
            }
        } catch (Throwable ignored) {}

        for (Field field : holder.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(holder);
                if (isCommentItem(value)) {
                    return value;
                }
                if (value == null || isSimpleType(value) || value instanceof View) {
                    continue;
                }
                Object nested = readNamedField(value, value.getClass(), "i");
                if (isCommentItem(nested)) {
                    return nested;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object discoverAdapterData(Object adapter, int pos) {
        if (adapter == null || pos < 0) return null;

        String[] methods = {"getItem", "getData", "getComment", "getReply"};
        for (String method : methods) {
            try {
                Object value = XposedHelpers.callMethod(adapter, method, pos);
                if (isCommentItem(value)) {
                    return value;
                }
                Object nested = findCommentItemDeep(value, 3, Collections.newSetFromMap(new IdentityHashMap<>()));
                if (isCommentItem(nested)) {
                    return nested;
                }
            } catch (Throwable ignored) {}
        }

        for (Field field : adapter.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(adapter);
                if (!(value instanceof List)) {
                    continue;
                }
                List<?> list = (List<?>) value;
                if (pos < 0 || pos >= list.size()) {
                    continue;
                }
                Object item = list.get(pos);
                if (isCommentItem(item)) {
                    return item;
                }
                Object nested = findCommentItemDeep(item, 3, Collections.newSetFromMap(new IdentityHashMap<>()));
                if (isCommentItem(nested)) {
                    return nested;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean isCommentItem(Object value) {
        if (value == null) return false;
        if (sCommentItemClass != null && sCommentItemClass.isInstance(value)) {
            return true;
        }
        return value.getClass().getName().contains("CommentItem");
    }

    private static CommentBlockFeature.Binding buildBinding(
            Object vh, Object data, View itemView, String scene) {
        if (!isCommentItem(data)) return null;
        String text = safeToString(data);

        Long itemId = extractCommentLong(data, text, "id", "rpId", "rpid");
        Long rootId = sVM.callRootRpid(data);
        if (rootId == null) {
            rootId = extractCommentLong(data, text, "rootId", "root", "rootRpid");
        }
        Long parentId = extractCommentLong(data, text, "parentId", "parent", "parentRpid");

        String mid = extractMid(data);
        if (!CommentBlockFeature.isValidMid(mid) && itemView != null) {
            mid = scanViewForMid(itemView);
        }
        if (CommentBlockFeature.isValidMid(mid)) {
            bindMidToItemView(itemView, mid);
        }

        CommentBlockFeature.InternalBlockInvoker invoker = buildInternalBlockInvoker(itemView, mid);
        return new CommentBlockFeature.Binding(mid, itemId, rootId, parentId, scene, invoker);
    }

    private static CommentBlockFeature.InternalBlockInvoker buildInternalBlockInvoker(
            View itemView, String mid) {
        if (itemView == null || !CommentBlockFeature.isValidMid(mid)) {
            return null;
        }
        return (context, itemRoot, binding) -> invokeCachedBlockCallback(itemRoot, mid, context);
    }

    private static Object findCommentItemDeep(Object obj, int depth, Set<Object> visited) {
        if (obj == null || depth < 0 || visited == null || !visited.add(obj)) {
            return null;
        }
        if (isCommentItem(obj)) {
            return obj;
        }
        if (shouldSkipObjectGraphProbe(obj)) {
            return null;
        }

        if (obj instanceof Iterable) {
            int scanned = 0;
            for (Object child : (Iterable<?>) obj) {
                Object found = findCommentItemDeep(child, depth - 1, visited);
                if (found != null) {
                    return found;
                }
                if (++scanned >= 8) {
                    break;
                }
            }
            return null;
        }

        Class<?> current = obj.getClass();
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object child = field.get(obj);
                    Object found = findCommentItemDeep(child, depth - 1, visited);
                    if (found != null) {
                        return found;
                    }
                } catch (Throwable ignored) {}
            }
            current = current.getSuperclass();
        }

        for (Method method : obj.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || method.getReturnType() == void.class) {
                continue;
            }
            String name = method.getName();
            if ("toString".equals(name) || "hashCode".equals(name) || "getClass".equals(name)) {
                continue;
            }
            if (!looksLikeDataAccessor(name, method.getReturnType())) {
                continue;
            }
            try {
                Object child = method.invoke(obj);
                Object found = findCommentItemDeep(child, depth - 1, visited);
                if (found != null) {
                    return found;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean looksLikeDataAccessor(String methodName, Class<?> returnType) {
        if (methodName == null || returnType == null) return false;
        String lower = methodName.toLowerCase();
        if (lower.contains("item")
                || lower.contains("comment")
                || lower.contains("reply")
                || lower.contains("model")
                || lower.contains("data")) {
            return true;
        }
        String typeName = returnType.getName();
        return typeName.contains("Comment")
                || typeName.contains("Reply")
                || typeName.contains("comment")
                || typeName.contains("reply");
    }

    private static boolean shouldSkipObjectGraphProbe(Object obj) {
        if (obj == null || obj instanceof CharSequence || obj instanceof Boolean || obj instanceof Number) {
            return true;
        }
        if (obj instanceof View) {
            return true;
        }
        Class<?> cls = obj.getClass();
        if (cls.isPrimitive()) {
            return true;
        }
        String className = cls.getName();
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("kotlin.")
                || className.startsWith("android.")
                || className.startsWith("androidx.")
                || className.startsWith("sun.")
                || className.startsWith("dalvik.");
    }

    private static String resolveCommentScene(Object... sources) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Object source : sources) {
            Object env = findCommentViewEnv(source, 3, visited);
            String scene = describeCommentViewEnv(env);
            if (scene != null) {
                return scene;
            }
        }

        for (Object source : sources) {
            if (source instanceof View) {
                String scene = fallbackSceneFromView((View) source);
                if (scene != null) {
                    return scene;
                }
            }
        }
        return null;
    }

    private static Object findCommentViewEnv(Object obj, int depth, Set<Object> visited) {
        if (obj == null || depth < 0 || shouldSkipSceneProbe(obj) || !visited.add(obj)) {
            return null;
        }
        String className = obj.getClass().getName();
        if (className.contains("CommentViewEnv")) {
            return obj;
        }
        if (depth == 0) {
            return null;
        }
        if (obj instanceof Iterable) {
            int scanned = 0;
            for (Object child : (Iterable<?>) obj) {
                Object env = findCommentViewEnv(child, depth - 1, visited);
                if (env != null) {
                    return env;
                }
                if (++scanned >= 8) {
                    break;
                }
            }
            return null;
        }

        Class<?> current = obj.getClass();
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object child = field.get(obj);
                    Object env = findCommentViewEnv(child, depth - 1, visited);
                    if (env != null) {
                        return env;
                    }
                } catch (Throwable ignored) {}
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static boolean shouldSkipSceneProbe(Object obj) {
        if (obj == null || obj instanceof CharSequence || obj instanceof Boolean || obj instanceof Number) {
            return true;
        }
        Class<?> cls = obj.getClass();
        if (cls.isPrimitive()) {
            return true;
        }
        String className = cls.getName();
        return className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("kotlin.")
                || className.startsWith("android.view.")
                || className.startsWith("android.widget.")
                || className.startsWith("androidx.")
                || className.startsWith("sun.")
                || className.startsWith("dalvik.");
    }

    private static String describeCommentViewEnv(Object env) {
        if (env == null) return null;
        String className = env.getClass().getName();
        if (className.contains("$Main")) {
            return "main";
        }
        if (className.contains("$Detail")) {
            return "detail";
        }
        if (className.contains("$Dialog")) {
            return "dialog";
        }
        if (className.contains("$Picture")) {
            String text = safeToString(env);
            if (text != null && text.toLowerCase().contains("hostscene=main")) {
                return "picture-main";
            }
            return "picture";
        }

        String text = safeToString(env);
        if (text == null) return null;
        if (text.startsWith("Main(")) return "main";
        if (text.startsWith("Detail(")) return "detail";
        if (text.startsWith("Dialog(")) return "dialog";
        if (text.startsWith("Picture(")) {
            return text.toLowerCase().contains("hostscene=main") ? "picture-main" : "picture";
        }
        return null;
    }

    private static String fallbackSceneFromView(View itemView) {
        if (itemView == null) return null;
        try {
            Context context = itemView.getContext();
            if (context instanceof Activity) {
                String name = ((Activity) context).getClass().getName();
                if (name.contains("UnitedBizDetailsActivity")
                        || name.contains("VideoDetailsActivity")
                        || name.contains("MainActivityV2")) {
                    return "main";
                }
            } else if (context != null
                    && context.getClass().getName().contains("ContextThemeWrapper")) {
                return "detail";
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String extractMid(Object obj) {
        return extractMid(obj, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static String extractMid(Object obj, int depth, Set<Object> visited) {
        if (obj == null || depth > 3 || !visited.add(obj)) return null;

        String textMid = extractMidFromText(safeToString(obj));
        if (CommentBlockFeature.isValidMid(textMid)) {
            return textMid;
        }

        String[] directFields = {
                "mid", "uid", "userId", "memberId", "authorMid", "authorUid"
        };
        for (String fieldName : directFields) {
            Object value = readNamedField(obj, obj.getClass(), fieldName);
            String mid = coerceMid(value);
            if (CommentBlockFeature.isValidMid(mid)) {
                return mid;
            }
            mid = callNamedGetter(obj, fieldName);
            if (CommentBlockFeature.isValidMid(mid)) {
                return mid;
            }
        }

        String[] nestedFields = {
                "member", "user", "author", "owner", "profile", "account", "upUser"
        };
        for (String fieldName : nestedFields) {
            Object nested = readNamedField(obj, obj.getClass(), fieldName);
            String mid = extractMid(nested, depth + 1, visited);
            if (mid != null) {
                return mid;
            }
        }

        for (Method method : obj.getClass().getMethods()) {
            String name = method.getName();
            if (method.getParameterCount() != 0) continue;
            if (!(method.getReturnType() == long.class || method.getReturnType() == Long.class
                    || method.getReturnType() == String.class)) {
                continue;
            }
            String lower = name.toLowerCase();
            if (!lower.contains("mid") && !lower.contains("uid") && !lower.contains("user")) {
                continue;
            }
            try {
                Object value = method.invoke(obj);
                String mid = coerceMid(value);
                if (CommentBlockFeature.isValidMid(mid)) {
                    return mid;
                }
            } catch (Throwable ignored) {}
        }

        for (Field field : obj.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(obj);
                if (value == null || isSimpleType(value) || value instanceof View) {
                    continue;
                }
                String mid = extractMid(value, depth + 1, visited);
                if (mid != null) {
                    return mid;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String callNamedGetter(Object obj, String fieldName) {
        try {
            Object value = XposedHelpers.callMethod(obj,
                    "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1));
            return coerceMid(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String coerceMid(Object value) {
        if (value == null) return null;
        if (value instanceof Number) {
            String mid = String.valueOf(((Number) value).longValue());
            return CommentBlockFeature.isValidMid(mid) ? mid : null;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            return CommentBlockFeature.isValidMid(text) ? text : null;
        }
        return null;
    }

    private static String extractMidFromText(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher matcher = MID_TEXT_PATTERN.matcher(text);
        if (matcher.find()) {
            String mid = matcher.group(1);
            return CommentBlockFeature.isValidMid(mid) ? mid : null;
        }
        return null;
    }

    private static String extractMidFromMenuItem(Object menuItem) {
        if (menuItem == null) return null;
        Set<String> seen = new HashSet<>();
        String[] result = {null};
        int[] bestDepth = {Integer.MAX_VALUE};
        scanMenuItemForMid(menuItem, "", 0, seen, result, bestDepth);
        return result[0];
    }

    private static void scanMenuItemForMid(Object obj, String path, int depth,
                                           Set<String> seen, String[] result, int[] bestDepth) {
        if (obj == null || depth > 5 || result[0] != null) return;
        if (obj.getClass().isPrimitive() || obj instanceof CharSequence
                || obj instanceof Boolean || obj instanceof View) return;
        String key = System.identityHashCode(obj) + "@" + obj.getClass().getName();
        if (!seen.add(key)) return;

        for (Field field : obj.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(obj);
                if (value instanceof Long || value instanceof Integer) {
                    String mid = value.toString();
                    if (CommentBlockFeature.isValidMid(mid) && depth < bestDepth[0]) {
                        log("  menuField: " + path + field.getName() + "("
                                + obj.getClass().getSimpleName() + ")=" + mid
                                + " depth=" + depth);
                        bestDepth[0] = depth;
                        result[0] = mid;
                    }
                } else if (value != null && !isSimpleType(value) && !(value instanceof View)) {
                    scanMenuItemForMid(value, path + field.getName() + ".", depth + 1,
                            seen, result, bestDepth);
                }
            } catch (Throwable ignored) {
            }
        }

        Class<?> current = obj.getClass().getSuperclass();
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    if (value instanceof Long || value instanceof Integer) {
                        String mid = value.toString();
                        if (CommentBlockFeature.isValidMid(mid) && depth < bestDepth[0]) {
                            log("  menuField(parent): " + path + field.getName() + "("
                                    + current.getSimpleName() + ")=" + mid
                                    + " depth=" + depth);
                            bestDepth[0] = depth;
                            result[0] = mid;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
    }

    private static void markCommentItemRoot(View itemView) {
        if (itemView == null) return;
        itemView.setTag(TAG_COMMENT_ROOT_MARKER, Boolean.TRUE);
    }

    private static void bindMidToItemView(View itemView, String mid) {
        if (itemView == null || mid == null) return;
        markCommentItemRoot(itemView);
        itemView.setTag(TAG_BOUND_MID, mid);
    }

    private static View findCommentItemRoot(View start) {
        View current = start;
        while (current != null) {
            Object marker = current.getTag(TAG_COMMENT_ROOT_MARKER);
            if (Boolean.TRUE.equals(marker)) {
                return current;
            }
            Object boundMid = current.getTag(TAG_BOUND_MID);
            if (boundMid != null && CommentBlockFeature.isValidMid(boundMid.toString())) {
                return current;
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    private static boolean isBlockMenuItem(String menuDesc) {
        if (menuDesc == null) return false;
        String str = menuDesc.toLowerCase();
        return str.contains("拉黑")
                || str.contains("黑名单")
                || str.contains("block")
                || str.contains("black");
    }

    private static String describeMenuItem(Object menuItem) {
        if (menuItem == null) return "";
        StringBuilder sb = new StringBuilder();
        appendMenuPart(sb, safeToString(menuItem));
        String[] fields = {"title", "text", "label", "name", "desc", "content", "action", "type"};
        for (String field : fields) {
            appendMenuPart(sb, readNamedFieldAsString(menuItem, menuItem.getClass(), field));
        }
        String[] getters = {"getTitle", "getText", "getLabel", "getName", "getDesc", "getAction", "getType"};
        for (String getter : getters) {
            try {
                Object value = XposedHelpers.callMethod(menuItem, getter);
                appendMenuPart(sb, value == null ? null : value.toString());
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }

    private static void appendMenuPart(StringBuilder sb, String value) {
        if (value == null) return;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return;
        if (sb.length() > 0 && sb.indexOf(trimmed) >= 0) return;
        if (sb.length() > 0) sb.append(" | ");
        sb.append(trimmed);
    }

    private static String readNamedFieldAsString(Object target, Class<?> cls, String fieldName) {
        Object value = readNamedField(target, cls, fieldName);
        return value == null ? null : value.toString();
    }

    private static Long extractCommentLong(Object obj, String text, String... names) {
        if (obj == null || names == null) return null;
        for (String name : names) {
            Object value = readNamedField(obj, obj.getClass(), name);
            Long parsed = coerceLong(value);
            if (parsed != null) return parsed;
            try {
                Object getterValue = XposedHelpers.callMethod(obj,
                        "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
                parsed = coerceLong(getterValue);
                if (parsed != null) return parsed;
            } catch (Throwable ignored) {}
            parsed = extractCommentLongFromText(text, name);
            if (parsed != null) return parsed;
        }
        return null;
    }

    private static Long extractCommentLongFromText(String text, String name) {
        if (text == null || text.isEmpty() || name == null || name.isEmpty()) return null;
        try {
            Pattern pattern = Pattern.compile(
                    "(?:^|[,(\\s])" + Pattern.quote(name) + "\\s*[=:]\\s*(-?\\d+)",
                    Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return Long.parseLong(matcher.group(1));
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Long coerceLong(Object value) {
        if (value instanceof Long) return (Long) value;
        if (value instanceof Integer) return ((Integer) value).longValue();
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static boolean isSimpleType(Object obj) {
        return obj instanceof CharSequence
                || obj instanceof Boolean
                || obj instanceof Number
                || obj.getClass().isPrimitive()
                || obj.getClass().getName().startsWith("java.");
    }

    private static Object readNamedField(Object target, Class<?> cls, String fieldName) {
        if (target == null || cls == null || fieldName == null) return null;
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {}
            current = current.getSuperclass();
        }
        return null;
    }

    private static String safeToString(Object obj) {
        if (obj == null) return null;
        try {
            return obj.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String summarize(String value, int maxLen) {
        if (value == null) return "null";
        if (value.length() <= maxLen) return value;
        return value.substring(0, maxLen) + "...";
    }

    private static void maybeDumpHolderShape(Object vh, Object adapter) {
        if (sCommentItemDebugCount >= 3) {
            return;
        }
        sCommentItemDebugCount++;
        dumpFieldShape("vh", vh);
        dumpFieldShape("adapter", adapter);
    }

    private static void dumpFieldShape(String prefix, Object obj) {
        if (obj == null) return;
        try {
            for (Field field : obj.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(obj);
                String valueClass = value == null ? "null" : value.getClass().getName();
                log("  " + prefix + "." + field.getName() + " -> " + valueClass);
            }
        } catch (Throwable ignored) {}
    }

    private static String shortClassName(String className) {
        int idx = className == null ? -1 : className.lastIndexOf('.');
        return idx >= 0 ? className.substring(idx + 1) : String.valueOf(className);
    }

    private static String scanViewForMid(View view) {
        if (view == null) return null;
        Object tag = view.getTag();
        if (tag != null) {
            String text = tag.toString();
            if (CommentBlockFeature.isValidMid(text)) {
                return text;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                String mid = scanViewForMid(vg.getChildAt(i));
                if (mid != null) {
                    return mid;
                }
            }
        }
        return null;
    }

    private static void hookCookieInterceptor(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> clientClass = XposedHelpers.findClass(
                    "okhttp3.OkHttpClient", lpparam.classLoader);
            XposedBridge.hookAllMethods(clientClass, "newCall", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 1 || param.args[0] == null) return;
                    try {
                        Object request = param.args[0];
                        Object url = XposedHelpers.callMethod(request, "url");
                        String host = XposedHelpers.callMethod(url, "host").toString();
                        if (!host.contains("bilibili.com")) return;

                        Object headers = XposedHelpers.callMethod(request, "headers");
                        String cookie = null;
                        try {
                            Object value = XposedHelpers.callMethod(headers, "get", "Cookie");
                            cookie = value == null ? null : value.toString();
                        } catch (Throwable ignored) {
                        }

                        if (cookie == null) {
                            try {
                                Object value = XposedHelpers.callMethod(headers, "get", "cookie");
                                cookie = value == null ? null : value.toString();
                            } catch (Throwable ignored) {
                            }
                        }

                        if (cookie != null && !cookie.isEmpty()) {
                            BlockHelper.cacheCookieHeader(cookie);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });

            try {
                Class<?> cookieJarClass = XposedHelpers.findClass(
                        "okhttp3.CookieJar", lpparam.classLoader);
                XposedBridge.hookAllMethods(cookieJarClass, "loadForRequest", new XC_MethodHook() {
                    @Override
                    @SuppressWarnings("unchecked")
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object result = param.getResult();
                        if (!(result instanceof List)) return;
                        StringBuilder cookieHeader = new StringBuilder();
                        for (Object cookie : (List<Object>) result) {
                            try {
                                String name = XposedHelpers.callMethod(cookie, "name").toString();
                                String value = XposedHelpers.callMethod(cookie, "value").toString();
                                if (name != null && value != null && !name.isEmpty() && !value.isEmpty()) {
                                    if (cookieHeader.length() > 0) cookieHeader.append("; ");
                                    cookieHeader.append(name).append("=").append(value);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                        if (cookieHeader.length() > 0) {
                            BlockHelper.cacheCookieHeader(cookieHeader.toString());
                        }
                    }
                });
            } catch (Throwable t) {
                log("  CookieJar hook: " + t);
            }

            log("✓ CookieInterceptor");
        } catch (Throwable t) {
            log("✗ CookieInterceptor: " + t);
        }
    }

    private static void log(String msg) {
        ModuleLog.i(msg);
    }

    private static class VersionMap {
        final String version;
        final String vhDataMethod;
        final String rootRpidMethod;

        VersionMap(String ver) {
            this.version = ver;
            if (ver.startsWith("8.95.")) {
                vhDataMethod = "v0";
                rootRpidMethod = "O";
            } else if (ver.startsWith("8.93.") || ver.startsWith("8.91.")) {
                vhDataMethod = "u0";
                rootRpidMethod = "L";
            } else {
                vhDataMethod = null;
                rootRpidMethod = null;
            }
        }

        Object callVhData(Object vh) {
            if (vhDataMethod != null) {
                try {
                    return XposedHelpers.callMethod(vh, vhDataMethod);
                } catch (Throwable ignored) {}
            }
            return discoverVhData(vh);
        }

        Long callRootRpid(Object data) {
            if (rootRpidMethod != null) {
                try {
                    Object obj = XposedHelpers.callMethod(data, rootRpidMethod);
                    if (obj instanceof Long) return (Long) obj;
                } catch (Throwable ignored) {}
            }
            return extractCommentLong(data, safeToString(data), "rootId", "root", "rootRpid");
        }

        @Override
        public String toString() {
            return "{vh=" + vhDataMethod + " rootId=" + rootRpidMethod + "}";
        }
    }

    private static Object discoverVhData(Object vh) {
        if (vh == null) return null;
        for (Method method : vh.getClass().getMethods()) {
            if (method.getParameterCount() != 0 || method.getReturnType() == void.class) {
                continue;
            }
            String name = method.getName();
            if ("toString".equals(name) || "hashCode".equals(name) || "getClass".equals(name)) {
                continue;
            }
            try {
                Object value = method.invoke(vh);
                if (isCommentItem(value)) {
                    return value;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
