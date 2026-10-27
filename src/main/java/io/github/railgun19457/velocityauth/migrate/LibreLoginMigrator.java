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
                         int emails, int uuidPreserved, int uuidDerived, boolean dryRun,
                         String source) {
    }

    /**
     * 迁移来源。
     * <ul>
     *   <li>{@link #sqliteFile}：LibreLogin 的 SQLite 文件（以只读方式打开，绝不修改）</li>
     *   <li>{@link #jdbc}：任意 JDBC 数据源，例如 {@code jdbc:mysql://127.0.0.1:3306/librelogin}
     *       —— LibreLogin 生产环境常用 MariaDB/MySQL</li>
     * </ul>
     */
    public record Source(Path file, String jdbcUrl, String user, String password) {

        public static Source sqliteFile(Path file) {
            return new Source(file, null, null, null);
        }

        public static Source jdbc(String url, String user, String password) {
            return new Source(null, url, user, password);
        }

        public boolean isJdbc() {
            return jdbcUrl != null && !jdbcUrl.isBlank();
        }

        public String describe() {
            if (isJdbc()) {
                return jdbcUrl;
            }
            return file == null ? "(未指定)" : file.toString();
        }
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
        return migrate(Source.sqliteFile(database), dryRun);
    }

    public Report migrate(Source source, boolean dryRun) throws IOException, SQLException {
        String url;
        if (source.isJdbc()) {
            url = source.jdbcUrl();
        } else {
            Path database = source.file();
            if (database == null || !Files.isRegularFile(database)) {
                throw new IOException("找不到 LibreLogin 数据库："
                        + (database == null ? "(未指定)" : database.toAbsolutePath().toString()));
            }
            // 只读打开，避免误改旧库
            url = "jdbc:sqlite:file:" + database.toAbsolutePath().toString().replace('\\', '/') + "?mode=ro";
        }
        try (Connection connection = open(url, source.user(), source.password())) {
            String table = detectTable(connection);
            if (table == null) {
                throw new SQLException("未在数据源中找到 LibreLogin 表"
                        + "（期望 librepremium_data 或含 hashed_password 的表）：" + source.describe());
            }
            log.info("从 {} 的 {} 表迁移", source.describe(), table);
            return migrateTable(connection, table, dryRun, source.describe());
        }
    }

    private static Connection open(String url, String user, String password) throws SQLException {
        if (user == null || user.isEmpty()) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, user, password == null ? "" : password);
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

    private Report migrateTable(Connection source, String table, boolean dryRun, String sourceName)
            throws SQLException {
        List<String> columns = columnsOf(source, table);
        String uuidColumn = pick(columns, "uuid").orElse(null);
        String nameColumn = pick(columns, "last_nickname", "nickname", "name", "username").orElse(null);
        String hashColumn = pick(columns, "hashed_password", "password", "password_hash").orElse(null);
        String algoColumn = pick(columns, "algo", "algorithm").orElse(null);
        String premiumColumn = pick(columns, "premium_uuid").orElse(null);
        String emailColumn = pick(columns, "email").orElse(null);
        String lastServerColumn = pick(columns, "last_server").orElse(null);
        String saltColumn = pick(columns, "salt").orElse(null);
        String lastSeenColumn = pick(columns, "last_seen").orElse(null);

        if (nameColumn == null || hashColumn == null) {
            throw new SQLException("LibreLogin 表缺少必要列（name/hash）：columns=" + columns);
        }

        int scanned = 0;
        int imported = 0;
        int uuidPreserved = 0;
        int uuidDerived = 0;
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
                String rawHash = rs.getString(hashColumn);
                String algo = algoColumn == null ? null : rs.getString(algoColumn);
                String salt = saltColumn == null ? null : rs.getString(saltColumn);
                String reusableHash = toReusableBcrypt(rawHash, salt, algo);
                String storedHash = reusableHash != null ? reusableHash : NEEDS_RESET_HASH;
                if (reusableHash == null) {
                    needsReset++;
                }
                // 关键：保留源库的 UUID。
                // LibreLogin 的 uuid 由「名字原样（大小写敏感）」派生，而本插件新注册用的是
                // 「小写规范名」，两者对含大写字母的名字并不相同。迁移必须沿用原 UUID，
                // 否则 LuckPerms 权限组、经济、领地等所有按 UUID 存的数据都会对不上。
                java.util.UUID legacyUuid = uuidColumn == null ? null : safeUuid(rs.getString(uuidColumn));
                java.util.UUID accountUuid;
                if (legacyUuid != null) {
                    accountUuid = legacyUuid;
                    uuidPreserved++;
                } else {
                    accountUuid = ProfileFactory.offlineUuid(name);
                    uuidDerived++;
                }
                if (storage.accounts().findByUuid(accountUuid).isPresent()) {
                    skipped++;
                    continue;
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
                        accountUuid, storedHash, AccountStatus.ACTIVE,
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
        return new Report(scanned, imported, skipped, needsReset, unmapped, emails, uuidPreserved,
                uuidDerived, dryRun, sourceName);
    }

    /**
     * 把 LibreLogin 的密码列还原成标准 bcrypt 串。
     * <p>
     * LibreLogin（{@code CryptoUtil.convertFromBCryptRaw} / {@code rawBcryptFromHashed}）把
     * bcrypt 结果拆成三列存储：
     * <pre>
     *   $2a$10$&lt;22 字符盐&gt;&lt;31 字符哈希&gt;
     *   ├ algo   = "BCrypt-2A"        （版本后缀，大写）
     *   ├ salt   = &lt;22 字符盐&gt;         （单独一列）
     *   └ hashed_password = "10$&lt;31 字符哈希&gt;"   （cost + '$' + 哈希，**不带 $2a$ 前缀**）
     * </pre>
     * 校验时再拼回 {@code "$" + 版本 + "$" + hashed_password（把 salt 插回 $ 之后）}。
     * 另外也兼容 hashed_password 本身就是完整 {@code $2a$...} 串的情况。
     *
     * @return 可被本插件 bcrypt 校验的标准 60 字符哈希；无法复用则返回 null
     */
    static String toReusableBcrypt(String hashedPassword, String salt, String algo) {
        if (hashedPassword == null || hashedPassword.isBlank()) {
            return null;
        }
        String stored = hashedPassword.trim();
        // 已是完整 bcrypt 串（部分版本/其它插件迁移过来的数据）
        if (isFullBcrypt(stored)) {
            return stored;
        }
        if (algo == null) {
            return null;
        }
        String upper = algo.trim().toUpperCase(Locale.ROOT);
        if (!upper.startsWith("BCRYPT")) {
            return null;
        }
        String suffix = upper.length() > "BCRYPT-".length()
                ? upper.substring("BCRYPT-".length()).toLowerCase(Locale.ROOT)
                : "2a";
        String[] parts = stored.split("\\$");
        if (parts.length != 2 || salt == null || salt.isBlank()) {
            return null;
        }
        String full = "$" + suffix + "$" + parts[0] + "$" + salt.trim() + parts[1];
        return isFullBcrypt(full) ? full : null;
    }

    /** 结构校验：$2a/$2b/$2y + 两位 cost + 53 字符（22 盐 + 31 哈希），共 60 字符。 */
    static boolean isFullBcrypt(String value) {
        return value != null && value.matches("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");
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
