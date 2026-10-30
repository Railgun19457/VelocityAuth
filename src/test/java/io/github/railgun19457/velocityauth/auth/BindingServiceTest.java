package io.github.railgun19457.velocityauth.auth;

import io.github.railgun19457.velocityauth.config.ConfigNode;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.storage.StorageService;
import io.github.railgun19457.velocityauth.storage.model.Account;
import io.github.railgun19457.velocityauth.storage.model.AccountStatus;
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
                new VelocityAuthConfig.Mysql("127.0.0.1", 3306, "x", "root", ""), 90, 90, 90), tempDir, NOPLogger.NOP_LOGGER);
        async = new AsyncExecutor();
        bindings = service(0);

        long aliceId = storage.accounts().insert("Alice", "alice", UUID.randomUUID(), "h",
                AccountStatus.ACTIVE, null, 1L);
        long bobId = storage.accounts().insert("Bob", "bob", UUID.randomUUID(), "h",
                AccountStatus.ACTIVE, null, 1L);
        alice = storage.accounts().findById(aliceId).orElseThrow();
        bob = storage.accounts().findById(bobId).orElseThrow();
    }

    private BindingService service(int cooldownMinutes) {
        VelocityAuthConfig config = VelocityAuthConfig.from(ConfigNode.root(Map.of(
                "binding", Map.of(
                        "email", Map.of(
                                "enabled", true,
                                "max-accounts", 1,
                                "cooldown-minutes", cooldownMinutes,
                                "domain", Map.of("mode", "whitelist", "list", List.of("qq.com"))),
                        "social", Map.of(
                                "qq", Map.of("enabled", true, "max-accounts", 1,
                                        "cooldown-minutes", cooldownMinutes))))));
        return new BindingService(() -> config, storage,
                new BindingIndex(storage.bindings(), new PluginLogger(NOPLogger.NOP_LOGGER)),
                async, new PluginLogger(NOPLogger.NOP_LOGGER));
    }

    @AfterEach
    void tearDown() {
        async.close();
        storage.close();
    }

    @Test
    void bindEmailHappyPathAndUnbind() {
        assertEquals(AuthOutcomes.BindStatus.SUCCESS,
                bindings.bind(alice, "email", "  Alice@QQ.com ", "1.2.3.4").join().status());
        assertEquals("alice@qq.com", bindings.binding(alice.id(), "email").orElseThrow().identifier());

        assertEquals(AuthOutcomes.BindStatus.ALREADY_BOUND,
                bindings.bind(alice, "email", "other@qq.com", "1.2.3.4").join().status());

        assertEquals(AuthOutcomes.BindStatus.SUCCESS,
                bindings.unbind(alice, "email", "1.2.3.4").join().status());
        assertTrue(bindings.binding(alice.id(), "email").isEmpty());
        assertEquals(AuthOutcomes.BindStatus.NOT_BOUND,
                bindings.unbind(alice, "email", "1.2.3.4").join().status());
    }

    @Test
    void emailSyntaxAndDomainRules() {
        assertEquals(AuthOutcomes.BindStatus.EMAIL_INVALID,
                bindings.bind(alice, "email", "not-an-email", "1.2.3.4").join().status());
        assertEquals(AuthOutcomes.BindStatus.DOMAIN_NOT_ALLOWED,
                bindings.bind(alice, "email", "alice@gmail.com", "1.2.3.4").join().status());
        // 子域名绕过应被拒绝（精确匹配）
        assertEquals(AuthOutcomes.BindStatus.DOMAIN_NOT_ALLOWED,
                bindings.bind(alice, "email", "alice@mail.qq.com", "1.2.3.4").join().status());
    }

    @Test
    void emailBaseLimitIsEnforced() {
        assertEquals(AuthOutcomes.BindStatus.SUCCESS,
                bindings.bind(alice, "email", "shared@qq.com", "1.2.3.4").join().status());
        assertEquals(AuthOutcomes.BindStatus.LIMIT,
                bindings.bind(bob, "email", "shared@qq.com", "1.2.3.4").join().status());
    }

    @Test
    void socialBindingAndBaseLimit() {
        assertEquals(AuthOutcomes.BindStatus.SUCCESS,
                bindings.bind(alice, "qq", "10001", "1.2.3.4").join().status());
        assertEquals(AuthOutcomes.BindStatus.ALREADY_BOUND,
                bindings.bind(alice, "qq", "10002", "1.2.3.4").join().status());
        assertEquals(AuthOutcomes.BindStatus.LIMIT,
                bindings.bind(bob, "qq", "10001", "1.2.3.4").join().status());
        assertEquals(AuthOutcomes.BindStatus.SUCCESS,
                bindings.unbind(alice, "qq", "1.2.3.4").join().status());
        assertEquals(AuthOutcomes.BindStatus.SUCCESS,
                bindings.bind(bob, "qq", "10001", "1.2.3.4").join().status());
    }

    @Test
    void bindingsAreAudited() {
        bindings.bind(alice, "email", "alice@qq.com", "1.2.3.4").join();
        assertEquals("bind", storage.audit().findByAccountId(alice.id(), 10).get(0).action());
        bindings.unbind(alice, "email", "1.2.3.4").join();
        assertEquals("unbind", storage.audit().findByAccountId(alice.id(), 10).get(0).action());
    }

    @Test
    void cooldownBlocksRebindAfterUnbind() {
        BindingService cooled = service(60);
        assertEquals(AuthOutcomes.BindStatus.SUCCESS,
                cooled.bind(alice, "email", "alice@qq.com", "1.2.3.4").join().status());
        assertTrue(cooled.cooldownRemainingSeconds("email", "alice@qq.com") == 0,
                "绑定期间不应有冷却");

        assertEquals(AuthOutcomes.BindStatus.SUCCESS,
                cooled.unbind(alice, "email", "1.2.3.4").join().status());
        assertTrue(cooled.cooldownRemainingSeconds("email", "alice@qq.com") > 0,
                "解绑后应进入冷却");

        // 同一标识：本人再绑 / 他人绑都被冷却拦下
        assertEquals(AuthOutcomes.BindStatus.COOLDOWN,
                cooled.bind(alice, "email", "alice@qq.com", "1.2.3.4").join().status());
        assertEquals(AuthOutcomes.BindStatus.COOLDOWN,
                cooled.bind(bob, "email", "alice@qq.com", "1.2.3.4").join().status());

        // 解绑会写入绑定历史（含标识），可追溯
        assertEquals(2, storage.bindingHistory().findByIdentifier("email", "alice@qq.com", 10).size());
    }
}
