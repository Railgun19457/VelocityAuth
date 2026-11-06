package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.storage.model.AuditEntry;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code audit_log} 表访问。
 */
public final class AuditRepository {

    private static final String COLUMNS =
            "id, at, account_id, action, ip, detail, actor, source, client_id";

    private final Database database;

    public AuditRepository(Database database) {
        this.database = database;
    }

    public void log(long at, Long accountId, String action, String ip, String detail) {
        log(at, accountId, action, ip, detail, null, null, null);
    }

    /**
     * 写入一条审计记录。
     *
     * @param actor    操作者（可空）
     * @param source   来源（可空，见 {@code AuditEntry.SOURCE_*}）
     * @param clientId 发起操作的 bot 客户端 id（可空）
     */
    public void log(long at, Long accountId, String action, String ip, String detail,
                    String actor, String source, String clientId) {
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO audit_log (at, account_id, action, ip, detail, actor, source, client_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, at);
                if (accountId == null) {
                    ps.setNull(2, Types.BIGINT);
                } else {
                    ps.setLong(2, accountId);
                }
                ps.setString(3, action);
                if (ip == null) {
                    ps.setNull(4, Types.VARCHAR);
                } else {
                    ps.setString(4, ip);
                }
                if (detail == null) {
                    ps.setNull(5, Types.VARCHAR);
                } else {
                    ps.setString(5, truncate(detail));
                }
                if (actor == null) {
                    ps.setNull(6, Types.VARCHAR);
                } else {
                    ps.setString(6, truncate(actor, 128));
                }
                if (source == null) {
                    ps.setNull(7, Types.VARCHAR);
                } else {
                    ps.setString(7, source);
                }
                if (clientId == null) {
                    ps.setNull(8, Types.VARCHAR);
                } else {
                    ps.setString(8, truncate(clientId, 64));
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    public List<AuditEntry> recent(int limit) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM audit_log ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    return read(rs);
                }
            }
        });
    }

    public List<AuditEntry> findByAccountId(long accountId, int limit) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM audit_log"
                            + " WHERE account_id = ? ORDER BY id DESC LIMIT ?")) {
                ps.setLong(1, accountId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    return read(rs);
                }
            }
        });
    }

    /**
     * 条件检索（管理页用）。所有条件都是可选的，{@code null} 表示不过滤。
     *
     * @param accountId 目标账号 id
     * @param action    动作标识（精确匹配）
     * @param actor     操作者（精确匹配）
     * @param source    来源（精确匹配）
     * @param since     起始时间（unix 秒，{@code <=0} 不过滤）
     * @param until     截止时间（unix 秒，{@code <=0} 不过滤）
     */
    public List<AuditEntry> search(Long accountId, String action, String actor, String source,
                                   long since, long until, int offset, int limit) {
        return database.withConnection(connection -> {
            List<Object> params = new ArrayList<>();
            String where = where(accountId, action, actor, source, since, until, params);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM audit_log" + where
                            + " ORDER BY id DESC LIMIT ? OFFSET ?")) {
                int index = bind(ps, params);
                ps.setInt(index++, limit);
                ps.setInt(index, Math.max(0, offset));
                try (ResultSet rs = ps.executeQuery()) {
                    return read(rs);
                }
            }
        });
    }

    /** 与 {@link #search} 同条件的总条数（分页用）。 */
    public int countSearch(Long accountId, String action, String actor, String source,
                           long since, long until) {
        return database.withConnection(connection -> {
            List<Object> params = new ArrayList<>();
            String where = where(accountId, action, actor, source, since, until, params);
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM audit_log" + where)) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    public int count() {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM audit_log");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /** 删除 {@code at < cutoff} 的审计记录；返回删除行数。 */
    public int prune(long cutoff) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM audit_log WHERE at < ?")) {
                ps.setLong(1, cutoff);
                return ps.executeUpdate();
            }
        });
    }

    private static String where(Long accountId, String action, String actor, String source,
                                long since, long until, List<Object> params) {
        List<String> conditions = new ArrayList<>();
        if (accountId != null) {
            conditions.add("account_id = ?");
            params.add(accountId);
        }
        if (action != null && !action.isBlank()) {
            conditions.add("action = ?");
            params.add(action);
        }
        if (actor != null && !actor.isBlank()) {
            conditions.add("actor = ?");
            params.add(actor);
        }
        if (source != null && !source.isBlank()) {
            conditions.add("source = ?");
            params.add(source);
        }
        if (since > 0) {
            conditions.add("at >= ?");
            params.add(since);
        }
        if (until > 0) {
            conditions.add("at <= ?");
            params.add(until);
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
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

    private static List<AuditEntry> read(ResultSet rs) throws SQLException {
        List<AuditEntry> out = new ArrayList<>();
        while (rs.next()) {
            out.add(map(rs));
        }
        return out;
    }

    private static String truncate(String value) {
        return truncate(value, 500);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static AuditEntry map(ResultSet rs) throws SQLException {
        long accountRaw = rs.getLong("account_id");
        Long accountId = rs.wasNull() ? null : accountRaw;
        return new AuditEntry(
                rs.getLong("id"),
                rs.getLong("at"),
                accountId,
                rs.getString("action"),
                rs.getString("ip"),
                rs.getString("detail"),
                rs.getString("actor"),
                rs.getString("source"),
                rs.getString("client_id"));
    }
}
