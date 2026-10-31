package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import io.github.railgun19457.velocityauth.storage.model.BindingHistory;
import io.github.railgun19457.velocityauth.storage.model.IdentityKind;
import io.github.railgun19457.velocityauth.storage.model.LoginHistory;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class StorageTest {

    @TempDir
    Path tempDir;

    private StorageService storage;

    @BeforeEach
    void setUp() throws Exception {
        storage = StorageService.open(sqliteConfig(), tempDir, NOPLogger.NOP_LOGGER);
    }

    @AfterEach
    void tearDown() {
        if (storage != null) {
            storage.close();
        }
    }

    private static VelocityAuthConfig.Database sqliteConfig() {
        return new VelocityAuthConfig.Database(
                VelocityAuthConfig.Database.Type.SQLITE,
                new VelocityAuthConfig.Sqlite("test.db"),
                new VelocityAuthConfig.Mysql("127.0.0.1", 3306, "x", "root", ""), 90, 90, 90);
    }

    @Test
    void accountCrudRoundTrip() {
        UUID uuid = UUID.nameUUIDFromBytes("OfflinePlayer:test1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long id = storage.accounts().insert("Test1", "test1", uuid, "$2a$10$hash", AccountStatus.ACTIVE, 1000L);

        assertTrue(storage.accounts().existsByNameLower("test1"));
        Account loaded = storage.accounts().findById(id).orElseThrow();
        assertEquals("Test1", loaded.name());
        assertEquals("test1", loaded.nameLower());
        assertEquals(uuid, loaded.uuid());
        assertEquals(AccountStatus.ACTIVE, loaded.status());
        assertEquals(1000L, loaded.createdAt());
        assertEquals(null, loaded.lastLoginAt());

        storage.accounts().updateLastServer(id, "survival");
        storage.accounts().updateLastLogin(id, 2000L, "1.2.3.4");
        storage.accounts().updateStatus(id, AccountStatus.LOCKED);
        storage.accounts().updatePasswordHash(id, "$2a$10$new");

        Account updated = storage.accounts().findById(id).orElseThrow();
        assertEquals("survival", updated.lastServer());
        assertEquals(2000L, updated.lastLoginAt());
        assertEquals("1.2.3.4", updated.lastIp());
        assertTrue(updated.locked());
        assertEquals("$2a$10$new", updated.passwordHash());
        assertEquals(1, storage.accounts().count());
    }

    @Test
    void identityUpsertIsIdempotent() {
        long accountId = storage.accounts().insert("Alice", "alice", UUID.randomUUID(), "h", AccountStatus.ACTIVE,
                1L);
        storage.identities().upsert(accountId, IdentityKind.JAVA, "alice", 10L);
        storage.identities().upsert(accountId, IdentityKind.JAVA, "alice", 20L);

        var identity = storage.identities().find(IdentityKind.JAVA, "alice").orElseThrow();
        assertEquals(accountId, identity.accountId());
        assertEquals(10L, identity.firstSeen());
        assertEquals(20L, identity.lastSeen());
        assertEquals(1, storage.identities().countByAccountId(accountId));
    }

    @Test
    void bindingsEnforceUniquenessAndCounting() {
        long a = storage.accounts().insert("A", "a", UUID.randomUUID(), "h", AccountStatus.ACTIVE, 1L);
        long b = storage.accounts().insert("B", "b", UUID.randomUUID(), "h", AccountStatus.ACTIVE, 1L);

        storage.bindings().insert(a, "email", "a@qq.com", 1L, 1L);
        storage.bindings().insert(b, "email", "a@qq.com", 1L, 1L);

        assertEquals(2, storage.bindings().countDistinctAccounts("email", "a@qq.com"));
        assertTrue(storage.bindings().exists(a, "email", "a@qq.com"));
        assertFalse(storage.bindings().exists(a, "qq", "10001"));
        assertEquals(1, storage.bindings().findByAccountId(a).size());

        storage.bindings().delete(a, "email");
        assertFalse(storage.bindings().exists(a, "email", "a@qq.com"));
        assertEquals(1, storage.bindings().countDistinctAccounts("email", "a@qq.com"));
    }

    @Test
    void cascadeDeleteRemovesChildren() {
        long accountId = storage.accounts().insert("C", "c", UUID.randomUUID(), "h", AccountStatus.ACTIVE, 1L);
        storage.identities().upsert(accountId, IdentityKind.JAVA, "c", 1L);
        storage.bindings().insert(accountId, "qq", "10002", 1L, 1L);
        storage.sessions().insert(accountId, "10.0.0.1", 1L, 100L);

        storage.accounts().delete(accountId);

        assertEquals(0, storage.identities().countByAccountId(accountId));
        assertTrue(storage.bindings().findByAccountId(accountId).isEmpty());
        assertTrue(storage.sessions().findValid(accountId, "10.0.0.1", 50L).isEmpty());
    }

    @Test
    void sessionsRespectExpiry() {
        long accountId = storage.accounts().insert("D", "d", UUID.randomUUID(), "h", AccountStatus.ACTIVE, 1L);
        storage.sessions().insert(accountId, "10.0.0.2", 100L, 200L);

        assertTrue(storage.sessions().findValid(accountId, "10.0.0.2", 150L).isPresent());
        assertTrue(storage.sessions().findValid(accountId, "10.0.0.2", 250L).isEmpty());

        assertEquals(1, storage.sessions().deleteExpired(250L));
        assertEquals(0, storage.sessions().count());
    }

    @Test
    void auditLogRecordsEntries() {
        long accountId = storage.accounts().insert("E", "e", UUID.randomUUID(), "h", AccountStatus.ACTIVE, 1L);
        storage.audit().log(100L, accountId, "login", "127.0.0.1", "ok");
        storage.audit().log(101L, null, "login_failed", "127.0.0.1", "bad password");

        assertEquals(2, storage.audit().count());
        assertEquals(1, storage.audit().findByAccountId(accountId, 10).size());
        assertEquals("login", storage.audit().recent(10).get(1).action());
    }

    @Test
    void loginHistoryRecordsFiltersAndPrunes() {
        long accountId = storage.accounts().insert("H", "h", UUID.randomUUID(), "h", AccountStatus.ACTIVE, 1L);
        storage.loginHistory().insert(accountId, 100L, "1.2.3.4", LoginHistory.SUCCESS, "h", null);
        storage.loginHistory().insert(accountId, 200L, "1.2.3.4", LoginHistory.FAILED, "h", "reason=wrong_password");
        storage.loginHistory().insert(null, 210L, "5.6.7.8", LoginHistory.FAILED, "ghost", "reason=not_found");
        storage.loginHistory().insert(accountId, 300L, "9.9.9.9", LoginHistory.LOGOUT, "h", "connection");

        assertEquals(4, storage.loginHistory().count());
        assertEquals(3, storage.loginHistory().findByAccountId(accountId, null, 0, 10).size());
        assertEquals(1, storage.loginHistory().findByAccountId(accountId, LoginHistory.FAILED, 0, 10).size());
        assertEquals(1, storage.loginHistory().findByAccountId(accountId, null, 250L, 10).size());
        assertEquals(List.of(accountId), storage.loginHistory().distinctAccountIdsByIp("1.2.3.4", 10));

        storage.accounts().updateLastLogin(accountId, 300L, "9.9.9.9");
        assertEquals(1, storage.accounts().findByLastIp("9.9.9.9", 10).size());

        // 只删 at < 200 的那一条
        assertEquals(1, storage.loginHistory().prune(200L));
        assertEquals(3, storage.loginHistory().count());
    }

    @Test
    void bindingHistoryPersistsAfterAccountDeletion() {
        long accountId = storage.accounts().insert("BH", "bh", UUID.randomUUID(), "h",
                AccountStatus.ACTIVE, 1L);
        storage.bindingHistory().insert(accountId, "email", "bh@qq.com",
                BindingHistory.BIND, 100L, "1.1.1.1");
        storage.bindingHistory().insert(accountId, "email", "bh@qq.com",
                BindingHistory.UNBIND, 200L, "1.1.1.1");

        assertEquals(2, storage.bindingHistory().findByIdentifier("email", "bh@qq.com", 10).size());
        assertEquals(200L, storage.bindingHistory().lastReleaseAt("email", "bh@qq.com"));
        assertEquals(0L, storage.bindingHistory().lastReleaseAt("qq", "10001"));

        // 账号删除后绑定历史仍在（无外键级联）
        storage.accounts().delete(accountId);
        assertEquals(2, storage.bindingHistory().findByIdentifier("email", "bh@qq.com", 10).size());
    }

    @Test
    void historyTablesPruneByCutoff() {
        long accountId = storage.accounts().insert("P", "p", UUID.randomUUID(), "h",
                AccountStatus.ACTIVE, 1L);
        storage.audit().log(100L, accountId, "login", "1.1.1.1", null);
        storage.audit().log(300L, accountId, "logout", "1.1.1.1", null);
        storage.bindingHistory().insert(accountId, "email", "p@qq.com",
                BindingHistory.BIND, 100L, "1.1.1.1");
        storage.bindingHistory().insert(accountId, "email", "p@qq.com",
                BindingHistory.UNBIND, 300L, "1.1.1.1");

        assertEquals(1, storage.audit().prune(200L));
        assertEquals(1, storage.bindingHistory().prune(200L));
        assertEquals(1, storage.audit().count());
        assertEquals(1, storage.bindingHistory().count());
        // 释放记录仍在 → 冷却判定不受影响
        assertEquals(300L, storage.bindingHistory().lastReleaseAt("email", "p@qq.com"));
    }

    @Test
    void schemaMigrationIsIdempotent() {
        // 重复迁移不应报错，版本保持
        Schema.migrate(storage.database(), NOPLogger.NOP_LOGGER);
        Schema.migrate(storage.database(), NOPLogger.NOP_LOGGER);
        assertTrue(storage.ping());
    }

    @Test
    void createAccountWritesAccountIdentityAndBindingsAtomically() {
        long id = storage.createAccount("Zoe", "zoe", UUID.randomUUID(), "$2a$10$h",
                AccountStatus.ACTIVE, 500L, IdentityKind.JAVA, "zoe",
                Map.of("email", "zoe@qq.com", "qq", "10009"));

        assertEquals("Zoe", storage.accounts().findById(id).orElseThrow().name());
        assertEquals(id, storage.identities().find(IdentityKind.JAVA, "zoe").orElseThrow().accountId());
        assertEquals("zoe@qq.com",
                storage.bindings().find(id, "email").orElseThrow().identifier());
        assertEquals("10009", storage.bindings().find(id, "qq").orElseThrow().identifier());
    }

    @Test
    void createAccountRollsBackEverythingOnFailure() {
        storage.createAccount("Dup", "dup", UUID.randomUUID(), "h", AccountStatus.ACTIVE, 1L,
                IdentityKind.JAVA, "dup", Map.of());

        assertThrows(StorageException.class, () -> storage.createAccount("Dup2", "dup", UUID.randomUUID(), "h",
                AccountStatus.ACTIVE, 2L, IdentityKind.JAVA, "dup2", Map.of("email", "dup2@qq.com")));

        // 名称冲突导致整体回滚：不应残留身份 / 绑定 / 第二个账号
        assertEquals(1, storage.accounts().count());
        assertTrue(storage.identities().find(IdentityKind.JAVA, "dup2").isEmpty(), "回滚后不应残留身份");
    }
}
