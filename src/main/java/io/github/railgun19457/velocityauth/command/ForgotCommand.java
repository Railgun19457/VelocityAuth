package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.auth.Provider;
import io.github.railgun19457.velocityauth.auth.RecoveryService;
import io.github.railgun19457.velocityauth.verify.VerificationPurpose;

import java.util.List;
import java.util.Locale;

/**
 * {@code /forgot <账号>} / {@code /forgot <账号> <渠道>}
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
            player.sendMessage(plugin.lang().msg("command.forgot.choose-channel",
                    "channels", String.join(" | ", Provider.ids(plugin.config()))));
            return;
        }
        String providerId = args[1].toLowerCase(Locale.ROOT);
        if (!Provider.exists(plugin.config(), providerId)) {
            player.sendMessage(plugin.lang().msg("command.forgot.invalid-channel"));
            return;
        }
        var identity = identityOf(player);
        String ip = ipOf(player);

        plugin.recovery().request(accountName, providerId, player.getUniqueId(), identity.identifier(), ip,
                        VerificationPrompts.dispatchHandler(plugin, player, null, VerificationPurpose.RECOVER))
                .thenAccept(start -> handle(player, providerId, start))
                .exceptionally(throwable -> {
                    plugin.log().error("找回密码命令异常", throwable);
                    return null;
                });
    }

    private void handle(Player player, String providerId, RecoveryService.Start start) {
        if (!start.accepted()) {
            // 静默：已经返回统一提示
            return;
        }
        if (!Provider.EMAIL_ID.equals(providerId)) {
            VerificationPrompts.sendSocialInstruction(plugin, player, providerId, start.session().code(),
                    VerificationPurpose.RECOVER);
        }
        start.completion().thenAccept(result -> {
            if (result.completed()) {
                player.sendMessage(plugin.lang().msg("command.forgot.verified"));
            }
        });
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        // /forgot <账号> [渠道]
        return suggestBindingChannel(invocation, 1);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return availableWhenUnauthenticated(invocation);
    }
}
