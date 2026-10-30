package io.github.railgun19457.velocityauth.verify;

/**
 * 社交渠道（provider）与 bot 之间的边界。
 * <p>
 * 实现见 {@code BotSocialGateway}（真实 bot 通道）。email 渠道不走这里（走内置 SMTP）。
 */
public interface SocialVerificationGateway {

    /** 该渠道是否可用（bot 通道启用且已有服务该 provider 的连接）。 */
    boolean available(String providerId);

    /** 登记一个待验证码，TTL 内被 bot 判定为有效命中即视为验证。 */
    void watch(String providerId, String code, int ttlSeconds);

    /** 会话取消/超时，通知 bot 停止监听。 */
    void cancel(String providerId, String code);

    /** 当前服务该渠道的已连接 bot 数量（用于状态展示）。 */
    default int connectedClients(String providerId) {
        return 0;
    }
}
