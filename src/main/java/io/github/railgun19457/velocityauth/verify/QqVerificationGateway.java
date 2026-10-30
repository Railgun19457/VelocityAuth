package io.github.railgun19457.velocityauth.verify;

/**
 * QQ 验证渠道与 bot 之间的边界。
 * <p>
 * 实现见 {@code BotQqGateway}（真实 bot 通道）。
 */
public interface QqVerificationGateway {

    /** 通道是否可用（qqbot.enabled 且已有 bot 连接）。 */
    boolean available();

    /** 登记一个待验证码，TTL 内群里出现该码即视为验证。 */
    void watch(String code, String groupId, int ttlSeconds);

    /** 会话取消/超时，通知 bot 停止监听。 */
    void cancel(String code);

    /** 当前已连接的 bot 数量（用于状态展示）。 */
    default int connectedClients() {
        return 0;
    }
}
