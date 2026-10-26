package io.github.railgun19457.velocityauth.auth;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import net.kyori.adventure.text.Component;

import java.net.InetSocketAddress;
import java.util.function.Supplier;

/**
 * 登录成功后的"重连"：优先使用 Transfer 包（1.20.5+），否则断开并提示重连。
 * <p>
 * 见 DESIGN.md 4.3 与 IMPLEMENTATION.md 5.2。
 */
public final class ReconnectService {

    private final ProxyServer server;
    private final Supplier<VelocityAuthConfig> config;
    private final PluginLogger log;

    private volatile boolean warnedWildcard;

    public ReconnectService(ProxyServer server, Supplier<VelocityAuthConfig> config, PluginLogger log) {
        this.server = server;
        this.config = config;
        this.log = log;
    }

    public boolean supportsTransfer(Player player) {
        return player.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_20_5) >= 0;
    }

    /**
     * 让玩家重新连接到代理自身，以触发 {@code GameProfileRequestEvent} 完成 profile 重写。
     *
     * @param disconnectMessage 客户端不支持 Transfer 时展示的提示
     */
    public void reconnect(Player player, Component disconnectMessage) {
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
