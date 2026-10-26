package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.util.Addresses;

/**
 * 玩家命令公共基类：统一的玩家校验、认证态校验与提示。
 */
public abstract class PlayerCommandSupport implements SimpleCommand {

    protected final VelocityAuthPlugin plugin;
    protected final AuthSessionRegistry registry;

    protected PlayerCommandSupport(VelocityAuthPlugin plugin, AuthSessionRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    /** 要求执行者是玩家，否则提示并返回 null。 */
    protected Player requirePlayer(Invocation invocation) {
        CommandSource source = invocation.source();
        if (source instanceof Player player) {
            return player;
        }
        source.sendMessage(plugin.lang().msg("generic.player-only"));
        return null;
    }

    protected AuthSession sessionOf(Player player) {
        return registry.get(player).orElse(null);
    }

    /** 已认证则提示并返回 false。 */
    protected boolean requireUnauthenticated(Player player) {
        AuthSession session = sessionOf(player);
        if (session != null && session.authenticated()) {
            player.sendMessage(plugin.lang().msg("generic.already-authenticated"));
            return false;
        }
        return true;
    }

    /** 未认证则提示并返回 false。 */
    protected boolean requireAuthenticated(Player player) {
        AuthSession session = sessionOf(player);
        if (session == null || !session.authenticated()) {
            player.sendMessage(plugin.lang().msg("generic.not-authenticated"));
            return false;
        }
        return true;
    }

    protected String ipOf(Player player) {
        return Addresses.ipOf(player);
    }

    protected static String lower(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
