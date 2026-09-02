package net.kdt.pojavlaunch.ai;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * 新建游戏目录实例（对应 iOS AiInstanceCreator）：create_instance
 * 创建一个新的 profile（游戏目录），可选同时切换为当前实例。
 */
public class AiInstanceCreator implements AiTool {
    @Override public String name() { return "create_instance"; }

    @Override public AiToolPermission permission() { return AiToolPermission.CONTROLLED_WRITE; }

    @Override
    public String summary() {
        return "新建一个游戏目录实例。"
                + "\n参数：name（string，必填，实例名，建议格式「Minecraft版本 加载器 YYYY.MM.DD」）、"
                + "mcVersion（string，可选，该实例的目标 MC 版本）、select（boolean，可选，默认 true，是否切换为当前实例）。"
                + "\n返回创建结果与实例信息。";
    }

    @Override
    public void execute(AiParams params, AiToolCallback completion) {
        try {
            String name = params.optString("name", "");
            if (name.isEmpty()) {
                completion.onResult(null, new RuntimeException("参数 name 必填"));
                return;
            }
            String mcVersion = params.optString("mcVersion", null);
            boolean select = params.optBool("select", true);

            // 防重名
            LauncherProfiles.load();
            for (MinecraftProfile p : LauncherProfiles.mainProfileJson.profiles.values()) {
                if (name.equals(p.name)) {
                    completion.onResult(null, new RuntimeException("已存在同名实例：" + name));
                    return;
                }
            }

            MinecraftProfile profile = MinecraftProfile.createTemplate();
            profile.name = name;
            profile.gameDir = new File(Tools.DIR_GAME_HOME, name).getAbsolutePath();
            if (mcVersion != null && !mcVersion.isEmpty()) profile.lastVersionId = mcVersion;

            // 创建目录
            File dir = new File(profile.gameDir);
            if (!dir.exists() && !dir.mkdirs()) {
                completion.onResult(null, new RuntimeException("创建目录失败：" + profile.gameDir));
                return;
            }

            LauncherProfiles.insertMinecraftProfile(profile);
            LauncherProfiles.write();

            String currentKey = null;
            if (select) {
                // 找到新插入的 profile 的 key 并设为当前
                for (Map.Entry<String, MinecraftProfile> entry : LauncherProfiles.mainProfileJson.profiles.entrySet()) {
                    if (entry.getValue() == profile) {
                        currentKey = entry.getKey();
                        break;
                    }
                }
                if (currentKey != null) {
                    net.kdt.pojavlaunch.prefs.LauncherPreferences.DEFAULT_PREF.edit()
                            .putString(net.kdt.pojavlaunch.prefs.LauncherPreferences.PREF_KEY_CURRENT_PROFILE, currentKey)
                            .apply();
                }
            }

            String result = "已创建实例「" + name + "」" + (select ? "并已切换为当前实例" : "")
                    + "\n游戏目录：" + profile.gameDir
                    + (mcVersion != null ? "\n目标版本：" + mcVersion + "（尚未安装本体，请先安装原版）" : "");
            completion.onResult(result, null);
        } catch (Exception e) {
            completion.onResult(null, e);
        }
    }
}
