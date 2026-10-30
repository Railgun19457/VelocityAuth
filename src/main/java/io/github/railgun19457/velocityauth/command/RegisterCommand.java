package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthOutcomes;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.auth.IdentityResolver;
import io.github.railgun19457.velocityauth.auth.PasswordHasher;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.model.Account;

/**
 * {@code /register <账号> <密码> <确认密码>}
 * <p>
 * 三个参数都必填。账号先落库：
 * <ul>
 *   <li>未配置 {@code registration.require-binding}：直接 {@code active}，注册即自动登录；</li>
 *   <li>配置了要求：先落为 {@code pending_binding}，提示玩家任选一个渠道
 *       {@code /bind email <邮箱>} 或 {@code /bind qq} 完成绑定；绑定成功才激活并自动登录。
 *       超时未绑定的账号会被自动清除（{@code registration.unbound-ttl-minutes}）。</li>
 * </ul>
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

        String[] args = invocation.arguments();
        if (args.length < 3) {
            player.sendMessage(plugin.lang().msg("command.register.usage"));
            return;
        }
        String name = args[0];
        String password = args[1];
        String confirm = args[2];

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
        if (pwIssue == AuthOutcomes.RegisterStatus.PASSWORD_TOO_LONG_BYTES) {
            player.sendMessage(plugin.lang().msg("command.register.password-too-long-bytes",
                    "bytes", PasswordHasher.MAX_PASSWORD_BYTES));
            return;
        }
        if (confirm == null || !password.equals(confirm)) {
            player.sendMessage(plugin.lang().msg("command.register.password-mismatch"));
            return;
        }

        IdentityResolver.Resolved identity = identityOf(player);
        String ip = ipOf(player);

        plugin.authService().register(name, password, confirm, identity, ip)
                .thenAccept(result -> handle(player, identity, ip, result))
                .exceptionally(throwable -> {
                    plugin.log().error("注册命令异常", throwable);
                    player.sendMessage(plugin.lang().msg("generic.internal-error"));
                    return null;
                });
    }

    private void handle(Player player, IdentityResolver.Resolved identity, String ip,
                        AuthOutcomes.Register result) {
        if (result.status() != AuthOutcomes.RegisterStatus.SUCCESS) {
            player.sendMessage(messageFor(result.status()));
            return;
        }
        Account account = result.account();

        // 无需绑定：注册即登录
        if (!plugin.config().registration().bindingRequired()) {
            finishAuthAndReconnect(player, identity, ip, account.id(),
                    plugin.lang().msg("command.register.success"));
            return;
        }

        // 需要绑定：先通知注册成功，再提示绑定渠道；放行本连接的 /bind
        AuthSession session = sessionOf(player);
        if (session != null) {
            session.allowPreLoginBinding(account.id());
        }
        player.sendMessage(plugin.lang().msg("command.register.success-pending", "account", account.name()));
        sendBindingRequirement(player);
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
            case PASSWORD_TOO_LONG_BYTES -> plugin.lang().msg("command.register.password-too-long-bytes",
                    "bytes", PasswordHasher.MAX_PASSWORD_BYTES);
            case PASSWORD_MISMATCH -> plugin.lang().msg("command.register.password-mismatch");
            case RATE_LIMITED -> plugin.lang().msg("command.register.rate-limited");
            case SUCCESS, ERROR -> plugin.lang().msg("generic.internal-error");
            // 以下状态不再由注册路径产生，保留兜底以免漏配
            case EMAIL_INVALID -> plugin.lang().msg("command.bind.email-invalid");
            case DOMAIN_NOT_ALLOWED -> plugin.lang().msg("command.bind.domain-not-allowed");
            case EMAIL_LIMIT -> plugin.lang().msg("command.bind.email-limit");
            case QQ_LIMIT -> plugin.lang().msg("command.bind.qq-limit");
            case VERIFICATION_CANCELLED -> plugin.lang().msg("verification.cancelled");
            case VERIFICATION_EXPIRED -> plugin.lang().msg("verification.expired");
            case VERIFICATION_FAILED -> plugin.lang().msg("generic.internal-error");
        };
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return availableWhenUnauthenticated(invocation);
    }
}
