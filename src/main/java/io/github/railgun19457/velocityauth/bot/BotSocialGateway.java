package io.github.railgun19457.velocityauth.bot;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.verify.SocialVerificationGateway;

import java.util.function.Supplier;

/**
 * 用真实 bot 通道实现 {@link SocialVerificationGateway}（替换 M4 的桩实现）。
 */
public final class BotSocialGateway implements SocialVerificationGateway {

    private final Supplier<VelocityAuthConfig> config;
    private final Supplier<BotServer> server;

    public BotSocialGateway(Supplier<VelocityAuthConfig> config, Supplier<BotServer> server) {
        this.config = config;
        this.server = server;
    }

    @Override
    public boolean available(String providerId) {
        BotServer bot = server.get();
        if (bot == null) {
            return false;
        }
        VelocityAuthConfig.Bot settings = config.get().bot();
        return settings.enabled() && bot.readyCount(providerId) > 0;
    }

    @Override
    public void watch(String providerId, String code, int ttlSeconds) {
        BotServer bot = server.get();
        if (bot != null) {
            bot.watch(providerId, code, ttlSeconds);
        }
    }

    @Override
    public void cancel(String providerId, String code) {
        BotServer bot = server.get();
        if (bot != null) {
            bot.cancel(providerId, code);
        }
    }

    @Override
    public int connectedClients(String providerId) {
        BotServer bot = server.get();
        return bot == null ? 0 : bot.readyCount(providerId);
    }
}
