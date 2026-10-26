package io.github.railgun19457.velocityauth.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.util.Addresses;

/**
 * 玩家生命周期：登录后挂载会话、断开时清理。
 */
public final class PlayerLifecycleListener {

    private final VelocityAuthPlugin plugin;
    private final AuthSessionRegistry registry;
    private final RoutingListener routingListener;

    public PlayerLifecycleListener(VelocityAuthPlugin plugin, AuthSessionRegistry registry,
                                   RoutingListener routingListener) {
        this.plugin = plugin;
        this.registry = registry;
        this.routingListener = routingListener;
    }

    @Subscribe
    public void onPostLogin(PostLoginEvent event) {
        if (!plugin.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        AuthSession session = registry.attach(player.getUniqueId(), player).orElseGet(() -> {
            AuthSession created = new AuthSession(player.getUsername(), Addresses.ipOf(player));
            created.player(player);
            registry.stage(player.getUniqueId(), created);
            return created;
        });

        if (session.authenticated()) {
            Account account = session.account();
            if (account != null) {
                plugin.async().run(() -> {
                    try {
                        plugin.authService().recordSession(account.id(), session.ip());
                    } catch (RuntimeException e) {
                        plugin.log().warn("记录会话保持失败：{}", e.getMessage());
                    }
                });
            }
            return;
        }
        player.sendMessage(plugin.lang().msg("routing.joining-login"));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (!plugin.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        AuthSession session = registry.remove(player.getUniqueId()).orElse(null);
        if (session == null) {
            return;
        }
        routingListener.recordOnDisconnect(player, session);
    }
}
