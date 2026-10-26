package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthOutcomes;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.storage.model.Account;

/**
 * {@code /changepassword <旧密码> <新密码>}
 */
public final class ChangePasswordCommand extends PlayerCommandSupport {

    public ChangePasswordCommand(VelocityAuthPlugin plugin, AuthSessionRegistry registry) {
        super(plugin, registry);
    }

    @Override
    public void execute(Invocation invocation) {
        Player player = requirePlayer(invocation);
        if (player == null || !requireAuthenticated(player)) {
            return;
        }
        String[] args = invocation.arguments();
        if (args.length < 2) {
            player.sendMessage(plugin.lang().msg("command.changepassword.usage"));
            return;
        }
        AuthSession session = sessionOf(player);
        Account account = session == null ? null : session.account();
        if (account == null) {
            player.sendMessage(plugin.lang().msg("generic.not-authenticated"));
            return;
        }
        plugin.authService().changePassword(account.id(), args[0], args[1], ipOf(player))
                .thenAccept(result -> handle(player, result))
                .exceptionally(throwable -> {
                    plugin.log().error("修改密码命令异常", throwable);
                    player.sendMessage(plugin.lang().msg("generic.internal-error"));
                    return null;
                });
    }

    private void handle(Player player, AuthOutcomes.ChangePassword result) {
        switch (result.status()) {
            case SUCCESS -> player.sendMessage(plugin.lang().msg("command.changepassword.success"));
            case WRONG_PASSWORD -> player.sendMessage(plugin.lang().msg("command.changepassword.wrong-password"));
            case SAME_AS_OLD -> player.sendMessage(plugin.lang().msg("command.changepassword.same-as-old"));
            case PASSWORD_TOO_SHORT -> player.sendMessage(plugin.lang().msg("command.register.password-too-short",
                    "min", plugin.config().auth().password().minLength()));
            case PASSWORD_TOO_LONG -> player.sendMessage(plugin.lang().msg("command.register.password-too-long",
                    "max", plugin.config().auth().password().maxLength()));
            case NOT_FOUND, ERROR -> player.sendMessage(plugin.lang().msg("generic.internal-error"));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof Player;
    }
}
