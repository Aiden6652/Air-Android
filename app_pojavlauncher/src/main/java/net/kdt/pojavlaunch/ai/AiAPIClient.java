package net.kdt.pojavlaunch.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI 兼容流式 Chat Completions 客户端（对应 iOS AiAPIClient）。
 * 同步方法：必须在后台线程调用。
 * - SSE 逐行解析，data: 前缀兼容（含无前缀裸行与 [DONE]）
 * - 内容增量节流 200ms 回调（onChunk）
 * - tool_calls 增量片段按 index 合并（accumulateToolCalls 在 AiAgent 中完成，
 *   这里把每个 delta 的 tool_calls 原样透传给 onToolCallDelta）
 */
public class AiAPIClient {
    /** 节流阈值（毫秒） */
    private static final long CHUNK_THROTTLE_MS = 200;
    private static final Gson GSON = new Gson();

    private volatile HttpURLConnection mCurrentConnection;

    public interface StreamCallbacks {
        /** 文本增量（已节流） */
        void onChunk(String delta);
        /** tool_calls 增量片段（未合并，原样透传） */
        void onToolCallDelta(JsonArray toolCalls);
    }

    /** 停止当前请求 */
    public void stop() {
        HttpURLConnection conn = mCurrentConnection;
        if (conn != null) conn.disconnect();
    }

    /**
     * 流式对话。同步阻塞直到流结束/出错/停止，然后返回。
     * @return null 表示成功；否则返回错误描述
     */
    public String streamChat(AiProvider provider, JsonArray messages, JsonArray tools, StreamCallbacks callbacks) {
        if (provider == null || !provider.isConfigured()) {
            return "AI 提供商配置不完整（缺少 baseURL 或 model）";
        }

        String base = provider.baseURL.trim();
        if (!base.endsWith("/")) base += "/";
        String urlString = base + "chat/completions";

        // 构造请求体
        JsonObject body = new JsonObject();
        body.addProperty("model", provider.model);
        body.add("messages", messages);
        body.addProperty("stream", true);
        body.addProperty("temperature", (float) provider.temperature);
        body.addProperty("max_tokens", provider.maxTokens);
        if (tools != null && tools.size() > 0) body.add("tools", tools);
        byte[] payload = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            mCurrentConnection = conn;
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            if (provider.apiKey != null && !provider.apiKey.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + provider.apiKey.trim());
            }
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(180000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int statusCode = conn.getResponseCode();
            if (statusCode != 200) {
                String errText = readAll(conn.getErrorStream());
                if (errText == null || errText.isEmpty()) errText = readAll(conn.getInputStream());
                return errorMessage(statusCode, errText);
            }

            // 流式读取（BufferedReader 按 UTF-8 解码，readLine 保证行完整）
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                long lastFlush = 0;
                StringBuilder pendingDelta = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty()) continue;

                    String payloadLine;
                    if (trimmed.startsWith("data: ")) payloadLine = trimmed.substring(6);
                    else if (trimmed.startsWith("data:")) payloadLine = trimmed.substring(5);
                    else payloadLine = trimmed;

                    payloadLine = payloadLine.trim();
                    if (payloadLine.equals("[DONE]")) break;

                    JsonObject json;
                    try {
                        JsonElement el = JsonParser.parseString(payloadLine);
                        json = el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
                    } catch (Exception e) { continue; }
                    if (json == null) continue;

                    JsonElement choicesEl = json.get("choices");
                    if (choicesEl == null || !choicesEl.isJsonArray() || choicesEl.getAsJsonArray().size() == 0) continue;
                    JsonElement choiceEl = choicesEl.getAsJsonArray().get(0);
                    if (!choiceEl.isJsonObject()) continue;
                    JsonObject choice = choiceEl.getAsJsonObject();
                    JsonElement deltaEl = choice.get("delta");
                    if (deltaEl == null || !deltaEl.isJsonObject()) continue;
                    JsonObject delta = deltaEl.getAsJsonObject();

                    // 内容增量
                    JsonElement contentEl = delta.get("content");
                    if (contentEl != null && contentEl.isJsonPrimitive()) {
                        pendingDelta.append(contentEl.getAsString());
                    }

                    // tool_calls 透传
                    JsonElement tcEl = delta.get("tool_calls");
                    if (tcEl != null && tcEl.isJsonArray() && tcEl.getAsJsonArray().size() > 0) {
                        callbacks.onToolCallDelta(tcEl.getAsJsonArray());
                    }

                    // 节流刷新
                    long now = System.currentTimeMillis();
                    if (now - lastFlush >= CHUNK_THROTTLE_MS) {
                        flushPending(pendingDelta, callbacks);
                        lastFlush = now;
                    }
                }
                // 结束时刷出剩余
                flushPending(pendingDelta, callbacks);
            }
            return null; // 成功
        } catch (IOException e) {
            return "网络错误：" + e.getMessage();
        } catch (Exception e) {
            return "请求失败：" + e.getMessage();
        } finally {
            mCurrentConnection = null;
            if (conn != null) conn.disconnect();
        }
    }

    /** 连通性测试：发一条 "ping"，返回 null 表示成功 */
    public String testConnection(AiProvider provider) {
        JsonArray messages = new JsonArray();
        JsonObject ping = new JsonObject();
        ping.addProperty("role", "user");
        ping.addProperty("content", "ping");
        messages.add(ping);
        return streamChat(provider, messages, null, new StreamCallbacks() {
            @Override public void onChunk(String delta) {}
            @Override public void onToolCallDelta(JsonArray toolCalls) {}
        });
    }

    private void flushPending(StringBuilder pending, StreamCallbacks callbacks) {
        if (pending.length() > 0) {
            callbacks.onChunk(pending.toString());
            pending.setLength(0);
        }
    }

    private static String readAll(InputStream stream) {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    /** 从错误响应体解析 error.message */
    private static String errorMessage(int statusCode, String body) {
        String message = "请求失败（HTTP " + statusCode + "）";
        try {
            JsonElement el = JsonParser.parseString(body);
            if (el != null && el.isJsonObject()) {
                JsonElement errEl = el.getAsJsonObject().get("error");
                if (errEl != null && errEl.isJsonObject()) {
                    JsonElement msgEl = errEl.getAsJsonObject().get("message");
                    if (msgEl != null && msgEl.isJsonPrimitive() && !msgEl.getAsString().isEmpty()) {
                        message = msgEl.getAsString();
                    }
                }
            }
        } catch (Exception ignored) {}
        return message;
    }
}
