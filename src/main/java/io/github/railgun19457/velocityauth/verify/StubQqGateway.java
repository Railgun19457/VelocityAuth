package io.github.railgun19457.velocityauth.verify;

import io.github.railgun19457.velocityauth.util.PluginLogger;

import java.util.function.Supplier;

/**
 * QQ 渠道桩实现（M4 用）：只记录登记/取消事件，不真正与 bot 通信。
 * <p>
 * 注意：绝不记录验证码明文。
 */
public final class StubQqGateway implements QqVerificationGateway {

    private final Supplier<Boolean> enabled;
    private final PluginLogger log;

    public StubQqGateway(Supplier<Boolean> enabled, PluginLogger log) {
        this.enabled = enabled;
        this.log = log;
    }

    @Override
    public boolean available() {
        return Boolean.TRUE.equals(enabled.get());
    }

    @Override
    public void watch(String code, String groupId, int ttlSeconds) {
        log.info("（桩）已登记 QQ 验证会话：群 {}，TTL {} 秒（等待 M6 接入 bot 通道）", groupId, ttlSeconds);
    }

    @Override
    public void cancel(String code) {
        log.debug("（桩）已取消 QQ 验证会话");
    }
}
