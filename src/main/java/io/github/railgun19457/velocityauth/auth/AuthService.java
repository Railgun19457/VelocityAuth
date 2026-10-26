package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import io.github.railgun19457.velocityauth.storage.model.AuditEntry;
import io.github.railgun19457.velocityauth.storage.model.IdentityKind;
import io.github.railgun19457.velocityauth.storage.model.LoginSession;
import io.github.railgun19457.velocityauth.util.AsyncExecutor;
import io.github.railgun19457.velocityauth.util.PluginLogger;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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

    /** 用于账号不存在时仍执行一次 bcrypt，尽量抹平响应时间差异（防枚举）。 */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final Supplier<VelocityAuthConfig> config;
    private final StorageService storage;
    private final AccountCache cache;
    private final PasswordHasher hasher;
    private final PendingBindingStore pendingBindings;
    private final RateLimiter rateLimiter;
    private final AsyncExecutor async;
    private final PluginLogger log;

    public AuthService(Supplier<VelocityAuthConfig> config,
                       StorageService storage,
                       AccountCache cache,
                       PasswordHasher hasher,
                       PendingBindingStore pendingBindings,
                       RateLimiter rateLimiter,
                       AsyncExecutor async,
                       PluginLogger log) {
        this.config = config;
        this.storage = storage;
        this.cache = cache;
        this.hasher = hasher;
        this.pendingBindings = pendingBindings;
        this.rateLimiter = rateLimiter;
        this.async = async;
        this.log = log;
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
        return null;
    }

    public static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ 注册

    public CompletableFuture<AuthOutcomes.Register> register(String name, String password, String confirm,
                                                            String connectionNameLower, String ip) {
        return async.supply(() -> {
            try {
                VelocityAuthConfig cfg = config.get();
                if (cfg.registration().gate() != VelocityAuthConfig.Registration.Gate.NONE) {
                    return AuthOutcomes.Register.gate(cfg.registration().gate().name().toLowerCase(Locale.ROOT));
                }
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
                if (confirm != null && !confirm.isEmpty() && !password.equals(confirm)) {
                    return AuthOutcomes.Register.failure(AuthOutcomes.RegisterStatus.PASSWORD_MISMATCH);
                }

                String nameLower = normalize(name);
                if (cache.existsByNameLower(nameLower) || storage.accounts().existsByNameLower(nameLower)) {
                    return AuthOutcomes.Register.failure(AuthOutcomes.RegisterStatus.NAME_TAKEN);
                }

                String hash = hasher.hash(password);
                long now = nowSeconds();
                long accountId = storage.accounts().insert(name, nameLower, ProfileFactory.offlineUuid(nameLower),
                        hash, AccountStatus.ACTIVE, null, now);
                storage.identities().upsert(accountId, IdentityKind.JAVA, connectionNameLower, now);
                storage.audit().log(now, accountId, AuditEntry.REGISTER, ip, "connection=" + connectionNameLower);

                Account account = storage.accounts().findById(accountId)
                        .orElseThrow(() -> new IllegalStateException("注册后无法读回账号"));
                cache.put(account);
                cache.indexIdentity(IdentityKind.JAVA, connectionNameLower, accountId);
                log.info("新账号注册：{}（连接名 {}，IP {}）", account.name(), connectionNameLower, ip);
                return AuthOutcomes.Register.success(account);
            } catch (RuntimeException e) {
                log.error("注册失败：" + e.getMessage(), e);
                return AuthOutcomes.Register.failure(AuthOutcomes.RegisterStatus.ERROR);
            }
        });
    }

    // ------------------------------------------------------------------ 登录

    public CompletableFuture<AuthOutcomes.Login> login(String name, String password,
                                                      String connectionNameLower, String ip) {
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
                    hasher.verify(password, DUMMY_HASH);
                    return AuthOutcomes.Login.failure(AuthOutcomes.LoginStatus.WRONG_PASSWORD);
                }
                if (account.locked()) {
                    return AuthOutcomes.Login.failure(AuthOutcomes.LoginStatus.LOCKED);
                }
                if (!hasher.verify(password, account.passwordHash())) {
                    storage.audit().log(nowSeconds(), account.id(), AuditEntry.LOGIN_FAILED, ip,
                            "connection=" + connectionNameLower);
                    return AuthOutcomes.Login.failure(AuthOutcomes.LoginStatus.WRONG_PASSWORD);
                }

                long now = nowSeconds();
                storage.accounts().updateLastLogin(account.id(), now, ip);
                storage.identities().upsert(account.id(), IdentityKind.JAVA, connectionNameLower, now);
                storage.audit().log(now, account.id(), AuditEntry.LOGIN, ip, "connection=" + connectionNameLower);

                Account refreshed = storage.accounts().findById(account.id()).orElse(account);
                cache.put(refreshed);
                cache.indexIdentity(IdentityKind.JAVA, connectionNameLower, account.id());
                rateLimiter.reset("login:account:" + nameLower);
                return AuthOutcomes.Login.success(refreshed);
            } catch (RuntimeException e) {
                log.error("登录失败：" + e.getMessage(), e);
                return AuthOutcomes.Login.failure(AuthOutcomes.LoginStatus.ERROR);
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
                storage.audit().log(nowSeconds(), accountId, action, ip, null);
                return new AuthOutcomes.ChangePassword(AuthOutcomes.ChangePasswordStatus.SUCCESS);
            } catch (RuntimeException e) {
                log.error("重置密码失败：" + e.getMessage(), e);
                return new AuthOutcomes.ChangePassword(AuthOutcomes.ChangePasswordStatus.ERROR);
            }
        });
    }

    // ------------------------------------------------------------------ 重连绑定

    public void createPendingBinding(String connectionNameLower, String ip, long accountId) {
        pendingBindings.put(connectionNameLower, ip, accountId);
    }

    /** 消费重连绑定（消费即删）。 */
    public Optional<Account> consumePendingBinding(String connectionNameLower, String ip) {
        return pendingBindings.consume(connectionNameLower, ip)
                .flatMap(id -> cache.byId(id).or(() -> storage.accounts().findById(id).map(account -> {
                    cache.put(account);
                    return account;
                })));
    }

    public boolean hasPendingBinding(String connectionNameLower, String ip) {
        return pendingBindings.peek(connectionNameLower, ip).isPresent();
    }

    // ------------------------------------------------------------------ 会话保持

    /** 会话保持命中则返回账号（跳过 /login）。 */
    public Optional<Account> resolveSessionLogin(String connectionNameLower, String ip) {
        VelocityAuthConfig.Session sessionConfig = config.get().auth().session();
        if (!sessionConfig.enabled()) {
            return Optional.empty();
        }
        Optional<Account> identity = cache.byIdentity(IdentityKind.JAVA, connectionNameLower);
        if (identity.isEmpty()) {
            return Optional.empty();
        }
        Account account = identity.get();
        if (account.locked()) {
            return Optional.empty();
        }
        long now = nowSeconds();
        Optional<LoginSession> session;
        if (sessionConfig.bindToIp()) {
            session = storage.sessions().findValid(account.id(), ip, now);
        } else {
            session = storage.sessions().findAllValidByIp(ip, now).stream()
                    .filter(candidate -> candidate.accountId() == account.id())
                    .findFirst();
        }
        return session.map(s -> account);
    }

    /** 记录会话保持（仅在开启时）。 */
    public void recordSession(long accountId, String ip) {
        VelocityAuthConfig.Session sessionConfig = config.get().auth().session();
        if (!sessionConfig.enabled()) {
            return;
        }
        long now = nowSeconds();
        storage.sessions().insert(accountId, ip, now, now + sessionConfig.ttlMinutes() * 60L);
    }

    public void clearSessions(long accountId) {
        storage.sessions().deleteByAccountId(accountId);
    }

    // ------------------------------------------------------------------ 维护

    /** 周期性清理过期内存态与过期会话。 */
    public void purgeExpired() {
        pendingBindings.purge();
        rateLimiter.purge(Duration.ofMinutes(10));
        async.run(() -> {
            try {
                storage.sessions().deleteExpired(nowSeconds());
            } catch (RuntimeException e) {
                log.warn("清理过期会话失败：{}", e.getMessage());
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
