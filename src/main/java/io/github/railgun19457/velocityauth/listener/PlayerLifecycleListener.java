package io.github.railgun19457.velocityauth.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.util.Addresses;

/**
 * 玩家生命周期：登录后挂载会话、未认证提示、断开时清理。
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
                plugin.authService().recordSessionAsync(account.id(), session.ip());
            }
        }
        // 未认证提示不在这里发：PostLoginEvent 早于 JoinGame，1.20.2+ 客户端此时还没进入
        // play 状态，聊天包会被丢弃。改在 ServerPostConnectEvent（见下）发送。
    }

    /**
     * 未认证提示：在玩家真正连上子服（JoinGame 之后）再发。
     * <p>
     * 1.20.2+ 客户端存在 configuration 阶段，{@code PostLoginEvent} 触发时玩家尚未进入 play
     * 状态，此时写的 play 阶段包会被 Velocity 的 play 包队列在 JoinGame 之前释放，客户端直接
     * 丢弃（mineflayer 对越界包更宽容，能收到，所以自动化测试容易漏掉这个坑）。
     * {@code ServerPostConnectEvent} 在玩家已进入登录服之后触发，聊天包才能正常显示。
     */
    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (!plugin.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        AuthSession session = registry.get(player).orElse(null);
        if (session == null || session.authenticated()) {
            return;
        }
        // 已知该连接对应的账号时，把 [登录] 按钮预填成 /login <账号> ，玩家只需打密码
        var identity = plugin.identities().resolve(player.getUniqueId(), player.getUsername());
        var linked = plugin.authService().cache().byIdentity(identity.kind(), identity.identifier());
        if (linked.isPresent()) {
            String account = linked.get().name();
            player.sendMessage(plugin.lang().msg("routing.joining-login-known", "account", account));
            player.sendMessage(plugin.lang().msg("routing.joining-login-usage-login-known", "account", account));
            player.sendMessage(plugin.lang().msg("routing.joining-login-usage-forgot"));
            return;
        }
        // 一行是 [登录]/[注册]/[忘记密码] 按钮（点击填充指令），随后三行分别是用法
        player.sendMessage(plugin.lang().msg("routing.joining-login"));
        player.sendMessage(plugin.lang().msg("routing.joining-login-usage-login"));
        player.sendMessage(plugin.lang().msg("routing.joining-login-usage-register"));
        player.sendMessage(plugin.lang().msg("routing.joining-login-usage-forgot"));
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
