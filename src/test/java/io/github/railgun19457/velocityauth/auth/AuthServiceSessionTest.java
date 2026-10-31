package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.config.ConfigNode;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import io.github.railgun19457.velocityauth.storage.model.IdentityKind;
import io.github.railgun19457.velocityauth.util.AsyncExecutor;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话保持：内存索引命中、按 IP 隔离、以及 clearSessions 必须同步失效内存索引
 * （否则登出/踢出后会被误判为会话有效）。
 */
@Timeout(30)
class AuthServiceSessionTest {

    @TempDir
    Path tempDir;

    private StorageService storage;
    private AccountCache cache;
    private AsyncExecutor async;
    private PluginLogger log;
    private long accountId;
    private IdentityResolver.Resolved identity;

    private AuthService service(boolean bindToIp) throws Exception {
        return service(bindToIp, true);
    }

    private AuthService service(boolean bindToIp, boolean enabled) throws Exception {
        VelocityAuthConfig config = VelocityAuthConfig.from(ConfigNode.root(Map.of(
                "auth", Map.of("session", Map.of(
                        "enabled", enabled, "ttl-minutes", 30, "bind-to-ip", bindToIp)))));
        return new AuthService(() -> config, storage, cache, new BindingIndex(storage.bindings(), log),
                new PasswordHasher(4), new PendingBindingStore(), new RateLimiter(), null, async, log);
    }

    @BeforeEach
    void setUp() throws Exception {
        storage = StorageService.open(new VelocityAuthConfig.Database(
                VelocityAuthConfig.Database.Type.SQLITE,
                new VelocityAuthConfig.Sqlite("session.db"),
                new VelocityAuthConfig.Mysql("127.0.0.1", 3306, "x", "root", ""), 90, 90, 90), tempDir, NOPLogger.NOP_LOGGER);
        log = new PluginLogger(NOPLogger.NOP_LOGGER);
        async = new AsyncExecutor();
        cache = new AccountCache(storage.accounts(), storage.identities(), log);
        cache.load();

        accountId = storage.createAccount("alice", "alice", UUID.randomUUID(), "h",
                AccountStatus.ACTIVE, 1L, IdentityKind.JAVA, "alice", java.util.Map.of());
        Account account = storage.accounts().findById(accountId).orElseThrow();
        cache.put(account);
        cache.indexIdentity(IdentityKind.JAVA, "alice", accountId);
        identity = new IdentityResolver.Resolved(IdentityKind.JAVA, "alice", "java:alice");
    }

    @AfterEach
    void tearDown() {
        async.close();
        storage.close();
    }

    @Test
    void sessionIsBoundToIpWhenConfigured() throws Exception {
        AuthService auth = service(true);
        assertTrue(auth.resolveSessionLogin(identity, "1.2.3.4").isEmpty(), "尚无会话");

        auth.recordSession(accountId, "1.2.3.4");
        assertTrue(auth.resolveSessionLogin(identity, "1.2.3.4").isPresent(), "内存命中");
        assertTrue(auth.resolveSessionLogin(identity, "9.9.9.9").isEmpty(), "不同 IP 不应命中");
    }

    @Test
    void sessionIgnoresIpWhenBindToIpDisabled() throws Exception {
        AuthService auth = service(false);
        auth.recordSession(accountId, "1.2.3.4");
        assertTrue(auth.resolveSessionLogin(identity, "9.9.9.9").isPresent(),
                "bind-to-ip=false 时任意 IP 都应命中");
    }

    @Test
    void clearSessionsInvalidatesMemoryIndex() throws Exception {
        AuthService auth = service(true);
        auth.recordSession(accountId, "1.2.3.4");
        assertTrue(auth.resolveSessionLogin(identity, "1.2.3.4").isPresent());

        auth.clearSessions(accountId);
        assertFalse(auth.resolveSessionLogin(identity, "1.2.3.4").isPresent(),
                "clearSessions 后内存索引必须失效");
    }

    @Test
    void passwordByteLimitIsRejected() throws Exception {
        AuthService auth = service(true);
        // 25 个中文字符 = 75 字节 > bcrypt 的 72 字节上限，但字符数远小于 max-length(64)
        assertEquals(AuthOutcomes.RegisterStatus.PASSWORD_TOO_LONG_BYTES,
                auth.passwordLengthIssue("密".repeat(25)));
        assertEquals(null, auth.passwordLengthIssue("密".repeat(24)));
    }

    @Test
    void recordSessionAsyncRecordsWhenEnabled() throws Exception {
        AuthService auth = service(true);
        auth.recordSessionAsync(accountId, "1.2.3.4");
        long deadline = System.currentTimeMillis() + 5000;
        boolean present = false;
        while (System.currentTimeMillis() < deadline) {
            if (auth.resolveSessionLogin(identity, "1.2.3.4").isPresent()) {
                present = true;
                break;
            }
            Thread.sleep(50);
        }
        assertTrue(present, "recordSessionAsync 应记录会话");
    }

    @Test
    void recordSessionAsyncIsNoOpWhenDisabled() throws Exception {
        AuthService auth = service(true, false);
        auth.recordSessionAsync(accountId, "1.2.3.4");
        Thread.sleep(500);
        assertTrue(auth.resolveSessionLogin(identity, "1.2.3.4").isEmpty(),
                "会话保持关闭时不应记录");
    }

    @Test
    void deleteAccountRecordsBindingRelease() throws Exception {
        AuthService auth = service(true);
        storage.bindings().insert(accountId, "email", "alice@qq.com", 1L, 1L);

        assertTrue(auth.deleteAccount(accountId, "1.2.3.4", "test").join());

        var history = storage.bindingHistory().findByIdentifier("email", "alice@qq.com", 10);
        assertEquals(1, history.size(), "注销前应把绑定记为释放");
        assertEquals("delete", history.get(0).action());
        assertTrue(storage.accounts().findById(accountId).isEmpty());
    }
}
