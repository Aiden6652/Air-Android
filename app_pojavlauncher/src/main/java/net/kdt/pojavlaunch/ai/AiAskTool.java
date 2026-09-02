package net.kdt.pojavlaunch.ai;

import android.app.Activity;
import android.text.Editable;
import android.text.InputType;
import android.widget.EditText;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 交互问答工具（对应 iOS AiAskTool）：向用户提问并等待回答。
 * 阻塞 Agent 循环线程直到用户提交/取消（最多 10 分钟，超时视为取消）。
 */
public class AiAskTool implements AiTool {
    @Override public String name() { return "ask"; }

    @Override public AiToolPermission permission() { return AiToolPermission.READ_ONLY; }

    @Override
    public String summary() {
        return "向用户提出问题并等待回答（弹窗输入）。"
                + "\n参数：question（string，必填，要问用户的问题，可以包含多个选项让用户选）。"
                + "\n返回用户输入的文本；用户取消时返回「用户取消了回答」。"
                + "\n在需要确认版本/加载器/目录等关键信息时务必先用此工具询问用户，不要擅自决定。";
    }

    @Override
    public void execute(AiParams params, AiToolCallback completion) {
        String question = params.optString("question", "");
        if (question.isEmpty()) {
            completion.onResult(null, new RuntimeException("参数 question 必填"));
            return;
        }

        Activity activity = currentActivity();
        if (activity == null || activity.isFinishing()) {
            completion.onResult("（当前无法弹出提问窗口，请直接给出文字建议）", null);
            return;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> answer = new AtomicReference<>(null);

        activity.runOnUiThread(() -> {
            try {
                final EditText input = new EditText(activity);
                input.setInputType(InputType.TYPE_CLASS_TEXT);
                input.setHint("输入回答…");
                input.setPadding(48, 32, 48, 16);
                new android.app.AlertDialog.Builder(activity)
                        .setTitle("AI 助手提问")
                        .setMessage(question)
                        .setView(input)
                        .setPositiveButton("提交", (d, w) -> {
                            Editable text = input.getText();
                            answer.set(text == null ? "" : text.toString().trim());
                            latch.countDown();
                        })
                        .setNegativeButton("取消", (d, w) -> latch.countDown())
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
        String result = answer.get();
        if (result == null || result.isEmpty()) {
            completion.onResult("用户取消了回答", null);
        } else {
            completion.onResult(result, null);
        }
    }

    private static Activity currentActivity() {
        return AiSafetyManager.getInstance().currentActivity();
    }
}
