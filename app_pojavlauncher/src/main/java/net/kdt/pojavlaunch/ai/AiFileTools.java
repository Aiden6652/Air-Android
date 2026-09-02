package net.kdt.pojavlaunch.ai;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文件工具（对应 iOS AiFileTools.m）：list_files / read_file / grep_files / write_file / edit_file / delete_file
 * 沙盒限制：只能访问 Tools.DIR_GAME_HOME（启动器目录）内；$GAMEDIR 宏指向当前实例目录。
 */
public class AiFileTools implements AiTool {
    private final String mName;

    public AiFileTools(String name) { mName = name; }

    @Override public String name() { return mName; }

    @Override public AiToolPermission permission() {
        if (mName.equals("write_file") || mName.equals("edit_file")) return AiToolPermission.CONTROLLED_WRITE;
        if (mName.equals("delete_file")) return AiToolPermission.DANGEROUS_WRITE;
        return AiToolPermission.READ_ONLY;
    }

    @Override public String summary() {
        switch (mName) {
            case "list_files":
                return "列出指定目录下的文件/子目录（不递归）。"
                        + "\n参数：path（string，可选，默认当前实例根目录，可用 $GAMEDIR 表示当前实例根）。"
                        + "\n返回 JSON 数组，每项含 name（名称）、type（file/dir）、size（字节）、modifiedUnixTS（修改时间戳），按名称排序。";
            case "read_file":
                return "读取文本文件内容。"
                        + "\n参数：path（string，必填）、maxChars（number，可选，默认 8000，超长截断）。"
                        + "\n返回文件文本内容；二进制文件返回「二进制文件不可读」。";
            case "grep_files":
                return "在文件中用正则表达式搜索匹配行。"
                        + "\n参数：path（string，可选，默认当前实例根目录）、pattern（string，必填，正则表达式）、"
                        + "recursive（boolean，可选，默认 false）、maxResults（number，可选，默认 50）。"
                        + "\n返回 JSON 数组 {path, lineNumber, line}。";
            case "write_file":
                return "写文本文件（自动创建父目录）。"
                        + "\n参数：path（string，必填）、content（string，必填）。"
                        + "\n返回「已写入（N 字符）」。覆盖已存在内容。";
            case "edit_file":
                return "精确替换文件中的一段文本。"
                        + "\n参数：path（string，必填）、oldText（string，必填）、newText（string，必填）。"
                        + "\n仅当 oldText 在文件中恰好出现 1 次时才替换，否则返回错误（出现 N 次）。";
            case "delete_file":
                return "删除文件（危险操作）。"
                        + "\n参数：path（string，必填）。"
                        + "\n出于安全考虑，仅允许删除文本类文件（.txt/.log/.json/.properties/.toml/.md/.cfg/.yml/.yaml/.xml/.lang 等），不删除目录。"
                        + "\n返回「已删除」。";
        }
        return "文件操作工具";
    }

    // ===== 沙盒路径安全 =====

    /** 沙盒根：启动器目录 */
    private static File sandboxRoot() {
        return new File(Tools.DIR_GAME_HOME);
    }

    /** 当前实例根目录 */
    public static File currentGameRoot() {
        try {
            return Tools.getGameDirPath(LauncherProfiles.getCurrentProfile());
        } catch (Exception e) {
            return sandboxRoot();
        }
    }

    /** 解析并检查越界（canonical path 前缀检查）。越界返回 null */
    private static File resolveSafely(String path) {
        if (path == null || path.isEmpty()) return null;
        try {
            File root = sandboxRoot().getCanonicalFile();
            String worked = path;
            boolean usedGameDir = path.contains("$GAMEDIR");
            if (usedGameDir) {
                worked = path.replace("$GAMEDIR", currentGameRoot().getAbsolutePath());
            }
            File joined;
            if (usedGameDir || worked.startsWith("/")) {
                joined = new File(worked);
            } else {
                joined = new File(currentGameRoot(), worked);
            }
            File canonical = joined.getCanonicalFile();
            String rootPath = root.getAbsolutePath();
            String canonicalPath = canonical.getAbsolutePath();
            if (!canonicalPath.equals(rootPath) && !canonicalPath.startsWith(rootPath + File.separator)) {
                return null; // 越界
            }
            return canonical;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void execute(AiParams params, AiToolCallback completion) {
        try {
            switch (mName) {
                case "list_files": performListFiles(params, completion); return;
                case "read_file": performReadFile(params, completion); return;
                case "grep_files": performGrepFiles(params, completion); return;
                case "write_file": performWriteFile(params, completion); return;
                case "edit_file": performEditFile(params, completion); return;
                case "delete_file": performDeleteFile(params, completion); return;
            }
            completion.onResult(null, new IllegalArgumentException("未知工具 " + mName));
        } catch (Exception e) {
            completion.onResult(null, e);
        }
    }

    private static String jsonString(String raw) {
        // 转成 JSON 字符串字面量（用于包裹 JSON 数组结果）
        com.google.gson.JsonParser parser = new com.google.gson.JsonParser();
        try {
            return parser.parse(raw).toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private void performListFiles(AiParams params, AiToolCallback completion) throws IOException {
        String path = params.optString("path", null);
        File dir = resolveSafely(path != null ? path : currentGameRoot().getAbsolutePath());
        if (dir == null) { completion.onResult(null, err("路径越界或无效")); return; }
        File[] items = dir.listFiles();
        if (items == null) { completion.onResult(null, err("目录不存在")); return; }

        List<File> sorted = new ArrayList<>(Arrays.asList(items));
        Collections.sort(sorted, Comparator.comparing(f -> f.getName().toLowerCase()));
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (File f : sorted) {
            if (f.getName().startsWith(".")) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"name\":").append(quote(f.getName()))
              .append(",\"type\":").append(f.isDirectory() ? "\"dir\"" : "\"file\"")
              .append(",\"size\":").append(f.isDirectory() ? 0 : f.length())
              .append(",\"modifiedUnixTS\":").append(f.lastModified() / 1000L)
              .append("}");
        }
        sb.append("]");
        completion.onResult(sb.toString(), null);
    }

    private void performReadFile(AiParams params, AiToolCallback completion) throws IOException {
        String path = params.getString("path");
        if (path == null || path.isEmpty()) { completion.onResult(null, err("参数 path 必填")); return; }
        File file = resolveSafely(path);
        if (file == null) { completion.onResult(null, err("路径越界或无效")); return; }

        int maxChars = params.optInt("maxChars", 8000);
        if (maxChars <= 0) maxChars = 8000;

        byte[] data;
        try {
            data = Files.readAllBytes(file.toPath());
        } catch (Exception e) {
            completion.onResult(null, err("文件不存在或不可读"));
            return;
        }
        // 二进制检测：包含 NUL 字节
        for (byte b : data) {
            if (b == 0) { completion.onResult("二进制文件不可读", null); return; }
        }
        String content = new String(data, StandardCharsets.UTF_8);
        if (content.length() > maxChars) {
            content = "（内容过长已截断，显示开头 " + maxChars + " 字符）\n" + content.substring(0, maxChars);
        }
        completion.onResult(content, null);
    }

    private void performGrepFiles(AiParams params, AiToolCallback completion) throws IOException {
        String path = params.optString("path", null);
        String pattern = params.getString("pattern");
        if (pattern == null || pattern.isEmpty()) { completion.onResult(null, err("参数 pattern（正则）必填")); return; }
        File dir = resolveSafely(path != null ? path : currentGameRoot().getAbsolutePath());
        if (dir == null) { completion.onResult(null, err("路径越界或无效")); return; }

        boolean recursive = params.optBool("recursive", false);
        int maxResults = params.optInt("maxResults", 50);
        if (maxResults <= 0) maxResults = 50;

        Pattern regex;
        try {
            regex = Pattern.compile(pattern);
        } catch (Exception e) {
            completion.onResult(null, err("正则表达式无效：" + e.getMessage()));
            return;
        }

        List<File> pendingDirs = new ArrayList<>();
        pendingDirs.add(dir);
        StringBuilder sb = new StringBuilder("[");
        int count = 0;
        boolean first = true;
        while (!pendingDirs.isEmpty() && count < maxResults) {
            File current = pendingDirs.remove(pendingDirs.size() - 1);
            File[] items = current.listFiles();
            if (items == null) continue;
            for (File f : items) {
                if (count >= maxResults) break;
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory()) {
                    if (recursive) pendingDirs.add(f);
                    continue;
                }
                String text;
                try {
                    text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                } catch (Exception e) { continue; }
                if (text.indexOf('\0') >= 0) continue;
                String[] lines = text.split("\n", -1);
                int lineNumber = 1;
                for (String line : lines) {
                    if (count >= maxResults) break;
                    if (regex.matcher(line).find()) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("{\"path\":").append(quote(f.getName()))
                          .append(",\"lineNumber\":").append(lineNumber)
                          .append(",\"line\":").append(quote(line))
                          .append("}");
                        count++;
                    }
                    lineNumber++;
                }
            }
        }
        sb.append("]");
        completion.onResult(sb.toString(), null);
    }

    private void performWriteFile(AiParams params, AiToolCallback completion) throws IOException {
        String path = params.getString("path");
        if (path == null || path.isEmpty()) { completion.onResult(null, err("参数 path 必填")); return; }
        if (!params.has("content") || !(params.raw().get("content") instanceof String)) {
            completion.onResult(null, err("参数 content 必填"));
            return;
        }
        String content = params.getString("content");
        File file = resolveSafely(path);
        if (file == null) { completion.onResult(null, err("路径越界或无效")); return; }

        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            completion.onResult(null, err("创建目录失败"));
            return;
        }
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        completion.onResult("已写入（" + content.length() + " 字符）", null);
    }

    private void performEditFile(AiParams params, AiToolCallback completion) throws IOException {
        String path = params.getString("path");
        String oldText = params.getString("oldText");
        if (path == null || path.isEmpty()) { completion.onResult(null, err("参数 path 必填")); return; }
        if (oldText == null || oldText.isEmpty()) { completion.onResult(null, err("参数 oldText 必填")); return; }
        if (!params.has("newText")) { completion.onResult(null, err("参数 newText 必填")); return; }
        String newText = params.optString("newText", "");

        File file = resolveSafely(path);
        if (file == null) { completion.onResult(null, err("路径越界或无效")); return; }

        String content;
        try {
            content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            completion.onResult(null, err("读取文件失败或无此文件"));
            return;
        }

        // 统计 oldText 出现次数
        int occurrences = 0;
        int index = 0;
        while ((index = content.indexOf(oldText, index)) >= 0) {
            occurrences++;
            index += oldText.length();
        }
        if (occurrences != 1) {
            completion.onResult(null, err("oldText 不唯一或未找到（出现 " + occurrences + " 次）"));
            return;
        }
        String newContent = content.replace(oldText, newText);
        Files.write(file.toPath(), newContent.getBytes(StandardCharsets.UTF_8));
        completion.onResult("已修改", null);
    }

    private static final Set<String> DELETABLE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "txt", "log", "json", "properties", "toml", "md", "cfg", "yml", "yaml",
            "xml", "xaml", "lang", "ini", "export", "conf", "gradle", "fnf", "more"));

    private void performDeleteFile(AiParams params, AiToolCallback completion) throws IOException {
        String path = params.getString("path");
        if (path == null || path.isEmpty()) { completion.onResult(null, err("参数 path 必填")); return; }
        File file = resolveSafely(path);
        if (file == null) { completion.onResult(null, err("路径越界或无效")); return; }

        if (!file.exists()) { completion.onResult(null, err("文件不存在")); return; }
        if (file.isDirectory()) {
            completion.onResult(null, err("出于安全考虑仅允许删除文件，不允许删除目录"));
            return;
        }
        String ext = file.getName().contains(".")
                ? file.getName().substring(file.getName().lastIndexOf('.') + 1).toLowerCase() : "";
        if (!DELETABLE_EXTENSIONS.contains(ext)) {
            completion.onResult(null, err("出于安全考虑仅允许删除文本类文件（不支持 ." + ext + "）"));
            return;
        }
        if (!file.delete()) {
            completion.onResult(null, err("删除失败"));
            return;
        }
        completion.onResult("已删除", null);
    }

    // ===== 工具方法 =====

    private static Throwable err(String message) {
        return new RuntimeException(message);
    }

    /** JSON 字符串字面量转义 */
    static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }
}
