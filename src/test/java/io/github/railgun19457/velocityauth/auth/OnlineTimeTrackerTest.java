package io.github.railgun19457.velocityauth.auth;

import com.velocitypowered.api.proxy.Player;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.StorageException;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class OnlineTimeTrackerTest {

    @TempDir
    Path dir;

    private StorageService openStorage() throws Exception {
        return StorageService.open(new VelocityAuthConfig.Database(
                VelocityAuthConfig.Database.Type.SQLITE,
                new VelocityAuthConfig.Sqlite("online.db"),
                new VelocityAuthConfig.Mysql("127.0.0.1", 3306, "x", "root", ""), 90, 90, 90),
                dir, NOPLogger.NOP_LOGGER);
    }

    private static Account createAccount(StorageService storage, String name) {
        long id = storage.accounts().insert(name, name, UUID.randomUUID(), "h", AccountStatus.ACTIVE, 1);
        return storage.accounts().findById(id).orElseThrow();
    }

    private static Player player(UUID uuid, int port) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> uuid;
                    case "getRemoteAddress" -> new InetSocketAddress("127.0.0.1", port);
                    case "getUsername" -> "alice";
                    case "isActive" -> true;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void timesRealConnectionsAndIgnoresStaleDisconnects() throws Exception {
        try (StorageService storage = openStorage()) {
            Account account = createAccount(storage, "alice");
            AtomicLong now = new AtomicLong();
            try (OnlineTimeTracker time = new OnlineTimeTracker(storage.accounts(), now::get)) {
                AuthSessionRegistry registry = new AuthSessionRegistry(time);
                Player first = player(account.uuid(), 1234);
                AuthSession firstSession = registry.attach(first).orElseThrow();
                now.set(1000);
                assertEquals(0, time.totalMillis(account.id()));
                assertTrue(firstSession.authenticate(account));
                now.set(2000);
                assertTrue(firstSession.authenticate(account));
                assertEquals(1000, time.totalMillis(account.id()));

                Player rejected = player(account.uuid(), 1235);
                AuthSession rejectedSession = new AuthSession("alice", "127.0.0.1");
                rejectedSession.authenticate(account);
                registry.stage(account.uuid(), rejected.getRemoteAddress(), rejectedSession);
                assertTrue(registry.remove(rejected).isEmpty());
                assertSame(firstSession, registry.get(first).orElseThrow());

                Player restored = player(account.uuid(), 1236);
                AuthSession restoredSession = new AuthSession("alice", "127.0.0.1");
                restoredSession.authenticate(account);
                registry.stage(account.uuid(), restored.getRemoteAddress(), restoredSession);
                now.set(3000);
                assertEquals(2000, time.totalMillis(account.id()));
                assertSame(restoredSession, registry.attach(restored).orElseThrow());
                assertTrue(registry.remove(first).isEmpty());
                assertFalse(firstSession.authenticate(account));

                now.set(4500);
                assertSame(account, restoredSession.deauthenticate());
                assertNull(restoredSession.deauthenticate());
                now.set(5500);
                registry.remove(restored);
                assertEquals(3500, time.totalMillis(account.id()));
                assertFalse(restoredSession.authenticate(account));
            }
            assertEquals(3500, storage.accounts().onlineMillis(account.id()));
        }
    }

    @Test
    void checkpointsOverlapOnceAndPersistsActiveTimeOnShutdown() throws Exception {
        long accountId;
        AtomicLong now = new AtomicLong();
        try (StorageService storage = openStorage()) {
            accountId = createAccount(storage, "alice").id();
            try (OnlineTimeTracker time = new OnlineTimeTracker(storage.accounts(), now::get)) {
                time.connected(accountId);
                now.set(300);
                time.connected(accountId);
                now.set(600);
                time.flush();
                assertEquals(600, storage.accounts().onlineMillis(accountId));
                assertEquals(600, time.totalMillis(accountId));
                time.disconnected(accountId);
                now.set(1800);
                assertEquals(1800, time.totalMillis(accountId));
            }
        }
        try (StorageService storage = openStorage();
             OnlineTimeTracker time = new OnlineTimeTracker(storage.accounts(), now::get)) {
            now.set(5000);
            assertEquals(1800, time.totalMillis(accountId));
        }
    }

    @Test
    void failedCheckpointRetriesWithoutLosingOrDuplicatingTime() throws Exception {
        AtomicLong now = new AtomicLong();
        try (StorageService storage = openStorage();
             OnlineTimeTracker time = new OnlineTimeTracker(storage.accounts(), now::get)) {
            long alice = createAccount(storage, "alice").id();
            long bob = createAccount(storage, "bob").id();
            time.connected(alice);
            time.connected(bob);
            now.set(1200);
            time.disconnected(alice);
            storage.database().withConnection(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.execute("CREATE TRIGGER fail_online_time BEFORE INSERT ON account_online_time"
                            + " WHEN NEW.account_id = " + bob
                            + " BEGIN SELECT RAISE(ABORT, 'temporary failure'); END");
                }
                return null;
            });
            try {
                assertThrows(StorageException.class, time::flush);
                assertEquals(0, storage.accounts().onlineMillis(alice));
                assertEquals(1200, time.totalMillis(alice));
                now.set(1500);
            } finally {
                storage.database().withConnection(connection -> {
                    try (var statement = connection.createStatement()) {
                        statement.execute("DROP TRIGGER fail_online_time");
                    }
                    return null;
                });
            }
            time.flush();
            time.flush();
            assertEquals(1200, storage.accounts().onlineMillis(alice));
            assertEquals(1500, storage.accounts().onlineMillis(bob));
            storage.accounts().delete(bob);
            now.set(2000);
            time.flush();
            assertEquals(0, storage.accounts().onlineMillis(bob));
        }
    }
}
