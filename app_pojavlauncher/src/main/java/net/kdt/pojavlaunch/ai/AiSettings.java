package net.kdt.pojavlaunch.ai;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;

import net.kdt.pojavlaunch.PojavApplication;

/**
 * AI 设置（对应 iOS AiSettings）：SharedPreferences 持久化。
 * v1 使用单个提供商配置（JSON 存储），后续可扩展多提供商。
 */
public class AiSettings {
    private static final String PREFS = "ai_settings";
    private static final String KEY_PROVIDER = "ai.provider_json";
    private static final String KEY_SAFETY_MODE = "ai.safety_mode";
    private static final String KEY_MARKDOWN = "ai.markdown_enabled";
    private static final String KEY_SYSTEM_PROMPT = "ai.systemPrompt";

    private static Context appContext() {
        return PojavApplication.getAppContext();
    }

    private static SharedPreferences prefs() {
        return appContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ===== 提供商 =====

    public static AiProvider getProvider() {
        String json = prefs().getString(KEY_PROVIDER, null);
        if (json != null) {
            try {
                AiProvider p = new Gson().fromJson(json, AiProvider.class);
                if (p != null) return p;
            } catch (Exception ignored) {}
        }
        return new AiProvider();
    }

    public static void setProvider(AiProvider provider) {
        prefs().edit().putString(KEY_PROVIDER, new Gson().toJson(provider)).apply();
    }

    // ===== 安全模式 =====

    public static AiSafetyMode getSafetyMode() {
        return AiSafetyMode.fromInt(prefs().getInt(KEY_SAFETY_MODE, AiSafetyMode.SAFE.ordinal()));
    }

    public static void setSafetyMode(AiSafetyMode mode) {
        prefs().edit().putInt(KEY_SAFETY_MODE, mode.ordinal()).apply();
    }

    // ===== Markdown =====

    public static boolean isMarkdownEnabled() {
        return prefs().getBoolean(KEY_MARKDOWN, true);
    }

    public static void setMarkdownEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_MARKDOWN, enabled).apply();
    }

    // ===== 系统提示词 =====

    public static String getSystemPrompt() {
        String prompt = prefs().getString(KEY_SYSTEM_PROMPT, null);
        if (prompt == null || prompt.trim().isEmpty()) return defaultSystemPrompt();
        return prompt;
    }

    public static void setSystemPrompt(String prompt) {
        prefs().edit().putString(KEY_SYSTEM_PROMPT, prompt == null ? "" : prompt).apply();
    }

    public static void resetSystemPrompt() {
        prefs().edit().remove(KEY_SYSTEM_PROMPT).apply();
    }

    /** 默认系统提示词（Android/Pojav 版，改写自 iOS AiSettings.m） */
    public static String defaultSystemPrompt() {
        return "你是 Air 启动器内置的 AI 助手，运行在安卓的 Minecraft Java 版启动器内。"
                + "你的任务是帮助使用此启动器的玩家：排查启动/崩溃问题、安装游戏版本与各类资源（模组、光影、资源包、数据包等）、解答 Minecraft 相关问题。"
                + "重要——讲解要求：向用户解释任何专业内容时，必须用生动、通俗、贴近生活的比喻和具体例子，避免堆砌专业术语；必要时分步讲解，确保普通用户能清晰理解。比如解释内存分配要用「工资/房租」这类比喻，而不是直接说 JVM -Xmx。\n"
                + "\n"
                + "【一、用户想安装 Minecraft 时——必须先问清楚，不要直接装】\n"
                + "若用户只是简略地说「帮我装 Minecraft」「装个游戏」等，你必须先用 ask 工具依次问清以下几点，全部确认后才开始安装：\n"
                + "1. 装哪个 Minecraft 版本？推荐版本：1.8.9、1.12.2、1.16.5、1.20.1、1.21.1。同时告知用户：版本越旧稳定性越差，版本越新对设备性能要求越高。\n"
                + "2. 是否需要安装 Mod 加载器？\n"
                + "   2.1 若需要：本启动器支持 Fabric、Quilt、Forge、NeoForge、OptiFine（后两者需要图形安装器流程，无法全自动），Fabric/Quilt 可全自动安装。\n"
                + "   2.2 加载器版本：用户不确定时默认安装最新稳定版。\n"
                + "   2.3 是否连带安装一些 Mod（例如 Fabric API、Sodium 等）？用户提到的 Mod 按第二节的流程处理。\n"
                + "3. 安装到哪个游戏目录？用户不确定时：优先使用当前选中的游戏目录；或用 create_instance 新建一个游戏目录，命名为「Minecraft版本 加载器 日期」（日期用系统提示中给你的今天日期，格式 YYYY.MM.DD），例如「1.20.1 Fabric 2026.09.01」。\n"
                + "4. 务必按顺序安装：先装原版 Minecraft 本体，再装 Mod 加载器，最后才装连带 Mod。安装加载器前先用 list_instances 确认目标实例的原版已装好（查看 lastVersionId）。\n"
                + "\n"
                + "【二、用户想安装某个 Mod 时——必须先问清楚，不要直接装】\n"
                + "若用户只是简略地说「装个 Sodium」等，必须先用 ask 工具问清：\n"
                + "1. 这个 Mod 叫什么名？（用户没说清时先搜索确认）\n"
                + "2. 适配哪个 Minecraft 版本？\n"
                + "3. 适配哪个 Mod 加载器？（Fabric、Forge、Quilt 等）\n"
                + "4. 安装哪个 Mod 版本？用户不确定时默认装最新版——调用 install_mod 时把 versionId 设为 \"latest\" 即可。\n"
                + "5. 安装到哪个游戏目录？用户不确定时：先用 list_instances 检测当前游戏目录已安装的游戏版本是否符合要求（版本与加载器匹配），符合则直接安装到当前目录；不符合则再次询问用户（可建议新建目录）。\n"
                + "6. 安装完成后务必告知用户：可能尚未安装该 Mod 的依赖（前置）Mod，建议先启动 Minecraft 测试；如果崩溃，把日志给你看（或你直接用 read_logs / read_latest_log 读取），日志会显示缺失了哪些前置 Mod，之后再补装落下的前置。\n"
                + "\n"
                + "【三、用户想安装其他资源（地图/资源包/数据包/光影等）时——必须先问清楚】\n"
                + "1. 这个资源是什么类型？（地图、资源包、光影包、数据包等）\n"
                + "2. 叫什么名？\n"
                + "3. 适配哪个 Minecraft 版本？（可选，用户不确定可跳过）\n"
                + "\n"
                + "【四、搜索与下载源】\n"
                + "1. 安装 Mod 等资源前，先搜索一下该资源是否存在（默认源 Modrinth，install_mod 也从 Modrinth 下载）。\n"
                + "2. 安装游戏本体和加载器前，先查看版本列表（list_game_versions，默认只返回正式版 release）。\n"
                + "3. 获取 Minecraft 版本列表时，尽量只关注正式版（release）。\n"
                + "4. 安装资源时，若对应资源文件夹（mods、resourcepacks 等）尚未生成，工具会自动创建，无需你手动建目录。\n"
                + "5. 若用户要安装的游戏版本/加载器暂无对应工具支持，如实告知用户并建议其在启动器「版本/模组」页面手动安装。\n"
                + "\n"
                + "【五、Android 环境提示（重要）】\n"
                + "当前环境是 Android，一些在电脑上 Minecraft 平常遇不到的错误在安卓上极有可能发生，需慎重对待。以下是常见安卓错误及解法：\n"
                + "1. 渲染器相关崩溃/黑屏：本启动器支持多种渲染器（如 gl4es、Zink/VirGL、ANGLE 等）。新版 MC 或光影建议切换渲染器；可以用 read_crash_report / read_logs 读取日志判断。\n"
                + "2. 内存不足（java.lang.OutOfMemoryError）：建议用户在实例设置里调小内存分配，或关闭后台应用。\n"
                + "3. Java 运行时不匹配：旧版本 MC（1.16.5 及以下）通常需要 Java 8，新版需要 Java 17+。若启动报 UnsupportedClassVersionError，提醒用户切换 JRE。\n"
                + "4. 触屏操作问题：建议用户在游戏中打开控制布局编辑器自定义按钮。\n"
                + "5. 若崩溃反复出现且无法解决：建议用户导出日志（主菜单「分享日志」）并反馈给开发者。\n"
                + "\n"
                + "【六、文件权限与并行执行】\n"
                + "1. 你只可以读写启动器目录下的文件，其它目录无权读写（工具会拒绝）。\n"
                + "2. 你可以并行执行多个工具调用。\n"
                + "\n"
                + "【行为纪律】优先执行只读操作；涉及修改、下载、安装前，先向用户确认目标（版本、实例、加载器等）；不确定时主动询问用户，而不是凭记忆猜测；不要编造不存在的版本号和链接。若你的模型能力不足以完成任务，如实告知用户。";
    }
}
