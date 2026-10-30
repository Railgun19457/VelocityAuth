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
        player.disconnect(plugin.lang().msg("auth.logout-disconnect"));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return availableWhenAuthenticated(invocation);
    }
}
