package io.github.railgun19457.velocityauth.util;

import com.velocitypowered.api.proxy.Player;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * 地址处理工具。
 */
public final class Addresses {

    private Addresses() {
    }

    public static String ipOf(InetSocketAddress address) {
        if (address == null) {
            return "unknown";
        }
        InetAddress inet = address.getAddress();
        return inet == null ? address.getHostString() : inet.getHostAddress();
    }

    public static String ipOf(Player player) {
        return ipOf(player.getRemoteAddress());
    }
}
