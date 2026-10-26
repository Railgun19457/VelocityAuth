package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.auth.RecoveryService;
import io.github.railgun19457.velocityauth.storage.model.BindingType;
import io.github.railgun19457.velocityauth.verify.VerificationChannel;

/**
 * {@code /forgot <账号>} / {@code /forgot <账号> <email|qq>}
 * <p>
 * 防账号枚举：无论账号是否存在、是否有所选绑定，都返回同一提示（DESIGN 6.3 / 12.1）。
 */
public final class ForgotCommand extends PlayerCommandSupport {

    public ForgotCommand(VelocityAuthPlugin plugin, AuthSessionRegistry registry) {
        super(plugin, registry);
    }

    @Override
    public void execute(Invocation invocation) {
        Player player = requirePlayer(invocation);
        if (player == null || !requireUnauthenticated(player)) {
            return;
        }
        String[] args = invocation.arguments();
        if (args.length < 1) {
            player.sendMessage(plugin.lang().msg("command.forgot.usage"));
            return;
        }
        String accountName = args[0];
        if (args.length < 2) {
            player.sendMessage(plugin.lang().msg("command.forgot.request-received"));
            player.sendMessage(plugin.lang().msg("command.forgot.choose-channel",
                    "channels", "email | qq"));
            return;
        }
        BindingType type = BindingType.fromName(args[1]);
        if (type == null) {
            player.sendMessage(plugin.lang().msg("command.forgot.invalid-channel"));
            return;
        }
        String connectionNameLower = lower(player.getUsername());
        String ip = ipOf(player);

        player.sendMessage(plugin.lang().msg("command.forgot.request-received"));

        plugin.recovery().request(accountName, type, player.getUniqueId(), connectionNameLower, ip,
                        VerificationPrompts.dispatchHandler(plugin, player, null))
                .thenAccept(start -> handle(player, start))
                .exceptionally(throwable -> {
                    plugin.log().error("找回密码命令异常", throwable);
                    return null;
                });
    }

    private void handle(Player player, RecoveryService.Start start) {
        if (!start.accepted()) {
            // 静默：已经返回统一提示
            return;
        }
        if (start.session().channel() == VerificationChannel.QQ) {
            VerificationPrompts.sendQqInstruction(plugin, player, start.session().code());
        }
        start.completion().thenAccept(result -> {
            if (result.completed()) {
                player.sendMessage(plugin.lang().msg("command.forgot.verified"));
            }
        });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof Player;
    }
}
