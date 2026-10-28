package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthOutcomes;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.auth.RegistrationFlow;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.verify.VerificationChannel;

/**
 * {@code /register <账号> <密码> [确认密码]}
 * <p>
 * 当 {@code registration.gate = email} 时为 {@code /register <账号> <密码> <邮箱> [确认密码]}（见 ADR-11）。
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
        VelocityAuthConfig cfg = plugin.config();
        VelocityAuthConfig.Registration.Gate gate = cfg.registration().gate();
        boolean emailGate = gate == VelocityAuthConfig.Registration.Gate.EMAIL;
        boolean qqGate = gate == VelocityAuthConfig.Registration.Gate.QQ;

        String[] args = invocation.arguments();
        int required = emailGate ? 3 : 2;
        if (args.length < required) {
            player.sendMessage(plugin.lang().msg("command.register.usage"));
            return;
        }
        String name = args[0];
        String password = args[1];
        String email = emailGate ? args[2] : null;
        String confirm = emailGate
                ? (args.length >= 4 ? args[3] : null)
                : (args.length >= 3 ? args[2] : null);

        // 快速前置校验（不碰 DB）
        VelocityAuthConfig.AccountName nameRule = cfg.auth().accountName();
        if (!plugin.authService().validName(name)) {
            player.sendMessage(plugin.lang().msg("command.register.name-invalid",
                    "min", nameRule.minLength(), "max", nameRule.maxLength()));
            return;
        }
        VelocityAuthConfig.Password pwRule = cfg.auth().password();
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

        var identity = identityOf(player);
        String ip = ipOf(player);

        if (!emailGate && !qqGate) {
            plugin.authService().register(name, password, confirm, identity, ip)
                    .thenAccept(result -> handle(player, identity, ip, result))
                    .exceptionally(throwable -> {
                        plugin.log().error("注册命令异常", throwable);
                        player.sendMessage(plugin.lang().msg("generic.internal-error"));
                        return null;
                    });
            return;
        }

        plugin.registrationFlow()
                .begin(new RegistrationFlow.Request(player.getUniqueId(), name, password, email,
                        identity, ip,
                        VerificationPrompts.dispatchHandler(plugin, player, email)))
                .thenAccept(started -> handleGated(player, identity, ip, started))
                .exceptionally(throwable -> {
                    plugin.log().error("注册命令异常", throwable);
                    player.sendMessage(plugin.lang().msg("generic.internal-error"));
                    return null;
                });
    }

    private void handleGated(Player player, io.github.railgun19457.velocityauth.auth.IdentityResolver.Resolved identity, String ip,
                             RegistrationFlow.Started started) {
        if (!started.accepted()) {
            if (!started.notified()) {
                player.sendMessage(messageFor(started.error()));
            }
            return;
        }
        if (started.channel() == VerificationChannel.QQ) {
            VerificationPrompts.sendQqInstruction(plugin, player, started.session().code());
        }
        started.completion().thenAccept(result -> {
            if (result.status() == AuthOutcomes.RegisterStatus.SUCCESS) {
                finishAuthAndReconnect(player, identity, ip, result.account().id(),
                        plugin.lang().msg("command.register.success"));
            } else {
                player.sendMessage(messageFor(result.status()));
            }
        });
    }

    private void handle(Player player, io.github.railgun19457.velocityauth.auth.IdentityResolver.Resolved identity, String ip, AuthOutcomes.Register result) {
        if (result.status() == AuthOutcomes.RegisterStatus.SUCCESS) {
            finishAuthAndReconnect(player, identity, ip, result.account().id(),
                    plugin.lang().msg("command.register.success"));
            return;
        }
        player.sendMessage(messageFor(result.status()));
    }

    private net.kyori.adventure.text.Component messageFor(AuthOutcomes.RegisterStatus status) {
        VelocityAuthConfig cfg = plugin.config();
        return switch (status) {
            case NAME_TAKEN -> plugin.lang().msg("command.register.name-taken");
            case NAME_INVALID -> plugin.lang().msg("command.register.name-invalid",
                    "min", cfg.auth().accountName().minLength(), "max", cfg.auth().accountName().maxLength());
            case PASSWORD_TOO_SHORT -> plugin.lang().msg("command.register.password-too-short",
                    "min", cfg.auth().password().minLength());
            case PASSWORD_TOO_LONG -> plugin.lang().msg("command.register.password-too-long",
                    "max", cfg.auth().password().maxLength());
            case PASSWORD_MISMATCH -> plugin.lang().msg("command.register.password-mismatch");
            case EMAIL_INVALID -> plugin.lang().msg("command.bind.email-invalid");
            case DOMAIN_NOT_ALLOWED -> plugin.lang().msg("command.bind.domain-not-allowed");
            case EMAIL_LIMIT -> plugin.lang().msg("command.bind.email-limit");
            case QQ_LIMIT -> plugin.lang().msg("command.bind.qq-limit");
            case RATE_LIMITED -> plugin.lang().msg("command.register.rate-limited");
            case VERIFICATION_CANCELLED -> plugin.lang().msg("verification.cancelled");
            case VERIFICATION_EXPIRED -> plugin.lang().msg("verification.expired");
            case VERIFICATION_FAILED -> plugin.lang().msg("generic.internal-error");
            case GATE_REQUIRED -> plugin.lang().msg("generic.feature-disabled");
            case SUCCESS, ERROR -> plugin.lang().msg("generic.internal-error");
        };
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof Player;
    }
}
