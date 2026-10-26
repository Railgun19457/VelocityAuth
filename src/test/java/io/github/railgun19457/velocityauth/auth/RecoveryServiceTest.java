package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.config.ConfigNode;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.lang.LangService;
import io.github.railgun19457.velocityauth.mail.MailSender;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import io.github.railgun19457.velocityauth.storage.model.BindingType;
import io.github.railgun19457.velocityauth.util.AsyncExecutor;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import io.github.railgun19457.velocityauth.verify.QqVerificationGateway;
import io.github.railgun19457.velocityauth.verify.VerificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class RecoveryServiceTest {

    @TempDir
    Path tempDir;

    private StorageService storage;
    private AsyncExecutor async;
    private RecoveryService recovery;
    private VerificationService verifications;
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<String> sentEmails = new ArrayList<>();
    private long accountId;

    @BeforeEach
    void setUp() throws Exception {
        storage = StorageService.open(new VelocityAuthConfig.Database(
                VelocityAuthConfig.Database.Type.SQLITE,
                new VelocityAuthConfig.Sqlite("recover.db"),
                new VelocityAuthConfig.Mysql("127.0.0.1", 3306, "x", "root", "")), tempDir, NOPLogger.NOP_LOGGER);
        VelocityAuthConfig config = VelocityAuthConfig.from(ConfigNode.root(Map.of(
                "verification", Map.of("ttl-seconds", 60, "max-attempts", 3,
                        "rate-limit", Map.of("per-ip-per-minute", 100, "per-account-per-minute", 100)),
                "qqbot", Map.of("enabled", true, "group-id", "123456"))));
        LangService lang = LangService.load("zh_cn", VelocityAuthPlugin.class, null, NOPLogger.NOP_LOGGER);
        PluginLogger log = new PluginLogger(NOPLogger.NOP_LOGGER);
        async = new AsyncExecutor();

        MailSender mail = new MailSender() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public CompletableFuture<Boolean> send(String to, String subject, String body) {
                sentEmails.add(to);
                return CompletableFuture.completedFuture(true);
            }
        };
        QqVerificationGateway gateway = new QqVerificationGateway() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public void watch(String code, String groupId, int ttlSeconds) {
            }

            @Override
            public void cancel(String code) {
            }
        };
        verifications = new VerificationService(() -> config, () -> lang, () -> "Test", mail, gateway,
                new RateLimiter(), (task, delay) -> scheduled.add(task), Runnable::run, log);
        recovery = new RecoveryService(() -> config, storage, verifications, async, log);

        accountId = storage.accounts().insert("Alice", "alice", UUID.randomUUID(), "h",
                AccountStatus.ACTIVE, null, 1L);
        storage.bindings().insert(accountId, BindingType.EMAIL, "alice@qq.com", 1L, 1L);
    }

    @AfterEach
    void tearDown() {
        async.close();
        storage.close();
    }

    @Test
    void unknownAccountIsSilentlyIgnored() {
        RecoveryService.Start start = recovery.request("nobody", BindingType.EMAIL, UUID.randomUUID(),
                "conn", "1.2.3.4", dispatch -> {
                }).join();
        assertFalse(start.accepted());
        assertTrue(sentEmails.isEmpty());
    }

    @Test
    void accountWithoutRequestedBindingIsSilentlyIgnored() {
        RecoveryService.Start start = recovery.request("alice", BindingType.QQ, UUID.randomUUID(),
                "conn", "1.2.3.4", dispatch -> {
                }).join();
        assertFalse(start.accepted());
    }

    @Test
    void emailRecoveryGrantsResetAfterVerification() {
        UUID playerUuid = UUID.randomUUID();
        RecoveryService.Start start = recovery.request("alice", BindingType.EMAIL, playerUuid,
                "conn", "1.2.3.4", dispatch -> {
                }).join();
        assertTrue(start.accepted());
        assertEquals("alice@qq.com", sentEmails.get(0));
        assertFalse(recovery.hasGrant(playerUuid), "验证完成前不应发放授权");

        String code = start.session().code();
        assertEquals(VerificationService.SubmitOutcome.OK, verifications.submitCode(playerUuid, code));
        assertTrue(start.completion().join().completed());
        assertTrue(recovery.hasGrant(playerUuid));

        assertEquals(accountId, recovery.consumeGrant(playerUuid).orElseThrow());
        assertTrue(recovery.consumeGrant(playerUuid).isEmpty(), "授权消费即删");
    }

    @Test
    void qqRecoveryGrantsAfterBotMatch() {
        storage.bindings().insert(accountId, BindingType.QQ, "10001", 1L, 1L);
        UUID playerUuid = UUID.randomUUID();
        RecoveryService.Start start = recovery.request("alice", BindingType.QQ, playerUuid,
                "conn", "1.2.3.4", dispatch -> {
                }).join();
        assertTrue(start.accepted());

        assertTrue(verifications.onQqMatched(start.session().code(), "10001", "123456", true));
        assertTrue(start.completion().join().completed());
        assertEquals(accountId, recovery.consumeGrant(playerUuid).orElseThrow());
    }

    @Test
    void expiredGrantIsRejected() {
        UUID playerUuid = UUID.randomUUID();
        recovery.grant(playerUuid, accountId);
        assertTrue(recovery.hasGrant(playerUuid));
        recovery.revokeGrant(playerUuid);
        assertTrue(recovery.consumeGrant(playerUuid).isEmpty());
    }
}
