package io.github.railgun19457.velocityauth.verify;

import io.github.railgun19457.velocityauth.auth.Provider;
import io.github.railgun19457.velocityauth.auth.RateLimiter;
import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.lang.LangService;
import io.github.railgun19457.velocityauth.mail.EmailTemplateService;
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
 * <p>
 * 渠道无关：{@code email} 走内置 SMTP；其余渠道（社交平台）由 bot 判定命中后回报。
 */
public final class VerificationService {

    /** 社交渠道码字符集：去除易混淆字符。 */
    private static final char[] SOCIAL_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final String SOCIAL_PREFIX = "MC-";
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);

    private final Supplier<VelocityAuthConfig> config;
    private final Supplier<LangService> lang;
    private final Supplier<String> serverName;
    private final MailSender smtpSender;
    private final EmailTemplateService emailTemplate;
    private final SocialVerificationGateway socialGateway;
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
                               EmailTemplateService emailTemplate,
                               SocialVerificationGateway socialGateway,
                               RateLimiter rateLimiter,
                               Timer timer,
                               Executor callbackExecutor,
                               PluginLogger log) {
        this.config = config;
        this.lang = lang;
        this.serverName = serverName;
        this.smtpSender = smtpSender;
        this.emailTemplate = emailTemplate;
        this.socialGateway = socialGateway;
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

        Optional<Provider> provider = Provider.find(config.get(), request.provider());
        if (provider.isEmpty() || !provider.get().enabled()) {
            request.onDispatch().accept(VerificationDispatch.SOCIAL_UNAVAILABLE);
            return Start.failed("provider_unavailable");
        }
        if (provider.get().email()) {
            if (!smtpSender.available()) {
                request.onDispatch().accept(VerificationDispatch.EMAIL_UNAVAILABLE);
                return Start.failed("email_unavailable");
            }
        } else if (!socialGateway.available(provider.get().id())) {
            request.onDispatch().accept(VerificationDispatch.SOCIAL_UNAVAILABLE);
            return Start.failed("social_unavailable");
        }

        // 同一玩家只允许一个进行中的验证；旧会话标记为被取代
        VerificationSession existing = byPlayer.get(request.playerUuid());
        if (existing != null) {
            finish(existing, VerificationResult.Status.REPLACED, "replaced");
        }

        String code = generateCode(provider.get(), rules);
        long now = System.currentTimeMillis();
        long ttlMillis = rules.ttlSeconds() * 1000L;
        VerificationSession session = new VerificationSession(
                UUID.randomUUID().toString(), request.purpose(), provider.get().id(), code,
                request.playerUuid(), request.accountName(), request.connectionName(), request.ip(),
                request.target(), request.data(), now, now + ttlMillis, rules.maxAttempts());

        CompletableFuture<VerificationResult> completion = new CompletableFuture<>();
        byPlayer.put(session.playerUuid(), session);
        byCode.put(code, session);
        completions.put(session.id(), completion);

        timer.schedule(() -> finish(session, VerificationResult.Status.EXPIRED, "expired"),
                Duration.ofSeconds(rules.ttlSeconds()));

        dispatch(request, session, provider.get(), rules);
        return new Start(session, completion, null);
    }

    private void dispatch(VerificationRequest request, VerificationSession session, Provider provider,
                          VelocityAuthConfig.Verification rules) {
        long minutes = Math.max(1, rules.ttlSeconds() / 60);
        if (provider.email()) {
            // {action} 取语言文件里的操作名称，让邮件能区分注册 / 绑定 / 找回等场景
            String action = lang.get().rawText(session.purpose().actionLangKey());
            String subject = emailTemplate.subject(serverName.get(), action);
            String text = emailTemplate.text(session.code(), minutes, serverName.get(), action);
            String html = emailTemplate.html(session.code(), minutes, serverName.get(), action);
            smtpSender.send(session.target(), subject, text, html).thenAccept(ok -> {
                if (Boolean.TRUE.equals(ok)) {
                    request.onDispatch().accept(VerificationDispatch.EMAIL_SENT);
                } else {
                    request.onDispatch().accept(VerificationDispatch.EMAIL_FAILED);
                    finish(session, VerificationResult.Status.FAILED, "email_failed");
                }
            });
        } else {
            socialGateway.watch(provider.id(), session.code(), rules.ttlSeconds());
            request.onDispatch().accept(VerificationDispatch.SOCIAL_WATCHING);
        }
    }

    private String generateCode(Provider provider, VelocityAuthConfig.Verification rules) {
        int length = provider.email() || provider.codeLength() <= 0
                ? Math.max(4, rules.codeLength())
                : Math.max(4, provider.codeLength());
        for (int attempt = 0; attempt < 32; attempt++) {
            String code = provider.email() ? randomDigits(length) : randomAlphaNumeric(length);
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
        StringBuilder sb = new StringBuilder(SOCIAL_PREFIX);
        for (int i = 0; i < length; i++) {
            sb.append(SOCIAL_ALPHABET[random.nextInt(SOCIAL_ALPHABET.length)]);
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
        /** 该验证必须在社交渠道内完成，游戏内输入无效。 */
        WRONG_CHANNEL
    }

    /** 游戏内输入验证码（仅 email 渠道；社交渠道必须由 bot 回报命中）。 */
    public SubmitOutcome submitCode(UUID playerUuid, String input) {
        VerificationSession session = byPlayer.get(playerUuid);
        if (session == null || session.finished()) {
            return SubmitOutcome.NO_SESSION;
        }
        // 安全：社交渠道的码在公开房间传播，若允许游戏内直接输入就完全失去了"证明持有该账号"的意义
        if (!isEmail(session.provider())) {
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
     * bot 回报：社交渠道命中验证码。
     * <p>
     * 平台策略（房间、成员、账号格式）已由 bot 侧判定；插件只做渠道无关的接受（码命中、未过期、未完成）。
     *
     * @return 是否消费了该码
     */
    public boolean onSocialMatched(String providerId, String code, String subject, String scope, String displayName) {
        VerificationSession session = byCode.get(code);
        if (session == null || session.finished() || isEmail(session.provider())) {
            return false;
        }
        if (providerId != null && !providerId.isBlank() && !providerId.equals(session.provider())) {
            log.warn("社交验证被拒绝：上报渠道 {} 与发起渠道 {} 不一致", providerId, session.provider());
            return false;
        }
        if (subject == null || subject.isBlank()) {
            log.warn("社交验证被拒绝：缺少 subject");
            return false;
        }
        session.resolvedValue(subject);
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

    private static boolean isEmail(String providerId) {
        return Provider.EMAIL_ID.equals(providerId);
    }

    private void finish(VerificationSession session, VerificationResult.Status status, String reason) {
        if (!session.markFinishedIfOpen()) {
            return;
        }
        byPlayer.remove(session.playerUuid(), session);
        byCode.remove(session.code(), session);
        if (!isEmail(session.provider())) {
            socialGateway.cancel(session.provider(), session.code());
        }
        CompletableFuture<VerificationResult> completion = completions.remove(session.id());
        if (completion != null) {
            VerificationResult result = new VerificationResult(status, session, reason);
            completion.completeAsync(() -> result, callbackExecutor);
        }
    }
}
