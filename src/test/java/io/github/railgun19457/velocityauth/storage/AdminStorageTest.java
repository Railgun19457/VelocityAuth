package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import io.github.railgun19457.velocityauth.storage.model.AuditEntry;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理端新增能力：账号检索、审计检索、活跃度聚合、schema v5 审计列。
 */
@Timeout(30)
class AdminStorageTest {

    @TempDir
    Path tempDir;

    private StorageService storage;

    private static VelocityAuthConfig.Database sqliteConfig() {
        return new VelocityAuthConfig.Database(
                VelocityAuthConfig.Database.Type.SQLITE,
                new VelocityAuthConfig.Sqlite("admin.db"),
                new VelocityAuthConfig.Mysql("127.0.0.1", 3306, "x", "root", ""), 90, 90, 90);
    }

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

    private long account(String name, AccountStatus status, long createdAt, String ip) {
        long id = storage.createAccount(name, name.toLowerCase(), UUID.randomUUID(), "h", status,
                createdAt, IdentityKind.JAVA, name.toLowerCase(), Map.of());
        if (ip != null) {
            storage.accounts().updateLastLogin(id, createdAt, ip);
        }
        return id;
    }

    // ------------------------------------------------------------------ schema v5

    @Test
    void auditLogHasActorColumnsAfterMigration() {
        List<String> cols = new java.util.ArrayList<>();
        storage.database().inTransaction(connection -> {
            try (var st = connection.createStatement();
                 var rs = st.executeQuery("PRAGMA table_info(audit_log)")) {
                while (rs.next()) {
                    cols.add(rs.getString("name"));
                }
            }
            return null;
        });
        assertTrue(cols.contains("actor"), cols.toString());
        assertTrue(cols.contains("source"), cols.toString());
        assertTrue(cols.contains("client_id"), cols.toString());
    }

    @Test
    void auditContextIsPersisted() {
        long id = account("Alice", AccountStatus.ACTIVE, 1000L, "1.2.3.4");
        storage.audit().log(2000L, id, AuditEntry.ADMIN_LOCK, "9.9.9.9", "reason=test",
                "admin@qq", AuditEntry.SOURCE_BOT, "astrbot-1");

        AuditEntry entry = storage.audit().recent(1).get(0);
        assertEquals("admin@qq", entry.actor());
        assertEquals(AuditEntry.SOURCE_BOT, entry.source());
        assertEquals("astrbot-1", entry.clientId());
        assertEquals(id, entry.accountId());
    }

    // ------------------------------------------------------------------ 账号检索

    @Test
    void searchFiltersByStatusAndKeyword() {
        long active = account("Alice", AccountStatus.ACTIVE, 1000L, "1.2.3.4");
        account("Bob", AccountStatus.LOCKED, 2000L, "5.6.7.8");
        storage.bindings().insert(active, "qq", "10001", 1000L, 1000L);

        AccountRepository.Search locked = spec(null, "locked", null, null, 0, 10);
        assertEquals(1, storage.accounts().countSearch(locked));
        assertEquals("Bob", storage.accounts().search(locked).get(0).name());

        // 关键词命中绑定标识
        AccountRepository.Search byBinding = spec("10001", null, null, null, 0, 10);
        assertEquals(1, storage.accounts().countSearch(byBinding));
        assertEquals("Alice", storage.accounts().search(byBinding).get(0).name());

        // 关键词命中 IP
        AccountRepository.Search byIp = spec("5.6.7.8", null, null, null, 0, 10);
        assertEquals("Bob", storage.accounts().search(byIp).get(0).name());
    }

    @Test
    void searchFiltersByProviderAndPaging() {
        long a = account("Alice", AccountStatus.ACTIVE, 1000L, null);
        long b = account("Bob", AccountStatus.ACTIVE, 2000L, null);
        account("Carol", AccountStatus.ACTIVE, 3000L, null);
        storage.bindings().insert(a, "qq", "10001", 1000L, 1000L);
        storage.bindings().insert(b, "email", "bob@qq.com", 1000L, 1000L);

        AccountRepository.Search qq = spec(null, null, "qq", null, 0, 10);
        assertEquals(1, storage.accounts().countSearch(qq));

        AccountRepository.Search all = spec(null, null, null, null, 0, 2);
        assertEquals(3, storage.accounts().countSearch(all));
        assertEquals(2, storage.accounts().search(all).size());
    }

    @Test
    void searchOnlineOnlyUsesGivenIds() {
        long a = account("Alice", AccountStatus.ACTIVE, 1000L, null);
        account("Bob", AccountStatus.ACTIVE, 2000L, null);

        AccountRepository.Search online = new AccountRepository.Search(
                null, null, null, null, null, null, null, null,
                true, List.of(a), null, 0, 10);
        assertEquals(1, storage.accounts().countSearch(online));
        assertEquals("Alice", storage.accounts().search(online).get(0).name());

        AccountRepository.Search noneOnline = new AccountRepository.Search(
                null, null, null, null, null, null, null, null,
                true, List.of(), null, 0, 10);
        assertEquals(0, storage.accounts().countSearch(noneOnline));
    }

    @Test
    void sortWhitelistIsApplied() {
        account("Carol", AccountStatus.ACTIVE, 3000L, null);
        account("Alice", AccountStatus.ACTIVE, 1000L, null);
        account("Bob", AccountStatus.ACTIVE, 2000L, null);

        AccountRepository.Search desc = spec(null, null, null, "-created_at", 0, 10);
        List<Account> rows = storage.accounts().search(desc);
        assertEquals(List.of("Carol", "Bob", "Alice"), rows.stream().map(Account::name).toList());
    }

    // ------------------------------------------------------------------ 审计检索

    @Test
    void auditSearchFiltersAndPages() {
        long id = account("Alice", AccountStatus.ACTIVE, 1000L, null);
        for (int i = 0; i < 5; i++) {
            storage.audit().log(1000L + i, id, "admin_lock", "1.1.1.1", "i=" + i,
                    "admin", AuditEntry.SOURCE_BOT, "bot-1");
        }
        storage.audit().log(2000L, id, "login", "1.1.1.1", null, null, AuditEntry.SOURCE_PLUGIN, null);

        assertEquals(5, storage.audit().countSearch(null, "admin_lock", null, null, 0, 0));
        assertEquals(6, storage.audit().countSearch(id, null, null, null, 0, 0));
        assertEquals(5, storage.audit().countSearch(null, null, "admin", AuditEntry.SOURCE_BOT, 0, 0));
        assertEquals(0, storage.audit().countSearch(null, null, "admin", AuditEntry.SOURCE_COMMAND, 0, 0));

        // 分页：第一页 2 条，按 id 倒序
        List<AuditEntry> page1 = storage.audit().search(id, null, null, null, 0, 0, 0, 2);
        assertEquals(2, page1.size());
        assertTrue(page1.get(0).id() > page1.get(1).id());

        // 时间窗过滤
        assertEquals(5, storage.audit().countSearch(null, null, null, null, 1000L, 1004L));
    }

    // ------------------------------------------------------------------ 活跃度

    @Test
    void dailyActivityAggregatesDistinctAccounts() {
        long a = account("Alice", AccountStatus.ACTIVE, 1000L, null);
        long b = account("Bob", AccountStatus.ACTIVE, 1000L, null);
        long now = System.currentTimeMillis() / 1000L;

        // 同一天：Alice 两次、Bob 一次 → 3 次登录，2 个账号
        storage.loginHistory().insert(a, now - 60L, "1.1.1.1", LoginHistory.SUCCESS, "alice", null);
        storage.loginHistory().insert(a, now - 30L, "1.1.1.1", LoginHistory.SUCCESS, "alice", null);
        storage.loginHistory().insert(b, now - 10L, "2.2.2.2", LoginHistory.SUCCESS, "bob", null);
        // 失败不计入活跃
        storage.loginHistory().insert(b, now - 5L, "2.2.2.2", LoginHistory.FAILED, "bob", "reason=x");

        assertEquals(2, storage.loginHistory().countDistinctAccountsSince(LoginHistory.SUCCESS, now - 86400L));

        List<LoginHistoryRepository.DailyActivity> daily =
                storage.loginHistory().dailySuccess(now - 86400L);
        assertEquals(1, daily.size());
        assertEquals(3, daily.get(0).logins());
        assertEquals(2, daily.get(0).accounts());
    }

    @Test
    void distinctAccountsExcludesOldLogins() {
        long a = account("Alice", AccountStatus.ACTIVE, 1000L, null);
        long now = System.currentTimeMillis() / 1000L;
        storage.loginHistory().insert(a, now - 40L * 86400L, "1.1.1.1", LoginHistory.SUCCESS, "alice", null);

        assertEquals(0, storage.loginHistory().countDistinctAccountsSince(LoginHistory.SUCCESS, now - 86400L));
        assertEquals(1, storage.loginHistory().countDistinctAccountsSince(LoginHistory.SUCCESS,
                now - 60L * 86400L));
    }

    // ------------------------------------------------------------------ 工具

    private static AccountRepository.Search spec(String keyword, String status, String provider,
                                                 String sort, int offset, int limit) {
        return new AccountRepository.Search(keyword, status, provider, null, null, null, null, null,
                false, List.of(), sort, offset, limit);
    }

    @Test
    void bindingHistorySurvivesAccountDelete() {
        long id = account("Alice", AccountStatus.ACTIVE, 1000L, null);
        storage.bindings().insert(id, "qq", "10001", 1000L, 1000L);
        storage.bindingHistory().insert(id, "qq", "10001", BindingHistory.DELETE, 2000L, "1.1.1.1");
        storage.accounts().delete(id);

        List<BindingHistory> history = storage.bindingHistory().findByIdentifier("qq", "10001", 10);
        assertFalse(history.isEmpty());
        assertNotNull(history.get(0).accountId());
    }
}
