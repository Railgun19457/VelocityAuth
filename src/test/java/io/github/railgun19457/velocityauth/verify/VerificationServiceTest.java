package io.github.railgun19457.velocityauth.verify;

import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.RateLimiter;
import io.github.railgun19457.velocityauth.config.ConfigNode;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.lang.LangService;
import io.github.railgun19457.velocityauth.mail.MailSender;
import io.github.railgun19457.velocityauth.util.AsyncExecutor;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.helpers.NOPLogger;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class VerificationServiceTest {

    private AsyncExecutor async;
    private VerificationService service;
    private final List<Runnable> scheduledTasks = new ArrayList<>();
    private final List<String> watched = new ArrayList<>();
    private final List<String> cancelled = new ArrayList<>();
    private final List<String> sentEmails = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        async = new AsyncExecutor();
        VelocityAuthConfig config = VelocityAuthConfig.from(ConfigNode.root(Map.of(
                "verification", Map.of(
                        "email-code-length", 6,
                        "qq-code-length", 6,
                        "ttl-seconds", 60,
                        "max-attempts", 3,
                        "rate-limit", Map.of("per-ip-per-minute", 100, "per-account-per-minute", 100)),
                "qqbot", Map.of("enabled", true, "group-id", "123456", "require-group-member", true))));
        LangService lang = LangService.load("zh_cn", VelocityAuthPlugin.class, null, NOPLogger.NOP_LOGGER);
        PluginLogger log = new PluginLogger(NOPLogger.NOP_LOGGER);

        MailSender smtp = new MailSender() {
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
                watched.add(code);
            }

            @Override
            public void cancel(String code) {
                cancelled.add(code);
            }
        };

        service = new VerificationService(() -> config, () -> lang, () -> "TestServer", smtp, gateway,
                new RateLimiter(), (task, delay) -> scheduledTasks.add(task), Runnable::run, log);
    }

    @AfterEach
    void tearDown() {
        async.close();
    }

    private VerificationService.Start start(VerificationChannel channel, VerificationPurpose purpose) {
        return start(channel, purpose, UUID.randomUUID());
    }

    private VerificationService.Start start(VerificationChannel channel, VerificationPurpose purpose, UUID playerUuid) {
        return service.start(VerificationRequest
                .builder(purpose, channel, playerUuid)
                .accountName("alice")
                .connectionName("conn1")
                .ip("1.2.3.4")
                .target(channel == VerificationChannel.EMAIL ? "a@qq.com" : null)
                .data(Map.of("password_hash", "$2a$04$fake"))
                .build());
    }

    @Test
    void qqCodeIsWatchedAndCompletesOnMatch() {
        VerificationService.Start start = start(VerificationChannel.QQ, VerificationPurpose.REGISTER);
        assertTrue(start.accepted());
        VerificationSession session = start.session();
        assertTrue(session.code().startsWith("MC-"), session.code());
        assertTrue(watched.contains(session.code()));

        assertTrue(service.onQqMatched(session.code(), "10001", "123456", true));
        assertTrue(start.completion().join().completed());
        assertEquals("10001", session.resolvedValue());
        assertTrue(cancelled.contains(session.code()), "完成后应通知 bot 停止监听");
        assertEquals(0, service.pendingCount());
    }

    @Test
    void nonGroupMemberIsRejected() {
        VerificationService.Start start = start(VerificationChannel.QQ, VerificationPurpose.BIND_QQ);
        assertFalse(service.onQqMatched(start.session().code(), "10002", "123456", false));
        assertFalse(start.completion().isDone());
    }

    @Test
    void emailCodeSubmitFlow() {
        VerificationService.Start start = start(VerificationChannel.EMAIL, VerificationPurpose.BIND_EMAIL);
        assertTrue(start.accepted());
        assertEquals("a@qq.com", sentEmails.get(0));
        VerificationSession session = start.session();
        assertEquals(6, session.code().length());
        assertTrue(session.code().chars().allMatch(Character::isDigit));

        assertEquals(VerificationService.SubmitOutcome.WRONG_CODE, service.submitCode(session.playerUuid(), "000000"));
        assertEquals(2, session.remainingAttempts());
        assertEquals(VerificationService.SubmitOutcome.OK, service.submitCode(session.playerUuid(), session.code()));
        assertTrue(start.completion().join().completed());
    }

    @Test
    void tooManyAttemptsVoidsSession() {
        VerificationService.Start start = start(VerificationChannel.EMAIL, VerificationPurpose.RECOVER);
        UUID uuid = start.session().playerUuid();
        for (int i = 0; i < 3; i++) {
            assertEquals(VerificationService.SubmitOutcome.WRONG_CODE, service.submitCode(uuid, "111111"));
        }
        assertEquals(VerificationService.SubmitOutcome.TOO_MANY_ATTEMPTS, service.submitCode(uuid, "111111"));
        assertEquals(VerificationResult.Status.TOO_MANY_ATTEMPTS, start.completion().join().status());
        assertEquals(VerificationService.SubmitOutcome.NO_SESSION, service.submitCode(uuid, "111111"));
    }

    @Test
    void newSessionReplacesPrevious() {
        UUID playerUuid = UUID.randomUUID();
        VerificationService.Start first = start(VerificationChannel.QQ, VerificationPurpose.REGISTER, playerUuid);
        VerificationService.Start second = start(VerificationChannel.QQ, VerificationPurpose.REGISTER, playerUuid);
        assertEquals(VerificationResult.Status.REPLACED, first.completion().join().status());
        assertFalse(second.completion().isDone());
        assertEquals(1, service.pendingCount());
    }

    @Test
    void cancelCompletesWithCancelled() {
        VerificationService.Start start = start(VerificationChannel.QQ, VerificationPurpose.BIND_QQ);
        service.cancel(start.session().playerUuid());
        assertEquals(VerificationResult.Status.CANCELLED, start.completion().join().status());
    }

    @Test
    void timeoutCompletesWithExpired() {
        VerificationService.Start start = start(VerificationChannel.QQ, VerificationPurpose.REGISTER);
        assertFalse(scheduledTasks.isEmpty(), "应注册超时任务");
        scheduledTasks.forEach(Runnable::run);
        assertEquals(VerificationResult.Status.EXPIRED, start.completion().join().status());
        assertEquals(0, service.pendingCount());
    }

    @Test
    void inGameSubmissionCannotCompleteQqSession() {
        VerificationService.Start start = start(VerificationChannel.QQ, VerificationPurpose.BIND_QQ);
        assertEquals(VerificationService.SubmitOutcome.WRONG_CHANNEL,
                service.submitCode(start.session().playerUuid(), start.session().code()));
        assertFalse(start.completion().isDone(), "QQ 渠道只能由群消息命中完成");
        assertEquals(3, start.session().remainingAttempts(), "不应消耗尝试次数");
    }

    @Test
    void dispatchCallbackReportsQqWatching() {
        List<VerificationDispatch> dispatches = new ArrayList<>();
        VerificationService.Start start = service.start(VerificationRequest
                .builder(VerificationPurpose.REGISTER, VerificationChannel.QQ, UUID.randomUUID())
                .accountName("bob").connectionName("c").ip("1.2.3.4")
                .onDispatch(dispatches::add)
                .build());
        assertNotNull(start.session());
        assertEquals(List.of(VerificationDispatch.QQ_WATCHING), dispatches);
    }
}
