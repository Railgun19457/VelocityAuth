package io.github.railgun19457.velocityauth.query;

import io.github.railgun19457.velocityauth.auth.AccountCache;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class AccountQueryServiceTest {

    @TempDir
    Path tempDir;

    private StorageService storage;
    private AccountQueryService queries;
    private long accountId;
    private UUID uuid;

    @BeforeEach
    void setUp() throws Exception {
        PluginLogger log = new PluginLogger(NOPLogger.NOP_LOGGER);
        storage = StorageService.open(new VelocityAuthConfig.Database(
                VelocityAuthConfig.Database.Type.SQLITE,
                new VelocityAuthConfig.Sqlite("query.db"),
                new VelocityAuthConfig.Mysql("127.0.0.1", 3306, "x", "root", ""), 90, 90, 90), tempDir, NOPLogger.NOP_LOGGER);
        uuid = UUID.randomUUID();
        accountId = storage.createAccount("Alice", "alice", uuid, "h", AccountStatus.ACTIVE,
                100L, IdentityKind.JAVA, "alice", Map.of("email", "alice@qq.com", "qq", "10001"));
        storage.accounts().updateLastLogin(accountId, 500L, "1.2.3.4");
        storage.loginHistory().insert(accountId, 500L, "1.2.3.4", LoginHistory.SUCCESS, "alice", null);
        storage.loginHistory().insert(accountId, 400L, "1.2.3.4", LoginHistory.FAILED, "alice",
                "reason=wrong_password");
        storage.bindingHistory().insert(accountId, "email", "alice@qq.com",
                BindingHistory.BIND, 100L, "1.2.3.4");
        storage.bindingHistory().insert(accountId, "email", "alice@qq.com",
                BindingHistory.UNBIND, 200L, "1.2.3.4");
        AccountCache cache = new AccountCache(storage.accounts(), storage.identities(), log);
        cache.load();
        queries = new AccountQueryService(storage, cache);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    @Test
    void viewIncludesBindingsIdentitiesAndLastLogin() {
        AccountQueryService.AccountView view = queries.byName("alice").orElseThrow();
        assertEquals("Alice", view.name());
        assertEquals(uuid, view.uuid());
        assertEquals("active", view.status());
        assertEquals(2, view.bindings().size());
        assertTrue(view.bindings().stream()
                .anyMatch(b -> b.type().equals("email") && b.identifier().equals("alice@qq.com")));
        assertTrue(view.bindings().stream()
                .anyMatch(b -> b.type().equals("qq") && b.identifier().equals("10001")));
        assertEquals("1.2.3.4", view.lastIp());
        assertEquals(500L, view.lastLoginAt());
        assertEquals(1, view.identities().size());
        assertEquals("alice", view.identities().get(0).identifier());

        assertEquals(accountId, queries.byUuid(uuid.toString()).orElseThrow().id());
        assertTrue(queries.byName("nobody").isEmpty());
        assertTrue(queries.byUuid("not-a-uuid").isEmpty());
    }

    @Test
    void lookupByQqEmailAndIp() {
        assertEquals(List.of("Alice"), queries.lookupBinding("qq", "10001", 10));
        assertEquals(List.of("Alice"), queries.lookupBinding("email", "alice@qq.com", 10));
        assertTrue(queries.lookupBinding("qq", "99999", 10).isEmpty());

        assertEquals(List.of("Alice"), queries.lookupIp("1.2.3.4", 10));
        assertTrue(queries.lookupIp("9.9.9.9", 10).isEmpty());
    }

    @Test
    void historyFiltersByResult() {
        assertEquals(2, queries.history(accountId, null, 0, 10).size());
        List<LoginHistory> failed = queries.history(accountId, LoginHistory.FAILED, 0, 10);
        assertEquals(1, failed.size());
        assertEquals("reason=wrong_password", failed.get(0).detail());
        assertEquals(1, queries.history(accountId, LoginHistory.SUCCESS, 0, 10).size());
        assertFalse(queries.history(accountId, LoginHistory.LOGOUT, 0, 10).iterator().hasNext());
    }

    @Test
    void bindingHistoryTracksReleases() {
        List<BindingHistory> history = queries.bindingHistory("email", "alice@qq.com", 10);
        assertEquals(2, history.size());
        assertEquals("unbind", history.get(0).action());
        assertEquals(200L, queries.lastReleaseAt("email", "alice@qq.com"));
        assertEquals(0L, queries.lastReleaseAt("qq", "10001"));
        assertEquals("Alice", queries.accountName(accountId).orElseThrow());
        assertTrue(queries.bindingHistory("qq", "10001", 10).isEmpty());
    }

    @Test
    void statsAggregatesAccountsAndBindings() {
        AccountQueryService.Stats stats = queries.stats();
        assertEquals(1, stats.accountsTotal());
        assertEquals(1, stats.accountsByStatus().getOrDefault("active", 0));
        assertEquals(1, stats.accountsByProvider().getOrDefault("email", 0));
        assertEquals(1, stats.accountsByProvider().getOrDefault("qq", 0));
    }
}
