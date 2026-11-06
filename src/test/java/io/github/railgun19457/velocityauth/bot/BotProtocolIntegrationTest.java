package io.github.railgun19457.velocityauth.bot;

import io.github.railgun19457.velocityauth.config.ConfigNode;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 起本地 {@link BotServer}，用测试客户端跑完整 pair + auth + verify 流程。
 */
@Timeout(60)
class BotProtocolIntegrationTest {

    @TempDir
    Path tempDir;

    private BotServer server;
    private KeyStore keyStore;
    private PairingService pairings;
    private VelocityAuthConfig config;
    private final AtomicInteger matchedCalls = new AtomicInteger();

    private static VelocityAuthConfig config(int port) {
        return VelocityAuthConfig.from(ConfigNode.root(Map.of(
                "bot", Map.of(
                        "enabled", true,
                        "listen", "127.0.0.1",
                        "port", port,
                        "pairing", Map.of("ttl-seconds", 600, "max-attempts", 5),
                        "query", Map.of("enabled", true),
                        "admin", Map.of("enabled", true, "rate-limit-per-minute", 1000)),
                "binding", Map.of("social", Map.of("qq", Map.of("enabled", true))),
                "security", Map.of("handshake-timeout-seconds", 15, "clock-skew-seconds", 120))));
    }

    private int port;

    @BeforeEach
    void setUp() throws Exception {
        config = config(0);
        PluginLogger log = new PluginLogger(NOPLogger.NOP_LOGGER);
        keyStore = KeyStore.load(tempDir, log);
        pairings = new PairingService();
        server = new BotServer(config.bot(), keyStore, pairings, () -> config, log, new BotServer.Handler() {
            @Override
            public boolean onVerifyMatched(String providerId, String code, String subject, String scope,
                                           String displayName) {
                matchedCalls.incrementAndGet();
                return "MC-TEST01".equals(code) && "qq".equals(providerId);
            }
        }, (type, payload) -> switch (type) {
            case "query.lookup" -> QueryResponse.ok(Map.of(
                    "found", true, "count", 1, "accounts", List.of("Alice")));
            case "query.account" -> QueryResponse.ok(Map.of("found", false));
            case "query.history" -> QueryResponse.error("bad_request", "history disabled in test");
            case "query.stats" -> QueryResponse.ok(Map.of("accounts_total", 42, "online", 7));
            case "query.accounts.list" -> QueryResponse.ok(Map.of(
                    "found", true, "total", 1, "count", 1,
                    "data", "[{\"name\":\"Alice\"}]"));
            case "query.audit.list" -> QueryResponse.ok(Map.of(
                    "found", true, "total", 1, "count", 1,
                    "data", "[{\"action\":\"login\"}]"));
            default -> QueryResponse.error("bad_request", "unsupported: " + type);
        }, (type, payload, clientId, remoteIp) -> switch (type) {
            case "admin.account.set-status" -> QueryResponse.ok(Map.of(
                    "ok", true, "id", 1, "name", "Alice", "status", "locked"));
            case "admin.account.delete" -> QueryResponse.ok(Map.of(
                    "ok", true, "id", 1, "name", "Alice", "deleted", true));
            default -> QueryResponse.error("bad_request", "unsupported: " + type);
        });
        server.start();
        // 等待端口绑定完成（端口 0 → 由系统分配）
        long deadline = System.currentTimeMillis() + 5000;
        while (server.getPort() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        this.port = server.getPort();
        assertTrue(port > 0, "bot 服务端未成功绑定端口");
    }

    private URI uri() {
        return URI.create("ws://127.0.0.1:" + port);
    }

    /** 等待服务端把会话标记为 READY（客户端先收到 auth.result，服务端随后才处理 auth.confirm）。 */
    private void awaitServerReady(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (server.readyCount() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(expected, server.readyCount(),
                "sessions=" + server.sessions().size() + " states=" + server.sessions().stream()
                        .map(s -> s.state().name()).toList());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void fullPairAuthAndVerifyFlow() throws Exception {
        PairingService.Created created = pairings.create(600, 5);

        TestBotClient bot = new TestBotClient(uri(), created.code());
        bot.setPairingId(created.pairingId());
        assertTrue(bot.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS), "无法连接 bot 服务端");

        assertTrue(bot.awaitReady(20), "握手未完成：" + bot.failure.getNow("未完成"));
        assertNotNull(bot.sessionId());
        awaitServerReady(1);

        // 配对记录已消费，客户端已 pin
        assertTrue(pairings.find(created.pairingId()).isEmpty(), "配对码应一次性消费");
        assertTrue(keyStore.find(TestBotClient.CLIENT_ID, TestBotClient.CLIENT_KEY_ID).isPresent());
        assertEquals(1, server.readyCount());

        // 业务消息：verify.watch → verify.matched → ack
        int sent = server.watch("qq", "MC-TEST01", 300);
        assertEquals(1, sent);
        assertEquals("MC-TEST01", bot.watchReceived.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(bot.matchedAck.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, matchedCalls.get());

        bot.closeBlocking();
    }

    @Test
    void unpairedClientIsRejected() throws Exception {
        TestBotClient bot = new TestBotClient(uri(),
                "not-a-real-code");
        bot.setPairingId("pair_does_not_exist");
        assertTrue(bot.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(bot.ready.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(bot.failure.get(10, java.util.concurrent.TimeUnit.SECONDS).contains("pairing_missing"));
        bot.closeBlocking();
    }

    @Test
    void pairingCodeIsSingleUse() throws Exception {
        PairingService.Created created = pairings.create(600, 5);

        TestBotClient first = new TestBotClient(uri(),
                created.code());
        first.setPairingId(created.pairingId());
        assertTrue(first.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(first.awaitReady(20), "首次配对应成功");

        TestBotClient second = new TestBotClient(uri(),
                created.code());
        second.setPairingId(created.pairingId());
        assertTrue(second.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(second.ready.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(second.failure.get(10, java.util.concurrent.TimeUnit.SECONDS).contains("pairing_missing"));

        first.closeBlocking();
        second.closeBlocking();
    }

    @Test
    void wrongPairingCodeFailsSignature() throws Exception {
        PairingService.Created created = pairings.create(600, 5);
        TestBotClient bot = new TestBotClient(uri(),
                "wrong-code-entirely");
        bot.setPairingId(created.pairingId());
        assertTrue(bot.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(bot.ready.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(bot.failure.get(10, java.util.concurrent.TimeUnit.SECONDS).contains("signature_invalid"));
        bot.closeBlocking();
    }

    @Test
    void existingPairedClientCanReconnectWithoutPairing() throws Exception {
        PairingService.Created created = pairings.create(600, 5);
        TestBotClient bot = new TestBotClient(uri(),
                created.code());
        bot.setPairingId(created.pairingId());
        assertTrue(bot.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(bot.awaitReady(20));

        // 第二次连接直接走 auth.*（无需配对码）
        TestBotClient again = new TestBotClient(uri(), "", bot.identity());
        again.setServerPublicKey(bot.serverPublicKey());
        assertTrue(again.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(again.awaitReady(20), "已配对客户端应能直接建立会话：" + again.failure.getNow("未完成"));
        awaitServerReady(2);

        bot.closeBlocking();
        again.closeBlocking();
    }

    @Test
    void capabilitiesAndKeyFingerprintArePinned() throws Exception {        PairingService.Created created = pairings.create(600, 5);
        TestBotClient bot = new TestBotClient(uri(),
                created.code());
        bot.setPairingId(created.pairingId());
        assertTrue(bot.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(bot.awaitReady(20));

        KeyStore.PairedClient pinned = keyStore.find(TestBotClient.CLIENT_ID, TestBotClient.CLIENT_KEY_ID)
                .orElseThrow();
        assertNotNull(pinned.fingerprint());
        assertEquals(32, pinned.publicKey().length);
        assertTrue(keyStore.clients().size() == 1);
        List<String> logs = bot.logs();
        assertTrue(logs.stream().anyMatch(entry -> entry.startsWith("paired")), logs.toString());

        bot.closeBlocking();
    }

    @Test
    void queryLookupRoundTrips() throws Exception {
        PairingService.Created created = pairings.create(600, 5);
        TestBotClient bot = new TestBotClient(uri(), created.code());
        bot.setPairingId(created.pairingId());
        assertTrue(bot.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(bot.awaitReady(20), "握手未完成：" + bot.failure.getNow("未完成"));

        String requestId = bot.sendQuery("query.lookup", Map.of("by", "qq", "value", "10001"));
        Map<String, Object> result = bot.awaitQueryResult(requestId, 10);
        assertEquals("true", result.get("found"));
        assertEquals("1", result.get("count"));
        assertEquals("Alice", result.get("accounts"));

        bot.closeBlocking();
    }

    @Test
    void queryAccountFoundFalse() throws Exception {
        PairingService.Created created = pairings.create(600, 5);
        TestBotClient bot = new TestBotClient(uri(), created.code());
        bot.setPairingId(created.pairingId());
        assertTrue(bot.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(bot.awaitReady(20));

        String requestId = bot.sendQuery("query.account", Map.of("by", "name", "value", "ghost"));
        Map<String, Object> result = bot.awaitQueryResult(requestId, 10);
        assertEquals("false", result.get("found"));

        bot.closeBlocking();
    }

    @Test
    void queryHandlerErrorBecomesSealedError() throws Exception {        PairingService.Created created = pairings.create(600, 5);
        TestBotClient bot = new TestBotClient(uri(), created.code());
        bot.setPairingId(created.pairingId());
        assertTrue(bot.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(bot.awaitReady(20));

        String requestId = bot.sendQuery("query.history", Map.of("by", "name", "value", "Alice"));
        Map<String, Object> error = bot.awaitQueryError(requestId, 10);
        assertEquals("bad_request", error.get("code"));

        bot.closeBlocking();
    }

    @Test
    void queryStatsRoundTrips() throws Exception {
        PairingService.Created created = pairings.create(600, 5);
        TestBotClient bot = new TestBotClient(uri(), created.code());
        bot.setPairingId(created.pairingId());
        assertTrue(bot.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(bot.awaitReady(20));

        String requestId = bot.sendQuery("query.stats", Map.of());
        Map<String, Object> result = bot.awaitQueryResult(requestId, 10);
        assertEquals("42", result.get("accounts_total"));
        assertEquals("7", result.get("online"));

        bot.closeBlocking();
    }

    @Test
    void capabilitiesAdvertiseAdminAndQuery() throws Exception {
        PairingService.Created created = pairings.create(600, 5);
        TestBotClient bot = new TestBotClient(uri(), created.code());
        bot.setPairingId(created.pairingId());
        assertTrue(bot.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(bot.awaitReady(20), "握手未完成：" + bot.failure.getNow("未完成"));

        assertTrue(server.capabilities().contains(BotSession.CAPABILITY_VERIFY),
                server.capabilities().toString());
        assertTrue(server.capabilities().contains(BotSession.CAPABILITY_QUERY),
                server.capabilities().toString());
        assertTrue(server.capabilities().contains(BotSession.CAPABILITY_ADMIN),
                server.capabilities().toString());

        bot.closeBlocking();
    }

    @Test
    void queryAccountsListRoundTrips() throws Exception {
        PairingService.Created created = pairings.create(600, 5);
        TestBotClient bot = new TestBotClient(uri(), created.code());
        bot.setPairingId(created.pairingId());
        assertTrue(bot.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(bot.awaitReady(20), "握手未完成：" + bot.failure.getNow("未完成"));

        String requestId = bot.sendQuery("query.accounts.list", Map.of("keyword", "ali", "limit", "10"));
        Map<String, Object> result = bot.awaitQueryResult(requestId, 10);
        assertEquals("1", result.get("total"));
        assertTrue(String.valueOf(result.get("data")).contains("Alice"), result.toString());

        bot.closeBlocking();
    }

    @Test
    void queryAuditListRoundTrips() throws Exception {
        PairingService.Created created = pairings.create(600, 5);
        TestBotClient bot = new TestBotClient(uri(), created.code());
        bot.setPairingId(created.pairingId());
        assertTrue(bot.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(bot.awaitReady(20), "握手未完成：" + bot.failure.getNow("未完成"));

        String requestId = bot.sendQuery("query.audit.list", Map.of("limit", "10"));
        Map<String, Object> result = bot.awaitQueryResult(requestId, 10);
        assertEquals("1", result.get("total"));
        assertTrue(String.valueOf(result.get("data")).contains("login"), result.toString());

        bot.closeBlocking();
    }

    @Test
    void adminSetStatusRoundTrips() throws Exception {
        PairingService.Created created = pairings.create(600, 5);
        TestBotClient bot = new TestBotClient(uri(), created.code());
        bot.setPairingId(created.pairingId());
        assertTrue(bot.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(bot.awaitReady(20), "握手未完成：" + bot.failure.getNow("未完成"));

        String requestId = bot.sendQuery("admin.account.set-status",
                Map.of("name", "Alice", "status", "locked", "actor", "admin"));
        Map<String, Object> result = bot.awaitQueryResult(requestId, 10);
        assertEquals("true", result.get("ok"));
        assertEquals("locked", result.get("status"));

        bot.closeBlocking();
    }

    @Test
    void adminIsRejectedWhenClientNotWhitelisted() throws Exception {
        // 服务端只允许 "other-bot" 发管理指令，测试客户端应被拒
        config = VelocityAuthConfig.from(ConfigNode.root(Map.of(
                "bot", Map.of(
                        "enabled", true,
                        "listen", "127.0.0.1",
                        "port", 0,
                        "pairing", Map.of("ttl-seconds", 600, "max-attempts", 5),
                        "query", Map.of("enabled", true),
                        "admin", Map.of("enabled", true, "clients", List.of("other-bot"))),
                "binding", Map.of("social", Map.of("qq", Map.of("enabled", true))),
                "security", Map.of("handshake-timeout-seconds", 15, "clock-skew-seconds", 120))));
        // 重建服务端以应用新配置
        server.shutdown();
        server = new BotServer(config.bot(), keyStore, pairings, () -> config,
                new PluginLogger(NOPLogger.NOP_LOGGER), new BotServer.Handler() {
            @Override
            public boolean onVerifyMatched(String providerId, String code, String subject, String scope,
                                           String displayName) {
                return true;
            }
        }, (type, payload) -> QueryResponse.ok(Map.of("found", true)),
                (type, payload, clientId, remoteIp) -> QueryResponse.ok(Map.of("ok", true)));
        server.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (server.getPort() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        this.port = server.getPort();

        PairingService.Created created = pairings.create(600, 5);
        TestBotClient bot = new TestBotClient(uri(), created.code());
        bot.setPairingId(created.pairingId());
        assertTrue(bot.connectBlocking(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(bot.awaitReady(20), "握手未完成：" + bot.failure.getNow("未完成"));

        String requestId = bot.sendQuery("admin.account.set-status",
                Map.of("name", "Alice", "status", "locked"));
        Map<String, Object> error = bot.awaitQueryError(requestId, 10);
        assertEquals("admin_disabled", error.get("code"));

        bot.closeBlocking();
    }
}
