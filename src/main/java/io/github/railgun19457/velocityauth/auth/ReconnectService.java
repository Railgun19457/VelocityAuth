package io.github.railgun19457.velocityauth.auth;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import net.kyori.adventure.text.Component;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * 登录成功后的"重连"：优先使用 Transfer 包（1.20.5+），否则断开并提示重连。
 */
public final class ReconnectService {

    private final ProxyServer server;
    private final Supplier<VelocityAuthConfig> config;
    private final PluginLogger log;

    /** 断开/Transfer 前的缓冲：保证紧随其后的提示（"登录成功…"）已经送达客户端。 */
    private static final Duration MESSAGE_GRACE = Duration.ofMillis(500);

    private final Object plugin;

    private volatile boolean warnedWildcard;

    public ReconnectService(ProxyServer server, Object plugin, Supplier<VelocityAuthConfig> config,
                            PluginLogger log) {
        this.server = server;
        this.plugin = plugin;
        this.config = config;
        this.log = log;
    }

    public boolean supportsTransfer(Player player) {
        return player.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_20_5) >= 0;
    }

    /**
     * 让玩家重新连接到代理自身，以触发 {@code GameProfileRequestEvent} 完成 profile 重写。
     * <p>
     * 延迟 {@link #MESSAGE_GRACE} 再断开：调用方通常刚发过"登录成功，正在进入服务器…"，
     * 立即断开时客户端可能先处理断线包，导致这条提示看不到。
     *
     * @param disconnectMessage 客户端不支持 Transfer 时展示的提示
     */
    public void reconnect(Player player, Component disconnectMessage) {
        server.getScheduler()
                .buildTask(plugin, () -> doReconnect(player, disconnectMessage))
                .delay(MESSAGE_GRACE)
                .schedule();
    }

    private void doReconnect(Player player, Component disconnectMessage) {
        if (!player.isActive()) {
            return;
        }
        if (supportsTransfer(player)) {
            InetSocketAddress target = selfAddress(player);
            try {
                player.transferToHost(target);
                return;
            } catch (RuntimeException e) {
                log.warn("Transfer 失败（{}），降级为断开重连", e.getMessage());
            }
        }
        player.disconnect(disconnectMessage);
    }

    /** 计算代理自身对外地址。 */
    public InetSocketAddress selfAddress(Player player) {
        VelocityAuthConfig.Proxy proxy = config.get().proxy();
        if (!proxy.address().isBlank()) {
            int port = proxy.port() > 0 ? proxy.port() : server.getBoundAddress().getPort();
            return new InetSocketAddress(proxy.address(), port);
        }
        InetSocketAddress bound = server.getBoundAddress();
        int port = proxy.port() > 0 ? proxy.port() : bound.getPort();
        String host = player.getVirtualHost().map(InetSocketAddress::getHostString).orElse(null);
        if (host == null || host.isBlank()) {
            host = bound.getHostString();
        }
        if (isWildcard(host)) {
            if (!warnedWildcard) {
                warnedWildcard = true;
                log.warn("无法确定代理对外地址（bind 为通配地址且客户端未提供 virtual host），"
                        + "Transfer 将回退到 127.0.0.1；请显式配置 proxy.address / proxy.port");
            }
            host = "127.0.0.1";
        }
        return new InetSocketAddress(host, port);
    }

    private static boolean isWildcard(String host) {
        return "0.0.0.0".equals(host) || "::".equals(host) || "0:0:0:0:0:0:0:0".equals(host);
    }
}
