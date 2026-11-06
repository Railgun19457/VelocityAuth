package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.AuditContext;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AuditEntry;
import io.github.railgun19457.velocityauth.storage.model.Binding;
import io.github.railgun19457.velocityauth.storage.model.BindingHistory;
import io.github.railgun19457.velocityauth.util.AsyncExecutor;
import io.github.railgun19457.velocityauth.util.PluginLogger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 绑定 / 解绑编排（DESIGN 6.1 / 6.2 / 6.4）。
 * <p>
 * 渠道无关：邮箱（{@code email}）走域名校验，社交渠道由 bot 证明持有后写入。
 * 只负责落库与基数 / 冷却校验；"证明身份"由 {@code VerificationService} 完成。
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

    public Optional<Binding> binding(long accountId, String providerId) {
        return storage.bindings().find(accountId, providerId);
    }

    public boolean hasBinding(long accountId, String providerId) {
        return storage.bindings().find(accountId, providerId).isPresent();
    }

    // ------------------------------------------------------------------ 绑定

    /**
     * 写入一条绑定。
     *
     * @param providerId    渠道 id
     * @param rawIdentifier 渠道标识（email 为邮箱地址；social 为 bot 回报的平台账号）
     */
    public CompletableFuture<AuthOutcomes.Bind> bind(Account account, String providerId, String rawIdentifier,
                                                     String ip) {
        return async.supply(() -> {
            try {
                VelocityAuthConfig cfg = config.get();
                Optional<Provider> resolved = Provider.find(cfg, providerId);
                if (resolved.isEmpty() || !resolved.get().enabled()) {
                    return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.DISABLED);
                }
                Provider provider = resolved.get();

                String identifier;
                if (provider.email()) {
                    String email = EmailRules.normalize(rawIdentifier);
                    EmailRules.DomainVerdict verdict = EmailRules.check(email, cfg.binding().email().domain());
                    if (verdict == EmailRules.DomainVerdict.INVALID_EMAIL) {
                        return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.EMAIL_INVALID);
                    }
                    if (verdict == EmailRules.DomainVerdict.DOMAIN_NOT_ALLOWED) {
                        return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.DOMAIN_NOT_ALLOWED);
                    }
                    identifier = email;
                } else {
                    if (rawIdentifier == null || rawIdentifier.isBlank()) {
                        return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.VERIFICATION_FAILED);
                    }
                    identifier = rawIdentifier.trim();
                }

                if (storage.bindings().find(account.id(), provider.id()).isPresent()) {
                    return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.ALREADY_BOUND);
                }
                if (cooldownRemainingSeconds(provider.id(), identifier) > 0) {
                    return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.COOLDOWN);
                }
                int bound = storage.bindings().countDistinctAccounts(provider.id(), identifier);
                if (bound >= provider.maxAccounts()) {
                    return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.LIMIT);
                }

                long now = AuthService.nowSeconds();
                storage.bindings().insert(account.id(), provider.id(), identifier, now, now);
                bindingIndex.put(account.id(), provider.id());
                storage.bindingHistory().insert(account.id(), provider.id(), identifier, BindingHistory.BIND, now, ip);
                storage.audit().log(now, account.id(), AuditEntry.BIND, ip, "provider=" + provider.id());
                log.info("账号 {} 绑定渠道 {}（{}）", account.name(), provider.id(), provider.displayName());
                return AuthOutcomes.Bind.success(identifier);
            } catch (RuntimeException e) {
                log.error("绑定失败：" + e.getMessage(), e);
                return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.ERROR);
            }
        });
    }

    // ------------------------------------------------------------------ 解绑

    public CompletableFuture<AuthOutcomes.Bind> unbind(Account account, String providerId, String ip) {
        return unbind(account, providerId, ip, AuditEntry.UNBIND, AuditContext.plugin());
    }

    /**
     * 解绑指定渠道。
     *
     * @param action 审计动作标识（{@code unbind} / {@code admin_unbind}）
     * @param audit  审计上下文
     */
    public CompletableFuture<AuthOutcomes.Bind> unbind(Account account, String providerId, String ip,
                                                      String action, AuditContext audit) {
        return async.supply(() -> {
            try {
                Optional<Binding> existing = storage.bindings().find(account.id(), providerId);
                if (existing.isEmpty()) {
                    return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.NOT_BOUND);
                }
                long now = AuthService.nowSeconds();
                storage.bindings().delete(account.id(), providerId);
                bindingIndex.remove(account.id(), providerId);
                storage.bindingHistory().insert(account.id(), providerId, existing.get().identifier(),
                        BindingHistory.UNBIND, now, ip);
                storage.audit().log(now, account.id(), action, ip, "provider=" + providerId,
                        audit.actor(), audit.source(), audit.clientId());
                log.info("账号 {} 解绑渠道 {}", account.name(), providerId);
                return AuthOutcomes.Bind.success(null);
            } catch (RuntimeException e) {
                log.error("解绑失败：" + e.getMessage(), e);
                return AuthOutcomes.Bind.failure(AuthOutcomes.BindStatus.ERROR);
            }
        });
    }

    /**
     * 该标识距离可再次绑定还剩多少秒；未启用冷却或从未释放返回 0。
     * <p>
     * 供命令层在发码前做前置提示（真正的拦截在 {@link #bind}）。
     */
    public long cooldownRemainingSeconds(String providerId, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return 0;
        }
        int minutes = Provider.find(config.get(), providerId).map(Provider::cooldownMinutes).orElse(0);
        if (minutes <= 0) {
            return 0;
        }
        long last = storage.bindingHistory().lastReleaseAt(providerId, identifier.trim());
        if (last <= 0) {
            return 0;
        }
        long until = last + minutes * 60L;
        long now = AuthService.nowSeconds();
        return until > now ? until - now : 0;
    }
}
