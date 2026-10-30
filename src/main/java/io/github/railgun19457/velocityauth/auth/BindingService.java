package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AuditEntry;
import io.github.railgun19457.velocityauth.storage.model.Binding;
import io.github.railgun19457.velocityauth.storage.model.BindingType;
import io.github.railgun19457.velocityauth.util.AsyncExecutor;
import io.github.railgun19457.velocityauth.util.PluginLogger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 绑定 / 解绑编排（DESIGN 6.1 / 6.2 / 6.4）。
 * <p>
 * 只负责落库与基数、域名校验；"证明身份"由 {@code VerificationService} 完成。
 */
public final class BindingService {

    private final Supplier<VelocityAuthConfig> config;
    private final StorageService storage;
    private final BindingIndex bindingIndex;
    private final AsyncExecutor async;
    private final PluginLogger log;

    public BindingService(Supplier<VelocityAuthConfig> config, StorageService storage, BindingIndex bindingIndex,
                          AsyncExecutor async, PluginLogger log) {
        this.config = config;
        this.storage = storage;
        this.bindingIndex = bindingIndex;
        this.async = async;
        this.log = log;
    }

    public List<Binding> bindings(long accountId) {
        return storage.bindings().findByAccountId(accountId);
    }

    public Optional<Binding> binding(long accountId, BindingType type) {
        return storage.bindings().find(accountId, type);
    }

    public boolean hasBinding(long accountId, BindingType type) {
        return storage.bindings().find(accountId, type).isPresent();
    }

    // ------------------------------------------------------------------ 绑定

    public CompletableFuture<AuthOutcomes.Bind> bindEmail(Account account, String rawEmail, String ip) {
        return async.supply(() -> {
            try {
                VelocityAuthConfig cfg = config.get();
                if (!cfg.binding().email().enabled()) {
                    return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.DISABLED);
                }
                String email = EmailRules.normalize(rawEmail);
                EmailRules.DomainVerdict verdict = EmailRules.check(email, cfg.binding().email().domain());
                if (verdict == EmailRules.DomainVerdict.INVALID_EMAIL) {
                    return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.EMAIL_INVALID);
                }
                if (verdict == EmailRules.DomainVerdict.DOMAIN_NOT_ALLOWED) {
                    return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.DOMAIN_NOT_ALLOWED);
                }
                if (storage.bindings().find(account.id(), BindingType.EMAIL).isPresent()) {
                    return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.ALREADY_BOUND);
                }
                int bound = storage.bindings().countDistinctAccounts(BindingType.EMAIL, email);
                if (bound >= cfg.binding().email().maxAccountsPerEmail()) {
                    return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.EMAIL_LIMIT);
                }
                long now = AuthService.nowSeconds();
                storage.bindings().insert(account.id(), BindingType.EMAIL, email, now, now);
                bindingIndex.put(account.id(), BindingType.EMAIL);
                storage.audit().log(now, account.id(), AuditEntry.BIND, ip, "type=email");
                log.info("账号 {} 绑定邮箱（域名 {}）", account.name(), EmailRules.domainOf(email));
                return AuthOutcomes.Bind.success(email);
            } catch (RuntimeException e) {
                log.error("绑定邮箱失败：" + e.getMessage(), e);
                return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.ERROR);
            }
        });
    }

    public CompletableFuture<AuthOutcomes.Bind> bindQq(Account account, String qq, String ip) {
        return async.supply(() -> {
            try {
                VelocityAuthConfig cfg = config.get();
                if (!cfg.binding().qq().enabled()) {
                    return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.DISABLED);
                }
                if (qq == null || qq.isBlank()) {
                    return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.VERIFICATION_FAILED);
                }
                if (storage.bindings().find(account.id(), BindingType.QQ).isPresent()) {
                    return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.ALREADY_BOUND);
                }
                int bound = storage.bindings().countDistinctAccounts(BindingType.QQ, qq);
                if (bound >= cfg.binding().qq().maxAccountsPerQq()) {
                    return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.QQ_LIMIT);
                }
                long now = AuthService.nowSeconds();
                storage.bindings().insert(account.id(), BindingType.QQ, qq, now, now);
                bindingIndex.put(account.id(), BindingType.QQ);
                storage.audit().log(now, account.id(), AuditEntry.BIND, ip, "type=qq");
                log.info("账号 {} 绑定 QQ {}", account.name(), qq);
                return AuthOutcomes.Bind.success(qq);
            } catch (RuntimeException e) {
                log.error("绑定 QQ 失败：" + e.getMessage(), e);
                return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.ERROR);
            }
        });
    }

    // ------------------------------------------------------------------ 解绑

    public CompletableFuture<AuthOutcomes.Bind> unbind(Account account, BindingType type, String ip) {
        return async.supply(() -> {
            try {
                if (storage.bindings().find(account.id(), type).isEmpty()) {
                    return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.NOT_BOUND);
                }
                long now = AuthService.nowSeconds();
                storage.bindings().delete(account.id(), type);
                bindingIndex.remove(account.id(), type);
                storage.audit().log(now, account.id(), AuditEntry.UNBIND, ip, "type=" + type.dbValue());
                log.info("账号 {} 解绑 {}", account.name(), type.dbValue());
                return AuthOutcomes.Bind.success(null);
            } catch (RuntimeException e) {
                log.error("解绑失败：" + e.getMessage(), e);
                return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.ERROR);
            }
        });
    }
}
