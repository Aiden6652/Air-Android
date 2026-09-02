package net.kdt.pojavlaunch.ai;

/**
 * AI 工具基础接口（对应 iOS AiTool.h）
 */
public interface AiTool {
    /** 工具名（供模型调用的 function name） */
    String name();

    /** 工具说明（含参数说明，直接作为 OpenAI schema 的 description） */
    String summary();

    /** 权限级别 */
    AiToolPermission permission();

    /**
     * 执行工具。参数已经过 camelCase 规范化。
     * completion 可能在任意线程被调用（注册表会统一回主线程）。
     */
    void execute(AiParams params, AiToolCallback completion);

    interface AiToolCallback {
        void onResult(String result, Throwable error);
    }

    /** 参数包装：宽松取值 */
    class AiParams {
        private final java.util.Map<String, Object> map;
        public AiParams(java.util.Map<String, Object> map) { this.map = map == null ? new java.util.HashMap<>() : map; }

        public String getString(String key) {
            Object v = map.get(key);
            return v == null ? null : String.valueOf(v);
        }
        public String optString(String key, String def) {
            String s = getString(key);
            return (s == null || s.isEmpty()) ? def : s;
        }
        public int optInt(String key, int def) {
            Object v = map.get(key);
            if (v instanceof Number) return ((Number) v).intValue();
            try { return v == null ? def : Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
        }
        public boolean optBool(String key, boolean def) {
            Object v = map.get(key);
            if (v instanceof Boolean) return (Boolean) v;
            if (v == null) return def;
            String s = String.valueOf(v).trim().toLowerCase();
            if (s.equals("true") || s.equals("yes") || s.equals("1")) return true;
            if (s.equals("false") || s.equals("no") || s.equals("0")) return false;
            return def;
        }
        public boolean has(String key) { return map.containsKey(key); }
        public java.util.Map<String, Object> raw() { return map; }
    }
}
