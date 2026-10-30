package io.github.railgun19457.velocityauth.command;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.AuthOutcomes;
import io.github.railgun19457.velocityauth.auth.AuthSession;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.auth.EmailRules;
import io.github.railgun19457.velocityauth.auth.IdentityResolver;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.BindingType;
import io.github.railgun19457.velocityauth.verify.VerificationChannel;
import io.github.railgun19457.velocityauth.verify.VerificationPurpose;
import io.github.railgun19457.velocityauth.verify.VerificationRequest;
import io.github.railgun19457.velocityauth.verify.VerificationResult;
import io.github.railgun19457.velocityauth.verify.VerificationService;

import java.util.Map;

/**
 * {@code /bind email <邮箱>} / {@code /bind qq}
 * <p>
 * 先通过一次验证会话证明身份/持有渠道，再写入绑定（DESIGN 6.1 / 6.2）。
 */
public final class BindCommand extends PlayerCommandSupport {

    public BindCommand(VelocityAuthPlugin plugin, AuthSessionRegistry registry) {
        super(plugin, registry);
    }

    @Override
    public void execute(Invocation invocation) {
        Player player = requirePlayer(invocation);
        if (player == null) {
            return;
        }
        AuthSession session = sessionOf(player);
        Account account = resolveAccount(player, session);
        if (account == null) {
            // 既未登录，也不属于「注册后待绑定 / 密码已通过」的放行场景
            player.sendMessage(plugin.lang().msg("command.bind.not-authenticated"));
            return;
        }
        String[] args = invocation.arguments();
        if (args.length < 1) {
            player.sendMessage(plugin.lang().msg("command.bind.usage"));
            return;
        }
        BindingType type = BindingType.fromName(args[0]);
        if (type == null) {
            player.sendMessage(plugin.lang().msg("command.bind.usage"));
            return;
        }
        if (type == BindingType.EMAIL) {
            bindEmail(player, account, args);
        } else {
            bindQq(player, account);
        }
    }

    /**
     * 确定本次 {@code /bind} 作用在哪个账号上。
     * <p>
     * 已认证玩家直接用自己的账号；未认证玩家仅当会话放行了该账号（刚注册 / 密码已通过但被绑定要求拦下）
     * 时才允许，否则返回 null。
     */
    private Account resolveAccount(Player player, AuthSession session) {
        if (session == null) {
            return null;
        }
        if (session.authenticated()) {
            return session.account();
        }
        IdentityResolver.Resolved identity = identityOf(player);
        Account account = plugin.authService().cache()
                .byIdentity(identity.kind(), identity.identifier())
                .orElse(null);
        if (account == null || account.locked() || !session.preLoginBindingAllowed(account.id())) {
            return null;
        }
        return account;
    }

    private void bindEmail(Player player, Account account, String[] args) {
        if (args.length < 2) {
            player.sendMessage(plugin.lang().msg("command.bind.email-usage"));
            player.sendMessage(VerificationPrompts.emailDomainHint(plugin));
            return;
        }
        if (!plugin.config().binding().email().enabled()) {
            player.sendMessage(plugin.lang().msg("generic.feature-disabled"));
            return;
        }
        String email = EmailRules.normalize(args[1]);
        EmailRules.DomainVerdict verdict = EmailRules.check(email,
                plugin.config().binding().email().domain());
        if (verdict == EmailRules.DomainVerdict.INVALID_EMAIL) {
            player.sendMessage(plugin.lang().msg("command.bind.email-invalid"));
            player.sendMessage(VerificationPrompts.emailDomainHint(plugin));
            return;
        }
        if (verdict == EmailRules.DomainVerdict.DOMAIN_NOT_ALLOWED) {
            player.sendMessage(plugin.lang().msg("command.bind.domain-not-allowed"));
            player.sendMessage(VerificationPrompts.emailDomainHint(plugin));
            return;
        }
        if (plugin.bindings().hasBinding(account.id(), BindingType.EMAIL)) {
            player.sendMessage(plugin.lang().msg("command.bind.already-bound"));
            return;
        }
        // 玩家「选择绑定邮箱」后，先告知支持的邮箱类型，再发码
        player.sendMessage(VerificationPrompts.emailDomainHint(plugin));
        String ip = ipOf(player);
        VerificationService.Start start = plugin.verifications().start(VerificationRequest
                .builder(VerificationPurpose.BIND_EMAIL, VerificationChannel.EMAIL, player.getUniqueId())
                .accountName(account.nameLower())
                .connectionName(identityOf(player).identifier())
                .ip(ip)
                .target(email)
                .data(Map.of("account_id", Long.toString(account.id())))
                .onDispatch(VerificationPrompts.dispatchHandler(plugin, player, email,
                        VerificationPurpose.BIND_EMAIL))
                .build());
        if (!start.accepted()) {
            return;
        }
        start.completion().thenAccept(result -> {
            if (result.status() != VerificationResult.Status.COMPLETED) {
                player.sendMessage(verificationFailureMessage(result.status()));
                return;
            }
            plugin.bindings().bindEmail(account, email, ip)
                    .thenAccept(outcome -> afterBind(player, account, outcome));
        });
    }

    private void bindQq(Player player, Account account) {
        if (!plugin.config().binding().qq().enabled()) {
            player.sendMessage(plugin.lang().msg("generic.feature-disabled"));
            return;
        }
        if (plugin.bindings().hasBinding(account.id(), BindingType.QQ)) {
            player.sendMessage(plugin.lang().msg("command.bind.already-bound"));
            return;
        }
        String ip = ipOf(player);
        VerificationService.Start start = plugin.verifications().start(VerificationRequest
                .builder(VerificationPurpose.BIND_QQ, VerificationChannel.QQ, player.getUniqueId())
                .accountName(account.nameLower())
                .connectionName(identityOf(player).identifier())
                .ip(ip)
                .data(Map.of("account_id", Long.toString(account.id())))
                .onDispatch(VerificationPrompts.dispatchHandler(plugin, player, null,
                        VerificationPurpose.BIND_QQ))
                .build());
        if (!start.accepted()) {
            return;
        }
        VerificationPrompts.sendQqInstruction(plugin, player, start.session().code(),
                VerificationPurpose.BIND_QQ);
        start.completion().thenAccept(result -> {
            if (result.status() != VerificationResult.Status.COMPLETED) {
                player.sendMessage(verificationFailureMessage(result.status()));
                return;
            }
            String qq = result.session().resolvedValue();
            plugin.bindings().bindQq(account, qq, ip)
                    .thenAccept(outcome -> afterBind(player, account, outcome));
        });
    }

    /**
     * 绑定收尾：若这次绑定让账号满足了「注册后必须绑定」的要求，则激活账号并自动登录；
     * 否则只提示绑定结果，并在仍缺渠道时继续提示。
     */
    private void afterBind(Player player, Account account, AuthOutcomes.Bind outcome) {
        if (!outcome.ok()) {
            player.sendMessage(bindMessage(outcome));
            return;
        }
        plugin.authService().activateIfBindingSatisfied(account.id()).thenAccept(activated -> {
            if (Boolean.TRUE.equals(activated)) {
                finishAuthAndReconnect(player, identityOf(player), ipOf(player), account.id(),
                        plugin.lang().msg("command.bind.activated"));
                return;
            }
            player.sendMessage(bindMessage(outcome));
            if (!plugin.config().registration().requireBinding().isEmpty()
                    && !plugin.authService().bindingSatisfied(account)) {
                sendBindingRequirement(player);
            }
        });
    }

    private net.kyori.adventure.text.Component bindMessage(AuthOutcomes.Bind outcome) {
        return switch (outcome.status()) {
            case SUCCESS -> plugin.lang().msg("command.bind.success");
            case ALREADY_BOUND -> plugin.lang().msg("command.bind.already-bound");
            case EMAIL_INVALID -> plugin.lang().msg("command.bind.email-invalid");
            case DOMAIN_NOT_ALLOWED -> plugin.lang().msg("command.bind.domain-not-allowed");
            case EMAIL_LIMIT -> plugin.lang().msg("command.bind.email-limit");
            case QQ_LIMIT -> plugin.lang().msg("command.bind.qq-limit");
            case DISABLED -> plugin.lang().msg("generic.feature-disabled");
            case VERIFICATION_CANCELLED -> plugin.lang().msg("verification.cancelled");
            case VERIFICATION_EXPIRED -> plugin.lang().msg("verification.expired");
            case NOT_BOUND, VERIFICATION_FAILED, ERROR -> plugin.lang().msg("generic.internal-error");
        };
    }

    private net.kyori.adventure.text.Component verificationFailureMessage(VerificationResult.Status status) {
        return switch (status) {
            case CANCELLED -> plugin.lang().msg("verification.cancelled");
            case EXPIRED -> plugin.lang().msg("verification.expired");
            case TOO_MANY_ATTEMPTS -> plugin.lang().msg("verification.too-many-attempts");
            case REPLACED -> plugin.lang().msg("verification.cancelled");
            default -> plugin.lang().msg("generic.internal-error");
        };
    }

    @Override
    public java.util.List<String> suggest(Invocation invocation) {
        // /bind <email|qq> [值]
        return suggestBindingChannel(invocation, 0);
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source() instanceof Player;
    }
}
