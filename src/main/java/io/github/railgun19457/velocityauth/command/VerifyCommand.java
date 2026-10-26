package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.verify.VerificationService;

/**
 * {@code /verify <验证码>} 或 {@code /verify cancel}
 */
public final class VerifyCommand extends PlayerCommandSupport {

    public VerifyCommand(VelocityAuthPlugin plugin, AuthSessionRegistry registry) {
        super(plugin, registry);
    }

    @Override
    public void execute(Invocation invocation) {
        Player player = requirePlayer(invocation);
        if (player == null) {
            return;
        }
        String[] args = invocation.arguments();
        if (args.length < 1) {
            player.sendMessage(plugin.lang().msg("command.verify.usage"));
            return;
        }
        VerificationService verifications = plugin.verifications();

        if ("cancel".equalsIgnoreCase(args[0])) {
            verifications.cancel(player.getUniqueId());
            player.sendMessage(plugin.lang().msg("verification.cancelled-by-player"));
            return;
        }

        VerificationService.SubmitOutcome outcome = verifications.submitCode(player.getUniqueId(), args[0]);
        switch (outcome) {
            case OK -> player.sendMessage(plugin.lang().msg("command.verify.success"));
            case NO_SESSION -> player.sendMessage(plugin.lang().msg("verification.no-session"));
            case WRONG_CODE -> {
                int remaining = verifications.current(player.getUniqueId())
                        .map(session -> session.remainingAttempts())
                        .orElse(0);
                player.sendMessage(plugin.lang().msg("verification.wrong-code", "remaining", remaining));
            }
            case EXPIRED -> player.sendMessage(plugin.lang().msg("verification.expired"));
            case TOO_MANY_ATTEMPTS -> player.sendMessage(plugin.lang().msg("verification.too-many-attempts"));
        }
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }
}
