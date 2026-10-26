package io.github.railgun19457.velocityauth.verify;

/**
 * 验证渠道。
 */
public enum VerificationChannel {
    /** 邮箱收码 → 游戏内输码。 */
    EMAIL,
    /** 游戏内出码 → 群里发送 → bot 回报。 */
    QQ;

    public String langKey() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
