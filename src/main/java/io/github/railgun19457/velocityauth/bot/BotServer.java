package io.github.railgun19457.velocityauth.bot;

import io.github.railgun19457.velocityauth.config.VelocityAuthConfig;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
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
 */
public final class BotServer extends WebSocketServer {

    private final VelocityAuthConfig.QqBot settings;
    private final KeyStore keyStore;
    private final PairingService pairingService;
    private final Supplier<VelocityAuthConfig> config;
    private final PluginLogger log;
    private final Handler handler;

    private final Set<BotSession> sessions = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService housekeeping = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "VelocityAuth-BotHousekeeping");
        thread.setDaemon(true);
        return thread;
    });

    public interface Handler {
        /** bot 回报群消息命中验证码；返回是否消费。 */
        boolean onVerifyMatched(String code, String qq, String groupId, boolean groupMember);

        /** 有 bot 会话就绪（可用于立即补发待验证码）。 */
        default void onSessionReady(BotSession session) {
        }
    }

    public BotServer(VelocityAuthConfig.QqBot settings, KeyStore keyStore, PairingService pairingService,
                     Supplier<VelocityAuthConfig> config, PluginLogger log, Handler handler) {
        super(new InetSocketAddress(settings.listen(), settings.port()));
        this.settings = settings;
        this.keyStore = keyStore;
        this.pairingService = pairingService;
        this.config = config;
        this.log = log;
        this.handler = handler;
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket connection, ClientHandshake handshake) {
        BotSession session = new BotSession(connection, keyStore, pairingService, new BotSession.BotCallbacks() {
            @Override
            public void onReady(BotSession ready) {
                handler.onSessionReady(ready);
            }

            @Override
            public void onClosed(BotSession closed) {
                sessions.remove(closed);
            }

            @Override
            public boolean onVerifyMatched(String code, String qq, String groupId, boolean groupMember) {
                return handler.onVerifyMatched(code, qq, groupId, groupMember);
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

    public int pendingCount() {
        return (int) sessions.stream().filter(s -> !s.ready()).count();
    }

    /** 向所有就绪会话登记一个待验证码（verify.watch）。 */
    public int watch(String code, String groupId, int ttlSeconds) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("code", code);
        payload.put("group_id", groupId);
        payload.put("ttl", ttlSeconds);
        int sent = 0;
        for (BotSession session : readySessions()) {
            try {
                session.sendSealed("verify.watch", payload);
                sent++;
            } catch (RuntimeException e) {
                log.warn("发送 verify.watch 失败：{}", e.getMessage());
            }
        }
        return sent;
    }

    /** 通知 bot 停止监听（verify.cancel）。 */
    public int cancel(String code) {
        Map<String, Object> payload = Map.of("code", code);
        int sent = 0;
        for (BotSession session : readySessions()) {
            try {
                session.sendSealed("verify.cancel", payload);
                sent++;
            } catch (RuntimeException e) {
                log.debug("发送 verify.cancel 失败：{}", e.getMessage());
            }
        }
        return sent;
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
