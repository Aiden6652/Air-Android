package net.kdt.pojavlaunch.ai;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 实例/版本工具（对应 iOS AiInstancesTool）：list_instances / list_game_versions
 */
public class AiInstancesTool implements AiTool {
    private final String mName;

    public AiInstancesTool(String name) { mName = name; }

    @Override public String name() { return mName; }

    @Override public AiToolPermission permission() { return AiToolPermission.READ_ONLY; }

    @Override public String summary() {
        if (mName.equals("list_instances")) {
            return "列出所有游戏实例（游戏目录）。"
                    + "\n无参数。"
                    + "\n返回 JSON 数组，每项含 name（实例名）、gameDir（绝对路径）、lastVersionId（上次启动的游戏版本）。";
        }
        return "列出本地已安装的 Minecraft 版本（默认仅正式版 release，快照会标注）。"
                + "\n无参数。"
                + "\n返回 JSON 数组，每项含 version（版本号）、release（是否正式版）。";
    }

    @Override
    public void execute(AiParams params, AiToolCallback completion) {
        try {
            if (mName.equals("list_instances")) {
                LauncherProfiles.load();
                List<Map.Entry<String, MinecraftProfile>> entries =
                        new ArrayList<>(LauncherProfiles.mainProfileJson.profiles.entrySet());
                entries.sort(Comparator.comparing(e -> e.getValue().name.toLowerCase()));
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Map.Entry<String, MinecraftProfile> entry : entries) {
                    MinecraftProfile p = entry.getValue();
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("{\"name\":").append(AiFileTools.quote(p.name == null ? "" : p.name))
                      .append(",\"gameDir\":").append(AiFileTools.quote(gameDirOf(p)))
                      .append(",\"lastVersionId\":").append(AiFileTools.quote(p.lastVersionId == null ? "" : p.lastVersionId))
                      .append("}");
                }
                sb.append("]");
                completion.onResult(sb.toString(), null);
                return;
            }

            // list_game_versions：扫描 versions 目录
            File versionsDir = new File(Tools.DIR_HOME_VERSION);
            List<String> results = new ArrayList<>();
            File[] dirs = versionsDir.listFiles();
            if (dirs != null) {
                List<File> sorted = new ArrayList<>(Arrays.asList(dirs));
                Collections.sort(sorted, Comparator.comparing(f -> f.getName().toLowerCase()));
                for (File dir : sorted) {
                    if (!dir.isDirectory()) continue;
                    File json = new File(dir, dir.getName() + ".json");
                    if (!json.exists()) continue;
                    results.add(dir.getName());
                }
            }
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (String v : results) {
                if (!first) sb.append(",");
                first = false;
                boolean release = !v.toLowerCase().contains("snapshot")
                        && !v.toLowerCase().contains("pre")
                        && !v.toLowerCase().contains("rc")
                        && !v.toLowerCase().contains("experimental");
                sb.append("{\"version\":").append(AiFileTools.quote(v))
                  .append(",\"release\":").append(release)
                  .append("}");
            }
            sb.append("]");
            completion.onResult(sb.toString(), null);
        } catch (Exception e) {
            completion.onResult(null, e);
        }
    }

    private static String gameDirOf(MinecraftProfile profile) {
        try {
            return Tools.getGameDirPath(profile).getAbsolutePath();
        } catch (Exception e) {
            return profile.gameDir == null ? "" : profile.gameDir;
        }
    }
}
