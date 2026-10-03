package com.fongmi.android.tv.ui.guide;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.activity.SubSettingActivity;
import com.github.catvod.utils.Prefers;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

// 首次安装新手引导状态机（仅手机端）：跟做式引导——遮罩挖洞高亮真实控件，
// 用户点击目标控件才推进下一步；跨页面接力，进度持久化，中断后下次启动续导。
public class GuideManager {

    // ===== 步骤 id =====
    private static final String ASK = "ask";                 // 询问：是否已有订阅源链接
    private static final String KEYWORDS = "keywords";       // 搜索引擎关键词卡
    private static final String HOME_ADD = "home_add";       // 首页空态「添加订阅」
    private static final String CFG_ADD = "cfg_add";         // 点播管理「添加」
    private static final String CFG_INPUT = "cfg_input";     // 添加弹窗链接输入框
    private static final String CFG_CONFIRM = "cfg_confirm"; // 添加弹窗「确定」
    private static final String SAVED_BACK = "saved_back";   // 保存成功→返回首页
    private static final String DONE = "done";               // 完成卡（合并功能介绍）
    private static final String MERGE_EXPLORE = "merge_explore"; // 高亮点播/直播源合并
    private static final String FINISH = "finish";           // 结束卡

    private static final String KEY_DONE = "guide_done";
    private static final String KEY_STEP = "guide_step";
    private static final String KEY_BRANCH = "guide_branch";
    private static final String BRANCH_HAS = "has";
    private static final String BRANCH_NO = "no";

    private static final String[] HAS_CHAIN = {HOME_ADD, CFG_ADD, CFG_INPUT, CFG_CONFIRM, SAVED_BACK};

    private static GuideManager sInstance;

    public static GuideManager get() {
        if (sInstance == null) sInstance = new GuideManager();
        return sInstance;
    }

    private String step; // 进程内当前步骤（null = 本进程尚未启动引导）
    private final Map<String, List<WeakReference<View>>> registry = new HashMap<>(); // 各页面已就绪目标控件
    private final List<WeakReference<Activity>> chain = new ArrayList<>(); // 引导期间进入过的非首页 Activity（返回首页时统一关闭）
    private GuideOverlayView overlay;
    private AlertDialog card;
    private Activity cardActivity;
    private Activity lastActivity;
    private final GuideOverlayView.Callback callback = new GuideOverlayView.Callback() {
        @Override
        public void onSkip() {
            skip();
        }

        @Override
        public void onContinue() {
            advance(lastActivity);
        }
    };

    public boolean isDone() {
        return Prefers.getBoolean(KEY_DONE);
    }

    // ===== 页面挂载点 =====

    // HomeActivity 权限链走完后调用：冷启动续导 / 首次弹询问卡
    public void startIfNeeded(Activity a) {
        if (isDone() || a == null || a.isFinishing() || a.isDestroyed()) return;
        // 其它弹窗（悬浮窗权限提醒等）持有焦点时让路，待焦点返回后由 onWindowFocusChanged 再次触发
        if (!a.hasWindowFocus()) return;
        if (step == null) {
            String saved = Prefers.getString(KEY_STEP);
            step = saved.isEmpty() ? ASK : resume(saved);
            Prefers.put(KEY_STEP, step);
        }
        refresh(a);
    }

    // 页面重新拿到焦点时兜底重检：目标仍在→重贴遮罩；目标已消失（弹窗取消/手动返回）→沿链回退到最近可引导步骤
    public void refresh(Activity a) {
        if (isDone() || step == null || a == null || a.isFinishing() || a.isDestroyed()) return;
        // 无焦点说明有弹窗/其它页在前，等它关闭后的焦点事件再重检，避免误回退
        if (!a.hasWindowFocus()) return;
        // 用户没点「返回首页」而是手动返回：回到首页时把回首页卡折算为完成卡
        if (a instanceof HomeActivity && SAVED_BACK.equals(step)) setStep(DONE);
        if (isCenter(step)) {
            if (card == null || !card.isShowing() || cardActivity != a) showCenter(a);
            return;
        }
        List<View> live = liveViews(targetKey(step));
        if (live.isEmpty() || actOf(live.get(0)) != a) {
            String prev = prevLiveStepOn(a);
            if (prev == null) {
                // 首页已非空时「添加订阅」目标永不出现：直接跳完成卡，避免引导隐身
                if (HOME_ADD.equals(step) && a instanceof HomeActivity && !homeEmpty()) {
                    setStep(DONE);
                    showCenter(a);
                    return;
                }
                // 本页面无可引导的目标：保持当前步骤，等该页上报就绪或用户走回正确页面
                detachOverlay();
                return;
            }
            setStep(prev);
            if (isCenter(prev)) {
                showCenter(a);
                return;
            }
            live = liveViews(targetKey(prev));
        }
        if (!live.isEmpty()) attach(live);
    }

    // 沿引导链回退，只接受目标就在当前页面（或居中卡且当前是首页）的步骤，避免把遮罩贴到后台页面
    private String prevLiveStepOn(Activity a) {
        String s = step;
        for (int i = 0; i < 8; i++) {
            s = prev(s);
            if (s == null) return null;
            if (isCenter(s)) {
                if (!(a instanceof HomeActivity)) return null;
                // 已回到首页且源已非空：询问卡无意义，直接进完成卡
                if (ASK.equals(s) && !homeEmpty()) return DONE;
                return s;
            }
            List<View> live = liveViews(targetKey(s));
            if (!live.isEmpty() && actOf(live.get(0)) == a) return s;
        }
        return null;
    }

    private static String prev(String s) {
        if (s == null) return null;
        switch (s) {
            case HOME_ADD:
                return ASK;
            case CFG_ADD:
                return HOME_ADD;
            case CFG_INPUT:
            case CFG_CONFIRM:
                return CFG_ADD;
            case MERGE_EXPLORE:
                return DONE;
            default:
                return null;
        }
    }

    // 页面目标控件就绪上报（视图可能尚未生成，重试约 2.4 秒覆盖慢机型的工具栏首次布局）
    public void report(Activity a, String key, Supplier<View> supplier) {
        report(a, key, supplier, 8);
    }

    private void report(Activity a, String key, Supplier<View> supplier, int retry) {
        if (isDone()) return;
        View view;
        try {
            view = supplier.get();
        } catch (Throwable ignored) {
            // 页面/弹窗已销毁，binding 为空：放弃本次上报，页面重建后会重新注册
            return;
        }
        if (view == null) {
            if (retry > 0) App.post(() -> report(a, key, supplier, retry - 1), 300);
            return;
        }
        onTargetReady(a, key, view);
    }

    // 页面目标控件就绪上报（多控件，如合并两行同时高亮）
    public void onTargetReady(Activity a, String key, View... views) {
        if (isDone() || views.length == 0) return;
        List<WeakReference<View>> refs = new ArrayList<>();
        for (View view : views) refs.add(new WeakReference<>(view));
        registry.put(key, refs);
        if (step != null && key.equals(targetKey(step))) {
            List<View> live = liveViews(targetKey(step));
            if (!live.isEmpty()) attach(live);
        }
    }

    // 页面动作完成上报：action 与当前步骤匹配才推进
    public void onActionDone(Activity a, String action) {
        if (isDone() || step == null) return;
        if (!action.equals(actionKey(step))) return;
        advance(a);
    }

    // ===== 状态推进 =====

    private void advance(Activity a) {
        String next = next(step);
        if (next == null) return;
        setStep(next);
        present(a);
    }

    private void setStep(String next) {
        step = next;
        Prefers.put(KEY_STEP, next);
    }

    private void present(Activity a) {
        if (isCenter(step)) {
            detachOverlay();
            showCenter(a);
        } else {
            dismissCard();
            List<View> live = liveViews(targetKey(step));
            if (live.isEmpty()) {
                // 首页已非空时「添加订阅」目标永不出现：直接跳完成卡，避免引导隐身
                if (HOME_ADD.equals(step) && !homeEmpty()) {
                    setStep(DONE);
                    showCenter(a);
                    return;
                }
                detachOverlay();
            } else {
                attach(live);
            }
        }
    }

    private void attach(List<View> views) {
        if (views.isEmpty()) return;
        Activity act = actOf(views.get(0));
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        View root = views.get(0).getRootView();
        if (!(root instanceof ViewGroup)) return;
        if (overlay == null) overlay = new GuideOverlayView(act);
        if (overlay.getParent() != root) {
            if (overlay.getParent() != null) ((ViewGroup) overlay.getParent()).removeView(overlay);
            ((ViewGroup) root).addView(overlay, new ViewGroup.LayoutParams(-1, -1));
        }
        lastActivity = act;
        track(act);
        // 目标在滚动容器折叠区外时先滚到可见，否则洞在屏幕外、洞外又被拦截，用户会卡死
        Rect rect = new Rect();
        for (View view : views) {
            if (view.getVisibility() == View.VISIBLE && view.getWidth() > 0) {
                rect.set(0, 0, view.getWidth(), view.getHeight());
                view.requestRectangleOnScreen(rect, false);
            }
        }
        overlay.show(views, bubbleText(step), counterIndex(step), counterTotal(step), MERGE_EXPLORE.equals(step), callback);
    }

    private void detachOverlay() {
        if (overlay != null) {
            overlay.detach();
            overlay = null;
        }
    }

    private void skip() {
        complete();
    }

    // 引导彻底结束（完成或跳过）：持久化 done，清理全部界面与内存状态
    private void complete() {
        Prefers.put(KEY_DONE, true);
        Prefers.remove(KEY_STEP);
        Prefers.remove(KEY_BRANCH);
        step = null;
        detachOverlay();
        dismissCard();
        registry.clear();
        chain.clear();
        lastActivity = null;
    }

    // 居中卡片按钮统一入口：记录分支 → 切步骤 → 呈现
    private void go(Activity a, String next, String branch) {
        if (branch != null) Prefers.put(KEY_BRANCH, branch);
        setStep(next);
        present(a);
    }

    // ===== 居中卡片 =====

    private void showCenter(Activity a) {
        if (a == null || a.isFinishing() || a.isDestroyed()) return;
        dismissCard();
        lastActivity = a;
        cardActivity = a;
        track(a);
        View content = LayoutInflater.from(a).inflate(R.layout.dialog_guide_card, null);
        TextView title = content.findViewById(R.id.title);
        TextView message = content.findViewById(R.id.message);
        TextView cancel = content.findViewById(R.id.cancel);
        TextView confirm = content.findViewById(R.id.confirm);
        TextView skipView = content.findViewById(R.id.skip);
        AlertDialog dialog = new AlertDialog.Builder(a).setView(content).setCancelable(false).create();
        card = dialog;
        skipView.setOnClickListener(v -> skip());
        switch (step) {
            case ASK:
                title.setText(R.string.guide_ask_title);
                message.setText(R.string.guide_ask_message);
                cancel.setVisibility(View.VISIBLE);
                cancel.setText(R.string.guide_ask_no);
                cancel.setOnClickListener(v -> go(a, KEYWORDS, BRANCH_NO));
                confirm.setText(R.string.guide_ask_has);
                confirm.setOnClickListener(v -> go(a, HOME_ADD, BRANCH_HAS));
                break;
            case KEYWORDS:
                title.setText(R.string.guide_kw_title);
                message.setText(R.string.guide_kw_message);
                cancel.setVisibility(View.GONE);
                confirm.setText(R.string.guide_kw_got);
                confirm.setOnClickListener(v -> go(a, HOME_ADD, BRANCH_HAS));
                break;
            case SAVED_BACK:
                title.setText(R.string.guide_saved_title);
                message.setText(R.string.guide_saved_message);
                cancel.setVisibility(View.GONE);
                confirm.setText(R.string.guide_back_home);
                confirm.setOnClickListener(v -> returnHome(a));
                break;
            case DONE:
                title.setText(R.string.guide_done_title);
                message.setText(R.string.guide_done_message);
                cancel.setVisibility(View.VISIBLE);
                cancel.setText(R.string.guide_done_look);
                cancel.setOnClickListener(v -> {
                    setStep(MERGE_EXPLORE);
                    dismissCard();
                    SubSettingActivity.start(a, 5);
                });
                confirm.setText(R.string.guide_done_finish);
                confirm.setOnClickListener(v -> go(a, FINISH, null));
                break;
            case FINISH:
                title.setText(R.string.guide_finish_title);
                message.setText(R.string.guide_finish_message);
                cancel.setVisibility(View.GONE);
                skipView.setVisibility(View.GONE);
                confirm.setText(R.string.guide_finish_ok);
                confirm.setOnClickListener(v -> complete());
                break;
            default:
                break;
        }
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }

    // 关闭引导期间进入过的全部非首页页面，回到首页（首页聚焦后自动续接 DONE 卡）
    private void returnHome(Activity a) {
        setStep(DONE);
        dismissCard();
        detachOverlay();
        for (WeakReference<Activity> ref : new ArrayList<>(chain)) {
            Activity act = ref.get();
            if (act != null && !act.isFinishing() && !act.isDestroyed()) act.finish();
        }
        chain.clear();
    }

    private void dismissCard() {
        AlertDialog dialog = card;
        card = null;
        cardActivity = null;
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    private void track(Activity a) {
        if (a instanceof HomeActivity) return;
        for (WeakReference<Activity> ref : chain) if (ref.get() == a) return;
        chain.add(new WeakReference<>(a));
    }

    // ===== 步骤映射表 =====

    private static boolean isCenter(String step) {
        return ASK.equals(step) || KEYWORDS.equals(step) || SAVED_BACK.equals(step)
                || DONE.equals(step) || FINISH.equals(step);
    }

    private static String targetKey(String step) {
        switch (step) {
            case HOME_ADD:
                return "home_add";
            case CFG_ADD:
                return "cfg_add";
            case CFG_INPUT:
                return "cfg_input";
            case CFG_CONFIRM:
                return "cfg_confirm";
            case MERGE_EXPLORE:
                return "merge_explore";
            default:
                return null;
        }
    }

    private static String actionKey(String step) {
        switch (step) {
            case CFG_INPUT:
                return "cfg_input_done";
            case CFG_CONFIRM:
                return "cfg_confirm_done";
            default:
                return targetKey(step);
        }
    }

    private static String next(String step) {
        if (step == null) return null;
        switch (step) {
            case HOME_ADD:
                return CFG_ADD;
            case CFG_ADD:
                return CFG_INPUT;
            case CFG_INPUT:
                return CFG_CONFIRM;
            case CFG_CONFIRM:
                return SAVED_BACK;
            case SAVED_BACK:
                return DONE;
            case MERGE_EXPLORE:
                return FINISH;
            default:
                return null;
        }
    }

    // 冷启动续导：把链中步骤折算到可在首页重新起步的锚点步骤（历史存档的社区链步骤自动折算）
    private String resume(String saved) {
        if (ASK.equals(saved) || KEYWORDS.equals(saved)) return saved;
        if (DONE.equals(saved) || MERGE_EXPLORE.equals(saved) || FINISH.equals(saved)) return DONE;
        return homeEmpty() ? (contains(HAS_CHAIN, saved) ? HOME_ADD : KEYWORDS) : DONE;
    }

    private static boolean contains(String[] arr, String value) {
        for (String item : arr) if (item.equals(value)) return true;
        return false;
    }

    private static boolean homeEmpty() {
        return VodConfig.get().getSites().isEmpty();
    }

    private static int counterIndex(String step) {
        int idx = indexOf(HAS_CHAIN, step);
        return idx >= 0 ? idx + 1 : 0;
    }

    private static int counterTotal(String step) {
        return indexOf(HAS_CHAIN, step) >= 0 ? HAS_CHAIN.length : 0;
    }

    private static int indexOf(String[] arr, String value) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(value)) return i;
        return -1;
    }

    private static int bubbleText(String step) {
        switch (step) {
            case CFG_ADD:
                return R.string.guide_cfg_add;
            case CFG_INPUT:
                return R.string.guide_cfg_input;
            case CFG_CONFIRM:
                return R.string.guide_cfg_confirm;
            case MERGE_EXPLORE:
                return R.string.guide_merge_explore;
            default:
                return R.string.guide_home_add;
        }
    }

    // ===== 工具 =====

    private List<View> liveViews(String key) {
        List<View> result = new ArrayList<>();
        if (key == null) return result;
        List<WeakReference<View>> refs = registry.get(key);
        if (refs == null) return result;
        for (WeakReference<View> ref : refs) {
            View view = ref.get();
            if (view != null && view.getWindowToken() != null && view.isAttachedToWindow()) result.add(view);
        }
        return result;
    }

    private static Activity actOf(View view) {
        Context context = view.getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
