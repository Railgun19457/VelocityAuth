package io.github.railgun19457.velocityauth.verify;

import io.github.railgun19457.velocityauth.auth.RateLimiter;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.lang.LangService;
import io.github.railgun19457.velocityauth.mail.MailSender;
import io.github.railgun19457.velocityauth.util.PluginLogger;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 统一验证会话服务（DESIGN 5）。
 * <p>
 * 所有需要"证明身份"的场景（注册门槛 / 绑定 / 找回 / 解绑）复用同一套机制：
 * 一次性验证码、TTL、限次、限流，全部只在内存中。
 */
public final class VerificationService {

    /** QQ 渠道码字符集：去除易混淆字符。 */
    private static final char[] QQ_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final String QQ_PREFIX = "MC-";
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    private final Supplier<VelocityAuthConfig> config;
    private final Supplier<LangService> lang;
    private final Supplier<String> serverName;
    private final MailSender smtpSender;
    private final QqVerificationGateway qqGateway;
    private final RateLimiter rateLimiter;
    private final Timer timer;
    private final PluginLogger log;
    /** 完成回调必须切线程：否则会在命令执行线程上内联触发（如建号后立即断开，导致后续提示丢失）。 */
    private final Executor callbackExecutor;
    private final SecureRandom random = new SecureRandom();

    private final Map<UUID, VerificationSession> byPlayer = new ConcurrentHashMap<>();
    private final Map<String, VerificationSession> byCode = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<VerificationResult>> completions = new ConcurrentHashMap<>();

    public VerificationService(Supplier<VelocityAuthConfig> config,
                               Supplier<LangService> lang,
                               Supplier<String> serverName,
                               MailSender smtpSender,
                               QqVerificationGateway qqGateway,
                               RateLimiter rateLimiter,
                               Timer timer,
                               Executor callbackExecutor,
                               PluginLogger log) {
        this.config = config;
        this.lang = lang;
        this.serverName = serverName;
        this.smtpSender = smtpSender;
        this.qqGateway = qqGateway;
        this.rateLimiter = rateLimiter;
        this.timer = timer;
        this.callbackExecutor = callbackExecutor;
        this.log = log;
    }

    // ------------------------------------------------------------------ 发起

    /** 一次发起的结果：被拒绝时 {@code session == null}。 */
    public record Start(VerificationSession session, CompletableFuture<VerificationResult> completion,
                        String error) {
        public boolean accepted() {
            return session != null;
        }

        static Start failed(String error) {
            return new Start(null, CompletableFuture.completedFuture(VerificationResult.failed(error)), error);
        }
    }

    public Start start(VerificationRequest request) {
        VelocityAuthConfig.Verification rules = config.get().verification();

        if (!rateLimiter.tryAcquire("verify:ip:" + request.ip(), rules.rateLimit().perIpPerMinute(), ONE_MINUTE)) {
            request.onDispatch().accept(VerificationDispatch.RATE_LIMITED);
            return Start.failed("rate_limited");
        }
        if (request.accountName() != null
                && !rateLimiter.tryAcquire("verify:account:" + request.accountName(),
                rules.rateLimit().perAccountPerMinute(), ONE_MINUTE)) {
            request.onDispatch().accept(VerificationDispatch.RATE_LIMITED);
            return Start.failed("rate_limited");
        }

        if (request.channel() == VerificationChannel.EMAIL && !smtpSender.available()) {
            request.onDispatch().accept(VerificationDispatch.EMAIL_UNAVAILABLE);
            return Start.failed("email_unavailable");
        }
        if (request.channel() == VerificationChannel.QQ && !qqGateway.available()) {
            request.onDispatch().accept(VerificationDispatch.QQ_UNAVAILABLE);
            return Start.failed("qq_unavailable");
        }

        // 同一玩家只允许一个进行中的验证；旧会话标记为被取代
        VerificationSession existing = byPlayer.get(request.playerUuid());
        if (existing != null) {
            finish(existing, VerificationResult.Status.REPLACED, "replaced");
        }

        String code = generateCode(request.channel());
        long now = System.currentTimeMillis();
        long ttlMillis = rules.ttlSeconds() * 1000L;
        VerificationSession session = new VerificationSession(
                UUID.randomUUID().toString(), request.purpose(), request.channel(), code,
                request.playerUuid(), request.accountName(), request.connectionName(), request.ip(),
                request.target(), request.data(), now, now + ttlMillis, rules.maxAttempts());

        CompletableFuture<VerificationResult> completion = new CompletableFuture<>();
        byPlayer.put(session.playerUuid(), session);
        byCode.put(code, session);
        completions.put(session.id(), completion);

        timer.schedule(() -> finish(session, VerificationResult.Status.EXPIRED, "expired"),
                Duration.ofSeconds(rules.ttlSeconds()));

        dispatch(request, session, rules);
        return new Start(session, completion, null);
    }

    private void dispatch(VerificationRequest request, VerificationSession session,
                          VelocityAuthConfig.Verification rules) {
        long minutes = Math.max(1, rules.ttlSeconds() / 60);
        if (session.channel() == VerificationChannel.EMAIL) {
            String subject = lang.get().render("verification.email-subject", "server", serverName.get());
            String body = lang.get().render("verification.email-body",
                    "code", session.code(), "minutes", minutes, "server", serverName.get());
            smtpSender.send(session.target(), subject, body).thenAccept(ok -> {
                if (Boolean.TRUE.equals(ok)) {
                    request.onDispatch().accept(VerificationDispatch.EMAIL_SENT);
                } else {
                    request.onDispatch().accept(VerificationDispatch.EMAIL_FAILED);
                    finish(session, VerificationResult.Status.FAILED, "email_failed");
                }
            });
        } else {
            qqGateway.watch(session.code(), config.get().qqbot().groupId(), rules.ttlSeconds());
            request.onDispatch().accept(VerificationDispatch.QQ_WATCHING);
        }
    }

    private String generateCode(VerificationChannel channel) {
        VelocityAuthConfig.Verification rules = config.get().verification();
        for (int attempt = 0; attempt < 32; attempt++) {
            String code = channel == VerificationChannel.EMAIL
                    ? randomDigits(Math.max(4, rules.emailCodeLength()))
                    : randomAlphaNumeric(Math.max(4, rules.qqCodeLength()));
            if (!byCode.containsKey(code)) {
                return code;
            }
        }
        throw new IllegalStateException("无法生成唯一验证码");
    }

    private String randomDigits(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('0' + random.nextInt(10)));
        }
        return sb.toString();
    }

    private String randomAlphaNumeric(int length) {
        StringBuilder sb = new StringBuilder(QQ_PREFIX);
        for (int i = 0; i < length; i++) {
            sb.append(QQ_ALPHABET[random.nextInt(QQ_ALPHABET.length)]);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 校验

    public enum SubmitOutcome {
        /** 验证通过（后续操作由 completion future 回调处理）。 */
        OK,
        /** 没有进行中的会话。 */
        NO_SESSION,
        /** 验证码错误。 */
        WRONG_CODE,
        /** 已过期。 */
        EXPIRED,
        /** 尝试次数超限。 */
        TOO_MANY_ATTEMPTS,
        /** 该验证必须在 QQ 群内完成，游戏内输入无效。 */
        WRONG_CHANNEL
    }

    /** 游戏内输入验证码（仅 email 渠道；qq 渠道必须由 bot 回报群消息命中）。 */
    public SubmitOutcome submitCode(UUID playerUuid, String input) {
        VerificationSession session = byPlayer.get(playerUuid);
        if (session == null || session.finished()) {
            return SubmitOutcome.NO_SESSION;
        }
        // 安全：QQ 渠道的码在公开群里传播，若允许游戏内直接输入就完全失去了"证明持有该 QQ"的意义
        if (session.channel() == VerificationChannel.QQ) {
            return SubmitOutcome.WRONG_CHANNEL;
        }
        if (session.expired()) {
            finish(session, VerificationResult.Status.EXPIRED, "expired");
            return SubmitOutcome.EXPIRED;
        }
        if (!session.matches(input)) {
            boolean withinLimit = session.registerAttempt();
            if (!withinLimit) {
                finish(session, VerificationResult.Status.TOO_MANY_ATTEMPTS, "too_many_attempts");
                return SubmitOutcome.TOO_MANY_ATTEMPTS;
            }
            return SubmitOutcome.WRONG_CODE;
        }
        finish(session, VerificationResult.Status.COMPLETED, "completed");
        return SubmitOutcome.OK;
    }

    /**
     * bot 回报：群消息命中验证码。
     *
     * @return 是否消费了该码
     */
    public boolean onQqMatched(String code, String qq, String groupId, boolean groupMember) {
        VerificationSession session = byCode.get(code);
        if (session == null || session.finished() || session.channel() != VerificationChannel.QQ) {
            return false;
        }
        if (config.get().qqbot().requireGroupMember() && !groupMember) {
            log.warn("QQ 验证被拒绝：{} 不是群成员", qq);
            return false;
        }
        session.resolvedValue(qq);
        finish(session, VerificationResult.Status.COMPLETED, "completed");
        return true;
    }

    public void cancel(UUID playerUuid) {
        VerificationSession session = byPlayer.get(playerUuid);
        if (session != null) {
            finish(session, VerificationResult.Status.CANCELLED, "cancelled");
        }
    }

    public Optional<VerificationSession> current(UUID playerUuid) {
        VerificationSession session = byPlayer.get(playerUuid);
        if (session == null || session.finished()) {
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public Optional<VerificationSession> byCode(String code) {
        return Optional.ofNullable(byCode.get(code));
    }

    public int pendingCount() {
        return byPlayer.size();
    }

    /** 兜底清理（正常情况下由定时器完成）。 */
    public void purgeExpired() {
        for (VerificationSession session : List.copyOf(byPlayer.values())) {
            if (session.expired() && !session.finished()) {
                finish(session, VerificationResult.Status.EXPIRED, "expired");
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    private void finish(VerificationSession session, VerificationResult.Status status, String reason) {
        if (!session.markFinishedIfOpen()) {
            return;
        }
        byPlayer.remove(session.playerUuid(), session);
        byCode.remove(session.code(), session);
        if (session.channel() == VerificationChannel.QQ) {
            qqGateway.cancel(session.code());
        }
        CompletableFuture<VerificationResult> completion = completions.remove(session.id());
        if (completion != null) {
            VerificationResult result = new VerificationResult(status, session, reason);
            completion.completeAsync(() -> result, callbackExecutor);
        }
    }
}
