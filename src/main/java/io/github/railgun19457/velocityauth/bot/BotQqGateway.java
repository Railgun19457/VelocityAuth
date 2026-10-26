package io.github.railgun19457.velocityauth.bot;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.verify.QqVerificationGateway;

import java.util.function.Supplier;

/**
 * 用真实 bot 通道实现 {@link QqVerificationGateway}（替换 M4 的桩实现）。
 */
public final class BotQqGateway implements QqVerificationGateway {

    private final Supplier<VelocityAuthConfig> config;
    private final Supplier<BotServer> server;

    public BotQqGateway(Supplier<VelocityAuthConfig> config, Supplier<BotServer> server) {
        this.config = config;
        this.server = server;
    }

    @Override
    public boolean available() {
        BotServer bot = server.get();
        if (bot == null) {
            return false;
        }
        VelocityAuthConfig.QqBot settings = config.get().qqbot();
        return settings.enabled() && !settings.groupId().isBlank() && bot.readyCount() > 0;
    }

    @Override
    public void watch(String code, String groupId, int ttlSeconds) {
        BotServer bot = server.get();
        if (bot == null) {
            return;
        }
        bot.watch(code, groupId, ttlSeconds);
    }

    @Override
    public void cancel(String code) {
        BotServer bot = server.get();
        if (bot != null) {
            bot.cancel(code);
        }
    }

    @Override
    public int connectedClients() {
        BotServer bot = server.get();
        return bot == null ? 0 : bot.readyCount();
    }
}
