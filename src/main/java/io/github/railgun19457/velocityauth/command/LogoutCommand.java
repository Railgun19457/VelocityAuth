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
        var reconnectMessage = plugin.lang().msg("command.logout.reconnect");
        player.sendMessage(plugin.lang().msg("command.logout.success"));

        // 先清会话保持再重连：否则重连后可能被旧 session 直接免密放行。
        // 重连会让 profile 回到原始游戏 ID，并由路由自动落到登录服。
        if (account == null) {
            plugin.reconnectService().reconnect(player, reconnectMessage);
            return;
        }
        long accountId = account.id();
        String ip = session.ip();
        String connectionName = session.connectionName();
        plugin.async().run(() -> {
            try {
                plugin.authService().clearSessions(accountId);
                plugin.storage().audit().log(
                        io.github.railgun19457.velocityauth.auth.AuthService.nowSeconds(),
                        accountId, AuditEntry.LOGOUT, ip, "command");
                plugin.storage().loginHistory().insert(
                        accountId, io.github.railgun19457.velocityauth.auth.AuthService.nowSeconds(),
                        ip, LoginHistory.LOGOUT, connectionName, "command");
            } catch (RuntimeException e) {
                plugin.log().warn("登出清理失败：{}", e.getMessage());
            } finally {
                plugin.reconnectService().reconnect(player, reconnectMessage);
            }
        });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return availableWhenAuthenticated(invocation);
    }
}
