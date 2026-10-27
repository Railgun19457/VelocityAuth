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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

            // 1) 真实格式的 bcrypt：LibreLogin 把 bcrypt 拆成 algo/salt/hashed_password 三列
            String[] split = splitLibreLoginHash(bcryptHash);
            insert(statement, UUID.nameUUIDFromBytes("OfflinePlayer:LegacyOne".getBytes()).toString(),
                    null, split[1], split[0], "BCrypt-2A", "LegacyOne", "2024-01-02 03:04:05", "survival",
                    "legacy1@qq.com");
            // 2) Argon2ID：本插件不支持 → 需重置
            insert(statement, UUID.nameUUIDFromBytes("OfflinePlayer:LegacyTwo".getBytes()).toString(),
                    null, "1,3,4096$ZGVhZGJlZWZkZWFkYmVlZg==", "c29tZXNhbHQ=", "Argon-2ID",
                    "LegacyTwo", null, null, null);
            // 3) 完全没有密码（LibreLogin 里正版自动注册的账号）→ 需重置
            insert(statement, UUID.nameUUIDFromBytes("OfflinePlayer:NoName".getBytes()).toString(),
                    null, null, null, null, "NoPassword", null, null, null);
            // 4) 与现有账号重名 → 跳过
            insert(statement, UUID.nameUUIDFromBytes("OfflinePlayer:Taken".getBytes()).toString(),
                    null, split[1], split[0], "BCrypt-2A", "Taken", null, null, null);
            // 5) 昵称为空串 → 无法映射
            insert(statement, UUID.nameUUIDFromBytes("OfflinePlayer:Blank".getBytes()).toString(),
                    null, split[1], split[0], "BCrypt-2A", "", null, null, null);
            // 6) 源库 uuid 非法/缺失 → 回退按小写规范名派生
            insert(statement, "not-a-uuid",
                    null, split[1], split[0], "BCrypt-2A", "DerivedOne", null, null, null);
        }
        storage.accounts().insert("Taken", "taken", UUID.randomUUID(), "h",
                io.github.railgun19457.velocityauth.storage.model.AccountStatus.ACTIVE, null, 1L);
    }

    /**
     * 按 LibreLogin 的 {@code CryptoUtil.convertFromBCryptRaw} 拆分完整 bcrypt 串：
     * {@code $2a$10$<22盐><31哈希>} → hashed_password={@code 10$<31哈希>}，salt={@code <22盐>}。
     *
     * @return {salt, hashed_password}
     */
    private static String[] splitLibreLoginHash(String fullBcrypt) {
        String[] parts = fullBcrypt.split("\\$");   // ["", "2a", "10", "<22盐><31哈希>"]
        String cost = parts[2];
        String rest = parts[3];
        String salt = rest.substring(0, 22);
        String hash = rest.substring(22);
        return new String[]{salt, cost + "$" + hash};
    }

    private void insert(Statement statement, String uuid, String premium, String hash, String salt, String algo,
                        String nickname, String lastSeen, String lastServer, String email) throws Exception {
        statement.execute("INSERT INTO librepremium_data(uuid, premium_uuid, hashed_password, salt, algo,"
                + " last_nickname, joined, last_seen, last_server, email) VALUES ("
                + quote(uuid) + ", " + quote(premium) + ", " + quote(hash) + ", " + quote(salt) + ", "
                + quote(algo) + ", " + quote(nickname) + ", NULL, " + quote(lastSeen) + ", "
                + quote(lastServer) + ", " + quote(email) + ")");
    }

    private static String quote(String value) {
        return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
    }

    @Test
    void migrationPreservesLegacyUuid() throws Exception {
        var report = migrator.migrate(legacyDb, false);

        // 关键：LibreLogin 的 uuid 由「名字原样」派生，本插件新注册用「小写规范名」，
        // 含大写字母的名字两者并不相同 —— 迁移必须保留原 UUID，否则按 UUID 存的数据全丢。
        UUID legacyUuid = UUID.nameUUIDFromBytes("OfflinePlayer:LegacyOne".getBytes());
        UUID derivedUuid = io.github.railgun19457.velocityauth.auth.ProfileFactory.offlineUuid("legacyone");
        assertNotEquals(legacyUuid, derivedUuid, "前置条件：混合大小写的名字两种派生结果必须不同");

        var one = storage.accounts().findByNameLower("legacyone").orElseThrow();
        assertEquals(legacyUuid, one.uuid(), "必须保留源库 UUID");
        assertTrue(storage.accounts().findByUuid(legacyUuid).isPresent());

        // 源库没有 uuid 时才回退派生
        var derived = storage.accounts().findByNameLower("derivedone").orElseThrow();
        assertEquals(io.github.railgun19457.velocityauth.auth.ProfileFactory.offlineUuid("derivedone"),
                derived.uuid());

        // Taken 因重名提前跳过，不参与 UUID 统计；Blank 因昵称为空跳过
        assertEquals(3, report.uuidPreserved());
        assertEquals(1, report.uuidDerived());
    }

    @Test
    void dryRunDoesNotWriteAnything() throws Exception {
        var report = migrator.migrate(legacyDb, true);
        assertEquals(6, report.scanned());
        assertEquals(4, report.imported());
        assertEquals(1, report.skipped());
        assertEquals(1, report.unmapped());
        assertTrue(report.dryRun());
        assertEquals(1, storage.accounts().count(), "试运行不应写入");
    }

    @Test
    void migrationMapsAccountsPasswordsAndMetadata() throws Exception {
        var report = migrator.migrate(legacyDb, false);
        assertEquals(4, report.imported());
        assertEquals(2, report.needsReset());
        assertEquals(1, report.skipped());
        assertEquals(1, report.unmapped());
        assertEquals(1, report.emails());

        // 大小写保留、规范名小写、UUID 沿用源库原值
        var one = storage.accounts().findByNameLower("legacyone").orElseThrow();
        assertEquals("LegacyOne", one.name());
        assertEquals(UUID.nameUUIDFromBytes("OfflinePlayer:LegacyOne".getBytes()), one.uuid());
        // 关键：LibreLogin 的三列存储被还原成标准 bcrypt，且**原密码仍然可用**
        assertEquals(60, one.passwordHash().length());
        assertTrue(one.passwordHash().startsWith("$2a$"), one.passwordHash());
        assertEquals(one.passwordHash(), bcryptHash, "还原结果应与原始 bcrypt 串完全一致");
        assertTrue(hasher.verify("LegacyPass1", one.passwordHash()), "bcrypt 哈希应可直接复用");
        assertFalse(hasher.verify("WrongPass1", one.passwordHash()));
        assertEquals("survival", one.lastServer());
        assertTrue(one.lastLoginAt() != null && one.lastLoginAt() > 0, "last_seen 应被迁移");
        assertEquals("legacy1@qq.com",
                storage.bindings().find(one.id(), BindingType.EMAIL).orElseThrow().identifier());

        // Argon2ID 与"无密码"账号 → 占位哈希（bcrypt 校验必然失败）
        var two = storage.accounts().findByNameLower("legacytwo").orElseThrow();
        assertEquals(LibreLoginMigrator.NEEDS_RESET_HASH, two.passwordHash());
        var noPassword = storage.accounts().findByNameLower("nopassword").orElseThrow();
        assertEquals(LibreLoginMigrator.NEEDS_RESET_HASH, noPassword.passwordHash());

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
        assertEquals(5, second.skipped());
        assertEquals(1, second.unmapped());
        assertEquals(after, storage.accounts().count(), "重复迁移不应产生重复账号");
    }

    @Test
    void libreLoginBcryptFormatIsReassembled() {
        // 真实格式：algo=BCrypt-2A，salt 单独一列，hashed_password = "10$<31字符哈希>"
        String[] split = splitLibreLoginHash(bcryptHash);
        String reassembled = LibreLoginMigrator.toReusableBcrypt(split[1], split[0], "BCrypt-2A");
        assertEquals(bcryptHash, reassembled, "应还原出与原始完全一致的 60 字符 bcrypt 串");
        assertTrue(hasher.verify("LegacyPass1", reassembled));

        // 版本后缀 2B / 2Y 同样支持
        assertEquals(bcryptHash.replaceFirst("\\$2a\\$", "\\$2b\\$"),
                LibreLoginMigrator.toReusableBcrypt(split[1], split[0], "BCrypt-2B"));
        assertEquals(bcryptHash.replaceFirst("\\$2a\\$", "\\$2y\\$"),
                LibreLoginMigrator.toReusableBcrypt(split[1], split[0], "bcrypt-2y"));

        // 已是完整 bcrypt 串时原样保留
        assertEquals(bcryptHash, LibreLoginMigrator.toReusableBcrypt(bcryptHash, null, null));
        assertEquals(bcryptHash, LibreLoginMigrator.toReusableBcrypt("  " + bcryptHash + " ", "x", "BCrypt-2A"));
    }

    @Test
    void unsupportedFormatsAreRejected() {
        String[] split = splitLibreLoginHash(bcryptHash);
        // 非 bcrypt 算法
        assertNull(LibreLoginMigrator.toReusableBcrypt(split[1], split[0], "Argon-2ID"));
        assertNull(LibreLoginMigrator.toReusableBcrypt("5e884898da28047151d0e56f8dc6292773603d0d6",
                "somesalt", "SHA256"));
        // 缺列 / 空值 / 结构异常
        assertNull(LibreLoginMigrator.toReusableBcrypt(null, split[0], "BCrypt-2A"));
        assertNull(LibreLoginMigrator.toReusableBcrypt("", split[0], "BCrypt-2A"));
        assertNull(LibreLoginMigrator.toReusableBcrypt(split[1], null, "BCrypt-2A"));
        assertNull(LibreLoginMigrator.toReusableBcrypt(split[1], "", "BCrypt-2A"));
        assertNull(LibreLoginMigrator.toReusableBcrypt(split[1], split[0], null));
        assertNull(LibreLoginMigrator.toReusableBcrypt("10$onlyonepart", split[0], "BCrypt-2A"));
        // 长度不对（盐被截断）→ 结构校验拒绝，避免写入无法校验的哈希
        assertNull(LibreLoginMigrator.toReusableBcrypt(split[1], split[0].substring(0, 10), "BCrypt-2A"));
    }

    @Test
    void fullBcryptStructureCheck() {
        assertTrue(LibreLoginMigrator.isFullBcrypt(bcryptHash));
        assertFalse(LibreLoginMigrator.isFullBcrypt("$2a$10$tooshort"));
        assertFalse(LibreLoginMigrator.isFullBcrypt("$2x$10$" + "a".repeat(53)));
        assertFalse(LibreLoginMigrator.isFullBcrypt(null));
    }
}
