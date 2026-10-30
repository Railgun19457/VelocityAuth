package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import io.github.railgun19457.velocityauth.storage.model.BindingType;
import io.github.railgun19457.velocityauth.storage.model.IdentityKind;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 数据层聚合入口：连接池 + 各表仓库。
 */
public final class StorageService implements AutoCloseable {

    private final Database database;
    private final AccountRepository accounts;
    private final IdentityRepository identities;
    private final BindingRepository bindings;
    private final SessionRepository sessions;
    private final AuditRepository audit;

    private StorageService(Database database) {
        this.database = database;
        this.accounts = new AccountRepository(database);
        this.identities = new IdentityRepository(database);
        this.bindings = new BindingRepository(database);
        this.sessions = new SessionRepository(database);
        this.audit = new AuditRepository(database);
    }

    public static StorageService open(VelocityAuthConfig.Database config, Path dataDirectory, Logger logger)
            throws SQLException {
        Database database = Database.open(config, dataDirectory, logger);
        Schema.migrate(database, logger);
        return new StorageService(database);
    }

    public Database database() {
        return database;
    }

    /**
     * 原子地建号：账号 + 初始身份（+ 可选邮箱/QQ 绑定）写入**同一事务**，任一步失败整体回滚。
     * <p>
     * 注册（无门槛与带验证门槛两条路径）都走这里，避免出现"有账号但无身份/无绑定"的半成品。
     *
     * @return 新账号 id
     */
    public long createAccount(String name, String nameLower, UUID uuid, String passwordHash,
                              AccountStatus status, UUID premiumUuid, long createdAt,
                              IdentityKind identityKind, String identityIdentifier,
                              String email, String qq) {
        return database.inTransaction(connection -> {
            long accountId = accounts.insert(connection, name, nameLower, uuid, passwordHash, status,
                    premiumUuid, createdAt);
            identities.upsert(connection, accountId, identityKind, identityIdentifier, createdAt);
            if (email != null) {
                bindings.insert(connection, accountId, BindingType.EMAIL, email, createdAt, createdAt);
            }
            if (qq != null) {
                bindings.insert(connection, accountId, BindingType.QQ, qq, createdAt, createdAt);
            }
            return accountId;
        });
    }

    public AccountRepository accounts() {
        return accounts;
    }

    public IdentityRepository identities() {
        return identities;
    }

    public BindingRepository bindings() {
        return bindings;
    }

    public SessionRepository sessions() {
        return sessions;
    }

    public AuditRepository audit() {
        return audit;
    }

    public String describe() {
        return database.description();
    }

    public boolean ping() {
        return database.ping();
    }

    @Override
    public void close() {
        database.close();
    }
}
