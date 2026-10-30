package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import io.github.railgun19457.velocityauth.storage.model.BindingType;
import io.github.railgun19457.velocityauth.storage.model.IdentityKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.nio.file.Path;
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
                new VelocityAuthConfig.Mysql("127.0.0.1", 3306, "x", "root", ""));
    }

    @Test
    void accountCrudRoundTrip() {
        UUID uuid = UUID.nameUUIDFromBytes("OfflinePlayer:test1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long id = storage.accounts().insert("Test1", "test1", uuid, "$2a$10$hash", AccountStatus.ACTIVE, null, 1000L);

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
                null, 1L);
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
        long a = storage.accounts().insert("A", "a", UUID.randomUUID(), "h", AccountStatus.ACTIVE, null, 1L);
        long b = storage.accounts().insert("B", "b", UUID.randomUUID(), "h", AccountStatus.ACTIVE, null, 1L);

        storage.bindings().insert(a, BindingType.EMAIL, "a@qq.com", 1L, 1L);
        storage.bindings().insert(b, BindingType.EMAIL, "a@qq.com", 1L, 1L);

        assertEquals(2, storage.bindings().countDistinctAccounts(BindingType.EMAIL, "a@qq.com"));
        assertTrue(storage.bindings().exists(a, BindingType.EMAIL, "a@qq.com"));
        assertFalse(storage.bindings().exists(a, BindingType.QQ, "10001"));
        assertEquals(1, storage.bindings().findByAccountId(a).size());

        storage.bindings().delete(a, BindingType.EMAIL);
        assertFalse(storage.bindings().exists(a, BindingType.EMAIL, "a@qq.com"));
        assertEquals(1, storage.bindings().countDistinctAccounts(BindingType.EMAIL, "a@qq.com"));
    }

    @Test
    void cascadeDeleteRemovesChildren() {
        long accountId = storage.accounts().insert("C", "c", UUID.randomUUID(), "h", AccountStatus.ACTIVE, null, 1L);
        storage.identities().upsert(accountId, IdentityKind.JAVA, "c", 1L);
        storage.bindings().insert(accountId, BindingType.QQ, "10002", 1L, 1L);
        storage.sessions().insert(accountId, "10.0.0.1", 1L, 100L);

        storage.accounts().delete(accountId);

        assertEquals(0, storage.identities().countByAccountId(accountId));
        assertTrue(storage.bindings().findByAccountId(accountId).isEmpty());
        assertTrue(storage.sessions().findValid(accountId, "10.0.0.1", 50L).isEmpty());
    }

    @Test
    void sessionsRespectExpiry() {
        long accountId = storage.accounts().insert("D", "d", UUID.randomUUID(), "h", AccountStatus.ACTIVE, null, 1L);
        storage.sessions().insert(accountId, "10.0.0.2", 100L, 200L);

        assertTrue(storage.sessions().findValid(accountId, "10.0.0.2", 150L).isPresent());
        assertTrue(storage.sessions().findValid(accountId, "10.0.0.2", 250L).isEmpty());

        assertEquals(1, storage.sessions().deleteExpired(250L));
        assertEquals(0, storage.sessions().count());
    }

    @Test
    void auditLogRecordsEntries() {
        long accountId = storage.accounts().insert("E", "e", UUID.randomUUID(), "h", AccountStatus.ACTIVE, null, 1L);
        storage.audit().log(100L, accountId, "login", "127.0.0.1", "ok");
        storage.audit().log(101L, null, "login_failed", "127.0.0.1", "bad password");

        assertEquals(2, storage.audit().count());
        assertEquals(1, storage.audit().findByAccountId(accountId, 10).size());
        assertEquals("login", storage.audit().recent(10).get(1).action());
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
                AccountStatus.ACTIVE, null, 500L, IdentityKind.JAVA, "zoe", "zoe@qq.com", "10009");

        assertEquals("Zoe", storage.accounts().findById(id).orElseThrow().name());
        assertEquals(id, storage.identities().find(IdentityKind.JAVA, "zoe").orElseThrow().accountId());
        assertEquals("zoe@qq.com",
                storage.bindings().find(id, BindingType.EMAIL).orElseThrow().identifier());
        assertEquals("10009", storage.bindings().find(id, BindingType.QQ).orElseThrow().identifier());
    }

    @Test
    void createAccountRollsBackEverythingOnFailure() {
        storage.createAccount("Dup", "dup", UUID.randomUUID(), "h", AccountStatus.ACTIVE, null, 1L,
                IdentityKind.JAVA, "dup", null, null);

        assertThrows(StorageException.class, () -> storage.createAccount("Dup2", "dup", UUID.randomUUID(), "h",
                AccountStatus.ACTIVE, null, 2L, IdentityKind.JAVA, "dup2", "dup2@qq.com", null));

        // 名称冲突导致整体回滚：不应残留身份 / 绑定 / 第二个账号
        assertEquals(1, storage.accounts().count());
        assertTrue(storage.identities().find(IdentityKind.JAVA, "dup2").isEmpty(), "回滚后不应残留身份");
    }
}
