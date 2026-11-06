package io.github.railgun19457.velocityauth.bot;

import io.github.railgun19457.velocityauth.admin.AdminService;
import io.github.railgun19457.velocityauth.auth.AccountCache;
import io.github.railgun19457.velocityauth.auth.AuthService;
import io.github.railgun19457.velocityauth.auth.AuthSessionRegistry;
import io.github.railgun19457.velocityauth.auth.BindingIndex;
import io.github.railgun19457.velocityauth.auth.BindingService;
import io.github.railgun19457.velocityauth.auth.PasswordHasher;
import io.github.railgun19457.velocityauth.auth.PendingBindingStore;
import io.github.railgun19457.velocityauth.auth.RateLimiter;
import io.github.railgun19457.velocityauth.config.ConfigNode;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import io.github.railgun19457.velocityauth.storage.model.AuditEntry;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code admin.*} 写操作：参数校验、二次确认、只读模式、审计落库。
 */
@Timeout(30)
class AccountAdminHandlerTest {

    @TempDir
    Path tempDir;

    private StorageService storage;
    private AsyncExecutor async;
    private AuthService auth;
    private AccountAdminHandler handler;
    private VelocityAuthConfig config;
    private long accountId;
    private final AtomicInteger mainThreadRuns = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        PluginLogger log = new PluginLogger(NOPLogger.NOP_LOGGER);
        storage = StorageService.open(new VelocityAuthConfig.Database(
                VelocityAuthConfig.Database.Type.SQLITE,
                new VelocityAuthConfig.Sqlite("admin.db"),
                new VelocityAuthConfig.Mysql("127.0.0.1", 3306, "x", "root", ""), 90, 90, 90),
                tempDir, NOPLogger.NOP_LOGGER);
        async = new AsyncExecutor();

        config = VelocityAuthConfig.from(ConfigNode.root(Map.of(
                "binding", Map.of("social", Map.of("qq", Map.of("enabled", true))),
                "bot", Map.of(
                        "query", Map.of("enabled", true),
                        "admin", Map.of("enabled", true)))));

        AccountCache cache = new AccountCache(storage.accounts(), storage.identities(), log);
        cache.load();
        BindingIndex bindingIndex = new BindingIndex(storage.bindings(), log);
        bindingIndex.load();
        auth = new AuthService(() -> config, storage, cache, bindingIndex,
                new PasswordHasher(4), new PendingBindingStore(), new RateLimiter(),
                null, async, log);
        BindingService bindings = new BindingService(() -> config, storage, bindingIndex, async, log);
        AdminService admin = new AdminService(() -> config, storage, auth, bindings,
                new AuthSessionRegistry(), task -> {
                    mainThreadRuns.incrementAndGet();
                    task.run();
                }, log);
        handler = new AccountAdminHandler(admin, () -> config, log);

        accountId = storage.createAccount("Alice", "alice", UUID.randomUUID(), "h", AccountStatus.ACTIVE,
                1000L, IdentityKind.JAVA, "alice", Map.of("qq", "10001"));
    }

    @AfterEach
    void tearDown() {
        if (async != null) {
            async.close();
        }
        if (storage != null) {
            storage.close();
        }
    }

    private QueryResponse call(String type, Map<String, Object> payload) {
        return handler.handle(type, payload, "bot-1", "10.0.0.1");
    }

    // ------------------------------------------------------------------ 锁定 / 解锁

    @Test
    void setStatusLocksAccountAndAudits() {
        QueryResponse response = call("admin.account.set-status",
                Map.of("name", "Alice", "status", "locked", "actor", "admin@qq"));
        assertNull(response.errorCode(), response.errorMessage());
        assertEquals(true, response.payload().get("ok"));

        Account account = storage.accounts().findById(accountId).orElseThrow();
        assertEquals(AccountStatus.LOCKED, account.status());

        // 锁定会顺带踢人，审计里同时有 admin_lock 与 admin_kick
        List<AuditEntry> entries = storage.audit().findByAccountId(accountId, 10);
        AuditEntry lock = entries.stream()
                .filter(e -> AuditEntry.ADMIN_LOCK.equals(e.action()))
                .findFirst().orElseThrow();
        assertEquals("admin@qq", lock.actor());
        assertEquals(AuditEntry.SOURCE_BOT, lock.source());
        assertEquals("bot-1", lock.clientId());
        assertEquals("10.0.0.1", lock.ip());
    }

    @Test
    void setStatusRejectsUnknownStatus() {
        QueryResponse response = call("admin.account.set-status",
                Map.of("name", "Alice", "status", "bogus"));
        assertEquals("bad_request", response.errorCode());
    }

    @Test
    void setStatusRejectsUnknownAccount() {
        QueryResponse response = call("admin.account.set-status",
                Map.of("name", "Ghost", "status", "locked"));
        assertEquals("bad_request", response.errorCode());
    }

    // ------------------------------------------------------------------ 重置密码

    @Test
    void resetPasswordChangesHash() {
        QueryResponse response = call("admin.account.reset-password",
                Map.of("id", accountId, "password", "newpass123"));
        assertNull(response.errorCode(), response.errorMessage());

        String hash = storage.accounts().findById(accountId).orElseThrow().passwordHash();
        assertTrue(new PasswordHasher(4).verify("newpass123", hash));
        assertEquals(AuditEntry.ADMIN_RESET_PASSWORD, storage.audit().recent(1).get(0).action());
    }

    @Test
    void resetPasswordRejectsTooShort() {
        QueryResponse response = call("admin.account.reset-password",
                Map.of("id", accountId, "password", "x"));
        assertEquals("password_too_short", response.errorCode());
    }

    // ------------------------------------------------------------------ 删除

    @Test
    void deleteRequiresMatchingConfirmName() {
        QueryResponse missing = call("admin.account.delete", Map.of("name", "Alice"));
        assertEquals("confirm_required", missing.errorCode());

        QueryResponse wrong = call("admin.account.delete",
                Map.of("name", "Alice", "confirm_name", "Bob"));
        assertEquals("confirm_required", wrong.errorCode());
        assertTrue(storage.accounts().findById(accountId).isPresent());

        QueryResponse ok = call("admin.account.delete",
                Map.of("name", "Alice", "confirm_name", "alice"));
        assertNull(ok.errorCode(), ok.errorMessage());
        assertEquals(true, ok.payload().get("deleted"));
        assertTrue(storage.accounts().findById(accountId).isEmpty());
        assertTrue(storage.audit().recent(20).stream()
                        .anyMatch(e -> AuditEntry.ADMIN_DELETE.equals(e.action())),
                "删除必须写审计：" + storage.audit().recent(20));
    }

    // ------------------------------------------------------------------ 踢人 / 清会话

    @Test
    void kickGoesThroughMainThread() {
        QueryResponse response = call("admin.account.kick", Map.of("name", "Alice", "reason", "test"));
        assertNull(response.errorCode(), response.errorMessage());
        assertEquals(0, response.payload().get("sessions"));
        assertTrue(mainThreadRuns.get() >= 1, "踢人必须切回代理主线程执行");
    }

    @Test
    void clearSessionSucceeds() {
        QueryResponse response = call("admin.account.clear-session", Map.of("name", "Alice"));
        assertNull(response.errorCode(), response.errorMessage());
        assertEquals(AuditEntry.ADMIN_CLEAR_SESSION, storage.audit().recent(1).get(0).action());
    }

    // ------------------------------------------------------------------ 解绑

    @Test
    void removeBindingDeletesRow() {
        QueryResponse response = call("admin.binding.remove",
                Map.of("name", "Alice", "provider", "qq"));
        assertNull(response.errorCode(), response.errorMessage());
        assertTrue(storage.bindings().find(accountId, "qq").isEmpty());
        assertEquals(AuditEntry.ADMIN_UNBIND, storage.audit().recent(1).get(0).action());
    }

    @Test
    void removeBindingRejectsNotBound() {
        QueryResponse response = call("admin.binding.remove",
                Map.of("name", "Alice", "provider", "email"));
        // email 是内置渠道（始终存在），但该账号没绑邮箱 → not_bound
        assertEquals("not_bound", response.errorCode());
    }

    // ------------------------------------------------------------------ 只读 / 未知操作

    @Test
    void readOnlyModeRejectsAllWrites() {
        VelocityAuthConfig readonly = VelocityAuthConfig.from(ConfigNode.root(Map.of(
                "binding", Map.of("social", Map.of("qq", Map.of("enabled", true))),
                "bot", Map.of(
                        "query", Map.of("enabled", true),
                        "admin", Map.of("enabled", true, "read-only", true)))));
        AccountAdminHandler readonlyHandler = new AccountAdminHandler(
                new AdminService(() -> readonly, storage, auth,
                        new BindingService(() -> readonly, storage, new BindingIndex(storage.bindings(), new PluginLogger(NOPLogger.NOP_LOGGER)), async, new PluginLogger(NOPLogger.NOP_LOGGER)),
                        new AuthSessionRegistry(), Runnable::run, new PluginLogger(NOPLogger.NOP_LOGGER)),
                () -> readonly, new PluginLogger(NOPLogger.NOP_LOGGER));

        QueryResponse response = readonlyHandler.handle("admin.account.set-status",
                Map.of("name", "Alice", "status", "locked"), "bot-1", "10.0.0.1");
        assertEquals("admin_read_only", response.errorCode());
        assertEquals(AccountStatus.ACTIVE, storage.accounts().findById(accountId).orElseThrow().status());
    }

    @Test
    void unknownAdminOperationIsBadRequest() {
        QueryResponse response = call("admin.account.explode", Map.of());
        assertEquals("bad_request", response.errorCode());
    }

    @Test
    void actorFallsBackToClientId() {
        call("admin.account.clear-session", Map.of("name", "Alice"));
        assertEquals("bot-1", storage.audit().recent(1).get(0).actor());
        assertFalse(storage.audit().recent(1).get(0).actor().isBlank());
    }
}
