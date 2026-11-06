package io.github.railgun19457.velocityauth.bot;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * bot 通道服务端（PROTOCOL.md 1：插件监听，bot 主动连接）。
 * <p>
 * 平台无关：bot 在握手时声明它服务的 {@code provider}，插件据此路由 {@code verify.watch}。
 */
public final class BotServer extends WebSocketServer {

    private final VelocityAuthConfig.Bot settings;
    private final KeyStore keyStore;
    private final PairingService pairingService;
    private final Supplier<VelocityAuthConfig> config;
    private final PluginLogger log;
    private final Handler handler;
    private final BotQueryHandler queryHandler;
    private final BotAdminHandler adminHandler;

    private final Set<BotSession> sessions = ConcurrentHashMap.newKeySet();
    /** admin.* 限流（按 client_id 计数）。 */
    private final io.github.railgun19457.velocityauth.auth.RateLimiter adminRateLimiter =
            new io.github.railgun19457.velocityauth.auth.RateLimiter();
    private final ScheduledExecutorService housekeeping = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "VelocityAuth-BotHousekeeping");
        thread.setDaemon(true);
        return thread;
    });

    public interface Handler {
        /** bot 回报社交渠道命中验证码；返回是否消费。 */
        boolean onVerifyMatched(String providerId, String code, String subject, String scope, String displayName);

        /** 有 bot 会话就绪（可用于立即补发待验证码）。 */
        default void onSessionReady(BotSession session) {
        }
    }

    public BotServer(VelocityAuthConfig.Bot settings, KeyStore keyStore, PairingService pairingService,
                     Supplier<VelocityAuthConfig> config, PluginLogger log, Handler handler,
                     BotQueryHandler queryHandler, BotAdminHandler adminHandler) {
        super(new InetSocketAddress(settings.listen(), settings.port()));
        this.settings = settings;
        this.keyStore = keyStore;
        this.pairingService = pairingService;
        this.config = config;
        this.log = log;
        this.handler = handler;
        this.queryHandler = queryHandler;
        this.adminHandler = adminHandler;
        setReuseAddr(true);
    }

    /** 本实例声明的服务端能力；按配置附带 {@code query.v1} / {@code admin.v1}。 */
    public List<String> capabilities() {
        List<String> out = new java.util.ArrayList<>();
        out.add(BotSession.CAPABILITY_VERIFY);
        if (queryHandler != null) {
            out.add(BotSession.CAPABILITY_QUERY);
        }
        if (adminHandler != null) {
            out.add(BotSession.CAPABILITY_ADMIN);
        }
        return List.copyOf(out);
    }

    /**
     * 该 bot 客户端是否被允许发送 {@code admin.*}。
     * <p>
     * 判定链：管理接口已启用 → 客户端在 {@code bot.admin.clients} 白名单内（空列表表示不限制）。
     */
    private boolean adminAllowed(String clientId) {
        if (adminHandler == null) {
            return false;
        }
        List<String> whitelist = config.get().bot().admin().clients();
        if (whitelist.isEmpty()) {
            return true;
        }
        for (String allowed : whitelist) {
            if (allowed.equalsIgnoreCase(clientId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        BotSession session = new BotSession(connection, keyStore, pairingService, capabilities(),
                new BotSession.BotCallbacks() {
            @Override
            public void onReady(BotSession ready) {
                handler.onSessionReady(ready);
            }

            @Override
            public void onClosed(BotSession closed) {
                sessions.remove(closed);
            }

            @Override
            public boolean onVerifyMatched(String providerId, String code, String subject, String scope,
                                           String displayName) {
                return handler.onVerifyMatched(providerId, code, subject, scope, displayName);
            }

            @Override
            public QueryResponse onQuery(String type, Map<String, Object> payload) {
                return queryHandler == null ? null : queryHandler.handle(type, payload);
            }

            @Override
            public QueryResponse onAdmin(String type, Map<String, Object> payload, String clientId,
                                         String remoteIp) {
                if (adminHandler == null || !adminAllowed(clientId)) {
                    log.warn("拒绝未授权的 bot 管理请求：client_id={} type={}", clientId, type);
                    return null;
                }
                if (!adminRateLimiter.tryAcquire("admin:" + clientId,
                        config.get().bot().admin().rateLimitPerMinute(), java.time.Duration.ofMinutes(1))) {
                    return QueryResponse.error("rate_limited", "管理操作过于频繁");
                }
                return adminHandler.handle(type, payload, clientId, remoteIp);
            }

            @Override
            public int clockSkewSeconds() {
                return config.get().security().clockSkewSeconds();
            }
        }, log);
        connection.setAttachment(session);
        sessions.add(session);
        log.debug("bot 连接建立：{}", session.remoteAddress());

        // 握手超时（PROTOCOL.md 10-12）
        int timeout = Math.max(1, config.get().security().handshakeTimeoutSeconds());
        housekeeping.schedule(() -> {
            if (!session.ready() && session.state() != BotSession.State.CLOSED) {
                log.warn("bot 握手超时（{}s），关闭连接", timeout);
                session.close();
            }
        }, timeout, TimeUnit.SECONDS);
    }

    @Override
    public void onClose(WebSocket connection, int code, String reason, boolean remote) {
        BotSession session = connection.getAttachment();
        if (session != null) {
            sessions.remove(session);
            session.close();
        }
        log.debug("bot 连接关闭（code={} remote={}）", code, remote);
    }

    @Override
    public void onMessage(WebSocket connection, String message) {
        BotSession session = connection.getAttachment();
        if (session == null) {
            connection.close(1011, "no session");
            return;
        }
        session.handleMessage(message);
    }

    @Override
    public void onError(WebSocket connection, Exception ex) {
        if (connection == null) {
            log.error("bot 服务端错误：" + ex.getMessage(), ex);
            return;
        }
        BotSession session = connection.getAttachment();
        log.warn("bot 连接错误（{}）：{}", session == null ? "?" : session.remoteAddress(), ex.toString());
    }

    @Override
    public void onStart() {
        log.info("bot 通道已监听 {}:{}", settings.listen(), settings.port());
    }

    // ------------------------------------------------------------------ 对外

    public List<BotSession> sessions() {
        return List.copyOf(sessions);
    }

    public List<BotSession> readySessions() {
        return sessions.stream().filter(BotSession::ready).toList();
    }

    public int readyCount() {
        return (int) sessions.stream().filter(BotSession::ready).count();
    }

    /** 服务指定渠道的已就绪 bot 数量。 */
    public int readyCount(String providerId) {
        return (int) sessions.stream().filter(BotSession::ready)
                .filter(session -> providerId.equals(session.provider())).count();
    }

    public int pendingCount() {
        return (int) sessions.stream().filter(s -> !s.ready()).count();
    }

    /** 向服务 {@code providerId} 的就绪会话登记一个待验证码（verify.watch）。 */
    public int watch(String providerId, String code, int ttlSeconds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("ttl", ttlSeconds);
        payload.put("provider", providerId);
        int sent = 0;
        for (BotSession session : readySessions()) {
            if (!providerId.equals(session.provider())) {
                continue;
            }
            try {
                session.sendSealed("verify.watch", payload);
                sent++;
            } catch (RuntimeException e) {
                log.warn("发送 verify.watch 失败：{}", e.getMessage());
            }
        }
        return sent;
    }

    /** 通知服务该渠道的 bot 停止监听（verify.cancel）。 */
    public int cancel(String providerId, String code) {
        Map<String, Object> payload = Map.of("code", code);
        int sent = 0;
        for (BotSession session : readySessions()) {
            if (!providerId.equals(session.provider())) {
                continue;
            }
            try {
                session.sendSealed("verify.cancel", payload);
                sent++;
            } catch (RuntimeException e) {
                log.debug("发送 verify.cancel 失败：{}", e.getMessage());
            }
        }
        return sent;
    }

    /** 该渠道 bot 上报的监听位置展示串（去重，逗号连接）；无则返回空串。 */
    public String watchScopeDisplay(String providerId) {
        Set<String> scopes = new LinkedHashSet<>();
        for (BotSession session : sessions) {
            if (!session.ready() || !providerId.equals(session.provider()) || session.scopes() == null) {
                continue;
            }
            for (String part : session.scopes().split(",")) {
                if (!part.isBlank()) {
                    scopes.add(part.trim());
                }
            }
        }
        return String.join(", ", scopes);
    }

    public void shutdown() {
        housekeeping.shutdownNow();
        for (BotSession session : sessions) {
            session.close();
        }
        try {
            stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("关闭 bot 通道失败：{}", e.getMessage());
        }
    }
}
