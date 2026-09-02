package net.kdt.pojavlaunch.ai;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 日志读取工具（对应 iOS AiLogReader）：read_latest_log / read_crash_report / read_logs
 */
public class AiLogReader implements AiTool {
    private final String mName;

    public AiLogReader(String name) { mName = name; }

    @Override public String name() { return mName; }

    @Override public AiToolPermission permission() { return AiToolPermission.READ_ONLY; }

    @Override public String summary() {
        switch (mName) {
            case "read_latest_log":
                return "读取当前实例的最新游戏日志（logs/latest.log）。"
                        + "\n参数：maxChars（number，可选，默认 12000，超长保留末尾截断——错误一般在日志末尾）。"
                        + "\n返回日志文本。";
            case "read_crash_report":
                return "读取最新一份崩溃报告（crash-reports 目录中最新文件）。"
                        + "\n参数：maxChars（number，可选，默认 12000，超长截断）。"
                        + "\n返回崩溃报告文本；没有崩溃报告时返回相应提示。";
            case "read_logs":
                return "综合读取启动器日志：优先返回最新崩溃报告，否则返回最新游戏日志。"
                        + "\n参数：maxChars（number，可选，默认 12000，超长截断）。"
                        + "\n返回日志文本。";
        }
        return "日志读取工具";
    }

    @Override
    public void execute(AiParams params, AiToolCallback completion) {
        try {
            int maxChars = params.optInt("maxChars", 12000);
            if (maxChars <= 0) maxChars = 12000;

            String result = null;
            switch (mName) {
                case "read_latest_log":
                    result = readLatestLog(maxChars);
                    break;
                case "read_crash_report":
                    result = readCrashReport(maxChars);
                    break;
                case "read_logs":
                    result = readCrashReport(maxChars);
                    if (result == null) result = readLatestLog(maxChars);
                    break;
            }
            if (result == null) {
                completion.onResult(null, new RuntimeException("未知工具 " + mName));
            } else {
                completion.onResult(result, null);
            }
        } catch (Exception e) {
            completion.onResult(null, e);
        }
    }

    private static String readLatestLog(int maxChars) {
        File log = new File(AiFileTools.currentGameRoot(), "logs/latest.log");
        if (!log.exists()) {
            return "（尚未生成游戏日志：logs/latest.log 不存在。请先启动一次游戏再读取。）";
        }
        String content = readText(log);
        if (content == null) return "（日志读取失败）";
        return truncateTail(content, maxChars);
    }

    private static String readCrashReport(int maxChars) {
        File crashDir = new File(Tools.DIR_HOME_CRASH);
        File[] files = crashDir.listFiles();
        if (files == null || files.length == 0) {
            return null; // 无崩溃报告（read_logs 会回退到游戏日志）
        }
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.sort(Comparator.comparingLong(File::lastModified));
        File latest = sorted.get(sorted.size() - 1);
        String content = readText(latest);
        if (content == null) return "（崩溃报告读取失败：" + latest.getName() + "）";
        return "（崩溃报告：" + latest.getName() + "）\n" + truncateTail(content, maxChars);
    }

    private static String readText(File file) {
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            for (byte b : data) {
                if (b == 0) return null;
            }
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** 保留末尾（错误一般在日志末尾） */
    private static String truncateTail(String content, int maxChars) {
        if (content.length() <= maxChars) return content;
        return "（内容过长已截断，显示末尾 " + maxChars + " 字符）\n" + content.substring(content.length() - maxChars);
    }
}
