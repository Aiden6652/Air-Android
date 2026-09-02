package net.kdt.pojavlaunch.ai;

/** 安全模式（对应 iOS AiSafetyMode）：SAFE=0 / ASK=1 / YOLO=2 */
public enum AiSafetyMode {
    SAFE, ASK, YOLO;

    public static AiSafetyMode fromInt(int v) {
        if (v < 0 || v >= values().length) return SAFE;
        return values()[v];
    }

    public String chineseName() {
        switch (this) {
            case SAFE: return "安全";
            case ASK: return "询问";
            case YOLO: return "完全";
        }
        return "";
    }
}
