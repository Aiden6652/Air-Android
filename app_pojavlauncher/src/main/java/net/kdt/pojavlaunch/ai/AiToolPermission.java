package net.kdt.pojavlaunch.ai;

/** 工具权限分级（对应 iOS AiToolPermission） */
public enum AiToolPermission {
    /** 只读 */
    READ_ONLY,
    /** 受控写入 */
    CONTROLLED_WRITE,
    /** 危险写入 */
    DANGEROUS_WRITE,
    /** 外部网络 */
    EXTERNAL_NETWORK;

    public String chineseName() {
        switch (this) {
            case READ_ONLY: return "只读操作";
            case CONTROLLED_WRITE: return "受控写入";
            case DANGEROUS_WRITE: return "危险写入";
            case EXTERNAL_NETWORK: return "网络访问";
        }
        return "未知操作";
    }
}
