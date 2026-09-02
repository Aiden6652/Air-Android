package net.kdt.pojavlaunch.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 工具注册表（对应 iOS AiToolRegistry）。
 * - openAIToolSchemas：宽松 object 描述（参数说明已写进 summary）
 * - normalizedKeyCamel：任意分隔符/驼峰键 → 小写 camelCase
 * - executeToolNamed：在调用线程直接执行（Agent 循环线程）
 */
public class AiToolRegistry {
    private static final AiToolRegistry sInstance = new AiToolRegistry();
    private final Map<String, AiTool> mTools = new HashMap<>();

    public static AiToolRegistry getInstance() {
        return sInstance;
    }

    private AiToolRegistry() {
        // 内置工具注册（对应 iOS AiToolBootstrapper）
        register(new AiInstancesTool("list_instances"));
        register(new AiInstancesTool("list_game_versions"));
        register(new AiLogReader("read_latest_log"));
        register(new AiLogReader("read_crash_report"));
        register(new AiLogReader("read_logs"));
        register(new AiCrashAnalyzer());
        register(new AiFileTools("list_files"));
        register(new AiFileTools("read_file"));
        register(new AiFileTools("grep_files"));
        register(new AiFileTools("write_file"));
        register(new AiFileTools("edit_file"));
        register(new AiFileTools("delete_file"));
        register(new AiAskTool());
        register(new AiAssetTools("search_mods"));
        register(new AiAssetTools("search_resourcepacks"));
        register(new AiAssetTools("search_shaders"));
        register(new AiAssetTools("search_datapacks"));
        register(new AiAssetTools("search_modpacks"));
        register(new AiAssetTools("search_worlds"));
        register(new AiAssetTools("install_mod"));
        register(new AiAssetTools("install_resourcepack"));
        register(new AiAssetTools("install_shader"));
        register(new AiAssetTools("install_datapack"));
        register(new AiSleepTool());
        register(new AiInstanceCreator());
    }

    public void register(AiTool tool) {
        if (tool == null || tool.name() == null || tool.name().isEmpty()) return;
        mTools.put(tool.name(), tool);
    }

    public AiTool toolForName(String name) {
        if (name == null || name.isEmpty()) return null;
        return mTools.get(name);
    }

    /** OpenAI 风格 schema（按名称排序） */
    public JsonArray openAIToolSchemas() {
        JsonArray schemas = new JsonArray();
        TreeMap<String, AiTool> sorted = new TreeMap<>(mTools);
        for (Map.Entry<String, AiTool> entry : sorted.entrySet()) {
            AiTool tool = entry.getValue();
            JsonObject parameters = new JsonObject();
            parameters.addProperty("type", "object");
            parameters.add("properties", new JsonObject());
            parameters.add("required", new JsonArray());
            JsonObject function = new JsonObject();
            function.addProperty("name", tool.name());
            function.addProperty("description", tool.summary() == null ? "" : tool.summary());
            function.add("parameters", parameters);
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "function");
            schema.add("function", function);
            schemas.add(schema);
        }
        return schemas;
    }

    // ===== 参数规范化 =====

    /** 把键规范化成小写 camelCase（"game-version"→"gameVersion"、"InstanceName"→"instanceName"） */
    public static String normalizedKeyCamel(String key) {
        if (key == null || key.isEmpty()) return key == null ? "" : key;
        int length = key.length();
        boolean[] wordStart = new boolean[length];
        wordStart[0] = true;
        for (int i = 1; i < length; i++) {
            char c = key.charAt(i);
            if (isSeparator(c)) continue;
            char prev = key.charAt(i - 1);
            boolean prevIsSep = isSeparator(prev);
            boolean prevIsLowerOrDigit = (prev >= 'a' && prev <= 'z') || (prev >= '0' && prev <= '9');
            boolean isUpper = (c >= 'A' && c <= 'Z');
            if (prevIsSep) wordStart[i] = true;
            else if (isUpper && prevIsLowerOrDigit) wordStart[i] = true;
        }
        StringBuilder result = new StringBuilder();
        StringBuilder word = new StringBuilder();
        boolean firstWord = true;
        for (int i = 0; i < length; i++) {
            char c = key.charAt(i);
            if (isSeparator(c)) {
                flushWord(result, word, firstWord);
                if (word.length() > 0 || result.length() > 0) firstWord = false;
                word.setLength(0);
                continue;
            }
            if (i > 0 && wordStart[i]) {
                flushWord(result, word, firstWord);
                firstWord = false;
                word.setLength(0);
            }
            char lower = (c >= 'A' && c <= 'Z') ? (char) (c - 'A' + 'a') : c;
            word.append(lower);
        }
        flushWord(result, word, firstWord);
        return result.toString();
    }

    private static void flushWord(StringBuilder result, StringBuilder word, boolean firstWord) {
        if (word.length() == 0) return;
        if (firstWord) {
            result.append(word);
        } else {
            char head = word.charAt(0);
            result.append(Character.toUpperCase(head)).append(word.substring(1));
        }
    }

    private static boolean isSeparator(char c) {
        return c == '_' || c == '-' || c == '.' || c == ' ' || c == '/';
    }

    private static boolean isEmptyValue(Object value) {
        if (value == null) return true;
        if (value instanceof String) return ((String) value).isEmpty();
        return false;
    }

    /** 键 camelCase 化 + 丢弃空值 */
    public static Map<String, Object> normalizedParams(Map<String, Object> params) {
        Map<String, Object> normalized = new HashMap<>();
        if (params == null) return normalized;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String rawKey = entry.getKey();
            String key = rawKey instanceof String ? (String) rawKey : String.valueOf(rawKey);
            String camelKey = normalizedKeyCamel(key);
            if (camelKey.isEmpty()) continue;
            if (isEmptyValue(entry.getValue())) continue;
            normalized.put(camelKey, entry.getValue());
        }
        return normalized;
    }

    /** 执行工具（在调用线程直接执行） */
    public void executeToolNamed(String name, Map<String, Object> params, AiTool.AiToolCallback completion) {
        AiTool tool = toolForName(name);
        if (tool == null) {
            if (completion != null) completion.onResult(null, new IllegalArgumentException("未知工具 " + name));
            return;
        }
        tool.execute(new AiTool.AiParams(normalizedParams(params)), completion);
    }
}
