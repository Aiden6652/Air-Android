package net.kdt.pojavlaunch.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.kdt.pojavlaunch.PojavApplication;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Agent 主循环（对应 iOS AiAgent.m）：
 * - 最多 10 轮工具调用（kMaxToolRounds）
 * - 同一 callID 最多 3 次尝试（kMaxToolAttempts）
 * - 每轮注入今天日期到系统提示词（gameDir 命名用）
 * - 工具存在时向系统提示词追加能力说明
 * - 出错时若助手占位消息为空则从历史移除
 * - 工具结果按调用顺序追加到会话
 */
public class AiAgent {
    /** 工具循环最多轮数 */
    private static final int MAX_TOOL_ROUNDS = 10;
    /** 同一工具调用最多尝试次数（含失败） */
    private static final int MAX_TOOL_ATTEMPTS = 3;

    private static volatile AiAgent sInstance;

    public interface AgentListener {
        /** 流式文本增量（Agent 循环线程回调） */
        void onChunk(String delta);
        /** 会话消息发生变化（新增/修改消息后回调，Agent 循环线程） */
        void onMessagesChanged();
    }

    public interface Completion {
        /** @param error null 表示成功 */
        void onComplete(String error);
    }

    private final AiAPIClient mClient = new AiAPIClient();
    private final Map<String, Integer> mAttempts = new HashMap<>();
    private volatile boolean mRunning = false;
    private int mToolRound = 0;

    public static AiAgent getInstance() {
        if (sInstance == null) {
            synchronized (AiAgent.class) {
                if (sInstance == null) sInstance = new AiAgent();
            }
        }
        return sInstance;
    }

    private AiAgent() {}

    public boolean isRunning() { return mRunning; }

    public void stopCurrent() {
        mRunning = false;
        mClient.stop();
    }

    /**
     * 发送用户消息，驱动工具循环。
     * onComplete 一定在主线程回调。
     */
    public void sendUserMessage(final String text, final AiSession session, final AiProvider provider,
                                final AgentListener listener, final Completion completion) {
        if (session == null) {
            postCompletion(completion, "会话为空");
            return;
        }
        if (mRunning) {
            postCompletion(completion, "上一条回复尚未完成，请稍候或先点停止");
            return;
        }

        mRunning = true;
        mToolRound = 0;
        mAttempts.clear();

        PojavApplication.sExecutorService.execute(() -> {
            // 追加用户消息
            AiMessage userMessage = new AiMessage("user", text == null ? "" : text);
            synchronized (session) { session.messages.add(userMessage); }
            if (session.title == null || session.title.isEmpty() || session.title.equals("AI 助手")) {
                session.title = AiSessionStore.autoTitleForMessage(text);
            }
            AiSessionStore.getInstance().updateSession(session);
            notifyChanged(listener);

            String error = runLoop(session, provider, listener);
            mRunning = false;
            postCompletion(completion, error);
        });
    }

    /** Agent 循环主体（后台线程）。返回 null 成功，否则错误描述 */
    private String runLoop(AiSession session, AiProvider provider, AgentListener listener) {
        while (mRunning) {
            // 轮数护栏
            if (mToolRound >= MAX_TOOL_ROUNDS) {
                AiMessage capMsg = new AiMessage("assistant", "本轮工具调用已达上限，请让用户进一步说明。");
                synchronized (session) { session.messages.add(capMsg); }
                AiSessionStore.getInstance().updateSession(session);
                notifyChanged(listener);
                return null;
            }

            // 1. 拼装消息
            JsonArray payloadMessages = new JsonArray();
            String systemPrompt = AiSettings.getSystemPrompt();

            // 动态注入今天日期
            String today = new SimpleDateFormat("yyyy 年 M 月 d 日", Locale.CHINA).format(new Date());
            systemPrompt += "\n\n【今天日期】今天是 " + today + "。若需新建游戏目录，命名格式为「Minecraft版本 加载器 YYYY.MM.DD」。";

            // 工具能力说明（修复 AI 不知道自己能用工具）
            JsonArray tools = AiToolRegistry.getInstance().openAIToolSchemas();
            if (tools.size() > 0) {
                systemPrompt += "\n\n你可以调用内置工具来直接操控启动器，例如：排查并分析崩溃日志（read_latest_log / read_crash_report / read_logs / match_known_errors）、读取已安装的游戏版本与实例状态（list_instances / list_game_versions）、搜索并下载/安装模组、光影、资源包、数据包（search_mods 等 + install_mod 等，默认源 Modrinth，versionId 传 \"latest\" 即装最新版）、新建游戏目录（create_instance）、向用户提问确认（ask）。"
                        + "安装顺序纪律：必须先装原版再装加载器、最后才装 Mod；调用 install_mod 前先用 list_instances 确认目标实例的原版已装好。"
                        + "当用户的请求可以通过这些工具完成时，请主动调用合适的工具去执行，而不是只给出文字建议；也请结合工具返回结果继续推进任务。";
            }
            if (!systemPrompt.isEmpty()) {
                payloadMessages.add(messageEntry("system", systemPrompt, null));
            }
            synchronized (session) {
                // 按原顺序遍历；连续的 isToolCall 助手消息合并为一条 tool_calls 数组（OpenAI 要求）
                JsonObject pendingToolCallMessage = null;
                for (AiMessage m : session.messages) {
                    if (m.streaming) continue;
                    if (m.isToolCall && "assistant".equals(m.role)) {
                        if (pendingToolCallMessage == null) {
                            pendingToolCallMessage = new JsonObject();
                            pendingToolCallMessage.addProperty("role", "assistant");
                            pendingToolCallMessage.addProperty("content", m.content == null ? "" : m.content);
                            pendingToolCallMessage.add("tool_calls", new JsonArray());
                        } else if (m.content != null && !m.content.isEmpty()
                                && pendingToolCallMessage.get("content").getAsString().isEmpty()) {
                            pendingToolCallMessage.addProperty("content", m.content);
                        }
                        JsonObject func = new JsonObject();
                        if (m.toolName != null && !m.toolName.isEmpty()) func.addProperty("name", m.toolName);
                        if (m.toolArguments != null && !m.toolArguments.isEmpty()) func.addProperty("arguments", m.toolArguments);
                        JsonObject call = new JsonObject();
                        if (m.toolCallID != null && !m.toolCallID.isEmpty()) call.addProperty("id", m.toolCallID);
                        call.addProperty("type", "function");
                        call.add("function", func);
                        pendingToolCallMessage.get("tool_calls").getAsJsonArray().add(call);
                        continue;
                    }
                    // 非工具调用消息：先刷出缓冲的 tool_calls 消息，再追加本条
                    if (pendingToolCallMessage != null) {
                        payloadMessages.add(pendingToolCallMessage);
                        pendingToolCallMessage = null;
                    }
                    payloadMessages.add(serializeMessage(m));
                }
                if (pendingToolCallMessage != null) payloadMessages.add(pendingToolCallMessage);
            }

            // 2. 助手占位
            AiMessage assistantMessage = new AiMessage("assistant", "");
            assistantMessage.streaming = true;
            synchronized (session) { session.messages.add(assistantMessage); }
            notifyChanged(listener);

            // 3. 流式请求 + 累积 tool_calls
            final TreeMap<Integer, JsonObject> accToolCalls = new TreeMap<>();
            StringBuilder contentBuilder = new StringBuilder(assistantMessage.content);

            String error = mClient.streamChat(provider, payloadMessages, tools, new AiAPIClient.StreamCallbacks() {
                @Override
                public void onChunk(String delta) {
                    if (!mRunning) return;
                    contentBuilder.append(delta);
                    assistantMessage.content = contentBuilder.toString();
                    if (listener != null) listener.onChunk(delta);
                }

                @Override
                public void onToolCallDelta(JsonArray toolCalls) {
                    if (!mRunning) return;
                    accumulateToolCalls(toolCalls, accToolCalls);
                }
            });

            assistantMessage.streaming = false;

            if (!mRunning) {
                // 用户点了停止：直接收尾
                AiSessionStore.getInstance().updateSession(session);
                notifyChanged(listener);
                return null;
            }

            if (error != null) {
                synchronized (session) {
                    if (assistantMessage.content.isEmpty()) {
                        session.messages.remove(assistantMessage);
                    }
                }
                AiSessionStore.getInstance().updateSession(session);
                notifyChanged(listener);
                return error;
            }

            if (accToolCalls.isEmpty()) {
                // 无工具调用，正常结束
                AiSessionStore.getInstance().updateSession(session);
                notifyChanged(listener);
                return null;
            }

            // 4. 执行工具
            String toolError = runToolCalls(accToolCalls, assistantMessage, session, listener);
            if (toolError != null) return toolError;
            mToolRound++;
        }
        return null;
    }

    /** 工具执行阶段（顺序执行；需确认的工具先确认）。返回 null 继续下一轮 */
    private String runToolCalls(TreeMap<Integer, JsonObject> accToolCalls, AiMessage assistantMessage,
                                AiSession session, AgentListener listener) {
        // 把调用挂到消息上（首个复用 assistantMessage，其余追加）
        int i = 0;
        for (Map.Entry<Integer, JsonObject> entry : accToolCalls.entrySet()) {
            JsonObject call = entry.getValue();
            String callID = call.has("id") ? call.get("id").getAsString() : "call_" + entry.getKey();
            String name = call.has("name") ? call.get("name").getAsString() : "";
            String args = call.has("arguments") ? call.get("arguments").getAsString() : "";
            if (i == 0) {
                assistantMessage.isToolCall = true;
                assistantMessage.toolCallID = callID;
                assistantMessage.toolName = name;
                assistantMessage.toolArguments = args;
            } else {
                AiMessage tc = AiMessage.toolCallMessage(name, args, callID);
                synchronized (session) { session.messages.add(tc); }
            }
            i++;
        }
        AiSessionStore.getInstance().updateSession(session);
        notifyChanged(listener);

        String terminalError = null;
        for (Map.Entry<Integer, JsonObject> entry : accToolCalls.entrySet()) {
            if (!mRunning) return null;

            JsonObject call = entry.getValue();
            String callID = call.has("id") ? call.get("id").getAsString() : "call_" + entry.getKey();
            String name = call.has("name") ? call.get("name").getAsString() : "";
            String arguments = call.has("arguments") ? call.get("arguments").getAsString() : "";

            // 重试护栏
            int attempts = mAttempts.containsKey(callID) ? mAttempts.get(callID) : 0;
            if (attempts >= MAX_TOOL_ATTEMPTS) {
                appendToolResult(session, listener, AiMessage.toolResultMessage("多次尝试仍失败：" + name, callID), name, false);
                terminalError = "工具 " + name + " 多次尝试仍失败";
                continue;
            }
            mAttempts.put(callID, attempts + 1);

            AiTool tool = AiToolRegistry.getInstance().toolForName(name);
            if (tool == null) {
                appendToolResult(session, listener, AiMessage.toolResultMessage("未知工具：" + name, callID), name, false);
                continue;
            }

            // 安全确认（阻塞等待用户）
            if (AiSafetyManager.getInstance().needsUserConfirmation(tool.permission())) {
                boolean approved = AiSafetyManager.getInstance().requestConfirmation(
                        "AI 请求执行「" + name + "」",
                        "该工具（" + tool.permission().chineseName() + "）需要你确认后才执行。\n参数：" + arguments);
                if (!mRunning) return null;
                if (!approved) {
                    appendToolResult(session, listener, AiMessage.toolResultMessage("用户已取消该操作", callID), name, false);
                    continue;
                }
            }

            // 执行（同步）
            Map<String, Object> params = parseArgumentsJSON(arguments);
            final AiMessage[] resultHolder = new AiMessage[1];
            AiToolRegistry.getInstance().executeToolNamed(name, params, (result, error) -> {
                String content = result;
                if ((content == null || content.isEmpty()) && error != null) {
                    content = error.getMessage() == null ? error.toString() : error.getMessage();
                }
                if (content == null || content.isEmpty()) content = "（无返回）";
                AiMessage resMsg = AiMessage.toolResultMessage(content, callID);
                resMsg.toolSucceeded = (error == null);
                resultHolder[0] = resMsg;
            });
            AiMessage resMsg = resultHolder[0] != null ? resultHolder[0]
                    : AiMessage.toolResultMessage("（无返回）", callID);
            appendToolResult(session, listener, resMsg, name, resMsg.toolSucceeded);

            if (resultHolder[0] != null && !resultHolder[0].toolSucceeded) {
                // 失败也继续把错误回喂给模型（iOS 同款行为：只有达重试上限才终止）
            }
        }
        return terminalError;
    }

    private void appendToolResult(AiSession session, AgentListener listener, AiMessage msg, String toolName, boolean succeeded) {
        msg.toolName = toolName;
        msg.toolSucceeded = succeeded;
        synchronized (session) { session.messages.add(msg); }
        AiSessionStore.getInstance().updateSession(session);
        notifyChanged(listener);
    }

    // ===== 流式 tool_calls 累积（按 index 合并 name 与 args 增量） =====

    private static void accumulateToolCalls(JsonArray rawToolCalls, TreeMap<Integer, JsonObject> accumulator) {
        for (JsonElement element : rawToolCalls) {
            if (!element.isJsonObject()) continue;
            JsonObject tc = element.getAsJsonObject();
            int idx = tc.has("index") && tc.get("index").isJsonPrimitive()
                    ? tc.get("index").getAsInt() : accumulator.size();
            JsonObject entry = accumulator.get(idx);
            if (entry == null) {
                entry = new JsonObject();
                entry.addProperty("index", idx);
                accumulator.put(idx, entry);
            }
            // id 通常只在首片段出现
            JsonElement idEl = tc.get("id");
            if (idEl != null && idEl.isJsonPrimitive() && !idEl.getAsString().isEmpty()) {
                entry.addProperty("id", idEl.getAsString());
            }
            JsonElement funcEl = tc.get("function");
            if (funcEl != null && funcEl.isJsonObject()) {
                JsonObject func = funcEl.getAsJsonObject();
                JsonElement nameEl = func.get("name");
                if (nameEl != null && nameEl.isJsonPrimitive() && !nameEl.getAsString().isEmpty()) {
                    entry.addProperty("name", nameEl.getAsString());
                }
                JsonElement argsEl = func.get("arguments");
                if (argsEl != null && argsEl.isJsonPrimitive()) {
                    String prev = entry.has("arguments") ? entry.get("arguments").getAsString() : "";
                    entry.addProperty("arguments", prev + argsEl.getAsString());
                }
            }
            // 兼容把 name 放顶层的模型
            if (!entry.has("name") || entry.get("name").getAsString().isEmpty()) {
                JsonElement topName = tc.get("name");
                if (topName != null && topName.isJsonPrimitive() && !topName.getAsString().isEmpty()) {
                    entry.addProperty("name", topName.getAsString());
                }
            }
        }
    }

    /** 消息序列化为 API 载荷 */
    private static JsonObject serializeMessage(AiMessage m) {
        JsonObject entry = new JsonObject();
        entry.addProperty("role", m.role == null ? "" : m.role);
        entry.addProperty("content", m.content == null ? "" : m.content);
        if ("tool".equals(m.role) && m.toolCallID != null && !m.toolCallID.isEmpty()) {
            entry.addProperty("tool_call_id", m.toolCallID);
        }
        return entry;
    }

    private static JsonObject messageEntry(String role, String content, JsonArray toolCalls) {
        JsonObject entry = new JsonObject();
        entry.addProperty("role", role);
        entry.addProperty("content", content);
        if (toolCalls != null) entry.add("tool_calls", toolCalls);
        return entry;
    }

    /** 解析工具参数 JSON 字符串为 Map（失败返回空 Map） */
    private static Map<String, Object> parseArgumentsJSON(String jsonString) {
        Map<String, Object> result = new HashMap<>();
        if (jsonString == null || jsonString.isEmpty()) return result;
        try {
            JsonElement el = JsonParser.parseString(jsonString);
            if (el != null && el.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : el.getAsJsonObject().entrySet()) {
                    JsonElement v = entry.getValue();
                    if (v == null || v.isJsonNull()) continue;
                    if (v.isJsonPrimitive()) {
                        if (v.getAsJsonPrimitive().isBoolean()) result.put(entry.getKey(), v.getAsBoolean());
                        else if (v.getAsJsonPrimitive().isNumber()) result.put(entry.getKey(), v.getAsNumber());
                        else result.put(entry.getKey(), v.getAsString());
                    } else {
                        result.put(entry.getKey(), v.toString());
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static void notifyChanged(AgentListener listener) {
        if (listener != null) listener.onMessagesChanged();
    }

    private static void postCompletion(final Completion completion, final String error) {
        if (completion == null) return;
        net.kdt.pojavlaunch.Tools.runOnUiThread(() -> completion.onComplete(error));
    }
}
