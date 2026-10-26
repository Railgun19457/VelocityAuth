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
 * {@code /unbind <email|qq>} —— 需先通过一次验证（DESIGN 6.4）。
 */
public final class UnbindCommand extends PlayerCommandSupport {

    public UnbindCommand(VelocityAuthPlugin plugin, AuthSessionRegistry registry) {
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
        String[] args = invocation.arguments();
        BindingType type = args.length >= 1 ? BindingType.fromName(args[0]) : null;
        if (type == null) {
            player.sendMessage(plugin.lang().msg("command.unbind.usage"));
            return;
        }
        Optional<Binding> binding = plugin.bindings().binding(account.id(), type);
        if (binding.isEmpty()) {
            player.sendMessage(plugin.lang().msg("command.unbind.not-bound"));
            return;
        }
        String target = binding.get().identifier();
        String ip = ipOf(player);
        VerificationChannel channel = type == BindingType.EMAIL
                ? VerificationChannel.EMAIL : VerificationChannel.QQ;
        VerificationService.Start start = plugin.verifications().start(VerificationRequest
                .builder(VerificationPurpose.UNBIND, channel, player.getUniqueId())
                .accountName(account.nameLower())
                .connectionName(identityOf(player).identifier())
                .ip(ip)
                .target(channel == VerificationChannel.EMAIL ? target : null)
                .data(Map.of("account_id", Long.toString(account.id()), "type", type.dbValue()))
                .onDispatch(VerificationPrompts.dispatchHandler(plugin, player,
                        channel == VerificationChannel.EMAIL ? target : null))
                .build());
        if (!start.accepted()) {
            return;
        }
        if (channel == VerificationChannel.QQ) {
            VerificationPrompts.sendQqInstruction(plugin, player, start.session().code());
        }
        start.completion().thenAccept(result -> {
            switch (result.status()) {
                case COMPLETED -> plugin.bindings().unbind(account, type, ip)
                        .thenAccept(outcome -> player.sendMessage(
                                outcome.ok() ? plugin.lang().msg("command.unbind.success")
                                        : plugin.lang().msg("command.unbind.not-bound")));
                case CANCELLED -> player.sendMessage(plugin.lang().msg("verification.cancelled"));
                case EXPIRED -> player.sendMessage(plugin.lang().msg("verification.expired"));
                case TOO_MANY_ATTEMPTS -> player.sendMessage(plugin.lang().msg("verification.too-many-attempts"));
                default -> player.sendMessage(plugin.lang().msg("generic.internal-error"));
            }
        });
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof Player;
    }
}
