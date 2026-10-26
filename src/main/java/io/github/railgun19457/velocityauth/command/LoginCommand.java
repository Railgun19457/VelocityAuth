package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthOutcomes;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;

/**
 * {@code /login <账号> <密码>}
 */
public final class LoginCommand extends PlayerCommandSupport {

    public LoginCommand(VelocityAuthPlugin plugin, AuthSessionRegistry registry) {
        super(plugin, registry);
    }

    @Override
    public void execute(Invocation invocation) {
        Player player = requirePlayer(invocation);
        if (player == null || !requireUnauthenticated(player)) {
            return;
        }
        String[] args = invocation.arguments();
        if (args.length < 2) {
            player.sendMessage(plugin.lang().msg("command.login.usage"));
            return;
        }
        String connectionNameLower = lower(player.getUsername());
        String ip = ipOf(player);
        plugin.authService().login(args[0], args[1], connectionNameLower, ip)
                .thenAccept(result -> handle(player, connectionNameLower, ip, result))
                .exceptionally(throwable -> {
                    plugin.log().error("登录命令异常", throwable);
                    player.sendMessage(plugin.lang().msg("generic.internal-error"));
                    return null;
                });
    }

    private void handle(Player player, String connectionNameLower, String ip, AuthOutcomes.Login result) {
        switch (result.status()) {
            case SUCCESS -> {
                plugin.authService().createPendingBinding(connectionNameLower, ip, result.account().id());
                player.sendMessage(plugin.lang().msg("command.login.success"));
                plugin.reconnectService().reconnect(player, plugin.lang().msg("auth.login-disconnect"));
            }
            case WRONG_PASSWORD -> player.sendMessage(plugin.lang().msg("command.login.wrong-password"));
            case LOCKED -> player.sendMessage(plugin.lang().msg("command.login.locked"));
            case RATE_LIMITED -> player.sendMessage(plugin.lang().msg("command.login.rate-limited"));
            case ERROR -> player.sendMessage(plugin.lang().msg("generic.internal-error"));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof Player;
    }
}
