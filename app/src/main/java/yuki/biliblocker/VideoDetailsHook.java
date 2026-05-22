package yuki.biliblocker;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class VideoDetailsHook {

    private static final Set<String> BLOCKED_MIDS = Collections.synchronizedSet(new HashSet<>());
    private static final String BTN_TAG = "bili_blocker_video_author_btn";

    public static void init(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> adapterClass = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView$Adapter", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(adapterClass, "bindViewHolder",
                    "androidx.recyclerview.widget.RecyclerView$ViewHolder",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            try {
                                Object holder = param.args[0];
                                if (holder == null) return;
                                
                                String holderName = holder.getClass().getName();
                                if (holderName.equals("com.bilibili.app.gemini.ui.UIComponentViewHolder")) {
                                    ModuleLog.w("VideoDetailsHook: bindViewHolder UIComponentViewHolder", true);
                                    Object bField = XposedHelpers.getObjectField(holder, "b");
                                    if (bField != null) {
                                        ModuleLog.w("VideoDetailsHook: bField=" + bField.getClass().getName(), true);
                                    } else {
                                        ModuleLog.w("VideoDetailsHook: bField is null", true);
                                    }
                                }

                                if (!holderName.equals("com.bilibili.app.gemini.ui.UIComponentViewHolder")) {
                                    return;
                                }

                                Object bField = XposedHelpers.getObjectField(holder, "b");
                                if (bField == null || !bField.getClass().getName().equals(
                                        "com.bilibili.ship.theseus.united.page.intro.module.owner.OwnerComponent")) {
                                    return;
                                }

                                View itemView = (View) XposedHelpers.getObjectField(holder, "itemView");
                                if (itemView == null) return;

                                // 提取 mid
                                long mid = 0L;
                                try {
                                    Object state = XposedHelpers.getObjectField(bField, "c");
                                    if (state != null) {
                                        Object followCallback = XposedHelpers.getObjectField(state, "c");
                                        if (followCallback != null) {
                                            Long midLong = (Long) XposedHelpers.getObjectField(followCallback, "c");
                                            if (midLong != null) {
                                                mid = midLong;
                                            }
                                        }
                                    }
                                } catch (Throwable t) {
                                    ModuleLog.e("VideoDetailsHook: failed to get mid", t);
                                }

                                if (mid == 0L) {
                                    ModuleLog.w("VideoDetailsHook: mid is 0, skip", true);
                                    return;
                                }

                                // 寻找 author_layout 并注入按钮
                                int authorLayoutId = itemView.getResources().getIdentifier(
                                        "author_layout", "id", itemView.getContext().getPackageName());
                                if (authorLayoutId != 0) {
                                    View authorLayout = itemView.findViewById(authorLayoutId);
                                    if (authorLayout instanceof ViewGroup) {
                                        injectBlockButton((ViewGroup) authorLayout, itemView, authorLayoutId, String.valueOf(mid));
                                    }
                                }

                            } catch (Throwable t) {
                                ModuleLog.e("VideoDetailsHook bind error: " + t, t);
                            }
                        }
                    });
            ModuleLog.i("✓ VideoDetailsHook registered", true);
        } catch (Throwable t) {
            ModuleLog.e("✗ VideoDetailsHook register failed", t);
        }
    }

    private static void setPriorityIfPossible(ViewGroup.LayoutParams lp, int priority) {
        if (lp == null) return;
        try {
            java.lang.reflect.Field fieldA = lp.getClass().getDeclaredField("a");
            fieldA.setAccessible(true);
            fieldA.setInt(lp, priority);
            ModuleLog.w("VideoDetailsHook: set priority field a to " + priority, true);
        } catch (Throwable t) {
            // ignore
        }
        try {
            java.lang.reflect.Field fieldB = lp.getClass().getDeclaredField("b");
            fieldB.setAccessible(true);
            fieldB.setInt(lp, priority);
            ModuleLog.w("VideoDetailsHook: set priority field b to " + priority, true);
        } catch (Throwable t) {
            // ignore
        }
    }

    private static void logChildrenLayoutParams(ViewGroup layout) {
        try {
            int count = layout.getChildCount();
            ModuleLog.w("VideoDetailsHook: logChildrenLayoutParams count=" + count, true);
            for (int i = 0; i < count; i++) {
                View child = layout.getChildAt(i);
                ViewGroup.LayoutParams lp = child.getLayoutParams();
                if (lp == null) {
                    ModuleLog.w("  child[" + i + "]=" + child.getClass().getName() + " lp is null", true);
                    continue;
                }
                String lpClassName = lp.getClass().getName();
                String fieldInfo = "";
                try {
                    java.lang.reflect.Field fieldA = lp.getClass().getDeclaredField("a");
                    fieldA.setAccessible(true);
                    fieldInfo += " a=" + fieldA.get(lp);
                } catch (Throwable t) {}
                try {
                    java.lang.reflect.Field fieldB = lp.getClass().getDeclaredField("b");
                    fieldB.setAccessible(true);
                    fieldInfo += " b=" + fieldB.get(lp);
                } catch (Throwable t) {}
                ModuleLog.w("  child[" + i + "]=" + child.getClass().getName() + " id=" + child.getId() + " lp=" + lpClassName + fieldInfo, true);
            }
        } catch (Throwable t) {
            ModuleLog.e("VideoDetailsHook: logChildrenLayoutParams failed", t);
        }
    }

    private static void injectBlockButton(ViewGroup layout, View itemView, final int authorLayoutId, final String mid) {
        TextView btn = (TextView) itemView.findViewWithTag(BTN_TAG);
        if (btn != null) {
            // 按钮已存在，同步更新状态即可，无需 addView
            layout.setTag(authorLayoutId, mid);
            updateButtonState(btn, mid, layout);
        } else {
            // 按钮不存在，延迟到 post 中安全 addView，并用 Tag 校验防止 ViewHolder 复用错位
            layout.setTag(authorLayoutId, mid);
            layout.post(() -> {
                try {
                    if (!mid.equals(layout.getTag(authorLayoutId))) {
                        return;
                    }
                    TextView currentBtn = (TextView) itemView.findViewWithTag(BTN_TAG);
                    if (currentBtn == null) {
                        try {
                            // 方案 A：使用LayoutInflater加载
                            currentBtn = (TextView) android.view.LayoutInflater.from(layout.getContext())
                                    .inflate(android.R.layout.simple_list_item_1, layout, false);
                        } catch (Throwable t) {
                            ModuleLog.e("VideoDetailsHook: inflate simple_list_item_1 failed", t);
                            currentBtn = new TextView(layout.getContext());
                        }

                        currentBtn.setTag(BTN_TAG);
                        currentBtn.setTextSize(11);
                        currentBtn.setGravity(Gravity.CENTER);
                        currentBtn.setText("");
                        currentBtn.setPadding(dp2px(layout.getContext(), 4), dp2px(layout.getContext(), 1),
                                       dp2px(layout.getContext(), 4), dp2px(layout.getContext(), 1));
                        
                        logChildrenLayoutParams(layout);

                        // Ensure layout params are generated, set unique priority
                        ViewGroup.LayoutParams lp = currentBtn.getLayoutParams();
                        if (lp == null) {
                            try {
                                lp = (ViewGroup.LayoutParams) XposedHelpers.callMethod(layout, "generateDefaultLayoutParams");
                                currentBtn.setLayoutParams(lp);
                            } catch (Throwable t) {
                                ModuleLog.e("VideoDetailsHook: generateDefaultLayoutParams failed", t);
                            }
                        }
                        if (lp != null) {
                            setPriorityIfPossible(lp, 99);
                        }

                        boolean added = false;
                        // 优先尝试直接添加到 layout
                        try {
                            layout.addView(currentBtn);
                            added = true;
                            ModuleLog.i("VideoDetailsHook: added button to author_layout directly", true);
                        } catch (Throwable t) {
                            ModuleLog.e("VideoDetailsHook: addView to author_layout failed, trying parent", t);
                        }

                        // 降级方案：添加到 layout.getParent() 同级
                        if (!added) {
                            try {
                                ViewGroup parent = (ViewGroup) layout.getParent();
                                if (parent != null) {
                                    int index = parent.indexOfChild(layout);
                                    if (index >= 0) {
                                        LinearLayout.LayoutParams lpParent = new LinearLayout.LayoutParams(
                                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                                ViewGroup.LayoutParams.WRAP_CONTENT
                                        );
                                        lpParent.leftMargin = dp2px(layout.getContext(), 8);
                                        lpParent.gravity = Gravity.CENTER_VERTICAL;
                                        currentBtn.setLayoutParams(lpParent);

                                        parent.addView(currentBtn, index + 1);
                                        added = true;
                                        ModuleLog.i("VideoDetailsHook: added button to parent view group", true);
                                    }
                                }
                            } catch (Throwable t) {
                                ModuleLog.e("VideoDetailsHook: addView to parent failed", t);
                            }
                        }

                        if (!added) {
                            ModuleLog.e("VideoDetailsHook: failed to add button to any group", null);
                            return;
                        }

                        // 仅当添加到 layout 成功时，设置 LayoutParams 并且再次确保 priority 是 99
                        if (currentBtn.getParent() == layout) {
                            ViewGroup.LayoutParams currentLp = currentBtn.getLayoutParams();
                            if (currentLp != null) {
                                currentLp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                                currentLp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                                if (currentLp instanceof ViewGroup.MarginLayoutParams) {
                                    ((ViewGroup.MarginLayoutParams) currentLp).leftMargin = dp2px(layout.getContext(), 8);
                                }
                                if (currentLp instanceof LinearLayout.LayoutParams) {
                                    ((LinearLayout.LayoutParams) currentLp).gravity = Gravity.CENTER_VERTICAL;
                                }
                                setPriorityIfPossible(currentLp, 99);
                                currentBtn.setLayoutParams(currentLp);
                            }
                        }
                    }
                    updateButtonState(currentBtn, mid, layout);
                } catch (Throwable t) {
                    ModuleLog.e("VideoDetailsHook: post addView error", t);
                }
            });
        }
    }

    private static void updateButtonState(final TextView btn, final String mid, final ViewGroup layout) {
        btn.setEnabled(true);
        if (BLOCKED_MIDS.contains(mid)) {
            applyBlockedState(btn);
        } else {
            btn.setText("拉黑");
            btn.setTextColor(Color.parseColor("#FF5A5F"));
            btn.setBackground(null);
            btn.setOnClickListener(v -> {
                btn.setEnabled(false);
                btn.setText("正在拉黑...");
                BlockHelper.blockUser(v.getContext(), mid, new BlockHelper.BlockCallback() {
                    @Override
                    public void onSuccess(String toast) {
                        v.post(() -> {
                            BLOCKED_MIDS.add(mid);
                            applyBlockedState(btn);
                            if (toast != null && !toast.isEmpty()) {
                                Toast.makeText(v.getContext(), toast, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        v.post(() -> {
                            if (BlockHelper.isAlreadyBlockedMessage(error)) {
                                BLOCKED_MIDS.add(mid);
                                applyBlockedState(btn);
                                Toast.makeText(v.getContext(), "该用户已在黑名单中", Toast.LENGTH_SHORT).show();
                            } else {
                                btn.setEnabled(true);
                                btn.setText("拉黑");
                                Toast.makeText(v.getContext(), "拉黑失败: " + error, Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
            });
        }
    }

    private static void applyBlockedState(TextView btn) {
        btn.setEnabled(false);
        btn.setText("已拉黑");
        btn.setTextColor(Color.parseColor("#888888"));
        btn.setBackground(null);
        btn.setOnClickListener(null);
    }

    private static int dp2px(Context context, float dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
