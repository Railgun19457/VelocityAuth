package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AuditEntry;
import io.github.railgun19457.velocityauth.storage.model.LoginHistory;

/**
 * {@code /logout}
 */
public final class LogoutCommand extends PlayerCommandSupport {

    public LogoutCommand(VelocityAuthPlugin plugin, AuthSessionRegistry registry) {
        super(plugin, registry);
    }

    @Override
    public void execute(Invocation invocation) {
        Player player = requirePlayer(invocation);
        if (player == null || !requireAuthenticated(player)) {
            return;
        }
        AuthSession session = sessionOf(player);
        Account account = session == null ? null : session.account();
        if (session != null) {
            session.deauthenticate();
        }
        if (account != null) {
            long accountId = account.id();
            String ip = session.ip();
            plugin.async().run(() -> {
                try {
                    plugin.authService().clearSessions(accountId);
                    plugin.storage().audit().log(
                            io.github.railgun19457.velocityauth.auth.AuthService.nowSeconds(),
                            accountId, AuditEntry.LOGOUT, ip, "command");
                    plugin.storage().loginHistory().insert(
                            accountId, io.github.railgun19457.velocityauth.auth.AuthService.nowSeconds(),
                            ip, LoginHistory.LOGOUT, session.connectionName(), "command");
                } catch (RuntimeException e) {
                    plugin.log().warn("登出清理失败：{}", e.getMessage());
                }
            });
        }
        // 退出登录后回到登录大厅，而不是把玩家踢出服务器
        plugin.server().getServer(plugin.config().servers().login()).ifPresentOrElse(lobby -> {
            String current = player.getCurrentServer()
                    .map(server -> server.getServerInfo().getName())
                    .orElse("");
            if (current.equals(lobby.getServerInfo().getName())) {
                // 已经在登录服：只提示，不用切服
                player.sendMessage(plugin.lang().msg("command.logout.success"));
                return;
            }
            player.sendMessage(plugin.lang().msg("command.logout.success"));
            player.createConnectionRequest(lobby).fireAndForget();
        }, () -> {
            // 登录服未注册：退回原来的行为，断开并提示重连
            plugin.log().warn("登录服 {} 未注册，/logout 只能断开连接",
                    plugin.config().servers().login());
            player.disconnect(plugin.lang().msg("command.logout.no-lobby"));
        });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return availableWhenAuthenticated(invocation);
    }
}
