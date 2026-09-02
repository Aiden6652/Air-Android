package net.kdt.pojavlaunch.ai;

/** 对话消息数据模型（对应 iOS AiMessage） */
public class AiMessage {
    /** system / user / assistant / tool */
    public String role;
    public String content;
    /** 仅运行时标记（流式占位），不序列化 */
    public transient boolean streaming;

    // ===== 工具调用扩展字段 =====
    public String toolCallID;
    public String toolName;
    public String toolArguments;
    public boolean isToolCall;
    public boolean isToolResult;
    public boolean toolSucceeded = true;

    public AiMessage() {}

    public AiMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public static AiMessage toolCallMessage(String name, String arguments, String callID) {
        AiMessage m = new AiMessage("assistant", "");
        m.isToolCall = true;
        m.toolCallID = callID;
        m.toolName = name;
        m.toolArguments = arguments;
        return m;
    }

    public static AiMessage toolResultMessage(String content, String callID) {
        AiMessage m = new AiMessage("tool", content);
        m.isToolResult = true;
        m.toolCallID = callID;
        return m;
    }
}
