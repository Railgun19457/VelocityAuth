package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.storage.model.LoginHistory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code login_history} 表访问：登录 / 失败 / 登出记录。
 */
public final class LoginHistoryRepository {

    private static final String COLUMNS = "id, account_id, at, ip, result, identity, detail";

    private final Database database;

    public LoginHistoryRepository(Database database) {
        this.database = database;
    }

    public void insert(Long accountId, long at, String ip, String result, String identity, String detail) {
        database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO login_history (account_id, at, ip, result, identity, detail)"
                            + " VALUES (?, ?, ?, ?, ?, ?)")) {
                if (accountId == null) {
                    ps.setNull(1, Types.BIGINT);
                } else {
                    ps.setLong(1, accountId);
                }
                ps.setLong(2, at);
                if (ip == null) {
                    ps.setNull(3, Types.VARCHAR);
                } else {
                    ps.setString(3, ip);
                }
                ps.setString(4, result);
                if (identity == null) {
                    ps.setNull(5, Types.VARCHAR);
                } else {
                    ps.setString(5, truncate(identity, 128));
                }
                if (detail == null) {
                    ps.setNull(6, Types.VARCHAR);
                } else {
                    ps.setString(6, truncate(detail, 255));
                }
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * 某账号的登录历史，可按结果与起始时间过滤，按时间倒序。
     *
     * @param result 结果过滤（{@code null} 表示不过滤）
     * @param since  起始时间（unix 秒，{@code <=0} 表示不过滤）
     */
    public List<LoginHistory> findByAccountId(long accountId, String result, long since, int limit) {
        return database.withConnection(connection -> {
            StringBuilder sql = new StringBuilder("SELECT " + COLUMNS
                    + " FROM login_history WHERE account_id = ?");
            if (result != null) {
                sql.append(" AND result = ?");
            }
            if (since > 0) {
                sql.append(" AND at >= ?");
            }
            sql.append(" ORDER BY at DESC, id DESC LIMIT ?");
            try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
                int index = 1;
                ps.setLong(index++, accountId);
                if (result != null) {
                    ps.setString(index++, result);
                }
                if (since > 0) {
                    ps.setLong(index++, since);
                }
                ps.setInt(index, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<LoginHistory> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    /** 曾用某 IP 登录过的账号 id（去重，倒序按最近出现）。 */
    public List<Long> distinctAccountIdsByIp(String ip, int limit) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT account_id FROM login_history WHERE ip = ? AND account_id IS NOT NULL"
                            + " GROUP BY account_id ORDER BY MAX(at) DESC LIMIT ?")) {
                ps.setString(1, ip);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Long> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(rs.getLong("account_id"));
                    }
                    return out;
                }
            }
        });
    }

    /** 删除 {@code at < cutoff} 的历史；返回删除行数。 */
    public int prune(long cutoff) {
        return database.inTransaction(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM login_history WHERE at < ?")) {
                ps.setLong(1, cutoff);
                return ps.executeUpdate();
            }
        });
    }

    public int count() {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM login_history");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    /** 结果等于 {@code result} 且时间不早于 {@code since}（unix 秒）的记录数。 */
    public int countByResultSince(String result, long since) {
        return database.withConnection(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM login_history WHERE result = ? AND at >= ?")) {
                ps.setString(1, result);
                ps.setLong(2, since);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static LoginHistory map(ResultSet rs) throws SQLException {
        long accountRaw = rs.getLong("account_id");
        Long accountId = rs.wasNull() ? null : accountRaw;
        return new LoginHistory(
                rs.getLong("id"),
                accountId,
                rs.getLong("at"),
                rs.getString("ip"),
                rs.getString("result"),
                rs.getString("identity"),
                rs.getString("detail"));
    }
}
