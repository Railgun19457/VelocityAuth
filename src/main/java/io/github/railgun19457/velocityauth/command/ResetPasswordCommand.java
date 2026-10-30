package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthOutcomes;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.auth.PasswordHasher;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.model.AuditEntry;

import java.util.Optional;

/**
 * {@code /resetpassword <新密码> <确认密码>}
 * <p>
 * 仅在 {@code /forgot} 验证通过后的授权窗口内可用；成功后自动完成登录（走重连流程）。
 */
public final class ResetPasswordCommand extends PlayerCommandSupport {

    public ResetPasswordCommand(VelocityAuthPlugin plugin, AuthSessionRegistry registry) {
        super(plugin, registry);
    }

    @Override
    public void execute(Invocation invocation) {
        Player player = requirePlayer(invocation);
        if (player == null) {
            return;
        }
        String[] args = invocation.arguments();
        if (args.length < 2) {
            player.sendMessage(plugin.lang().msg("command.resetpassword.usage"));
            return;
        }
        // 先校验密码，再消费授权：避免玩家手误（两次不一致/过短）白白丢掉一次 /forgot 的验证结果
        String newPassword = args[0];
        if (!newPassword.equals(args[1])) {
            player.sendMessage(plugin.lang().msg("command.resetpassword.mismatch"));
            return;
        }
        VelocityAuthConfig.Password rule = plugin.config().auth().password();
        AuthOutcomes.RegisterStatus pwIssue = plugin.authService().passwordLengthIssue(newPassword);
        if (pwIssue == AuthOutcomes.RegisterStatus.PASSWORD_TOO_SHORT) {
            player.sendMessage(plugin.lang().msg("command.register.password-too-short", "min", rule.minLength()));
            return;
        }
        if (pwIssue == AuthOutcomes.RegisterStatus.PASSWORD_TOO_LONG) {
            player.sendMessage(plugin.lang().msg("command.register.password-too-long", "max", rule.maxLength()));
            return;
        }
        if (pwIssue == AuthOutcomes.RegisterStatus.PASSWORD_TOO_LONG_BYTES) {
            player.sendMessage(plugin.lang().msg("command.register.password-too-long-bytes",
                    "bytes", PasswordHasher.MAX_PASSWORD_BYTES));
            return;
        }

        Optional<Long> grant = plugin.recovery().consumeGrant(player.getUniqueId());
        if (grant.isEmpty()) {
            player.sendMessage(plugin.lang().msg("command.resetpassword.no-grant"));
            return;
        }
        long accountId = grant.get();

        var identity = identityOf(player);
        String ip = ipOf(player);
        plugin.authService().forcePassword(accountId, newPassword, ip, AuditEntry.RECOVER)
                .thenAccept(result -> {
                    if (result.status() != AuthOutcomes.ChangePasswordStatus.SUCCESS) {
                        player.sendMessage(plugin.lang().msg("generic.internal-error"));
                        return;
                    }
                    // 重置密码同样属于"登录"，必须满足注册后必须绑定的要求，
                    // 否则「只绑了非门槛渠道的账号」可以靠找回绕过绑定门槛
                    var account = plugin.authService().cache().byId(accountId).orElse(null);
                    if (account != null && !plugin.authService().bindingSatisfied(account)) {
                        var session = sessionOf(player);
                        if (session != null) {
                            session.allowPreLoginBinding(accountId);
                        }
                        player.sendMessage(plugin.lang().msg("command.resetpassword.binding-required"));
                        sendBindingRequirement(player);
                        return;
                    }
                    finishAuthAndReconnect(player, identity, ip, accountId,
                            plugin.lang().msg("command.resetpassword.success"));
                })
                .exceptionally(throwable -> {
                    plugin.log().error("重置密码命令异常", throwable);
                    player.sendMessage(plugin.lang().msg("generic.internal-error"));
                    return null;
                });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return availableWhenUnauthenticated(invocation);
    }
}
