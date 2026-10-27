package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AuditEntry;
import io.github.railgun19457.velocityauth.storage.model.BindingType;
import io.github.railgun19457.velocityauth.storage.model.IdentityKind;
import io.github.railgun19457.velocityauth.util.AsyncExecutor;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import io.github.railgun19457.velocityauth.verify.VerificationChannel;
import io.github.railgun19457.velocityauth.verify.VerificationPurpose;
import io.github.railgun19457.velocityauth.verify.VerificationRequest;
import io.github.railgun19457.velocityauth.verify.VerificationResult;
import io.github.railgun19457.velocityauth.verify.VerificationService;
import io.github.railgun19457.velocityauth.verify.VerificationSession;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 带验证门槛的注册流程（{@code registration.gate = email | qq}）。
 * <p>
 * 流程：校验参数 → 预计算 bcrypt 哈希 → 发起验证会话 → 验证通过后落库（建号 + 绑定）。
 * 明文密码不进入内存态：会话里保存的是**已哈希**的密码。
 * <p>
 * 参数约定：
 * <ul>
 *   <li>{@code gate=qq}：{@code /register <账号> <密码> [确认密码]}</li>
 *   <li>{@code gate=email}：{@code /register <账号> <密码> <邮箱> [确认密码]}</li>
 * </ul>
 */
public final class RegistrationFlow {

    private final Supplier<VelocityAuthConfig> config;
    private final AuthService auth;
    private final VerificationService verifications;
    private final AsyncExecutor async;
    private final PluginLogger log;

    public RegistrationFlow(Supplier<VelocityAuthConfig> config, AuthService auth,
                            VerificationService verifications, AsyncExecutor async, PluginLogger log) {
        this.config = config;
        this.auth = auth;
        this.verifications = verifications;
        this.async = async;
        this.log = log;
    }

    public record Request(UUID playerUuid, String name, String password, String email,
                          IdentityResolver.Resolved identity, String ip,
                          java.util.function.Consumer<io.github.railgun19457.velocityauth.verify.VerificationDispatch>
                                  onDispatch) {
    }

    public record Started(boolean accepted, AuthOutcomes.RegisterStatus error, VerificationChannel channel,
                          String email, VerificationSession session,
                          CompletableFuture<AuthOutcomes.Register> completion, boolean notified) {
        static Started rejected(AuthOutcomes.RegisterStatus error) {
            return new Started(false, error, null, null, null, null, false);
        }

        /** 派发回调已经给过玩家具体提示（限流 / 渠道不可用），调用方不必重复提示。 */
        static Started rejectedNotified() {
            return new Started(false, AuthOutcomes.RegisterStatus.VERIFICATION_FAILED, null, null, null, null, true);
        }
    }

    /** 发起注册（异步：包含 bcrypt 计算）。 */
    public CompletableFuture<Started> begin(Request request) {
        return async.supply(() -> {
            VelocityAuthConfig cfg = config.get();
            VelocityAuthConfig.Registration.Gate gate = cfg.registration().gate();
            if (gate == VelocityAuthConfig.Registration.Gate.NONE) {
                return Started.rejected(AuthOutcomes.RegisterStatus.ERROR);
            }
            VerificationChannel channel = gate == VelocityAuthConfig.Registration.Gate.EMAIL
                    ? VerificationChannel.EMAIL : VerificationChannel.QQ;

            if (!auth.validName(request.name())) {
                return Started.rejected(AuthOutcomes.RegisterStatus.NAME_INVALID);
            }
            AuthOutcomes.RegisterStatus pwIssue = auth.passwordLengthIssue(request.password());
            if (pwIssue != null) {
                return Started.rejected(pwIssue);
            }
            String nameLower = AuthService.normalize(request.name());
            if (auth.cache().existsByNameLower(nameLower) || auth.storage().accounts().existsByNameLower(nameLower)) {
                return Started.rejected(AuthOutcomes.RegisterStatus.NAME_TAKEN);
            }

            String email = null;
            if (channel == VerificationChannel.EMAIL) {
                email = EmailRules.normalize(request.email());
                EmailRules.DomainVerdict verdict = EmailRules.check(email, cfg.binding().email().domain());
                if (verdict == EmailRules.DomainVerdict.INVALID_EMAIL) {
                    return Started.rejected(AuthOutcomes.RegisterStatus.EMAIL_INVALID);
                }
                if (verdict == EmailRules.DomainVerdict.DOMAIN_NOT_ALLOWED) {
                    return Started.rejected(AuthOutcomes.RegisterStatus.DOMAIN_NOT_ALLOWED);
                }
                if (!cfg.binding().email().enabled()) {
                    return Started.rejected(AuthOutcomes.RegisterStatus.ERROR);
                }
                int bound = auth.storage().bindings()
                        .countDistinctAccounts(BindingType.EMAIL, email);
                if (bound >= cfg.binding().email().maxAccountsPerEmail()) {
                    return Started.rejected(AuthOutcomes.RegisterStatus.EMAIL_LIMIT);
                }
            }

            Map<String, String> data = new HashMap<>();
            data.put("password_hash", auth.hasher().hash(request.password()));
            data.put("name", request.name());
            data.put("name_lower", nameLower);
            if (email != null) {
                data.put("email", email);
            }

            VerificationRequest verificationRequest = VerificationRequest
                    .builder(VerificationPurpose.REGISTER, channel, request.playerUuid())
                    .accountName(nameLower)
                    .connectionName(request.identity().identifier())
                    .ip(request.ip())
                    .target(email)
                    .data(data)
                    .onDispatch(request.onDispatch())
                    .build();

            VerificationService.Start start = verifications.start(verificationRequest);
            if (!start.accepted()) {
                return Started.rejectedNotified();
            }
            CompletableFuture<AuthOutcomes.Register> completion =
                    start.completion().thenApply(result -> complete(result, data, request));
            return new Started(true, null, channel, email, start.session(), completion, false);
        });
    }

    private AuthOutcomes.Register complete(VerificationResult result, Map<String, String> data,
                                           Request request) {
        try {
            if (!result.completed()) {
                return AuthOutcomes.Register.failure(switch (result.status()) {
                    case CANCELLED -> AuthOutcomes.RegisterStatus.VERIFICATION_CANCELLED;
                    case EXPIRED -> AuthOutcomes.RegisterStatus.VERIFICATION_EXPIRED;
                    case FAILED -> AuthOutcomes.RegisterStatus.VERIFICATION_FAILED;
                    default -> AuthOutcomes.RegisterStatus.VERIFICATION_FAILED;
                });
            }
            VerificationSession session = result.session();
            String name = data.get("name");
            String nameLower = data.get("name_lower");
            String hash = data.get("password_hash");
            String email = data.get("email");
            long now = AuthService.nowSeconds();

            if (auth.cache().existsByNameLower(nameLower) || auth.storage().accounts().existsByNameLower(nameLower)) {
                return AuthOutcomes.Register.failure(AuthOutcomes.RegisterStatus.NAME_TAKEN);
            }

            long accountId = auth.storage().accounts().insert(name, nameLower,
                    ProfileFactory.offlineUuid(name), hash, io.github.railgun19457.velocityauth.storage.model
                            .AccountStatus.ACTIVE, null, now);
            auth.storage().identities().upsert(accountId, request.identity().kind(), request.identity().identifier(), now);

            if (email != null) {
                auth.storage().bindings().insert(accountId, BindingType.EMAIL, email, now, now);
            }
            if (session.channel() == VerificationChannel.QQ && session.resolvedValue() != null) {
                String qq = session.resolvedValue();
                int bound = auth.storage().bindings().countDistinctAccounts(BindingType.QQ, qq);
                if (bound >= config.get().binding().qq().maxAccountsPerQq()) {
                    auth.storage().accounts().delete(accountId);
                    return AuthOutcomes.Register.failure(AuthOutcomes.RegisterStatus.QQ_LIMIT);
                }
                auth.storage().bindings().insert(accountId, BindingType.QQ, qq, now, now);
            }

            auth.storage().audit().log(now, accountId, AuditEntry.REGISTER, request.ip(),
                    "connection=" + request.identity().identifier() + " kind=" + request.identity().kind().dbValue() + " gate=" + session.channel().name()
                            .toLowerCase(Locale.ROOT));

            Account account = auth.storage().accounts().findById(accountId)
                    .orElseThrow(() -> new IllegalStateException("注册后无法读回账号"));
            auth.cache().put(account);
            auth.cache().indexIdentity(request.identity().kind(), request.identity().identifier(), accountId);
            log.info("新账号注册（{} 验证）：{}（连接名 {}，IP {}）",
                    session.channel().name().toLowerCase(Locale.ROOT), account.name(),
                    request.identity().identifier(), request.ip());
            return AuthOutcomes.Register.success(account);
        } catch (RuntimeException e) {
            log.error("验证后建号失败：" + e.getMessage(), e);
            return AuthOutcomes.Register.failure(AuthOutcomes.RegisterStatus.ERROR);
        }
    }
}
