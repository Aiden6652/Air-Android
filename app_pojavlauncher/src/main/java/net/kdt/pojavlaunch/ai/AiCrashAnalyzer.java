package net.kdt.pojavlaunch.ai;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 崩溃分析工具（对应 iOS AiCrashAnalyzer）：match_known_errors
 * 对最新崩溃报告/游戏日志做本地规则匹配，返回已知错误与建议。
 */
public class AiCrashAnalyzer implements AiTool {
    @Override public String name() { return "match_known_errors"; }

    @Override public AiToolPermission permission() { return AiToolPermission.READ_ONLY; }

    @Override
    public String summary() {
        return "分析崩溃报告/游戏日志中的已知错误（本地规则匹配，非 AI）。"
                + "\n参数：无（自动读取最新崩溃报告，无则读最新游戏日志）。"
                + "\n返回 JSON 数组 {error, suggestion}；无匹配时返回空数组。";
    }

    /** 规则表：匹配关键字（小写包含）→ 建议文案（Android 环境相关） */
    private static final String[][] RULES = {
            {"outofmemoryerror", "内存不足（OOM）。建议在实例设置中调小内存分配（-Xmx），或关闭后台应用后重试。"},
            {"unsupportedclassversionerror", "Java 版本不匹配。旧版 MC（1.16.5-）需要 Java 8，新版需要 Java 17+，请在运行时设置中切换 JRE。"},
            {"gl_invalid_operation", "OpenGL 操作错误。多为渲染器兼容性问题，建议在实例设置中切换渲染器（如 Zink / ANGLE / gl4es）。"},
            {"gl_out_of_memory", "显卡/渲染内存不足。建议降低游戏渲染距离与画质，或更换渲染器。"},
            {"could not create window", "窗口创建失败，多为渲染器问题。建议切换渲染器（gl4es / Zink / VirGL / ANGLE）。"},
            {"virgl", "VirGL 渲染器相关错误。VirGL 需要设备支持，建议尝试切换为 Zink 或 gl4es。"},
            {"zink", "Zink 渲染器相关错误。Zink 依赖 Vulkan，若设备不支持建议切换为 gl4es。"},
            {"shader", "着色器/光影相关错误。建议更换或移除光影包；Iris 需要配套 Sodium 使用。"},
            {"missing or unsupported", "缺少前置组件或版本不匹配。请检查 Mod 前置与 MC 版本、加载器版本是否匹配。"},
            {"modresolutionexception", "Fabric Mod 依赖解析失败。日志中会列出缺失的前置 Mod，需要先安装对应前置。"},
            {"duplicate mod", "Mod 重复安装（同名 Mod 存在多份）。请删除 mods 目录中重复的旧版本文件。"},
            {"lwjgl", "LWJGL 相关错误。尝试清除并重新解压运行时组件，或更换渲染器。"},
            {"exit code 1", "通用启动失败。请结合完整日志判断；常见原因是 Mod 不兼容或内存不足。"},
            {"exit code -1073741819", "原生库崩溃（0xC0000005）。多为渲染器或驱动问题，建议切换渲染器。"},
            {"filenotfoundexception", "文件缺失。请根据日志中的路径补齐文件，或重新安装对应组件。"},
            {"access denied", "文件访问被拒。检查存储权限，或文件是否被其它程序占用。"},
            {"incompatible", "版本不兼容。Mod/组件与当前 MC 版本或加载器不匹配。"},
            {"sodium", "Sodium 相关。注意：某些修改版 Sodium 需要配套前置（如 Podium）屏蔽检测，否则会强制崩溃。"},
            {"securityexception", "安全策略阻止了类加载。多为 Java 版本或 JVM 参数问题。"},
            {"stack overflow", "栈溢出。可能是 JVM 参数问题或 Mod 死循环。"},
    };

    @Override
    public void execute(AiParams params, AiToolCallback completion) {
        try {
            String content = readSource();
            if (content == null) {
                completion.onResult("（没有可分析的日志/崩溃报告）", null);
                return;
            }
            String lower = content.toLowerCase();
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (String[] rule : RULES) {
                String keyword = rule[0].toLowerCase();
                if (lower.contains(keyword)) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{\"error\":").append(AiFileTools.quote(rule[0]))
                      .append(",\"suggestion\":").append(AiFileTools.quote(rule[1]))
                      .append("}");
                }
            }
            sb.append("]");
            completion.onResult(sb.toString(), null);
        } catch (Exception e) {
            completion.onResult(null, e);
        }
    }

    private static String readSource() {
        // 优先最新崩溃报告
        try {
            File crashDir = new File(net.kdt.pojavlaunch.Tools.DIR_HOME_CRASH);
            File[] files = crashDir.listFiles();
            if (files != null && files.length > 0) {
                File latest = files[0];
                for (File f : files) {
                    if (f.lastModified() > latest.lastModified()) latest = f;
                }
                byte[] data = Files.readAllBytes(latest.toPath());
                return new String(data, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        // 回退最新游戏日志
        try {
            File log = new File(AiFileTools.currentGameRoot(), "logs/latest.log");
            if (log.exists()) {
                byte[] data = Files.readAllBytes(log.toPath());
                return new String(data, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
