package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthOutcomes;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
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
        Optional<Long> grant = plugin.recovery().consumeGrant(player.getUniqueId());
        if (grant.isEmpty()) {
            player.sendMessage(plugin.lang().msg("command.resetpassword.no-grant"));
            return;
        }
        long accountId = grant.get();
        String newPassword = args[0];
        if (!newPassword.equals(args[1])) {
            // 授权已消费，这里直接给出错误；玩家可重新 /forgot
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

        var identity = identityOf(player);
        String ip = ipOf(player);
        plugin.authService().forcePassword(accountId, newPassword, ip, AuditEntry.RECOVER)
                .thenAccept(result -> {
                    if (result.status() != AuthOutcomes.ChangePasswordStatus.SUCCESS) {
                        player.sendMessage(plugin.lang().msg("generic.internal-error"));
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
        return true;
    }
}
