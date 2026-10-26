package io.github.railgun19457.velocityauth.storage;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.SQLException;

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
