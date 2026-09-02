package net.kdt.pojavlaunch.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 资源搜索/安装工具（对应 iOS AiAssetTools，精简版）：
 * search_mods / search_resourcepacks / search_shaders / search_datapacks / search_modpacks / search_worlds
 * install_mod / install_resourcepack / install_shader / install_datapack
 * 数据源：Modrinth API v2。
 * 搜索工具为 ExternalNetwork 权限；安装工具为 CONTROLLED_WRITE。
 */
public class AiAssetTools implements AiTool {
    private static final String API_BASE = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "Air-Android-AI-Agent/1.0 (pojav-launcher fork)";

    private final String mName;

    public AiAssetTools(String name) { mName = name; }

    @Override public String name() { return mName; }

    @Override public AiToolPermission permission() {
        return mName.startsWith("search_") ? AiToolPermission.EXTERNAL_NETWORK : AiToolPermission.CONTROLLED_WRITE;
    }

    private static String projectTypeOf(String toolName) {
        switch (toolName) {
            case "search_mods": return "mod";
            case "search_resourcepacks": return "resourcepack";
            case "search_shaders": return "shader";
            case "search_datapacks": return "datapack";
            case "search_modpacks": return "modpack";
            case "search_worlds": return "world";
        }
        return "mod";
    }

    private static String targetFolderOf(String toolName) {
        switch (toolName) {
            case "install_mod": return "mods";
            case "install_resourcepack": return "resourcepacks";
            case "install_shader": return "shaderpacks";
            case "install_datapack": return "datapacks";
        }
        return "mods";
    }

    @Override public String summary() {
        if (mName.startsWith("search_")) {
            String type = projectTypeOf(mName);
            String cn;
            switch (type) {
                case "mod": cn = "模组"; break;
                case "resourcepack": cn = "资源包"; break;
                case "shader": cn = "光影包"; break;
                case "datapack": cn = "数据包"; break;
                case "modpack": cn = "整合包"; break;
                default: cn = "地图"; break;
            }
            return "在 Modrinth 上搜索" + cn + "。"
                    + "\n参数：query（string，必填，搜索关键词）、limit（number，可选，默认 10）。"
                    + "\n返回 JSON 数组，每项含 title（名称）、projectId、slug、description、downloads（下载量）、versions（支持的 MC 版本摘要）、author（作者）。";
        }
        // install_*
        String cn;
        switch (targetFolderOf(mName)) {
            case "mods": cn = "模组"; break;
            case "resourcepacks": cn = "资源包"; break;
            case "shaderpacks": cn = "光影包"; break;
            default: cn = "数据包"; break;
        }
        return "从 Modrinth 下载并安装" + cn + "到指定实例的对应目录。"
                + "\n参数：query（string，必填，" + cn + "名称/slug）、versionId（string，可选，默认 \"latest\" 装最新版）、"
                + "gameVersion（string，可选，目标 MC 版本）、loader（string，可选，加载器：fabric/forge/quilt/neoforge，仅模组需要）、"
                + "instance（string，可选，目标实例名，默认当前实例）。"
                + "\n返回安装结果（文件路径）；若找不到匹配版本返回错误说明。";
    }

    @Override
    public void execute(AiParams params, AiToolCallback completion) {
        try {
            if (mName.startsWith("search_")) {
                performSearch(params, completion);
            } else {
                performInstall(params, completion);
            }
        } catch (Exception e) {
            completion.onResult(null, e);
        }
    }

    // ===== HTTP =====

    private static JsonObject httpGetJson(String urlString) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new RuntimeException("Modrinth 请求失败（HTTP " + code + "）");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                JsonElement el = JsonParser.parseString(sb.toString());
                return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static JsonArray httpGetJsonArray(String urlString) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new RuntimeException("Modrinth 请求失败（HTTP " + code + "）");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                JsonElement el = JsonParser.parseString(sb.toString());
                return el != null && el.isJsonArray() ? el.getAsJsonArray() : new JsonArray();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ===== 搜索 =====

    private void performSearch(AiParams params, AiToolCallback completion) throws Exception {
        String query = params.optString("query", "");
        if (query.isEmpty()) {
            completion.onResult(null, new RuntimeException("参数 query 必填"));
            return;
        }
        int limit = params.optInt("limit", 10);
        if (limit <= 0) limit = 10;
        if (limit > 20) limit = 20;

        String facets = "[[\"project_type:" + projectTypeOf(mName) + "\"]]";
        String url = API_BASE + "/search?query=" + URLEncoder.encode(query, "UTF-8")
                + "&facets=" + URLEncoder.encode(facets, "UTF-8")
                + "&limit=" + limit;
        JsonObject response = httpGetJson(url);
        JsonArray hits = response != null && response.has("hits") && response.get("hits").isJsonArray()
                ? response.getAsJsonArray("hits") : new JsonArray();

        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (JsonElement el : hits) {
            if (!el.isJsonObject()) continue;
            JsonObject hit = el.getAsJsonObject();
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"title\":").append(AiFileTools.quote(str(hit, "title")))
              .append(",\"projectId\":").append(AiFileTools.quote(str(hit, "project_id")))
              .append(",\"slug\":").append(AiFileTools.quote(str(hit, "slug")))
              .append(",\"description\":").append(AiFileTools.quote(str(hit, "description")))
              .append(",\"author\":").append(AiFileTools.quote(str(hit, "author")))
              .append(",\"downloads\":").append(hit.has("downloads") ? hit.get("downloads").getAsLong() : 0)
              .append(",\"categories\":").append(hit.has("categories") ? hit.get("categories").toString() : "[]")
              .append("}");
        }
        sb.append("]");
        completion.onResult(sb.toString(), null);
    }

    // ===== 安装 =====

    private void performInstall(AiParams params, AiToolCallback completion) throws Exception {
        String query = params.optString("query", "");
        String versionId = params.optString("versionId", "latest");
        String gameVersion = params.optString("gameVersion", "");
        String loader = params.optString("loader", "");
        String instance = params.optString("instance", "");

        if (query.isEmpty()) {
            completion.onResult(null, new RuntimeException("参数 query 必填"));
            return;
        }

        // 1. 解析项目：query 可能是 slug/projectId/名称
        String projectId = query;
        if (!looksLikeId(query)) {
            // 搜索取第一个结果
            String facets = "[[\"project_type:" + projectTypeOf("search_" + folderToTool(mName)) + "\"]]";
            String searchUrl = API_BASE + "/search?query=" + URLEncoder.encode(query, "UTF-8")
                    + "&facets=" + URLEncoder.encode(facets, "UTF-8") + "&limit=1";
            JsonObject response = httpGetJson(searchUrl);
            JsonArray hits = response != null && response.has("hits") && response.get("hits").isJsonArray()
                    ? response.getAsJsonArray("hits") : null;
            if (hits == null || hits.size() == 0) {
                completion.onResult(null, new RuntimeException("在 Modrinth 上找不到：" + query));
                return;
            }
            JsonObject hit = hits.get(0).getAsJsonObject();
            projectId = str(hit, "slug");
        }

        // 2. 目标实例目录
        File gameDir = resolveInstanceDir(instance);
        if (gameDir == null) {
            completion.onResult(null, new RuntimeException("找不到实例：" + instance));
            return;
        }

        // 若未指定 gameVersion，尝试用实例的 lastVersionId
        if (gameVersion.isEmpty()) {
            try {
                MinecraftProfile profile = instance.isEmpty()
                        ? LauncherProfiles.getCurrentProfile() : findProfileByName(instance);
                if (profile != null && profile.lastVersionId != null
                        && !profile.lastVersionId.isEmpty()
                        && !profile.lastVersionId.startsWith("latest")) {
                    gameVersion = profile.lastVersionId;
                }
            } catch (Exception ignored) {}
        }

        // 3. 拉版本列表并过滤
        StringBuilder versionUrl = new StringBuilder(API_BASE + "/project/" + URLEncoder.encode(projectId, "UTF-8") + "/version");
        boolean hasQuery = false;
        if (!gameVersion.isEmpty()) {
            versionUrl.append(hasQuery ? '&' : '?').append("game_versions=[\"").append(gameVersion).append("\"]");
            hasQuery = true;
        }
        if (!loader.isEmpty() && mName.equals("install_mod")) {
            versionUrl.append(hasQuery ? '&' : '?').append("loaders=[\"").append(loader.toLowerCase()).append("\"]");
            hasQuery = true;
        }
        JsonArray versions = httpGetJsonArray(versionUrl.toString());
        if (versions.size() == 0) {
            // 过滤无结果时回退到不带过滤的版本列表，由 AI 提示版本不匹配
            versions = httpGetJsonArray(API_BASE + "/project/" + URLEncoder.encode(projectId, "UTF-8") + "/version");
            if (versions.size() == 0) {
                completion.onResult(null, new RuntimeException("该项目没有任何可用版本"));
                return;
            }
            // 给出可用版本提示
            StringBuilder tips = new StringBuilder("没有找到匹配的版本（MC " + gameVersion
                    + (loader.isEmpty() ? "" : " / " + loader) + "）。该项目最近的版本有：");
            int count = 0;
            for (JsonElement el : versions) {
                if (count >= 5) break;
                if (!el.isJsonObject()) continue;
                JsonObject v = el.getAsJsonObject();
                tips.append("\n- ").append(str(v, "version_number"))
                        .append("（支持 ").append(el.getAsJsonObject().has("game_versions")
                                ? el.getAsJsonObject().get("game_versions").toString() : "?").append("）");
                count++;
            }
            completion.onResult(null, new RuntimeException(tips.toString()));
            return;
        }

        // 4. 选版本：versionId 匹配或最新（列表已按新到旧排序）
        JsonObject chosen = null;
        if (!versionId.equals("latest")) {
            for (JsonElement el : versions) {
                if (!el.isJsonObject()) continue;
                JsonObject v = el.getAsJsonObject();
                if (str(v, "version_number").equals(versionId) || str(v, "id").equals(versionId)) {
                    chosen = v;
                    break;
                }
            }
            if (chosen == null) {
                completion.onResult(null, new RuntimeException("找不到指定版本：" + versionId));
                return;
            }
        } else {
            chosen = versions.get(0).getAsJsonObject();
        }

        // 5. 找主文件
        JsonArray files = chosen.has("files") && chosen.get("files").isJsonArray()
                ? chosen.getAsJsonArray("files") : new JsonArray();
        String fileUrl = null, fileName = null;
        for (JsonElement el : files) {
            if (!el.isJsonObject()) continue;
            JsonObject f = el.getAsJsonObject();
            boolean primary = f.has("primary") && f.get("primary").getAsBoolean();
            if (primary || fileUrl == null) {
                fileUrl = str(f, "url");
                fileName = str(f, "filename");
                if (primary) break;
            }
        }
        if (fileUrl == null) {
            completion.onResult(null, new RuntimeException("该版本没有可下载文件"));
            return;
        }

        // 6. 下载到目标目录
        String folder = targetFolderOf(mName);
        File targetDir = new File(gameDir, folder);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            completion.onResult(null, new RuntimeException("创建目录失败：" + targetDir.getAbsolutePath()));
            return;
        }
        File targetFile = new File(targetDir, fileName);
        try {
            Tools.downloadFile(fileUrl, targetFile.getAbsolutePath());
        } catch (Exception e) {
            completion.onResult(null, new RuntimeException("下载失败：" + e.getMessage()));
            return;
        }

        // 7. 依赖提示
        StringBuilder deps = new StringBuilder();
        JsonArray dependencies = chosen.has("dependencies") && chosen.get("dependencies").isJsonArray()
                ? chosen.getAsJsonArray("dependencies") : null;
        if (dependencies != null && dependencies.size() > 0) {
            int required = 0;
            for (JsonElement el : dependencies) {
                if (el.isJsonObject() && el.getAsJsonObject().has("dependency_type")
                        && el.getAsJsonObject().get("dependency_type").getAsString().equals("required")) {
                    required++;
                }
            }
            if (required > 0) {
                deps.append("\n注意：该版本有 ").append(required).append(" 个必需依赖（前置），建议告知用户先确认前置是否已安装。");
            }
        }

        completion.onResult("已安装「" + str(chosen, "version_number") + "」到 "
                + targetFile.getAbsolutePath()
                + "\n目标实例：" + (instance.isEmpty() ? "当前实例" : instance)
                + (gameVersion.isEmpty() ? "" : "\n匹配 MC 版本：" + gameVersion)
                + deps, null);
    }

    // ===== 辅助 =====

    private static boolean looksLikeId(String s) {
        // slug：短横线小写；projectId：8-10 位 base62
        return s.matches("[a-z0-9][a-z0-9-_]{1,63}") || s.matches("[A-Za-z0-9]{8}");
    }

    private static String str(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el == null || el.isJsonNull() ? "" : el.getAsString();
    }

    private static String folderToTool(String installTool) {
        switch (installTool) {
            case "install_mod": return "mods";
            case "install_resourcepack": return "resourcepacks";
            case "install_shader": return "shaders";
            case "install_datapack": return "datapacks";
        }
        return "mods";
    }

    private static MinecraftProfile findProfileByName(String name) {
        for (MinecraftProfile p : LauncherProfiles.mainProfileJson.profiles.values()) {
            if (p.name != null && p.name.equals(name)) return p;
        }
        return null;
    }

    private static File resolveInstanceDir(String instance) {
        try {
            if (instance == null || instance.isEmpty()) {
                return AiFileTools.currentGameRoot();
            }
            LauncherProfiles.load();
            MinecraftProfile profile = findProfileByName(instance);
            if (profile == null) return null;
            return Tools.getGameDirPath(profile);
        } catch (Exception e) {
            return null;
        }
    }
}
