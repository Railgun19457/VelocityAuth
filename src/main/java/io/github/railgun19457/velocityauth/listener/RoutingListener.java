package io.github.railgun19457.velocityauth.listener;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AuditEntry;
import io.github.railgun19457.velocityauth.storage.model.LoginHistory;

import java.util.Optional;

/**
 * 路由与未认证隔离：
 * <ul>
 *   <li>{@code PlayerChooseInitialServerEvent} —— 登录服 / 记忆子服</li>
 *   <li>{@code ServerPreConnectEvent} —— 未认证跳服兜底拦截（R1）</li>
 *   <li>{@code ServerPostConnectEvent} —— 记录 last_server</li>
 * </ul>
 */
public final class RoutingListener {

    private final VelocityAuthPlugin plugin;
    private final AuthSessionRegistry registry;

    public RoutingListener(VelocityAuthPlugin plugin, AuthSessionRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        if (!plugin.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        VelocityAuthConfig cfg = plugin.config();
        AuthSession session = registry.get(player).orElse(null);

        if (session == null || !session.authenticated()) {
            Optional<RegisteredServer> login = plugin.server().getServer(cfg.servers().login());
            if (login.isPresent()) {
                event.setInitialServer(login.get());
            } else {
                plugin.log().error("登录服 {} 未在代理中注册，玩家 {} 无法进入",
                        cfg.servers().login(), player.getUsername());
                player.disconnect(plugin.lang().msg("routing.login-server-missing",
                        "server", cfg.servers().login()));
            }
            return;
        }

        Optional<RegisteredServer> target = targetServer(session);
        target.ifPresent(event::setInitialServer);
    }

    /** 已认证玩家的目标服务器：优先记忆子服，否则默认服。 */
    public Optional<RegisteredServer> targetServer(AuthSession session) {
        VelocityAuthConfig cfg = plugin.config();
        if (cfg.servers().rememberLast() && session.account() != null) {
            Account fresh = plugin.authService().cache().byId(session.account().id())
                    .orElse(session.account());
            String last = fresh.lastServer();
            if (last != null && !last.isBlank()
                    && !last.equals(cfg.servers().login())
                    && cfg.servers().allowed().contains(last)) {
                Optional<RegisteredServer> remembered = plugin.server().getServer(last);
                if (remembered.isPresent()) {
                    return remembered;
                }
            }
        }
        return plugin.server().getServer(cfg.servers().defaultServer());
    }

    /** 未认证玩家只能留在登录服（兜底，覆盖传送门/其它插件跳服）。 */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!plugin.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        AuthSession session = registry.get(player).orElse(null);
        if (session != null && session.authenticated()) {
            return;
        }
        VelocityAuthConfig cfg = plugin.config();
        String target = event.getOriginalServer().getServerInfo().getName();
        if (target.equals(cfg.servers().login())) {
            return;
        }
        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        player.sendMessage(plugin.lang().msg("auth.login-required"));
    }

    /** 记录玩家所在子服，供"记忆上次所在子服"使用。 */
    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        if (!plugin.enabled()) {
            return;
        }
        Player player = event.getPlayer();
        AuthSession session = registry.get(player).orElse(null);
        if (session == null || !session.authenticated() || session.account() == null) {
            return;
        }
        String serverName = player.getCurrentServer()
                .map(ServerConnection::getServerInfo)
                .map(ServerInfo::getName)
                .orElse(null);
        if (serverName == null) {
            return;
        }
        VelocityAuthConfig cfg = plugin.config();
        if (serverName.equals(cfg.servers().login())) {
            return;
        }
        Account account = session.account();
        String previous = plugin.authService().cache().byId(account.id())
                .map(Account::lastServer)
                .orElse(null);
        if (serverName.equals(previous)) {
            return;
        }
        plugin.async().run(() -> {
            try {
                plugin.storage().accounts().updateLastServer(account.id(), serverName);
                plugin.storage().accounts().findById(account.id()).ifPresent(
                        updated -> plugin.authService().cache().put(updated));
            } catch (RuntimeException e) {
                plugin.log().warn("记录 last_server 失败：{}", e.getMessage());
            }
        });
    }

    /** 断开时也更新一次 last_server，并写登出审计。 */
    public void recordOnDisconnect(Player player, AuthSession session) {
        if (session == null || !session.authenticated() || session.account() == null) {
            return;
        }
        Account account = session.account();
        String serverName = player.getCurrentServer()
                .map(ServerConnection::getServerInfo)
                .map(ServerInfo::getName)
                .orElse(null);
        plugin.async().run(() -> {
            try {
                if (serverName != null) {
                    plugin.storage().accounts().updateLastServer(account.id(), serverName);
                    plugin.storage().accounts().findById(account.id()).ifPresent(
                            updated -> plugin.authService().cache().put(updated));
                }
                plugin.storage().audit().log(
                        io.github.railgun19457.velocityauth.auth.AuthService.nowSeconds(),
                        account.id(), AuditEntry.LOGOUT, session.ip(), "connection=" + session.connectionName());
                plugin.storage().loginHistory().insert(
                        account.id(), io.github.railgun19457.velocityauth.auth.AuthService.nowSeconds(),
                        session.ip(), LoginHistory.LOGOUT, session.connectionName(), "connection");
            } catch (RuntimeException e) {
                plugin.log().warn("断开清理失败：{}", e.getMessage());
            }
        });
    }
}
