package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.auth.Provider;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.Binding;
import io.github.railgun19457.velocityauth.verify.VerificationPurpose;
import io.github.railgun19457.velocityauth.verify.VerificationRequest;
import io.github.railgun19457.velocityauth.verify.VerificationService;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /unbind <渠道>} —— 需先通过一次验证（DESIGN 6.4）。
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
        Optional<Provider> provider = args.length >= 1
                ? Provider.find(plugin.config(), args[0].toLowerCase(Locale.ROOT)) : Optional.empty();
        if (provider.isEmpty()) {
            player.sendMessage(plugin.lang().msg("command.unbind.usage"));
            return;
        }
        String providerId = provider.get().id();
        Optional<Binding> binding = plugin.bindings().binding(account.id(), providerId);
        if (binding.isEmpty()) {
            player.sendMessage(plugin.lang().msg("command.unbind.not-bound"));
            return;
        }
        // 不允许把账号解绑成「不满足注册后必须绑定要求」的状态
        if (plugin.config().registration().requireBinding().contains(providerId)
                && !plugin.authService().bindingSatisfiedWithout(account, providerId)) {
            player.sendMessage(plugin.lang().msg("command.unbind.required",
                    "provider", provider.get().displayName()));
            return;
        }
        boolean email = provider.get().email();
        String target = binding.get().identifier();
        String ip = ipOf(player);
        VerificationService.Start start = plugin.verifications().start(VerificationRequest
                .builder(VerificationPurpose.UNBIND, providerId, player.getUniqueId())
                .accountName(account.nameLower())
                .connectionName(identityOf(player).identifier())
                .ip(ip)
                .target(email ? target : null)
                .data(Map.of("account_id", Long.toString(account.id()), "provider", providerId))
                .onDispatch(VerificationPrompts.dispatchHandler(plugin, player, email ? target : null,
                        VerificationPurpose.UNBIND))
                .build());
        if (!start.accepted()) {
            return;
        }
        if (!email) {
            VerificationPrompts.sendSocialInstruction(plugin, player, providerId, start.session().code(),
                    VerificationPurpose.UNBIND);
        }
        start.completion().thenAccept(result -> {
            switch (result.status()) {
                case COMPLETED -> plugin.bindings().unbind(account, providerId, ip)
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
    public java.util.List<String> suggest(Invocation invocation) {
        // /unbind <渠道>
        return suggestBindingChannel(invocation, 0);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return availableWhenAuthenticated(invocation);
    }
}
