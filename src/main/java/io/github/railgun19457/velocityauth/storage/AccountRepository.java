package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code accounts} 表访问。
 */
public final class AccountRepository {

    private static final String COLUMNS =
            "id, name, name_lower, uuid, password_hash, status, last_server,"
                    + " created_at, last_login_at, last_ip";

    private final Database database;

    public AccountRepository(Database database) {
        this.database = database;
    }

    public Optional<Account> findById(long id) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM accounts WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Account>empty();
                }
            }
        });
    }

    public Optional<Account> findByNameLower(String nameLower) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM accounts WHERE name_lower = ?")) {
                ps.setString(1, nameLower);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Account>empty();
                }
            }
        });
    }

    public Optional<Account> findByUuid(UUID uuid) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM accounts WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Account>empty();
                }
            }
        });
    }

    public boolean existsByNameLower(String nameLower) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT 1 FROM accounts WHERE name_lower = ?")) {
                ps.setString(1, nameLower);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public List<Account> findAll() {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM accounts ORDER BY name_lower")) {
                try (ResultSet rs = ps.executeQuery()) {
                    List<Account> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    public List<Account> findByNamePrefix(String prefixLower, int limit) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM accounts WHERE name_lower LIKE ? ORDER BY name_lower LIMIT ?")) {
                ps.setString(1, prefixLower + "%");
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Account> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    /** 最近一次登录 IP 为 {@code ip} 的账号（用于按 IP 反查）。 */
    public List<Account> findByLastIp(String ip, int limit) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM accounts WHERE last_ip = ? ORDER BY last_login_at DESC LIMIT ?")) {
                ps.setString(1, ip);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Account> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    // ------------------------------------------------------------------ 管理端检索

    /**
     * 管理端账号检索的筛选条件。所有字段可空；{@code keyword} 会匹配账号名、UUID、
     * 绑定标识与最近登录 IP。
     */
    public record Search(
            String keyword,
            String status,
            String provider,
            String providerIdentifier,
            Long createdAfter,
            Long createdBefore,
            Long lastLoginAfter,
            Long lastLoginBefore,
            boolean onlineOnly,
            List<Long> onlineAccountIds,
            String sort,
            int offset,
            int limit
    ) {
    }

    /** 按条件分页检索账号（管理端列表）。 */
    public List<Account> search(Search spec) {
        return database.withConnection(connection -> {
            List<Object> params = new ArrayList<>();
            String where = where(spec, params);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM accounts" + where
                            + " ORDER BY " + orderBy(spec.sort()) + " LIMIT ? OFFSET ?")) {
                int index = bind(ps, params);
                ps.setInt(index++, Math.max(1, spec.limit()));
                ps.setInt(index, Math.max(0, spec.offset()));
                try (ResultSet rs = ps.executeQuery()) {
                    List<Account> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    /** 与 {@link #search} 同条件的总条数。 */
    public int countSearch(Search spec) {
        return database.withConnection(connection -> {
            List<Object> params = new ArrayList<>();
            String where = where(spec, params);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM accounts" + where)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    private static String where(Search spec, List<Object> params) {
        List<String> conditions = new ArrayList<>();
        if (spec.keyword() != null && !spec.keyword().isBlank()) {
            String like = "%" + spec.keyword().trim().toLowerCase(java.util.Locale.ROOT) + "%";
            String raw = "%" + spec.keyword().trim() + "%";
            conditions.add("(name_lower LIKE ? OR uuid LIKE ? OR last_ip LIKE ?"
                    + " OR id IN (SELECT account_id FROM bindings WHERE identifier LIKE ?))");
            params.add(like);
            params.add(like);
            params.add(raw);
            params.add(raw);
        }
        if (spec.status() != null && !spec.status().isBlank()) {
            conditions.add("status = ?");
            params.add(spec.status().toLowerCase(java.util.Locale.ROOT));
        }
        if (spec.provider() != null && !spec.provider().isBlank()) {
            if (spec.providerIdentifier() != null && !spec.providerIdentifier().isBlank()) {
                conditions.add("id IN (SELECT account_id FROM bindings WHERE type = ? AND identifier = ?)");
                params.add(spec.provider().toLowerCase(java.util.Locale.ROOT));
                params.add(spec.providerIdentifier());
            } else {
                conditions.add("id IN (SELECT account_id FROM bindings WHERE type = ?)");
                params.add(spec.provider().toLowerCase(java.util.Locale.ROOT));
            }
        }
        if (spec.createdAfter() != null && spec.createdAfter() > 0) {
            conditions.add("created_at >= ?");
            params.add(spec.createdAfter());
        }
        if (spec.createdBefore() != null && spec.createdBefore() > 0) {
            conditions.add("created_at <= ?");
            params.add(spec.createdBefore());
        }
        if (spec.lastLoginAfter() != null && spec.lastLoginAfter() > 0) {
            conditions.add("last_login_at >= ?");
            params.add(spec.lastLoginAfter());
        }
        if (spec.lastLoginBefore() != null && spec.lastLoginBefore() > 0) {
            conditions.add("last_login_at <= ?");
            params.add(spec.lastLoginBefore());
        }
        if (spec.onlineOnly()) {
            List<Long> ids = spec.onlineAccountIds();
            if (ids == null || ids.isEmpty()) {
                // 没有任何在线账号：直接让条件不成立，避免拼出非法 SQL
                conditions.add("1 = 0");
            } else {
                conditions.add("id IN (" + String.join(",",
                        java.util.Collections.nCopies(ids.size(), "?")) + ")");
                params.addAll(ids);
            }
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    /** 排序白名单：只允许固定几列，避免 SQL 注入。 */
    private static String orderBy(String sort) {
        String key = sort == null ? "" : sort.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (key) {
            case "created_at", "created", "created-asc" -> "created_at ASC";
            case "-created_at", "-created", "created-desc" -> "created_at DESC";
            case "last_login_at", "last-login", "last-login-asc" -> "last_login_at ASC";
            case "-last_login_at", "-last-login", "last-login-desc" -> "last_login_at DESC";
            case "id", "id-asc" -> "id ASC";
            case "-id", "id-desc" -> "id DESC";
            case "name", "name-asc" -> "name_lower ASC";
            default -> "name_lower ASC";
        };
    }

    private static int bind(PreparedStatement ps, List<Object> params) throws SQLException {
        int index = 1;
        for (Object param : params) {
            if (param instanceof Long value) {
                ps.setLong(index++, value);
            } else {
                ps.setString(index++, String.valueOf(param));
            }
        }
        return index;
    }

    public long insert(String name, String nameLower, UUID uuid, String passwordHash,
                       AccountStatus status, long createdAt) {
        return database.inTransaction(connection -> insert(connection, name, nameLower, uuid, passwordHash,
                status, createdAt));
    }

    /**
     * 在调用方提供的事务连接内插入账号（供注册流程把账号 + 身份 + 绑定写入同一事务）。
     * 不在此方法内提交/回滚。
     */
    long insert(Connection connection, String name, String nameLower, UUID uuid, String passwordHash,
                AccountStatus status, long createdAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO accounts (name, name_lower, uuid, password_hash, status,"
                        + " last_server, created_at, last_login_at, last_ip)"
                        + " VALUES (?, ?, ?, ?, ?, NULL, ?, NULL, NULL)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, nameLower);
            ps.setString(3, uuid.toString());
            ps.setString(4, passwordHash);
            ps.setString(5, status.dbValue());
            ps.setLong(6, createdAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new StorageException("插入账号后未返回主键");
    }

    public void updatePasswordHash(long id, String passwordHash) {
        update("UPDATE accounts SET password_hash = ? WHERE id = ?", ps -> {
            ps.setString(1, passwordHash);
            ps.setLong(2, id);
        });
    }

    public void updateStatus(long id, AccountStatus status) {
        update("UPDATE accounts SET status = ? WHERE id = ?", ps -> {
            ps.setString(1, status.dbValue());
            ps.setLong(2, id);
        });
    }

    /** 更新状态并返回受影响行数（管理端需要判断账号是否仍存在）。 */
    public int updateStatusReturning(long id, AccountStatus status) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "UPDATE accounts SET status = ? WHERE id = ?")) {
                ps.setString(1, status.dbValue());
                ps.setLong(2, id);
                return ps.executeUpdate();
            }
        });
    }

    public void updateLastServer(long id, String server) {
        update("UPDATE accounts SET last_server = ? WHERE id = ?", ps -> {
            if (server == null) {
                ps.setNull(1, java.sql.Types.VARCHAR);
            } else {
                ps.setString(1, server);
            }
            ps.setLong(2, id);
        });
    }

    public void updateLastLogin(long id, long at, String ip) {
        update("UPDATE accounts SET last_login_at = ?, last_ip = ? WHERE id = ?", ps -> {
            ps.setLong(1, at);
            if (ip == null) {
                ps.setNull(2, java.sql.Types.VARCHAR);
            } else {
                ps.setString(2, ip);
            }
            ps.setLong(3, id);
        });
    }

    public int delete(long id) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM accounts WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate();
            }
        });
    }

    /** 仍处于 {@code pending_binding} 且注册时间早于 {@code cutoff}（unix 秒）的账号。 */
    public List<Account> findPendingOlderThan(long cutoff) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM accounts WHERE status = ? AND created_at < ?")) {
                ps.setString(1, AccountStatus.PENDING_BINDING.dbValue());
                ps.setLong(2, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Account> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    public int count() {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM accounts");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /** 按状态分组的账号数（status → count）。 */
    public Map<String, Integer> countByStatus() {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT status, COUNT(*) FROM accounts GROUP BY status");
                 ResultSet rs = ps.executeQuery()) {
                Map<String, Integer> out = new java.util.LinkedHashMap<>();
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getInt(2));
                }
                return out;
            }
        });
    }

    /** 注册时间不早于 {@code since}（unix 秒）的账号数。 */
    public int countCreatedSince(long since) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM accounts WHERE created_at >= ?")) {
                ps.setLong(1, since);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    private void update(String sql, StatementBinder binder) {
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                binder.bind(ps);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static Account map(ResultSet rs) throws SQLException {
        long lastLoginRaw = rs.getLong("last_login_at");
        Long lastLogin = rs.wasNull() ? null : lastLoginRaw;
        return new Account(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("name_lower"),
                UUID.fromString(rs.getString("uuid")),
                rs.getString("password_hash"),
                AccountStatus.fromDb(rs.getString("status")),
                rs.getString("last_server"),
                rs.getLong("created_at"),
                lastLogin,
                rs.getString("last_ip"));
    }

    @FunctionalInterface
    interface StatementBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}
