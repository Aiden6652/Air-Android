package net.kdt.pojavlaunch.ai;

/** AI 提供商配置（对应 iOS AiProvider） */
public class AiProvider {
    public String identifier = "default";
    public String name = "";
    /** OpenAI 兼容 API 根地址（如 https://api.openai.com/v1），末尾自动补 / */
    public String baseURL = "";
    public String apiKey = "";
    public String model = "";
    public double temperature = 0.7;
    public int maxTokens = 4096;

    public boolean isConfigured() {
        return baseURL != null && !baseURL.trim().isEmpty()
                && model != null && !model.trim().isEmpty();
    }
}
