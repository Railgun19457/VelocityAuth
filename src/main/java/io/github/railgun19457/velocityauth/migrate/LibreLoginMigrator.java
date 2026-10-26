package io.github.railgun19457.velocityauth.migrate;

import io.github.railgun19457.velocityauth.auth.EmailRules;
import io.github.railgun19457.velocityauth.auth.ProfileFactory;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import io.github.railgun19457.velocityauth.storage.model.AuditEntry;
import io.github.railgun19457.velocityauth.storage.model.BindingType;
import io.github.railgun19457.velocityauth.storage.model.IdentityKind;
import io.github.railgun19457.velocityauth.util.PluginLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * LibreLogin（kyngs/LibreLogin，旧名 LibrePremium）迁移工具（DESIGN 7.3）。
 * <p>
 * 目标表结构（LibreLogin 的 SQL 提供者）：
 * <pre>
 * librepremium_data(
 *   uuid VARCHAR(255) PRIMARY KEY, premium_uuid VARCHAR(255) UNIQUE,
 *   hashed_password VARCHAR(255), salt VARCHAR(255), algo VARCHAR(255),
 *   last_nickname VARCHAR(255) NOT NULL UNIQUE,
 *   joined TIMESTAMP, last_seen TIMESTAMP, last_server VARCHAR(255),
 *   secret, ip, last_authentication, email )
 * </pre>
 * 映射规则：
 * <ul>
 *   <li>账号名 = {@code last_nickname}（保留大小写），规范名 = 小写</li>
 *   <li>规范 UUID = {@code OfflinePlayer:小写名}（**不沿用 LibreLogin 的 uuid**，见 ADR-2）</li>
 *   <li>密码：{@code algo=BCRYPT} 且哈希形如 {@code $2a$/$2b$/$2y$} → 直接复用；
 *       其它算法（SHA256/SHA512/…）无法转换 → 写入占位哈希并计入"需重置"</li>
 *   <li>邮箱 → bindings(email)；last_server → accounts.last_server；
 *       last_seen → accounts.last_login_at；premium_uuid → accounts.premium_uuid</li>
 *   <li>同时写入 identities(java, 小写名)，让玩家用原连接名登录后即可命中账号</li>
 * </ul>
 * 迁移**绝不自动执行**，必须由管理员显式运行 {@code /vauth migrate librelogin}。
 */
public final class LibreLoginMigrator {

    /** 无法转换的密码占位值（bcrypt 校验必然失败，需要管理员/找回流程重置）。 */
    public static final String NEEDS_RESET_HASH = "!needs-reset";

    private static final List<String> CANDIDATE_FILES = List.of(
            "librelogin/librepremium_data.db",
            "librelogin/librelogin.db",
            "librelogin/database.db",
            "LibreLogin/database.db",
            "LibreLogin/librelogin.db");

    private final StorageService storage;
    private final PluginLogger log;

    public LibreLoginMigrator(StorageService storage, PluginLogger log) {
        this.storage = storage;
        this.log = log;
    }

    public record Report(int scanned, int imported, int skipped, int needsReset, int unmapped,
                         int emails, boolean dryRun, Path source) {
    }

    /** 在插件数据目录的上一级（即 plugins/）里寻找 LibreLogin 的 SQLite 文件。 */
    public static Optional<Path> findDefaultDatabase(Path dataDirectory) {
        Path plugins = dataDirectory.getParent();
        if (plugins == null) {
            return Optional.empty();
        }
        for (String candidate : CANDIDATE_FILES) {
            Path path = plugins.resolve(candidate);
            if (Files.isRegularFile(path)) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    public Report migrate(Path database, boolean dryRun) throws IOException, SQLException {
        if (!Files.isRegularFile(database)) {
            throw new IOException("找不到 LibreLogin 数据库：" + database.toAbsolutePath());
        }
        // 只读打开，避免误改旧库
        String url = "jdbc:sqlite:file:" + database.toAbsolutePath().toString().replace('\\', '/') + "?mode=ro";
        try (Connection source = DriverManager.getConnection(url)) {
            String table = detectTable(source);
            if (table == null) {
                throw new SQLException("未在数据库中找到 LibreLogin 表（期望 librepremium_data 或含 hashed_password 的表）");
            }
            log.info("从 {} 的 {} 表迁移", database.getFileName(), table);
            return migrateTable(source, table, dryRun, database);
        }
    }

    private String detectTable(Connection source) throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        try (ResultSet rs = source.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        for (String name : tables) {
            if (name.equalsIgnoreCase("librepremium_data")) {
                return name;
            }
        }
        for (String name : tables) {
            if (columnsOf(source, name).stream().anyMatch(c -> c.equalsIgnoreCase("hashed_password"))) {
                return name;
            }
        }
        return null;
    }

    private List<String> columnsOf(Connection source, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rs = source.getMetaData().getColumns(null, null, table, "%")) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    private Report migrateTable(Connection source, String table, boolean dryRun, Path file)
            throws SQLException {
        List<String> columns = columnsOf(source, table);
        String uuidColumn = pick(columns, "uuid").orElse(null);
        String nameColumn = pick(columns, "last_nickname", "nickname", "name", "username").orElse(null);
        String hashColumn = pick(columns, "hashed_password", "password", "password_hash").orElse(null);
        String algoColumn = pick(columns, "algo", "algorithm").orElse(null);
        String premiumColumn = pick(columns, "premium_uuid").orElse(null);
        String emailColumn = pick(columns, "email").orElse(null);
        String lastServerColumn = pick(columns, "last_server").orElse(null);
        String lastSeenColumn = pick(columns, "last_seen").orElse(null);

        if (nameColumn == null || hashColumn == null) {
            throw new SQLException("LibreLogin 表缺少必要列（name/hash）：columns=" + columns);
        }

        int scanned = 0;
        int imported = 0;
        int skipped = 0;
        int needsReset = 0;
        int unmapped = 0;
        int emails = 0;
        long now = io.github.railgun19457.velocityauth.auth.AuthService.nowSeconds();

        String sql = "SELECT * FROM " + table;
        try (Statement statement = source.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                scanned++;
                String name = rs.getString(nameColumn);
                if (name == null || name.isBlank()) {
                    unmapped++;
                    continue;
                }
                name = name.trim();
                String nameLower = name.toLowerCase(Locale.ROOT);
                if (storage.accounts().existsByNameLower(nameLower)) {
                    skipped++;
                    continue;
                }
                String hash = rs.getString(hashColumn);
                String algo = algoColumn == null ? null : rs.getString(algoColumn);
                boolean reusable = isReusableBcrypt(hash, algo);
                String storedHash = reusable ? hash : NEEDS_RESET_HASH;
                if (!reusable) {
                    needsReset++;
                }
                String premium = premiumColumn == null ? null : rs.getString(premiumColumn);
                String email = emailColumn == null ? null : EmailRules.normalize(rs.getString(emailColumn));
                String lastServer = lastServerColumn == null ? null : rs.getString(lastServerColumn);
                Long lastSeen = lastSeenColumn == null ? null : epochOf(rs, lastSeenColumn);

                if (dryRun) {
                    imported++;
                    if (email != null && !email.isBlank()) {
                        emails++;
                    }
                    continue;
                }
                long accountId = storage.accounts().insert(name, nameLower,
                        ProfileFactory.offlineUuid(nameLower), storedHash, AccountStatus.ACTIVE,
                        premium == null || premium.isBlank() ? null : safeUuid(premium), now);
                storage.identities().upsert(accountId, IdentityKind.JAVA, nameLower, now);
                if (lastServer != null && !lastServer.isBlank()) {
                    storage.accounts().updateLastServer(accountId, lastServer);
                }
                if (lastSeen != null && lastSeen > 0) {
                    storage.accounts().updateLastLogin(accountId, lastSeen, null);
                }
                if (email != null && !email.isBlank() && EmailRules.isValid(email)) {
                    storage.bindings().insert(accountId, BindingType.EMAIL, email, now, now);
                    emails++;
                }
                imported++;
            }
        }
        if (!dryRun) {
            storage.audit().log(now, null, AuditEntry.MIGRATE, "console",
                    "librelogin imported=" + imported + " skipped=" + skipped + " needs_reset=" + needsReset);
        }
        return new Report(scanned, imported, skipped, needsReset, unmapped, emails, dryRun, file);
    }

    /** LibreLogin 的 bcrypt：algo 标注为 BCRYPT（或哈希本身带 $2 前缀）。 */
    static boolean isReusableBcrypt(String hash, String algo) {
        if (hash == null || hash.isBlank()) {
            return false;
        }
        if (algo != null && algo.toUpperCase(Locale.ROOT).contains("BCRYPT") && hash.startsWith("$2")) {
            return true;
        }
        return hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$");
    }

    private static java.util.UUID safeUuid(String value) {
        try {
            return java.util.UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Long epochOf(ResultSet rs, String column) {
        try {
            Object value = rs.getObject(column);
            if (value == null) {
                return null;
            }
            if (value instanceof Number number) {
                long raw = number.longValue();
                // 毫秒 → 秒
                return raw > 100_000_000_000L ? raw / 1000L : raw;
            }
            String text = String.valueOf(value);
            if (text.isBlank()) {
                return null;
            }
            // 尝试解析 "yyyy-MM-dd HH:mm:ss" / ISO-8601
            String normalized = text.replace('T', ' ').replace("Z", "").trim();
            if (normalized.length() >= 19) {
                normalized = normalized.substring(0, 19);
            }
            try {
                return java.time.LocalDateTime
                        .parse(normalized, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
            } catch (RuntimeException ignored) {
                return null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    private static Optional<String> pick(List<String> columns, String... candidates) {
        for (String candidate : candidates) {
            for (String column : columns) {
                if (column.equalsIgnoreCase(candidate)) {
                    return Optional.of(column);
                }
            }
        }
        return Optional.empty();
    }

}
