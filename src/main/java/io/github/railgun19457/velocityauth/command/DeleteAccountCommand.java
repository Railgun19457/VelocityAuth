package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.Binding;
import io.github.railgun19457.velocityauth.storage.model.BindingType;
import io.github.railgun19457.velocityauth.verify.VerificationChannel;
import io.github.railgun19457.velocityauth.verify.VerificationPurpose;
import io.github.railgun19457.velocityauth.verify.VerificationRequest;
import io.github.railgun19457.velocityauth.verify.VerificationService;

import java.util.Map;
import java.util.Optional;

/**
 * {@code /deleteaccount} —— 注销自己的账号。
 * <p>
 * 必须**已登录**，且需要**二次验证**：向已绑定的邮箱 / QQ 发一次验证码，验证通过才真正删除
 * （级联删除身份与绑定），并断开当前连接。没有绑定渠道时无法完成二次验证，会要求先绑定。
 */
public final class DeleteAccountCommand extends PlayerCommandSupport {

    public DeleteAccountCommand(VelocityAuthPlugin plugin, AuthSessionRegistry registry) {
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
        if (account == null) {
            player.sendMessage(plugin.lang().msg("generic.not-authenticated"));
            return;
        }

        // 二次验证渠道：优先邮箱，其次 QQ
        Optional<Binding> email = plugin.bindings().binding(account.id(), BindingType.EMAIL);
        Optional<Binding> qq = plugin.bindings().binding(account.id(), BindingType.QQ);
        VerificationChannel channel;
        String target;
        if (email.isPresent()) {
            channel = VerificationChannel.EMAIL;
            target = email.get().identifier();
        } else if (qq.isPresent()) {
            channel = VerificationChannel.QQ;
            target = null;
        } else {
            player.sendMessage(plugin.lang().msg("command.deleteaccount.no-binding"));
            return;
        }

        long accountId = account.id();
        String accountName = account.name();
        String ip = ipOf(player);
        String connectionName = identityOf(player).identifier();

        player.sendMessage(plugin.lang().msg("command.deleteaccount.request", "account", accountName));

        VerificationService.Start start = plugin.verifications().start(VerificationRequest
                .builder(VerificationPurpose.DELETE_ACCOUNT, channel, player.getUniqueId())
                .accountName(account.nameLower())
                .connectionName(connectionName)
                .ip(ip)
                .target(target)
                .data(Map.of("account_id", Long.toString(accountId)))
                .onDispatch(VerificationPrompts.dispatchHandler(plugin, player, target,
                        VerificationPurpose.DELETE_ACCOUNT))
                .build());
        if (!start.accepted()) {
            return;
        }
        if (channel == VerificationChannel.QQ) {
            VerificationPrompts.sendQqInstruction(plugin, player, start.session().code(),
                    VerificationPurpose.DELETE_ACCOUNT);
        }

        start.completion().thenAccept(result -> {
            switch (result.status()) {
                case COMPLETED -> plugin.authService()
                        .deleteAccount(accountId, ip, "connection=" + connectionName + " by=self")
                        .thenAccept(deleted -> {
                            if (!deleted) {
                                player.sendMessage(plugin.lang().msg("generic.internal-error"));
                                return;
                            }
                            player.disconnect(plugin.lang().msg("command.deleteaccount.deleted",
                                    "account", accountName));
                        });
                case CANCELLED -> player.sendMessage(plugin.lang().msg("verification.cancelled"));
                case EXPIRED -> player.sendMessage(plugin.lang().msg("verification.expired"));
                case TOO_MANY_ATTEMPTS -> player.sendMessage(plugin.lang().msg("verification.too-many-attempts"));
                default -> player.sendMessage(plugin.lang().msg("generic.internal-error"));
            }
        });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return availableWhenAuthenticated(invocation);
    }
}
