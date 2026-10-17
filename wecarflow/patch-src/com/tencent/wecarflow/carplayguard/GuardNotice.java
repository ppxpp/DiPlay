package com.tencent.wecarflow.carplayguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.DialogInterface;
import android.widget.CompoundButton;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import java.lang.ref.WeakReference;

/** One foreground notice across all existing pages; never changes the playback guard. */
public final class GuardNotice implements Application.ActivityLifecycleCallbacks {
    private static GuardNotice instance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final GuardNoticePolicy policy = new GuardNoticePolicy();
    private WeakReference<Activity> resumed = new WeakReference<Activity>(null);
    private int started;
    private boolean foreground;
    private AlertDialog dialog;
    private final Runnable background = new Runnable() {
        @Override public void run() { if (started == 0) foreground = false; }
    };
    private final Runnable check = new Runnable() {
        @Override public void run() { maybeShow(); }
    };

    private GuardNotice() {}

    static void install(Application app) {
        if (instance != null) return;
        instance = new GuardNotice();
        app.registerActivityLifecycleCallbacks(instance);
    }

    /** Called after guard changes, including delayed DiPlay rebinding while a page is visible. */
    static void guardChanged() {
        GuardNotice current = instance;
        if (current != null) current.main.post(current.check);
    }

    private void maybeShow() {
        if (!GuardRuntime.isGuarded()) { dismiss(); return; }
        final Activity activity = resumed.get();
        if (!foreground || activity == null || activity.isFinishing() || activity.isDestroyed()
                || dialog != null || !policy.shouldShow(true)) return;
        final CheckBox mute = new CheckBox(activity);
        mute.setText("本次不再提示");
        mute.setTextSize(18);
        int padding = (int) (20 * activity.getResources().getDisplayMetrics().density + 0.5f);
        LinearLayout content = new LinearLayout(activity);
        content.setPadding(padding, 0, padding, 0);
        content.addView(mute, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        mute.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) {
                policy.setSuppressed(checked);
            }
        });
        final AlertDialog next = new AlertDialog.Builder(activity)
                .setTitle("CarPlay 音乐使用提示")
                .setMessage("CarPlay 已连接，方控按键冲突保护正在生效。\n\n请使用 CarPlay 播放音乐，继续使用本应用播放音乐可能产生声音冲突。\n\n关闭此提示不会关闭冲突保护。")
                .setView(content)
                .setPositiveButton("关闭", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface ignored, int which) { }
                })
                .create();
        next.setCanceledOnTouchOutside(false);
        next.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface ignored) { if (dialog == next) dialog = null; }
        });
        try {
            next.show();
            dialog = next;
            policy.shown();
        } catch (RuntimeException error) {
            Log.w("CarPlayGuard", "Notice window unavailable", error);
        }
    }

    private void dismiss() {
        AlertDialog previous = dialog;
        dialog = null;
        if (previous != null) previous.dismiss();
    }

    @Override public void onActivityStarted(Activity activity) {
        ++started;
        main.removeCallbacks(background);
        if (!foreground) { foreground = true; policy.enterForeground(); }
    }
    @Override public void onActivityResumed(Activity activity) {
        resumed = new WeakReference<Activity>(activity);
        main.post(check);
    }
    @Override public void onActivityPaused(Activity activity) {
        if (resumed.get() == activity) { resumed.clear(); dismiss(); }
    }
    @Override public void onActivityStopped(Activity activity) {
        started = Math.max(0, started - 1);
        if (started == 0 && !activity.isChangingConfigurations()) main.post(background);
    }
    @Override public void onActivityDestroyed(Activity activity) {
        if (resumed.get() == activity) { resumed.clear(); dismiss(); }
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
}
