package net.kdt.pojavlaunch.ai;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 安全确认管理（对应 iOS AiSafetyManager）。
 * 弹 AlertDialog 确认；调用方（Agent 循环线程）阻塞等待用户选择。
 */
public class AiSafetyManager {
    private static final AiSafetyManager sInstance = new AiSafetyManager();
    private WeakReference<Activity> mActivity = new WeakReference<>(null);

    public static AiSafetyManager getInstance() {
        return sInstance;
    }

    private AiSafetyManager() {}

    /** 绑定当前 Activity（AIFragment onResume 时调用） */
    public void attach(Activity activity) {
        mActivity = new WeakReference<>(activity);
    }

    public void detach() {
        mActivity.clear();
    }

    Activity currentActivity() {
        return mActivity.get();
    }

    /**
     * 是否需要用户确认：
     * READ_ONLY 永不确认；DANGEROUS_WRITE 任何模式都确认；
     * CONTROLLED_WRITE / EXTERNAL_NETWORK 仅 YOLO 模式免确认。
     */
    public boolean needsUserConfirmation(AiToolPermission permission) {
        switch (permission) {
            case READ_ONLY: return false;
            case DANGEROUS_WRITE: return true;
            case CONTROLLED_WRITE:
            case EXTERNAL_NETWORK:
                return AiSettings.getSafetyMode() != AiSafetyMode.YOLO;
        }
        return true;
    }

    /**
     * 弹确认框（阻塞当前线程直到用户选择或 10 分钟超时，超时视为拒绝）。
     * @return 用户是否同意
     */
    public boolean requestConfirmation(final String title, final String message) {
        Activity activity = currentActivity();
        if (activity == null || activity.isFinishing()) return false;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean approved = new AtomicBoolean(false);

        activity.runOnUiThread(() -> {
            try {
                new AlertDialog.Builder(activity)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton("允许", (DialogInterface d, int w) -> {
                            approved.set(true);
                            latch.countDown();
                        })
                        .setNegativeButton("拒绝", (DialogInterface d, int w) -> latch.countDown())
                        .setOnCancelListener(d -> latch.countDown())
                        .show();
            } catch (Exception e) {
                latch.countDown();
            }
        });

        try {
            //noinspection ResultOfMethodCallIgnored
            latch.await(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return approved.get();
    }
}
