package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.config.ConfigNode;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
import io.github.railgun19457.velocityauth.storage.model.BindingType;
import io.github.railgun19457.velocityauth.util.AsyncExecutor;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class BindingServiceTest {

    @TempDir
    Path tempDir;

    private StorageService storage;
    private BindingService bindings;
    private AsyncExecutor async;
    private Account alice;
    private Account bob;

    @BeforeEach
    void setUp() throws Exception {
        storage = StorageService.open(new VelocityAuthConfig.Database(
                VelocityAuthConfig.Database.Type.SQLITE,
                new VelocityAuthConfig.Sqlite("bind.db"),
                new VelocityAuthConfig.Mysql("127.0.0.1", 3306, "x", "root", "")), tempDir, NOPLogger.NOP_LOGGER);
        VelocityAuthConfig config = VelocityAuthConfig.from(ConfigNode.root(Map.of(
                "binding", Map.of(
                        "email", Map.of(
                                "enabled", true,
                                "max-accounts-per-email", 1,
                                "domain", Map.of("mode", "whitelist", "list", List.of("qq.com"))),
                        "qq", Map.of("enabled", true, "max-accounts-per-qq", 1)))));
        async = new AsyncExecutor();
        bindings = new BindingService(() -> config, storage, async, new PluginLogger(NOPLogger.NOP_LOGGER));

        long aliceId = storage.accounts().insert("Alice", "alice", UUID.randomUUID(), "h",
                AccountStatus.ACTIVE, null, 1L);
        long bobId = storage.accounts().insert("Bob", "bob", UUID.randomUUID(), "h",
                AccountStatus.ACTIVE, null, 1L);
        alice = storage.accounts().findById(aliceId).orElseThrow();
        bob = storage.accounts().findById(bobId).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        async.close();
        storage.close();
    }

    @Test
    void bindEmailHappyPathAndUnbind() {
        assertEquals(AuthOutcomes.BindStatus.SUCCESS,
                bindings.bindEmail(alice, "  Alice@QQ.com ", "1.2.3.4").join().status());
        assertEquals("alice@qq.com", bindings.binding(alice.id(), BindingType.EMAIL).orElseThrow().identifier());

        assertEquals(AuthOutcomes.BindStatus.ALREADY_BOUND,
                bindings.bindEmail(alice, "other@qq.com", "1.2.3.4").join().status());

        assertEquals(AuthOutcomes.BindStatus.SUCCESS,
                bindings.unbind(alice, BindingType.EMAIL, "1.2.3.4").join().status());
        assertTrue(bindings.binding(alice.id(), BindingType.EMAIL).isEmpty());
        assertEquals(AuthOutcomes.BindStatus.NOT_BOUND,
                bindings.unbind(alice, BindingType.EMAIL, "1.2.3.4").join().status());
    }

    @Test
    void emailSyntaxAndDomainRules() {
        assertEquals(AuthOutcomes.BindStatus.EMAIL_INVALID,
                bindings.bindEmail(alice, "not-an-email", "1.2.3.4").join().status());
        assertEquals(AuthOutcomes.BindStatus.DOMAIN_NOT_ALLOWED,
                bindings.bindEmail(alice, "alice@gmail.com", "1.2.3.4").join().status());
        // 子域名绕过应被拒绝（精确匹配）
        assertEquals(AuthOutcomes.BindStatus.DOMAIN_NOT_ALLOWED,
                bindings.bindEmail(alice, "alice@mail.qq.com", "1.2.3.4").join().status());
    }

    @Test
    void emailBaseLimitIsEnforced() {
        assertEquals(AuthOutcomes.BindStatus.SUCCESS,
                bindings.bindEmail(alice, "shared@qq.com", "1.2.3.4").join().status());
        assertEquals(AuthOutcomes.BindStatus.EMAIL_LIMIT,
                bindings.bindEmail(bob, "shared@qq.com", "1.2.3.4").join().status());
    }

    @Test
    void qqBindingAndBaseLimit() {
        assertEquals(AuthOutcomes.BindStatus.SUCCESS,
                bindings.bindQq(alice, "10001", "1.2.3.4").join().status());
        assertEquals(AuthOutcomes.BindStatus.ALREADY_BOUND,
                bindings.bindQq(alice, "10002", "1.2.3.4").join().status());
        assertEquals(AuthOutcomes.BindStatus.QQ_LIMIT,
                bindings.bindQq(bob, "10001", "1.2.3.4").join().status());
        assertEquals(AuthOutcomes.BindStatus.SUCCESS,
                bindings.unbind(alice, BindingType.QQ, "1.2.3.4").join().status());
        assertEquals(AuthOutcomes.BindStatus.SUCCESS,
                bindings.bindQq(bob, "10001", "1.2.3.4").join().status());
    }

    @Test
    void bindingsAreAudited() {
        bindings.bindEmail(alice, "alice@qq.com", "1.2.3.4").join();
        assertEquals("bind", storage.audit().findByAccountId(alice.id(), 10).get(0).action());
        bindings.unbind(alice, BindingType.EMAIL, "1.2.3.4").join();
        assertEquals("unbind", storage.audit().findByAccountId(alice.id(), 10).get(0).action());
    }
}
