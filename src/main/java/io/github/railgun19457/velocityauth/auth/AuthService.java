package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.platform.FloodgateHook;
import io.github.railgun19457.velocityauth.storage.AuditContext;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import io.github.railgun19457.velocityauth.storage.model.AuditEntry;
import io.github.railgun19457.velocityauth.storage.model.Binding;
import io.github.railgun19457.velocityauth.storage.model.BindingHistory;
import io.github.railgun19457.velocityauth.storage.model.Identity;import io.github.railgun19457.velocityauth.storage.model.IdentityKind;
import io.github.railgun19457.velocityauth.storage.model.LoginHistory;
import io.github.railgun19457.velocityauth.storage.model.LoginSession;
import io.github.railgun19457.velocityauth.util.AsyncExecutor;
import io.github.railgun19457.velocityauth.util.PluginLogger;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 认证核心编排：注册 / 登录 / 改密 / 会话保持 / 重连绑定。
 * <p>
 * 所有数据库与 bcrypt 计算都在 {@link AsyncExecutor} 中执行，绝不阻塞事件循环。
 */
public final class AuthService {

    /** 登录限流：单 IP 每分钟。 */
    private static final int LOGIN_PER_IP_PER_MINUTE = 20;
    /** 登录限流：单账号每分钟。 */
    private static final int LOGIN_PER_ACCOUNT_PER_MINUTE = 10;
    /** 注册限流：单 IP 每分钟。 */
    private static final int REGISTER_PER_IP_PER_MINUTE = 5;
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    private final Supplier<VelocityAuthConfig> config;
    private final StorageService storage;
    private final AccountCache cache;
    private final BindingIndex bindingIndex;
    private final PasswordHasher hasher;
    private final PendingBindingStore pendingBindings;
    private final RateLimiter rateLimiter;
    private final FloodgateHook floodgate;
    private final AsyncExecutor async;
    private final PluginLogger log;

    /**
     * 账号不存在/被锁定时仍执行一次 bcrypt，尽量抹平响应时间差异（防枚举）。
     * 使用与配置一致的 cost，避免用固定 cost 反而产生可区分的耗时。
     */
    private final String dummyHash;

    /**
     * 会话保持的内存索引：key = {@code accountId[|ip]}，value = 过期时间（unix 秒）。
     * <p>
     * {@code GameProfileRequestEvent} 是 @AwaitingEvent，代理会同步等待其返回，
     * 因此热路径尽量只查内存；仅在内存未命中（如插件重启后）时回源一次数据库。
     */
    private final Map<String, Long> sessionCache = new ConcurrentHashMap<>();

    public AuthService(Supplier<VelocityAuthConfig> config,
                       StorageService storage,
                       AccountCache cache,
                       BindingIndex bindingIndex,
                       PasswordHasher hasher,
                       PendingBindingStore pendingBindings,
                       RateLimiter rateLimiter,
                       FloodgateHook floodgate,
                       AsyncExecutor async,
                       PluginLogger log) {
        this.config = config;
        this.storage = storage;
        this.cache = cache;
        this.bindingIndex = bindingIndex;
        this.hasher = hasher;
        this.pendingBindings = pendingBindings;
        this.rateLimiter = rateLimiter;
        this.floodgate = floodgate;
        this.async = async;
        this.log = log;
        this.dummyHash = hasher.hash("velocityauth-dummy-password");
    }

    // ------------------------------------------------------------------ 校验

    public boolean validName(String name) {
        if (name == null) {
            return false;
        }
        VelocityAuthConfig.AccountName rule = config.get().auth().accountName();
        if (name.length() < rule.minLength() || name.length() > rule.maxLength()) {
            return false;
        }
        return rule.pattern().matcher(name).matches();
    }

    public boolean validPassword(String password) {
        return password != null && passwordLengthIssue(password) == null;
    }

    /** 返回密码长度问题的状态，合法则返回 null。 */
    public AuthOutcomes.RegisterStatus passwordLengthIssue(String password) {
        VelocityAuthConfig.Password rule = config.get().auth().password();
        if (password == null || password.length() < rule.minLength()) {
            return AuthOutcomes.RegisterStatus.PASSWORD_TOO_SHORT;
        }
        if (password.length() > rule.maxLength()) {
            return AuthOutcomes.RegisterStatus.PASSWORD_TOO_LONG;
        }
        if (!PasswordHasher.withinByteLimit(password)) {
            return AuthOutcomes.RegisterStatus.PASSWORD_TOO_LONG_BYTES;
        }
        return null;
    }

    public static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ 注册

    public CompletableFuture<AuthOutcomes.Register> register(String name, String password, String confirm,
                                                            IdentityResolver.Resolved identity, String ip) {
        return async.supply(() -> {
            try {
                VelocityAuthConfig cfg = config.get();
                if (!rateLimiter.tryAcquire("register:ip:" + ip, REGISTER_PER_IP_PER_MINUTE, ONE_MINUTE)) {
                    return AuthOutcomes.Register.failure(AuthOutcomes.RegisterStatus.RATE_LIMITED);
                }
                if (!validName(name)) {
                    return AuthOutcomes.Register.failure(AuthOutcomes.RegisterStatus.NAME_INVALID);
                }
                AuthOutcomes.RegisterStatus pwIssue = passwordLengthIssue(password);
                if (pwIssue != null) {
                    return AuthOutcomes.Register.failure(pwIssue);
                }
                if (confirm == null || confirm.isEmpty() || !password.equals(confirm)) {
                    return AuthOutcomes.Register.failure(AuthOutcomes.RegisterStatus.PASSWORD_MISMATCH);
                }

                String nameLower = normalize(name);
                if (cache.existsByNameLower(nameLower) || storage.accounts().existsByNameLower(nameLower)) {
                    return AuthOutcomes.Register.failure(AuthOutcomes.RegisterStatus.NAME_TAKEN);
                }

                // 要求绑定渠道时，账号先落库为「待绑定」：不能登录，且超时未绑定会被自动清除
                AccountStatus status = cfg.registration().bindingRequired()
                        ? AccountStatus.PENDING_BINDING : AccountStatus.ACTIVE;
                String hash = hasher.hash(password);
                long now = nowSeconds();
                long accountId = storage.createAccount(name, nameLower, ProfileFactory.offlineUuid(name),
                        hash, status, now, identity.kind(), identity.identifier(),
                        java.util.Map.of());
                storage.audit().log(now, accountId, AuditEntry.REGISTER, ip,
                        "connection=" + identity.identifier() + " kind=" + identity.kind().dbValue()
                                + " status=" + status.dbValue());

                Account account = storage.accounts().findById(accountId)
                        .orElseThrow(() -> new IllegalStateException("注册后无法读回账号"));
                cache.put(account);
                cache.indexIdentity(identity.kind(), identity.identifier(), accountId);
                log.info("新账号注册：{}（连接名 {}，{} 端，状态 {}，IP {}）", account.name(),
                        identity.identifier(), identity.kind().dbValue(), status.dbValue(), ip);
                return AuthOutcomes.Register.success(account);
            } catch (RuntimeException e) {
                log.error("注册失败：" + e.getMessage(), e);
                return AuthOutcomes.Register.failure(AuthOutcomes.RegisterStatus.ERROR);
            }
        });
    }

    // ------------------------------------------------------------------ 登录

    public CompletableFuture<AuthOutcomes.Login> login(String name, String password,
                                                      IdentityResolver.Resolved identity, String ip) {
        return async.supply(() -> {
            try {
                String nameLower = normalize(name);
                if (!rateLimiter.tryAcquire("login:ip:" + ip, LOGIN_PER_IP_PER_MINUTE, ONE_MINUTE)
                        || !rateLimiter.tryAcquire("login:account:" + nameLower,
                        LOGIN_PER_ACCOUNT_PER_MINUTE, ONE_MINUTE)) {
                    return AuthOutcomes.Login.failure(AuthOutcomes.LoginStatus.RATE_LIMITED);
                }

                Account account = cache.byNameLower(nameLower)
                        .or(() -> storage.accounts().findByNameLower(nameLower))
                        .orElse(null);

                if (account == null) {
                    // 仍执行一次 bcrypt，抹平响应时间差异
                    hasher.verify(password, dummyHash);
                    recordLoginHistory(null, ip, LoginHistory.FAILED, identity.identifier(),
                            "reason=not_found name=" + name);
                    return AuthOutcomes.Login.failure(AuthOutcomes.LoginStatus.WRONG_PASSWORD);
                }
                if (account.locked()) {
                    // 同样执行一次 bcrypt，避免"锁定"状态可通过耗时区分
                    hasher.verify(password, dummyHash);
                    recordLoginHistory(account.id(), ip, LoginHistory.FAILED, identity.identifier(),
                            "reason=locked");
                    return AuthOutcomes.Login.failure(AuthOutcomes.LoginStatus.LOCKED);
                }
                if (!hasher.verify(password, account.passwordHash())) {
                    storage.audit().log(nowSeconds(), account.id(), AuditEntry.LOGIN_FAILED, ip,
                            "connection=" + identity.identifier());
                    recordLoginHistory(account.id(), ip, LoginHistory.FAILED, identity.identifier(),
                            "reason=wrong_password");
                    return AuthOutcomes.Login.failure(AuthOutcomes.LoginStatus.WRONG_PASSWORD);
                }
                // 密码正确但未满足「注册后必须绑定」的要求：不放行，提示先绑定
                if (!bindingSatisfied(account)) {
                    return AuthOutcomes.Login.bindingRequired(account);
                }

                long now = nowSeconds();
                storage.accounts().updateLastLogin(account.id(), now, ip);
                storage.identities().upsert(account.id(), identity.kind(), identity.identifier(), now);
                storage.audit().log(now, account.id(), AuditEntry.LOGIN, ip, "connection=" + identity.identifier());
                recordLoginHistory(account.id(), ip, LoginHistory.SUCCESS, identity.identifier(), null);

                Account refreshed = storage.accounts().findById(account.id()).orElse(account);
                cache.put(refreshed);
                cache.indexIdentity(identity.kind(), identity.identifier(), account.id());
                rateLimiter.reset("login:account:" + nameLower);
                return AuthOutcomes.Login.success(refreshed);
            } catch (RuntimeException e) {
                log.error("登录失败：" + e.getMessage(), e);
                return AuthOutcomes.Login.failure(AuthOutcomes.LoginStatus.ERROR);
            }
        });
    }

    /**
     * 记录一条登录历史（成功 / 失败）。登出记录由断开/命令路径写入。
     * <p>
     * 失败记录会尽力而为：写库异常只记日志，不影响登录结果。
     */
    private void recordLoginHistory(Long accountId, String ip, String result, String identity, String detail) {
        try {
            storage.loginHistory().insert(accountId, nowSeconds(), ip, result, identity, detail);
        } catch (RuntimeException e) {
            log.warn("记录登录历史失败：{}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 注册后绑定要求

    /**
     * 账号是否已满足「注册后必须绑定」的要求。
     * <p>
     * 未配置 {@code registration.require-binding} 时恒为 true；否则只要**绑定渠道之一**已绑定即算满足。
     * 该判断是「绑定为准」而非「状态为准」，因此把配置从无到有后，历史未绑定账号也会被拦在登录外。
     * <p>
     * <b>纯内存</b>（查 {@link BindingIndex}），所以可以用在登录握手热路径上。
     */
    public boolean bindingSatisfied(Account account) {
        return bindingIndex.satisfies(account.id(), config.get().registration().requireBinding());
    }

    /**
     * 若解绑 {@code providerId} 渠道，账号是否仍满足绑定要求。
     * <p>
     * 用于 {@code /unbind} 的前置校验：不允许把账号解绑成「不满足要求」的状态。
     */
    public boolean bindingSatisfiedWithout(Account account, String providerId) {
        List<String> remaining = config.get().registration().requireBinding().stream()
                .filter(id -> !id.equals(providerId))
                .toList();
        return bindingIndex.satisfies(account.id(), remaining);
    }

    /** 账号被删除时同步清理内存索引（账号缓存 + 绑定索引）。 */
    public void forgetAccount(long accountId) {
        cache.remove(accountId);
        bindingIndex.removeAccount(accountId);
    }

    /**
     * 删除账号（级联删除身份 / 绑定 / 会话），并同步清理内存索引。
     *
     * @return 是否确实删除了账号
     */
    public CompletableFuture<Boolean> deleteAccount(long accountId, String ip, String detail) {
        return deleteAccount(accountId, ip, AuditEntry.DELETE_ACCOUNT, detail);
    }

    /**
     * 删除账号（级联删除身份 / 绑定 / 会话），并同步清理内存索引。
     * <p>
     * 删除前先把当前绑定记为「释放」写入 {@code binding_history}，使 QQ/邮箱在注销后仍可追溯、
     * 并触发再绑定冷却。
     *
     * @return 是否确实删除了账号
     */
    public CompletableFuture<Boolean> deleteAccount(long accountId, String ip, String action, String detail) {
        return deleteAccount(accountId, ip, action, detail, AuditContext.plugin());
    }

    /**
     * 删除账号（级联删除身份 / 绑定 / 会话），并同步清理内存索引。
     *
     * @param audit 审计上下文（谁删的、来自哪里）
     */
    public CompletableFuture<Boolean> deleteAccount(long accountId, String ip, String action, String detail,
                                                    AuditContext audit) {
        return async.supply(() -> {
            try {
                recordBindingRelease(accountId, ip, BindingHistory.DELETE);
                int removed = storage.accounts().delete(accountId);
                if (removed > 0) {
                    forgetAccount(accountId);
                    storage.audit().log(nowSeconds(), null, action, ip, detail,
                            audit.actor(), audit.source(), audit.clientId());
                }
                return removed > 0;
            } catch (RuntimeException e) {
                log.error("删除账号失败：" + e.getMessage(), e);
                return false;
            }
        });
    }

    /** 修改账号状态并写审计（管理端锁定 / 解锁共用）。 */
    public CompletableFuture<Boolean> setStatus(long accountId, AccountStatus status, String ip,
                                                String action, AuditContext audit) {
        return async.supply(() -> {
            try {
                int updated = storage.accounts().updateStatusReturning(accountId, status);
                if (updated <= 0) {
                    return false;
                }
                storage.accounts().findById(accountId).ifPresent(cache::put);
                storage.audit().log(nowSeconds(), accountId, action, ip, null,
                        audit.actor(), audit.source(), audit.clientId());
                return true;
            } catch (RuntimeException e) {
                log.error("更新账号状态失败：" + e.getMessage(), e);
                return false;
            }
        });
    }

    /** 把账号当前的所有绑定记为「释放」（解绑 / 注销），写入绑定历史。 */
    private void recordBindingRelease(long accountId, String ip, String action) {
        try {
            long now = nowSeconds();
            for (Binding binding : storage.bindings().findByAccountId(accountId)) {
                storage.bindingHistory().insert(accountId, binding.type(), binding.identifier(), action, now, ip);
            }
        } catch (RuntimeException e) {
            log.warn("记录绑定释放历史失败：{}", e.getMessage());
        }
    }

    /**
     * 绑定完成后尝试激活：仅当账号仍为 {@code pending_binding} 且已满足绑定要求时置为 {@code active}。
     *
     * @return 是否发生了激活（调用方据此决定是否自动登录）
     */
    public CompletableFuture<Boolean> activateIfBindingSatisfied(long accountId) {
        return async.supply(() -> {
            try {
                Account account = cache.byId(accountId)
                        .or(() -> storage.accounts().findById(accountId))
                        .orElse(null);
                if (account == null || account.status() != AccountStatus.PENDING_BINDING) {
                    return false;
                }
                if (!bindingSatisfied(account)) {
                    return false;
                }
                storage.accounts().updateStatus(accountId, AccountStatus.ACTIVE);
                storage.accounts().findById(accountId).ifPresent(cache::put);
                log.info("账号 {} 已完成绑定，激活为 active", account.name());
                return true;
            } catch (RuntimeException e) {
                log.error("激活账号失败：" + e.getMessage(), e);
                return false;
            }
        });
    }

    // ------------------------------------------------------------------ 改密

    public CompletableFuture<AuthOutcomes.ChangePassword> changePassword(long accountId, String oldPassword,
                                                                        String newPassword, String ip) {
        return async.supply(() -> {
            try {
                Account account = storage.accounts().findById(accountId)
                        .or(() -> cache.byId(accountId))
                        .orElse(null);
                if (account == null) {
                    return new AuthOutcomes.ChangePassword(AuthOutcomes.ChangePasswordStatus.NOT_FOUND);
                }
                if (oldPassword != null && !hasher.verify(oldPassword, account.passwordHash())) {
                    return new AuthOutcomes.ChangePassword(AuthOutcomes.ChangePasswordStatus.WRONG_PASSWORD);
                }
                if (newPassword != null && newPassword.equals(oldPassword)) {
                    return new AuthOutcomes.ChangePassword(AuthOutcomes.ChangePasswordStatus.SAME_AS_OLD);
                }
                AuthOutcomes.RegisterStatus pwIssue = passwordLengthIssue(newPassword);
                if (pwIssue == AuthOutcomes.RegisterStatus.PASSWORD_TOO_SHORT) {
                    return new AuthOutcomes.ChangePassword(AuthOutcomes.ChangePasswordStatus.PASSWORD_TOO_SHORT);
                }
                if (pwIssue == AuthOutcomes.RegisterStatus.PASSWORD_TOO_LONG) {
                    return new AuthOutcomes.ChangePassword(AuthOutcomes.ChangePasswordStatus.PASSWORD_TOO_LONG);
                }

                storage.accounts().updatePasswordHash(accountId, hasher.hash(newPassword));
                storage.accounts().findById(accountId).ifPresent(cache::put);
                storage.audit().log(nowSeconds(), accountId, AuditEntry.CHANGE_PASSWORD, ip, null);
                return new AuthOutcomes.ChangePassword(AuthOutcomes.ChangePasswordStatus.SUCCESS);
            } catch (RuntimeException e) {
                log.error("修改密码失败：" + e.getMessage(), e);
                return new AuthOutcomes.ChangePassword(AuthOutcomes.ChangePasswordStatus.ERROR);
            }
        });
    }

    /** 管理员 / 找回流程直接设置新密码（不校验旧密码）。 */
    public CompletableFuture<AuthOutcomes.ChangePassword> forcePassword(long accountId, String newPassword,
                                                                       String ip, String action) {
        return forcePassword(accountId, newPassword, ip, action, AuditContext.plugin());
    }

    /**
     * 管理员 / 找回流程直接设置新密码（不校验旧密码），并记录审计上下文。
     *
     * @param action 审计动作标识（{@code change_password} / {@code admin_reset_password} 等）
     */
    public CompletableFuture<AuthOutcomes.ChangePassword> forcePassword(long accountId, String newPassword,
                                                                       String ip, String action,
                                                                       AuditContext audit) {
        return async.supply(() -> {
            try {
                Account account = storage.accounts().findById(accountId).orElse(null);
                if (account == null) {
                    return new AuthOutcomes.ChangePassword(AuthOutcomes.ChangePasswordStatus.NOT_FOUND);
                }
                AuthOutcomes.RegisterStatus pwIssue = passwordLengthIssue(newPassword);
                if (pwIssue == AuthOutcomes.RegisterStatus.PASSWORD_TOO_SHORT) {
                    return new AuthOutcomes.ChangePassword(AuthOutcomes.ChangePasswordStatus.PASSWORD_TOO_SHORT);
                }
                if (pwIssue == AuthOutcomes.RegisterStatus.PASSWORD_TOO_LONG) {
                    return new AuthOutcomes.ChangePassword(AuthOutcomes.ChangePasswordStatus.PASSWORD_TOO_LONG);
                }
                storage.accounts().updatePasswordHash(accountId, hasher.hash(newPassword));
                storage.accounts().findById(accountId).ifPresent(cache::put);
                storage.audit().log(nowSeconds(), accountId, action, ip, null,
                        audit.actor(), audit.source(), audit.clientId());
                return new AuthOutcomes.ChangePassword(AuthOutcomes.ChangePasswordStatus.SUCCESS);
            } catch (RuntimeException e) {
                log.error("重置密码失败：" + e.getMessage(), e);
                return new AuthOutcomes.ChangePassword(AuthOutcomes.ChangePasswordStatus.ERROR);
            }
        });
    }

    // ------------------------------------------------------------------ 重连绑定

    public void createPendingBinding(String identityKey, String ip, long accountId) {
        pendingBindings.put(identityKey, ip, accountId);
    }

    /**
     * 基岩玩家认证成功后，把其 Floodgate 身份**认领**到账号（若启用自动关联）。
     * <p>
     * 「禁止多对一」：一个账号同时只保留一个基岩身份。若该账号已被**其它**基岩身份关联，
     * 旧的会被**自动解绑**（删除身份记录 + 解除 Floodgate link），由新登录的基岩账号顶替；
     * 反之，基岩账号登录另一个账号时，其旧关联也会自然转移过去（身份记录随之改挂）。
     * <p>
     * 关联后 Floodgate 会在**下次握手**原生注入账号的 name/uuid，因此 profile 重写变成空操作，
     * 也不会因 UUID 被改写而被 Floodgate 判为非 Floodgate 玩家断开。
     * <p>
     * 返回的 future 在「认领完成或失败」后完成，调用方应在它完成后再触发重连，保证关联先落库。
     *
     * <p>Floodgate link 的 bedrock 侧键用 {@code identity.identifier()}（即 Floodgate 稳定 ID，
     * 由 XUID 派生）：已关联的基岩玩家其 profile UUID 已经是账号 UUID，不能再用它当键。
     */
    public CompletableFuture<Void> claimBedrockAccount(IdentityResolver.Resolved identity, long accountId) {
        if (!identity.bedrock() || !config.get().auth().bedrock().autoLink()) {
            return CompletableFuture.completedFuture(null);
        }
        final java.util.UUID bedrockKey;
        try {
            bedrockKey = java.util.UUID.fromString(identity.identifier());
        } catch (IllegalArgumentException e) {
            log.warn("基岩身份标识不是合法 UUID，跳过账号认领：{}", identity.identifier());
            return CompletableFuture.completedFuture(null);
        }
        return async.<Void>supply(() -> {
            try {
                evictOtherBedrockIdentities(accountId, identity.identifier());
                Account account = cache.byId(accountId).orElse(null);
                if (account == null || !floodgate.linkingAvailable()) {
                    return null;
                }
                log.info("基岩端自动关联：把 Floodgate 身份关联到账号 {}", account.name());
                floodgate.linkAccount(bedrockKey, account.uuid(), account.name()).join();
            } catch (RuntimeException e) {
                log.warn("基岩端账号认领失败：{}", e.getMessage());
            }
            return null;
        }).exceptionally(t -> null);
    }

    /** 删除账号上「其它」基岩身份，并解除其在 Floodgate 侧的关联（一个账号只保留一个基岩身份）。 */
    private void evictOtherBedrockIdentities(long accountId, String keepIdentifier) {
        for (Identity existing : storage.identities().findByAccountId(accountId)) {
            if (existing.kind() != IdentityKind.BEDROCK || existing.identifier().equals(keepIdentifier)) {
                continue;
            }
            storage.identities().delete(IdentityKind.BEDROCK, existing.identifier());
            cache.removeIdentity(IdentityKind.BEDROCK, existing.identifier());
            try {
                floodgate.unlinkAccount(java.util.UUID.fromString(existing.identifier()));
            } catch (IllegalArgumentException ignored) {
                // 身份标识不是合法 UUID（异常数据）：跳过 Floodgate 侧解绑
            }
            log.info("基岩端换绑：账号 {} 原基岩身份 {} 已自动解绑", accountId, existing.identifier());
        }
    }

    /** 消费重连绑定（消费即删）。 */
    public Optional<Account> consumePendingBinding(String identityKey, String ip) {
        return pendingBindings.consume(identityKey, ip)
                .flatMap(id -> cache.byId(id).or(() -> storage.accounts().findById(id).map(account -> {
                    cache.put(account);
                    return account;
                })));
    }

    public boolean hasPendingBinding(String identityKey, String ip) {
        return pendingBindings.peek(identityKey, ip).isPresent();
    }

    // ------------------------------------------------------------------ 会话保持

    /** 会话保持命中则返回账号（跳过 /login）。 */
    public Optional<Account> resolveSessionLogin(IdentityResolver.Resolved identity, String ip) {
        VelocityAuthConfig.Session sessionConfig = config.get().auth().session();
        if (!sessionConfig.enabled()) {
            return Optional.empty();
        }
        Optional<Account> resolved = cache.byIdentity(identity.kind(), identity.identifier());
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Account account = resolved.get();
        if (account.locked()) {
            return Optional.empty();
        }
        // 会话保持同样属于"登录"：未满足绑定要求的账号不放行（纯内存判断，不碰热路径性能）
        if (!bindingSatisfied(account)) {
            return Optional.empty();
        }
        long now = nowSeconds();
        String key = sessionKey(account.id(), ip, sessionConfig.bindToIp());
        Long cached = sessionCache.get(key);
        if (cached != null) {
            // 内存命中：热路径完全不查库
            if (cached > now) {
                return Optional.of(account);
            }
            sessionCache.remove(key);
            return Optional.empty();
        }
        // 内存未命中（如插件刚重启）：回源一次并写入缓存
        Optional<LoginSession> session = sessionConfig.bindToIp()
                ? storage.sessions().findValid(account.id(), ip, now)
                : storage.sessions().findAnyValid(account.id(), now);
        session.ifPresent(s -> sessionCache.put(key, s.expiresAt()));
        return session.map(s -> account);
    }

    /** 记录会话保持（仅在开启时）。 */
    public void recordSession(long accountId, String ip) {
        VelocityAuthConfig.Session sessionConfig = config.get().auth().session();
        if (!sessionConfig.enabled()) {
            return;
        }
        long now = nowSeconds();
        long expiresAt = now + sessionConfig.ttlMinutes() * 60L;
        storage.sessions().insert(accountId, ip, now, expiresAt);
        sessionCache.put(sessionKey(accountId, ip, sessionConfig.bindToIp()), expiresAt);
    }

    /**
     * 异步记录会话保持（供事件/命令线程调用）。
     * <p>
     * 除了 {@code PostLogin}，**登录成功但免重连**（连接名与账号名一致）时也必须记录，
     * 否则这类玩家（最常见情形）永远无法通过会话保持免密登录。
     */
    public void recordSessionAsync(long accountId, String ip) {
        if (!config.get().auth().session().enabled()) {
            return;
        }
        async.run(() -> {
            try {
                recordSession(accountId, ip);
            } catch (RuntimeException e) {
                log.warn("记录会话保持失败：{}", e.getMessage());
            }
        });
    }

    public void clearSessions(long accountId) {
        storage.sessions().deleteByAccountId(accountId);
        // 同步失效内存索引，否则登出/踢出后仍会被判定为"会话有效"
        String prefix = accountId + "|";
        String exact = Long.toString(accountId);
        sessionCache.keySet().removeIf(k -> k.equals(exact) || k.startsWith(prefix));
    }

    /** 会话缓存 key：绑定 IP 时为 {@code accountId|ip}，否则仅 {@code accountId}。 */
    private static String sessionKey(long accountId, String ip, boolean bindToIp) {
        return bindToIp ? accountId + "|" + (ip == null ? "" : ip) : Long.toString(accountId);
    }

    // ------------------------------------------------------------------ 维护

    /** 周期性清理过期内存态与过期会话。 */
    public void purgeExpired() {
        pendingBindings.purge();
        rateLimiter.purge(Duration.ofMinutes(10));
        long now = nowSeconds();
        sessionCache.entrySet().removeIf(entry -> entry.getValue() <= now);
        purgeUnboundAccounts(now);
        async.run(() -> {
            try {
                storage.sessions().deleteExpired(nowSeconds());
            } catch (RuntimeException e) {
                log.warn("清理过期会话失败：{}", e.getMessage());
            }
        });
        purgeTimeSeries(now);
    }

    /**
     * 按 {@code database.*-days} 清理有时效性的历史表（登录历史 / 审计日志 / 绑定历史）；
     * 0 表示永久保留。绑定历史即使被清理，也不影响已生效的冷却窗口（冷却通常远短于保留期）。
     */
    private void purgeTimeSeries(long now) {
        VelocityAuthConfig.Database db = config.get().database();
        prune("登录历史", db.loginHistoryDays(), now, storage.loginHistory()::prune);
        prune("审计日志", db.auditLogDays(), now, storage.audit()::prune);
        prune("绑定历史", db.bindingHistoryDays(), now, storage.bindingHistory()::prune);
    }

    private void prune(String label, int days, long now, java.util.function.LongFunction<Integer> prune) {
        if (days <= 0) {
            return;
        }
        long cutoff = now - days * 86_400L;
        async.run(() -> {
            try {
                int removed = prune.apply(cutoff);
                if (removed > 0) {
                    log.debug("已清理 {} 条过期{}（早于 {} 天前）", removed, label, days);
                }
            } catch (RuntimeException e) {
                log.warn("清理{}失败：{}", label, e.getMessage());
            }
        });
    }

    /**
     * 清除超时未绑定的账号（{@code registration.unbound-ttl-minutes}）。
     * <p>
     * 只删「注册后从未完成绑定」的 {@code pending_binding} 账号，级联删除其身份/绑定，
     * 从而释放被占用的账号名。ttl = 0 表示不自动清除。
     */
    private void purgeUnboundAccounts(long now) {
        int ttlMinutes = config.get().registration().unboundTtlMinutes();
        if (ttlMinutes <= 0) {
            return;
        }
        long cutoff = now - ttlMinutes * 60L;
        async.run(() -> {
            try {
                List<Account> stale = storage.accounts().findPendingOlderThan(cutoff);
                for (Account account : stale) {
                    recordBindingRelease(account.id(), null, BindingHistory.DELETE);
                    storage.accounts().delete(account.id());
                    forgetAccount(account.id());
                    log.info("未绑定账号超时清除：{}（注册于 {}，TTL {} 分钟）",
                            account.name(), account.createdAt(), ttlMinutes);
                }
            } catch (RuntimeException e) {
                log.warn("清理未绑定账号失败：{}", e.getMessage());
            }
        });
    }

    public PendingBindingStore pendingBindings() {
        return pendingBindings;
    }

    public RateLimiter rateLimiter() {
        return rateLimiter;
    }

    public AccountCache cache() {
        return cache;
    }

    public BindingIndex bindingIndex() {
        return bindingIndex;
    }

    public StorageService storage() {
        return storage;
    }

    public PasswordHasher hasher() {
        return hasher;
    }

    public static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
