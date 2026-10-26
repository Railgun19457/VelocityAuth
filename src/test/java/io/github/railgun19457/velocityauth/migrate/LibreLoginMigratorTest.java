package io.github.railgun19457.velocityauth.migrate;

import io.github.railgun19457.velocityauth.auth.PasswordHasher;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.BindingType;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LibreLogin 迁移：用真实表结构（librepremium_data）造数据验证映射规则。
 */
@Timeout(30)
class LibreLoginMigratorTest {

    @TempDir
    Path tempDir;

    private StorageService storage;
    private LibreLoginMigrator migrator;
    private Path legacyDb;
    private final PasswordHasher hasher = new PasswordHasher(4);
    private String bcryptHash;

    @BeforeEach
    void setUp() throws Exception {
        storage = StorageService.open(new VelocityAuthConfig.Database(
                VelocityAuthConfig.Database.Type.SQLITE,
                new VelocityAuthConfig.Sqlite("velocityauth.db"),
                new VelocityAuthConfig.Mysql("127.0.0.1", 3306, "x", "root", "")), tempDir, NOPLogger.NOP_LOGGER);
        migrator = new LibreLoginMigrator(storage, new PluginLogger(NOPLogger.NOP_LOGGER));

        legacyDb = tempDir.resolve("librepremium_data.db");
        bcryptHash = hasher.hash("LegacyPass1");
        createLegacyDatabase();
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    private void createLegacyDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + legacyDb);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE librepremium_data("
                    + "uuid VARCHAR(255) NOT NULL PRIMARY KEY,"
                    + "premium_uuid VARCHAR(255) UNIQUE,"
                    + "hashed_password VARCHAR(255),"
                    + "salt VARCHAR(255),"
                    + "algo VARCHAR(255),"
                    + "last_nickname VARCHAR(255) NOT NULL UNIQUE,"
                    + "joined TIMESTAMP NULL DEFAULT NULL,"
                    + "last_seen TIMESTAMP NULL DEFAULT NULL,"
                    + "last_server VARCHAR(255),"
                    + "secret VARCHAR(255),"
                    + "ip VARCHAR(255),"
                    + "last_authentication TIMESTAMP NULL DEFAULT NULL,"
                    + "email VARCHAR(255))");

            // 1) bcrypt 可直接复用
            insert(statement, UUID.nameUUIDFromBytes("OfflinePlayer:LegacyOne".getBytes()).toString(),
                    null, bcryptHash, "BCRYPT", "LegacyOne", "2024-01-02 03:04:05", "survival",
                    "legacy1@qq.com");
            // 2) SHA256 无法转换 → 需重置
            insert(statement, UUID.nameUUIDFromBytes("OfflinePlayer:LegacyTwo".getBytes()).toString(),
                    null, "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8", "SHA256",
                    "LegacyTwo", null, null, null);
            // 3) 昵称为空串 → 无法映射（真实表 last_nickname 是 NOT NULL，这里覆盖空值分支）
            insert(statement, UUID.nameUUIDFromBytes("OfflinePlayer:NoName".getBytes()).toString(),
                    null, bcryptHash, "BCRYPT", "", null, null, null);
            // 4) 与现有账号重名 → 跳过
            insert(statement, UUID.nameUUIDFromBytes("OfflinePlayer:Taken".getBytes()).toString(),
                    null, bcryptHash, "BCRYPT", "Taken", null, null, null);
        }
        storage.accounts().insert("Taken", "taken", UUID.randomUUID(), "h",
                io.github.railgun19457.velocityauth.storage.model.AccountStatus.ACTIVE, null, 1L);
    }

    private void insert(Statement statement, String uuid, String premium, String hash, String algo,
                        String nickname, String lastSeen, String lastServer, String email) throws Exception {
        statement.execute("INSERT INTO librepremium_data(uuid, premium_uuid, hashed_password, salt, algo,"
                + " last_nickname, joined, last_seen, last_server, email) VALUES ("
                + quote(uuid) + ", " + quote(premium) + ", " + quote(hash) + ", NULL, " + quote(algo) + ", "
                + quote(nickname) + ", NULL, " + quote(lastSeen) + ", " + quote(lastServer) + ", "
                + quote(email) + ")");
    }

    private static String quote(String value) {
        return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
    }

    @Test
    void dryRunDoesNotWriteAnything() throws Exception {
        var report = migrator.migrate(legacyDb, true);
        assertEquals(4, report.scanned());
        assertEquals(2, report.imported());
        assertEquals(1, report.skipped());
        assertEquals(1, report.unmapped());
        assertTrue(report.dryRun());
        assertEquals(1, storage.accounts().count(), "试运行不应写入");
    }

    @Test
    void migrationMapsAccountsPasswordsAndMetadata() throws Exception {
        var report = migrator.migrate(legacyDb, false);
        assertEquals(2, report.imported());
        assertEquals(1, report.needsReset());
        assertEquals(1, report.skipped());
        assertEquals(1, report.unmapped());
        assertEquals(1, report.emails());

        // 大小写保留、规范名小写、UUID 由小写名派生
        var one = storage.accounts().findByNameLower("legacyone").orElseThrow();
        assertEquals("LegacyOne", one.name());
        assertEquals(io.github.railgun19457.velocityauth.auth.ProfileFactory.offlineUuid("legacyone"),
                one.uuid());
        assertTrue(hasher.verify("LegacyPass1", one.passwordHash()), "bcrypt 哈希应可直接复用");
        assertEquals("survival", one.lastServer());
        assertTrue(one.lastLoginAt() != null && one.lastLoginAt() > 0, "last_seen 应被迁移");
        assertEquals("legacy1@qq.com",
                storage.bindings().find(one.id(), BindingType.EMAIL).orElseThrow().identifier());

        // 不可转换的算法 → 占位哈希（bcrypt 校验必然失败）
        var two = storage.accounts().findByNameLower("legacytwo").orElseThrow();
        assertEquals(LibreLoginMigrator.NEEDS_RESET_HASH, two.passwordHash());
        assertFalse(hasher.verify("anything", two.passwordHash()));
        assertFalse(hasher.verify("5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef621d1542d8",
                two.passwordHash()));

        // 身份映射已写入，玩家用原连接名即可命中账号
        assertTrue(storage.identities().find(
                io.github.railgun19457.velocityauth.storage.model.IdentityKind.JAVA, "legacyone").isPresent());
        assertTrue(storage.identities().find(
                io.github.railgun19457.velocityauth.storage.model.IdentityKind.JAVA, "legacytwo").isPresent());

        // 审计
        assertEquals("migrate", storage.audit().recent(5).get(0).action());
    }

    @Test
    void migrationIsIdempotent() throws Exception {
        migrator.migrate(legacyDb, false);
        int after = storage.accounts().count();
        var second = migrator.migrate(legacyDb, false);
        assertEquals(0, second.imported());
        assertEquals(3, second.skipped());
        assertEquals(1, second.unmapped());
        assertEquals(after, storage.accounts().count(), "重复迁移不应产生重复账号");
    }

    @Test
    void bcryptDetectionRules() {
        assertTrue(LibreLoginMigrator.isReusableBcrypt("$2a$10$abc", "BCRYPT"));
        assertTrue(LibreLoginMigrator.isReusableBcrypt("$2b$10$abc", null));
        assertTrue(LibreLoginMigrator.isReusableBcrypt("$2y$10$abc", "bcrypt"));
        assertFalse(LibreLoginMigrator.isReusableBcrypt("deadbeef", "SHA256"));
        assertFalse(LibreLoginMigrator.isReusableBcrypt(null, "BCRYPT"));
        assertFalse(LibreLoginMigrator.isReusableBcrypt("", "BCRYPT"));
    }
}
