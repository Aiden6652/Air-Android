package net.kdt.pojavlaunch.ai;

/** sleep 工具（对应 iOS AiSleepTool）：无副作用等待，用于轮询场景 */
public class AiSleepTool implements AiTool {
    @Override public String name() { return "sleep"; }

    @Override public AiToolPermission permission() { return AiToolPermission.READ_ONLY; }

    @Override
    public String summary() {
        return "暂停等待指定毫秒数（用于轮询等待某操作完成后再查询）。"
                + "\n参数：milliseconds（number，可选，默认 1000，上限 30000）。"
                + "\n返回「已等待 N 毫秒」。";
    }

    @Override
    public void execute(AiParams params, AiToolCallback completion) {
        long ms = params.optInt("milliseconds", 1000);
        if (ms <= 0) ms = 1000;
        if (ms > 30000) ms = 30000;
        try {
            Thread.sleep(ms);
            completion.onResult("已等待 " + ms + " 毫秒", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            completion.onResult("等待被中断", null);
        }
    }
}
