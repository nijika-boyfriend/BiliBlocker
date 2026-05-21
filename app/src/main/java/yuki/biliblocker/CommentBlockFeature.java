package yuki.biliblocker;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class CommentBlockFeature {

    private static final int TAG_BUTTON_VIEW = 0x5f0a1001;
    private static final int TAG_BUTTON_MID = 0x5f0a1002;
    private static final int TAG_ITEM_ROOT = 0x5f0a1004;
    private static final int TAG_BOUND_MID = 0x5f0a1006;
    private static final int TAG_ACTIVE_BIND_KEY = 0x5f0a1007;

    private static final Set<String> BLOCKED_MIDS =
            Collections.synchronizedSet(new HashSet<>());
    private static final Map<String, ActiveBinding> ACTIVE_BINDINGS =
            Collections.synchronizedMap(new HashMap<>());

    private CommentBlockFeature() {
    }

    static void prepareForBind(View itemView) {
        if (itemView == null) return;
        releaseActiveBinding(itemView);
        itemView.setTag(TAG_BOUND_MID, null);
    }

    static boolean bind(View itemView, Binding binding) {
        if (!(itemView instanceof ViewGroup)) {
            clear(itemView, "itemView not ViewGroup");
            return false;
        }
        if (binding == null) {
            clear(itemView, "binding null");
            return false;
        }
        if (!binding.hasValidMid()) {
            clear(itemView, "missing mid scene=" + binding.scene);
            return false;
        }
        if (binding.shouldHideInMainScene()) {
            clear(itemView, "hide child preview scene=" + binding.scene);
            return false;
        }
        if (hasAncestorBoundSameMid(itemView, binding.mid)) {
            clear(itemView, "skip nested duplicate mid=" + binding.mid);
            ModuleLog.d("skip nested duplicate mid=" + binding.mid);
            return false;
        }
        addBlockButton((ViewGroup) itemView, binding);
        return true;
    }

    static void clear(View itemView, String reason) {
        if (itemView == null) return;
        releaseActiveBinding(itemView);
        Object existing = itemView.getTag(TAG_BUTTON_VIEW);
        if (existing instanceof View) {
            View button = (View) existing;
            ViewParent parent = button.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(button);
            }
        }
        itemView.setTag(TAG_BUTTON_VIEW, null);
        itemView.setTag(TAG_BUTTON_MID, null);
        itemView.setTag(TAG_ITEM_ROOT, null);
        itemView.setTag(TAG_BOUND_MID, null);
        itemView.setTag(TAG_ACTIVE_BIND_KEY, null);
        if (reason != null && existing != null) {
            ModuleLog.d("clear block button: " + reason);
        }
    }

    static boolean isValidMid(String value) {
        return value != null && value.matches("\\d{6,}");
    }

    private static void addBlockButton(ViewGroup root, Binding binding) {
        try {
            Context context = root.getContext();
            bindMid(root, binding.mid);

            View headerAnchor = findTopRightAnchor(root);
            ViewGroup host = findTopRightHost(root, headerAnchor);
            if (!reserveActiveBinding(root, binding, host)) {
                return;
            }
            TextView btn = ensureBlockButton(root, host, context);
            if (btn == null) return;

            btn.setTag(TAG_BUTTON_MID, binding.mid);
            btn.setTag(TAG_ITEM_ROOT, root);

            if (BLOCKED_MIDS.contains(binding.mid)) {
                applyBlockedState(btn, binding.mid);
            } else {
                btn.setEnabled(true);
                btn.setText("拉黑");
                btn.setTextColor(0xffFB7299);
            }

            btn.setOnClickListener(v -> {
                Object tag = v.getTag(TAG_BUTTON_MID);
                String mid = tag == null ? null : tag.toString();
                if (!isValidMid(mid)) {
                    Toast.makeText(v.getContext(), "mid为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                v.setEnabled(false);
                boolean invoked = false;
                if (binding.blockInvoker != null) {
                    try {
                        invoked = binding.blockInvoker.invoke(context, (ViewGroup) root, binding);
                    } catch (Throwable t) {
                        ModuleLog.e("invoke internal block failed", t);
                    }
                } else {
                    ModuleLog.w("internal block invoker missing mid=" + mid, true);
                }

                if (invoked) {
                    v.post(() -> v.setEnabled(true));
                    return;
                }

                BlockHelper.blockUser(context, mid, new BlockHelper.BlockCallback() {
                    @Override
                    public void onSuccess(String toast) {
                        v.post(() -> {
                            applyBlockedState((TextView) v, mid);
                            if (toast != null && !toast.isEmpty()) {
                                Toast.makeText(context, toast, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        v.post(() -> {
                            if (BlockHelper.isAlreadyBlockedMessage(error)) {
                                applyBlockedState((TextView) v, mid);
                                Toast.makeText(context,
                                        error == null || error.isEmpty() ? "该用户已在黑名单中" : error,
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                v.setEnabled(true);
                                Toast.makeText(context, "拉黑失败: " + error, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
            });

            positionBlockButton(host, btn, headerAnchor);
            ModuleLog.i("  +btn mid=" + binding.mid + " scene=" + binding.scene
                    + " host=" + host.getClass().getSimpleName());
        } catch (Throwable t) {
            ModuleLog.e("addBlockButton failed", t);
        }
    }

    private static void bindMid(View itemView, String mid) {
        if (itemView == null || mid == null) return;
        itemView.setTag(TAG_BOUND_MID, mid);
    }

    private static boolean hasAncestorBoundSameMid(View itemView, String mid) {
        if (itemView == null || mid == null || mid.isEmpty()) return false;
        View current = itemView;
        while (true) {
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) {
                return false;
            }
            current = (View) parent;

            Object ancestorMid = current.getTag(TAG_BOUND_MID);
            if (mid.equals(ancestorMid)) {
                return true;
            }

            Object buttonMid = current.getTag(TAG_BUTTON_MID);
            if (mid.equals(buttonMid)) {
                Object button = current.getTag(TAG_BUTTON_VIEW);
                if (button instanceof View) {
                    return true;
                }
            }
        }
    }

    private static boolean reserveActiveBinding(ViewGroup root, Binding binding, ViewGroup host) {
        if (root == null || binding == null || host == null) return true;
        String key = buildBindingKey(binding);
        if (key == null || key.isEmpty()) {
            return true;
        }

        Object currentKey = root.getTag(TAG_ACTIVE_BIND_KEY);
        if (key.equals(currentKey)) {
            return true;
        }

        releaseActiveBinding(root);

        String hostName = host.getClass().getSimpleName();
        ActiveBinding existing = ACTIVE_BINDINGS.get(key);
        if (existing != null) {
            View otherRoot = existing.root.get();
            if (!isActiveRoot(otherRoot)) {
                ACTIVE_BINDINGS.remove(key);
            } else if (otherRoot != root) {
                if (shouldPreferCurrentBinding(existing.hostClassName, hostName)) {
                    clear(otherRoot, "replace duplicate binding key=" + key);
                } else {
                    ModuleLog.d("skip duplicate binding key=" + key
                            + " existingHost=" + existing.hostClassName
                            + " host=" + hostName);
                    return false;
                }
            }
        }

        ACTIVE_BINDINGS.put(key, new ActiveBinding(root, hostName));
        root.setTag(TAG_ACTIVE_BIND_KEY, key);
        return true;
    }

    private static void releaseActiveBinding(View itemView) {
        if (itemView == null) return;
        Object keyObj = itemView.getTag(TAG_ACTIVE_BIND_KEY);
        if (!(keyObj instanceof String)) {
            return;
        }
        String key = (String) keyObj;
        ActiveBinding existing = ACTIVE_BINDINGS.get(key);
        if (existing != null) {
            View boundRoot = existing.root.get();
            if (boundRoot == null || boundRoot == itemView) {
                ACTIVE_BINDINGS.remove(key);
            }
        }
        itemView.setTag(TAG_ACTIVE_BIND_KEY, null);
    }

    private static String buildBindingKey(Binding binding) {
        if (binding == null) return null;
        String scene = binding.scene == null ? "unknown" : binding.scene;
        if (binding.itemId != null && binding.itemId > 0) {
            return scene + "|item|" + binding.itemId;
        }
        if (binding.rootId != null && binding.rootId > 0 && binding.parentId != null && binding.parentId >= 0) {
            return scene + "|thread|" + binding.mid + "|" + binding.rootId + "|" + binding.parentId;
        }
        return null;
    }

    private static boolean isActiveRoot(View view) {
        return view != null && view.getParent() != null;
    }

    private static boolean shouldPreferCurrentBinding(String existingHostName, String currentHostName) {
        boolean existingLinear = "LinearLayout".equals(existingHostName);
        boolean currentLinear = "LinearLayout".equals(currentHostName);
        if (existingLinear != currentLinear) {
            return existingLinear;
        }
        return false;
    }

    private static TextView ensureBlockButton(ViewGroup root, ViewGroup host, Context context) {
        Object existing = root.getTag(TAG_BUTTON_VIEW);
        if (existing instanceof TextView) {
            TextView btn = (TextView) existing;
            ViewParent parent = btn.getParent();
            if (parent instanceof ViewGroup && parent != host) {
                ((ViewGroup) parent).removeView(btn);
            }
            if (btn.getParent() == null) {
                host.addView(btn, new ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            return btn;
        }

        TextView btn = new TextView(context);
        btn.setText("拉黑");
        btn.setTextSize(11);
        btn.setTextColor(0xffFB7299);
        btn.setPadding(dp(context, 8), dp(context, 3), dp(context, 8), dp(context, 3));
        btn.setClickable(true);
        host.addView(btn, new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setTag(TAG_BUTTON_VIEW, btn);
        return btn;
    }

    private static void applyBlockedState(TextView btn, String mid) {
        if (btn == null || mid == null) return;
        BLOCKED_MIDS.add(mid);
        btn.setEnabled(false);
        btn.setText("已拉黑");
        btn.setTextColor(0xff888888);
    }

    private static ViewGroup findTopRightHost(ViewGroup root, View headerAnchor) {
        if (headerAnchor instanceof ViewGroup) {
            return (ViewGroup) headerAnchor;
        }
        if (headerAnchor != null && headerAnchor.getParent() instanceof ViewGroup) {
            return (ViewGroup) headerAnchor.getParent();
        }
        return root;
    }

    private static View findTopRightAnchor(ViewGroup root) {
        String[] candidates = {
                "header_info_root",
                "layout_header_info_layout",
                "header_info",
                "header_info_nick_layout",
                "header_info_contents"
        };
        for (String idName : candidates) {
            View view = findTopRightAnchorCandidate(root, idName);
            if (view != null) {
                return view;
            }
        }
        return null;
    }

    private static View findTopRightAnchorCandidate(ViewGroup root, String idName) {
        if (root == null || idName == null || idName.isEmpty()) return null;
        int id = resolveId(root, idName);
        if (id == 0) return null;

        ArrayDeque<View> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            View current = queue.removeFirst();
            if (current.getId() == id) {
                return current;
            }
            if (current instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) current;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    queue.addLast(vg.getChildAt(i));
                }
            }
        }
        return null;
    }

    private static void positionBlockButton(ViewGroup host, TextView btn, View anchor) {
        host.post(() -> {
            try {
                btn.bringToFront();
                btn.measure(
                        View.MeasureSpec.makeMeasureSpec(host.getWidth(), View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(host.getHeight(), View.MeasureSpec.AT_MOST)
                );

                int margin = dp(host.getContext(), 6);
                float x = Math.max(margin, host.getWidth() - btn.getMeasuredWidth() - margin);
                float y;
                if (anchor != null) {
                    float anchorTop = anchor == host ? 0 : anchor.getY();
                    y = Math.max(dp(host.getContext(), 4), anchorTop + dp(host.getContext(), 2));
                } else {
                    y = dp(host.getContext(), 4);
                }

                btn.setX(x);
                btn.setY(y);
            } catch (Throwable t) {
                ModuleLog.e("positionBlockButton failed", t);
            }
        });
    }

    private static int resolveId(View root, String idName) {
        if (root == null || idName == null || idName.isEmpty()) return 0;
        try {
            return root.getResources().getIdentifier(
                    idName, "id", root.getContext().getPackageName());
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int dp(Context context, int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    static final class Binding {
        final String mid;
        final Long itemId;
        final Long rootId;
        final Long parentId;
        final String scene;
        final InternalBlockInvoker blockInvoker;

        Binding(String mid, Long itemId, Long rootId, Long parentId, String scene,
                InternalBlockInvoker blockInvoker) {
            this.mid = mid;
            this.itemId = itemId;
            this.rootId = rootId;
            this.parentId = parentId;
            this.scene = scene;
            this.blockInvoker = blockInvoker;
        }

        boolean hasValidMid() {
            return isValidMid(mid);
        }

        boolean shouldHideInMainScene() {
            if (!isMainScene()) return false;
            boolean childByRoot = rootId != null && rootId > 0
                    && (itemId == null || !rootId.equals(itemId));
            boolean childByParent = parentId != null && parentId > 0
                    && (itemId == null || !parentId.equals(itemId));
            return childByRoot || childByParent;
        }

        private boolean isMainScene() {
            return "main".equals(scene) || "picture-main".equals(scene);
        }
    }

    interface InternalBlockInvoker {
        boolean invoke(Context context, ViewGroup itemRoot, Binding binding) throws Throwable;
    }

    private static final class ActiveBinding {
        final WeakReference<View> root;
        final String hostClassName;

        ActiveBinding(View root, String hostClassName) {
            this.root = new WeakReference<>(root);
            this.hostClassName = hostClassName;
        }
    }
}
