package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthOutcomes;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;

/**
 * {@code /register <账号> <密码> [确认密码]}
 */
public final class RegisterCommand extends PlayerCommandSupport {

    public RegisterCommand(VelocityAuthPlugin plugin, AuthSessionRegistry registry) {
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
            player.sendMessage(plugin.lang().msg("command.register.usage"));
            return;
        }
        String name = args[0];
        String password = args[1];
        String confirm = args.length >= 3 ? args[2] : null;

        VelocityAuthConfig.AccountName nameRule = plugin.config().auth().accountName();
        if (!plugin.authService().validName(name)) {
            player.sendMessage(plugin.lang().msg("command.register.name-invalid",
                    "min", nameRule.minLength(), "max", nameRule.maxLength()));
            return;
        }
        VelocityAuthConfig.Password pwRule = plugin.config().auth().password();
        AuthOutcomes.RegisterStatus pwIssue = plugin.authService().passwordLengthIssue(password);
        if (pwIssue == AuthOutcomes.RegisterStatus.PASSWORD_TOO_SHORT) {
            player.sendMessage(plugin.lang().msg("command.register.password-too-short", "min", pwRule.minLength()));
            return;
        }
        if (pwIssue == AuthOutcomes.RegisterStatus.PASSWORD_TOO_LONG) {
            player.sendMessage(plugin.lang().msg("command.register.password-too-long", "max", pwRule.maxLength()));
            return;
        }
        if (confirm != null && !password.equals(confirm)) {
            player.sendMessage(plugin.lang().msg("command.register.password-mismatch"));
            return;
        }

        String connectionNameLower = lower(player.getUsername());
        String ip = ipOf(player);
        plugin.authService().register(name, password, confirm, connectionNameLower, ip)
                .thenAccept(result -> handle(player, connectionNameLower, ip, result))
                .exceptionally(throwable -> {
                    plugin.log().error("注册命令异常", throwable);
                    player.sendMessage(plugin.lang().msg("generic.internal-error"));
                    return null;
                });
    }

    private void handle(Player player, String connectionNameLower, String ip, AuthOutcomes.Register result) {
        switch (result.status()) {
            case SUCCESS -> {
                plugin.authService().createPendingBinding(connectionNameLower, ip, result.account().id());
                player.sendMessage(plugin.lang().msg("command.register.success"));
                plugin.reconnectService().reconnect(player, plugin.lang().msg("auth.login-disconnect"));
            }
            case NAME_TAKEN -> player.sendMessage(plugin.lang().msg("command.register.name-taken"));
            case NAME_INVALID -> player.sendMessage(plugin.lang().msg("command.register.name-invalid",
                    "min", plugin.config().auth().accountName().minLength(),
                    "max", plugin.config().auth().accountName().maxLength()));
            case PASSWORD_TOO_SHORT -> player.sendMessage(plugin.lang().msg("command.register.password-too-short",
                    "min", plugin.config().auth().password().minLength()));
            case PASSWORD_TOO_LONG -> player.sendMessage(plugin.lang().msg("command.register.password-too-long",
                    "max", plugin.config().auth().password().maxLength()));
            case PASSWORD_MISMATCH -> player.sendMessage(plugin.lang().msg("command.register.password-mismatch"));
            case GATE_REQUIRED -> player.sendMessage(plugin.lang().msg(
                    "email".equals(result.gate()) ? "command.register.gate-email" : "command.register.gate-qq"));
            case RATE_LIMITED -> player.sendMessage(plugin.lang().msg("command.register.rate-limited"));
            case ERROR -> player.sendMessage(plugin.lang().msg("generic.internal-error"));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof Player;
    }
}
