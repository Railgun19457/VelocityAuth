package io.github.railgun19457.velocityauth.verify;

import io.github.railgun19457.velocityauth.VelocityAuthPlugin;
import io.github.railgun19457.velocityauth.auth.RateLimiter;
import io.github.railgun19457.velocityauth.config.ConfigNode;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.lang.LangService;
import io.github.railgun19457.velocityauth.mail.EmailTemplateService;
import io.github.railgun19457.velocityauth.mail.MailSender;
import io.github.railgun19457.velocityauth.util.AsyncExecutor;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.helpers.NOPLogger;

import java.io.IOException;
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

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path tempDir;
    private final List<String> watched = new ArrayList<>();
    private final List<String> cancelled = new ArrayList<>();
    private final List<String> sentEmails = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        async = new AsyncExecutor();
        VelocityAuthConfig config = VelocityAuthConfig.from(ConfigNode.root(Map.of(
                "verification", Map.of(
                        "code-length", 6,
                        "ttl-seconds", 60,
                        "max-attempts", 3,
                        "rate-limit", Map.of("per-ip-per-minute", 100, "per-account-per-minute", 100)),
                "binding", Map.of("social", Map.of("qq", Map.of("enabled", true))),
                "bot", Map.of("enabled", true))));
        LangService lang = LangService.load("zh_cn", VelocityAuthPlugin.class, null, NOPLogger.NOP_LOGGER);
        PluginLogger log = new PluginLogger(NOPLogger.NOP_LOGGER);

        MailSender smtp = new MailSender() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public CompletableFuture<Boolean> send(String to, String subject, String text, String html) {
                sentEmails.add(to);
                return CompletableFuture.completedFuture(true);
            }
        };

        SocialVerificationGateway gateway = new SocialVerificationGateway() {
            @Override
            public boolean available(String providerId) {
                return "qq".equals(providerId);
            }

            @Override
            public void watch(String providerId, String code, int ttlSeconds) {
                watched.add(code);
            }

            @Override
            public void cancel(String providerId, String code) {
                cancelled.add(code);
            }
        };

        service = new VerificationService(() -> config, () -> lang, () -> "TestServer", smtp,
                new EmailTemplateService(tempDir, VelocityAuthPlugin.class, NOPLogger.NOP_LOGGER), gateway,
                new RateLimiter(), (task, delay) -> scheduledTasks.add(task), Runnable::run, log);
    }

    @AfterEach
    void tearDown() {
        async.close();
    }

    private VerificationService.Start start(String provider, VerificationPurpose purpose) {
        return start(provider, purpose, UUID.randomUUID());
    }

    private VerificationService.Start start(String provider, VerificationPurpose purpose, UUID playerUuid) {
        return service.start(VerificationRequest
                .builder(purpose, provider, playerUuid)
                .accountName("alice")
                .connectionName("conn1")
                .ip("1.2.3.4")
                .target("email".equals(provider) ? "a@qq.com" : null)
                .data(Map.of("password_hash", "$2a$04$fake"))
                .build());
    }

    @Test
    void socialCodeIsWatchedAndCompletesOnMatch() {
        VerificationService.Start start = start("qq", VerificationPurpose.REGISTER);
        assertTrue(start.accepted());
        VerificationSession session = start.session();
        assertTrue(session.code().startsWith("MC-"), session.code());
        assertTrue(watched.contains(session.code()));

        assertTrue(service.onSocialMatched("qq", session.code(), "10001", "123456", "tester"));
        assertTrue(start.completion().join().completed());
        assertEquals("10001", session.resolvedValue());
        assertTrue(cancelled.contains(session.code()), "完成后应通知 bot 停止监听");
        assertEquals(0, service.pendingCount());
    }

    @Test
    void matchFromWrongProviderIsRejected() {
        VerificationService.Start start = start("qq", VerificationPurpose.BIND_SOCIAL);
        // 发起渠道为 qq；来自其它渠道的上报不得消费验证码
        assertFalse(service.onSocialMatched("discord", start.session().code(), "10003", "room", "x"));
        assertFalse(start.completion().isDone());
        assertTrue(service.onSocialMatched("qq", start.session().code(), "10003", "123456", "x"));
        assertTrue(start.completion().join().completed());
    }

    @Test
    void matchWithoutSubjectIsRejected() {
        VerificationService.Start start = start("qq", VerificationPurpose.BIND_SOCIAL);
        assertFalse(service.onSocialMatched("qq", start.session().code(), "", "room", "x"));
        assertFalse(start.completion().isDone());
    }

    @Test
    void emailCodeSubmitFlow() {
        VerificationService.Start start = start("email", VerificationPurpose.BIND_EMAIL);
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
        VerificationService.Start start = start("email", VerificationPurpose.RECOVER);
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
        VerificationService.Start first = start("qq", VerificationPurpose.REGISTER, playerUuid);
        VerificationService.Start second = start("qq", VerificationPurpose.REGISTER, playerUuid);
        assertEquals(VerificationResult.Status.REPLACED, first.completion().join().status());
        assertFalse(second.completion().isDone());
        assertEquals(1, service.pendingCount());
    }

    @Test
    void cancelCompletesWithCancelled() {
        VerificationService.Start start = start("qq", VerificationPurpose.BIND_SOCIAL);
        service.cancel(start.session().playerUuid());
        assertEquals(VerificationResult.Status.CANCELLED, start.completion().join().status());
    }

    @Test
    void timeoutCompletesWithExpired() {
        VerificationService.Start start = start("qq", VerificationPurpose.REGISTER);
        assertFalse(scheduledTasks.isEmpty(), "应注册超时任务");
        scheduledTasks.forEach(Runnable::run);
        assertEquals(VerificationResult.Status.EXPIRED, start.completion().join().status());
        assertEquals(0, service.pendingCount());
    }

    @Test
    void inGameSubmissionCannotCompleteSocialSession() {
        VerificationService.Start start = start("qq", VerificationPurpose.BIND_SOCIAL);
        assertEquals(VerificationService.SubmitOutcome.WRONG_CHANNEL,
                service.submitCode(start.session().playerUuid(), start.session().code()));
        assertFalse(start.completion().isDone(), "社交渠道只能由 bot 命中完成");
        assertEquals(3, start.session().remainingAttempts(), "不应消耗尝试次数");
    }

    @Test
    void dispatchCallbackReportsSocialWatching() {
        List<VerificationDispatch> dispatches = new ArrayList<>();
        VerificationService.Start start = service.start(VerificationRequest
                .builder(VerificationPurpose.REGISTER, "qq", UUID.randomUUID())
                .accountName("bob").connectionName("c").ip("1.2.3.4")
                .onDispatch(dispatches::add)
                .build());
        assertNotNull(start.session());
        assertEquals(List.of(VerificationDispatch.SOCIAL_WATCHING), dispatches);
    }
}
