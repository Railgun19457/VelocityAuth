package io.github.railgun19457.velocityauth.bot;

import io.github.railgun19457.velocityauth.auth.AccountCache;
import io.github.railgun19457.velocityauth.config.ConfigNode;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.query.AccountQueryService;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import io.github.railgun19457.velocityauth.storage.model.BindingHistory;
import io.github.railgun19457.velocityauth.storage.model.IdentityKind;
import io.github.railgun19457.velocityauth.storage.model.LoginHistory;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class AccountQueryHandlerTest {

    @TempDir
    Path tempDir;

    private StorageService storage;
    private AccountQueryHandler handler;
    private long accountId;

    @BeforeEach
    void setUp() throws Exception {
        PluginLogger log = new PluginLogger(NOPLogger.NOP_LOGGER);
        storage = StorageService.open(new VelocityAuthConfig.Database(
                VelocityAuthConfig.Database.Type.SQLITE,
                new VelocityAuthConfig.Sqlite("handler.db"),
                new VelocityAuthConfig.Mysql("127.0.0.1", 3306, "x", "root", ""), 90, 90, 90), tempDir, NOPLogger.NOP_LOGGER);
        long now = System.currentTimeMillis() / 1000L;
        long createdId = storage.createAccount("Alice", "alice", UUID.randomUUID(), "h", AccountStatus.ACTIVE,
                now - 3600L, IdentityKind.JAVA, "alice", Map.of("email", "alice@qq.com", "qq", "10001"));
        this.accountId = createdId;
        storage.accounts().updateLastLogin(createdId, now - 600L, "1.2.3.4");
        storage.loginHistory().insert(createdId, now - 600L, "1.2.3.4", LoginHistory.SUCCESS, "alice", null);
        storage.loginHistory().insert(createdId, 400L, "1.2.3.4", LoginHistory.FAILED, "alice",
                "reason=wrong_password");
        storage.bindingHistory().insert(createdId, "email", "alice@qq.com",
                BindingHistory.BIND, 100L, "1.2.3.4");
        storage.bindingHistory().insert(createdId, "email", "alice@qq.com",
                BindingHistory.UNBIND, now - 600L, "1.2.3.4");
        AccountCache cache = new AccountCache(storage.accounts(), storage.identities(), log);
        cache.load();
        AccountQueryService queries = new AccountQueryService(storage, cache);
        VelocityAuthConfig config = VelocityAuthConfig.from(ConfigNode.root(Map.of(
                "binding", Map.of(
                        "email", Map.of("cooldown-minutes", 60),
                        "social", Map.of("qq", Map.of("cooldown-minutes", 60))),
                "bot", Map.of("query", Map.of("enabled", true, "max-results", 5)))));
        handler = new AccountQueryHandler(queries, storage, () -> config, () -> 3, () -> 1,
                () -> java.util.Set.of(createdId), java.util.List::of);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    void accountPayloadCarriesAllFields() {
        QueryResponse response = handler.handle("query.account", Map.of("by", "name", "value", "alice"));
        assertNull(response.errorCode());
        Map<String, Object> payload = response.payload();
        assertEquals(true, payload.get("found"));
        assertEquals("Alice", payload.get("name"));
        assertTrue(String.valueOf(payload.get("bindings")).contains("email:alice@qq.com"));
        assertTrue(String.valueOf(payload.get("bindings")).contains("qq:10001"));
        assertTrue(((Number) payload.get("last_login_at")).longValue() > 0);
        assertEquals("1.2.3.4", payload.get("last_ip"));
        assertEquals("java:alice", payload.get("identities"));
    }

    @Test
    void lookupByQqReturnsAccountNames() {
        QueryResponse response = handler.handle("query.lookup", Map.of("by", "qq", "value", "10001"));
        assertEquals(true, response.payload().get("found"));
        assertEquals(1, response.payload().get("count"));
        assertEquals("Alice", response.payload().get("accounts"));
    }

    @Test
    void historyEncodesEntries() {
        QueryResponse response = handler.handle("query.history",
                Map.of("by", "name", "value", "alice", "result", "failed"));
        assertEquals(1, response.payload().get("count"));
        assertEquals("400|failed|1.2.3.4|alice", response.payload().get("entries"));
    }

    @Test
    void badRequestReturnsError() {
        assertTrue(handler.handle("query.account", Map.of("by", "bogus", "value", "x")).isError());
        assertEquals("bad_request",
                handler.handle("query.lookup", Map.of("by", "qq")).errorCode());
        assertEquals("bad_request",
                handler.handle("query.history", Map.of("by", "name", "value", "alice", "result", "weird"))
                        .errorCode());
    }

    @Test
    void missingAccountReturnsFoundFalse() {
        QueryResponse response = handler.handle("query.account", Map.of("by", "name", "value", "ghost"));
        assertEquals(false, response.payload().get("found"));
    }

    @Test
    void bindingHistoryPayloadIncludesCooldown() {
        QueryResponse response = handler.handle("query.binding-history",
                Map.of("by", "email", "value", "alice@qq.com"));
        assertNull(response.errorCode());
        assertEquals(true, response.payload().get("found"));
        assertEquals(2, response.payload().get("count"));
        String entries = (String) response.payload().get("entries");
        assertTrue(entries.contains("unbind"), entries);
        assertTrue(entries.contains("Alice"), entries);
        assertTrue(((Number) response.payload().get("cooldown_until")).longValue() > 0);
    }

    @Test
    void statsReportsAggregates() {
        QueryResponse response = handler.handle("query.stats", Map.of());
        assertNull(response.errorCode());
        Map<String, Object> payload = response.payload();
        assertEquals(1, payload.get("accounts_total"));
        assertEquals(1, payload.get("accounts_active"));
        assertEquals(1, payload.get("bound_email"));
        assertEquals(1, payload.get("bound_qq"));
        assertEquals(3, payload.get("online"));
        assertEquals(1, payload.get("bot_connected"));
        assertEquals(1, payload.get("registered_30d"));
        assertEquals(1, payload.get("logins_30d"));
        // 活跃度：24h 内有 1 次成功登录 → dau=1
        assertEquals(1, payload.get("dau"));
        assertEquals(1, payload.get("wau"));
        assertEquals(1, payload.get("mau"));
        // 未指定 range 时不返回趋势，保持旧响应兼容
        assertNull(payload.get("trend"));
    }

    @Test
    void statsWithRangeReturnsTrend() {
        QueryResponse response = handler.handle("query.stats", Map.of("range", 7));
        assertNull(response.errorCode());
        String trend = (String) response.payload().get("trend");
        assertTrue(trend.startsWith("["), trend);
        assertTrue(trend.contains("\"accounts\":1"), trend);
    }

    @Test
    void accountsListFiltersAndPages() {
        QueryResponse response = handler.handle("query.accounts.list",
                Map.of("keyword", "ali", "limit", 10));
        assertNull(response.errorCode());
        Map<String, Object> payload = response.payload();
        assertEquals(1, payload.get("total"));
        assertEquals(1, payload.get("count"));
        String data = (String) payload.get("data");
        assertTrue(data.contains("\"name\":\"Alice\""), data);
        assertTrue(data.contains("\"online\":true"), data);
    }

    @Test
    void accountsListRejectsUnknownStatus() {
        QueryResponse response = handler.handle("query.accounts.list", Map.of("status", "bogus"));
        assertEquals("bad_request", response.errorCode());
    }

    @Test
    void auditListReturnsEntries() {
        storage.audit().log(1000L, accountId, "admin_lock", "9.9.9.9", "reason=test",
                "admin", "bot", "bot-1");
        QueryResponse response = handler.handle("query.audit.list",
                Map.of("account_id", accountId, "limit", 10));
        assertNull(response.errorCode());
        Map<String, Object> payload = response.payload();
        assertEquals(1, payload.get("total"));
        String data = (String) payload.get("data");
        assertTrue(data.contains("\"action\":\"admin_lock\""), data);
        assertTrue(data.contains("\"actor\":\"admin\""), data);
        assertTrue(data.contains("\"client_id\":\"bot-1\""), data);
    }

    @Test
    void auditListRejectsUnknownSource() {
        QueryResponse response = handler.handle("query.audit.list", Map.of("source", "weird"));
        assertEquals("bad_request", response.errorCode());
    }

    @Test
    void sessionsListReturnsRows() {
        QueryResponse response = handler.handle("query.sessions.list", Map.of());
        assertNull(response.errorCode());
        assertEquals(0, response.payload().get("total"));
    }

    @Test
    void accountDetailCarriesOnlineAndStructuredData() {
        QueryResponse response = handler.handle("query.account", Map.of("by", "name", "value", "alice"));
        assertNull(response.errorCode());
        assertEquals(true, response.payload().get("online"));
        String data = (String) response.payload().get("data");
        assertTrue(data.contains("\"provider\":\"email\""), data);
        assertTrue(data.contains("\"kind\":\"java\""), data);
    }

    @Test
    void historyCarriesFailureDetail() {
        QueryResponse response = handler.handle("query.history",
                Map.of("by", "name", "value", "alice", "result", "failed"));
        assertNull(response.errorCode());
        String data = (String) response.payload().get("data");
        assertTrue(data.contains("reason=wrong_password"), data);
    }
}
