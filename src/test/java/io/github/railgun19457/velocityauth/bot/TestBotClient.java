package io.github.railgun19457.velocityauth.bot;

import io.github.railgun19457.velocityauth.bot.protocol.Base64Url;
import io.github.railgun19457.velocityauth.bot.protocol.Crypto;
import io.github.railgun19457.velocityauth.bot.protocol.Frame;
import io.github.railgun19457.velocityauth.bot.protocol.Hashes;
import io.github.railgun19457.velocityauth.bot.protocol.KeySchedule;
import io.github.railgun19457.velocityauth.bot.protocol.Sealer;
import io.github.railgun19457.velocityauth.bot.protocol.Transcript;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 测试用 bot 客户端：按 PROTOCOL.md 实现 pair + auth + verify 流程，用于插件侧集成测试。
 */
final class TestBotClient extends WebSocketClient {

    static final String CLIENT_ID = "testbot-1";
    static final String CLIENT_KEY_ID = "bot-k1";
    static final String PROVIDER = "qq";

    private final java.security.KeyPair edPair;
    private final PrivateKey edPrivate;
    private final byte[] edPublic;
    private final java.security.KeyPair ephPair = Crypto.x25519Generate();
    private final PrivateKey ephPrivate = ephPair.getPrivate();
    private final byte[] ephPublic = Crypto.rawX25519PublicKey(ephPair.getPublic());

    private final String pairingCode;

    private final List<String> logs = new CopyOnWriteArrayList<>();
    final CompletableFuture<Boolean> ready = new CompletableFuture<>();
    final CompletableFuture<String> watchReceived = new CompletableFuture<>();
    final CompletableFuture<Boolean> matchedAck = new CompletableFuture<>();
    final CompletableFuture<String> failure = new CompletableFuture<>();
    final java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> queryResults =
            new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> queryErrors =
            new java.util.concurrent.ConcurrentHashMap<>();

    private String helloId;
    private String clientNonce;
    private String pairServerNonce;
    private String authServerNonce;
    private byte[] serverPublicKey;
    private byte[] serverEphemeralKey;
    private Sealer.Outbound outbound;
    private Sealer.Inbound inbound;
    private String sessionId;
    private long counter;

    TestBotClient(URI uri, String pairingCode) {
        this(uri, pairingCode, Crypto.ed25519Generate());
    }

    TestBotClient(URI uri, String pairingCode, java.security.KeyPair edPair) {
        super(uri);
        this.pairingCode = pairingCode;
        this.edPair = edPair;
        this.edPrivate = edPair.getPrivate();
        this.edPublic = Crypto.rawPublicKey(edPair.getPublic());
    }

    /** 长期身份密钥（同一 bot 重连时必须复用）。 */
    java.security.KeyPair identity() {
        return edPair;
    }

    private static String unusedHelper() {
        return null;
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        if (pairingId == null || pairingId.isBlank()) {
            // 已配对客户端：直接进入会话认证
            sendAuthHello();
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pairing_id", pairingId);
        payload.put("client_id", CLIENT_ID);
        payload.put("client_key_id", CLIENT_KEY_ID);
        payload.put("client_public_key", Base64Url.encode(edPublic));
        payload.put("provider", PROVIDER);
        payload.put("capabilities", List.of("verify.v1"));
        helloId = nextId();
        clientNonce = Crypto.randomNonce();
        sendPlain("pair.hello", helloId, clientNonce, payload, true);
    }

    void setPairingId(String value) {
        this.pairingId = value;
    }

    /** 已配对 bot 本地 pin 的服务端公钥。 */
    void setServerPublicKey(byte[] value) {
        this.serverPublicKey = value;
    }

    byte[] serverPublicKey() {
        return serverPublicKey;
    }

    private String pairingId = "";

    @Override
    public void onMessage(String message) {
        try {
            Frame frame = Frame.parse(message);
            if (frame.sealed()) {
                handleSealed(frame);
            } else {
                handlePlain(frame);
            }
        } catch (Exception e) {
            failure.complete(String.valueOf(e));
        }
    }

    private void handlePlain(Frame frame) {
        switch (frame.type()) {
            case "pair.challenge" -> {
                serverPublicKey = Base64Url.decode(frame.str("server_public_key"));
                pairServerNonce = frame.str("server_nonce");
                verify(frame, serverPublicKey, true);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("pairing_id", frame.str("pairing_id"));
                payload.put("challenge_id", frame.str("challenge_id"));
                payload.put("hello_id", helloId);
                payload.put("client_id", CLIENT_ID);
                payload.put("client_key_id", CLIENT_KEY_ID);
                payload.put("client_public_key_sha256", Hashes.sha256B64Url(edPublic));
                payload.put("server_id", frame.str("server_id"));
                payload.put("server_key_id", frame.str("server_key_id"));
                payload.put("server_public_key_sha256", Hashes.sha256B64Url(serverPublicKey));
                payload.put("client_nonce", clientNonce);
                payload.put("server_nonce", pairServerNonce);
                payload.put("selected_v", 1);
                payload.put("client_capabilities_sha256", Transcript.capabilitiesSha256(List.of("verify.v1")));
                payload.put("server_capabilities_sha256",
                        Transcript.capabilitiesSha256(frame.strings("capabilities")));
                sendPlain("pair.prove", nextId(), Crypto.randomNonce(), payload, true);
            }
            case "pair.complete" -> {
                verify(frame, serverPublicKey, true);
                logs.add("paired session=" + frame.str("session_id"));
                sendAuthHello();
            }
            case "auth.challenge" -> {
                serverEphemeralKey = Base64Url.decode(frame.str("server_ephemeral_key"));
                authServerNonce = frame.str("server_nonce");
                verify(frame, serverPublicKey, false);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("challenge_id", frame.str("challenge_id"));
                payload.put("hello_id", helloId);
                payload.put("client_id", CLIENT_ID);
                payload.put("client_key_id", CLIENT_KEY_ID);
                payload.put("client_public_key_sha256", Hashes.sha256B64Url(edPublic));
                payload.put("client_ephemeral_key", Base64Url.encode(ephPublic));
                payload.put("server_id", frame.str("server_id"));
                payload.put("server_key_id", frame.str("server_key_id"));
                payload.put("server_public_key_sha256", Hashes.sha256B64Url(serverPublicKey));
                payload.put("server_ephemeral_key", Base64Url.encode(serverEphemeralKey));
                payload.put("client_nonce", clientNonce);
                payload.put("server_nonce", authServerNonce);
                payload.put("selected_v", 1);
                payload.put("client_capabilities_sha256", Transcript.capabilitiesSha256(List.of("verify.v1")));
                payload.put("server_capabilities_sha256",
                        Transcript.capabilitiesSha256(frame.strings("capabilities")));
                sendPlain("auth.prove", nextId(), Crypto.randomNonce(), payload, false);

                byte[] shared = Crypto.x25519SharedSecret(ephPrivate,
                        Crypto.x25519Public(serverEphemeralKey));
                KeySchedule.SessionKeys keys = KeySchedule.derive(Base64Url.decode(clientNonce),
                        Base64Url.decode(authServerNonce), shared);
                outbound = new Sealer.Outbound(keys.keyC2s(), 0L);
                inbound = new Sealer.Inbound(keys.keyS2c(), 0L);
            }
            case "error" -> failure.complete(frame.str("code") + ": " + frame.str("message"));
            default -> logs.add("unexpected plain " + frame.type());
        }
    }

    private void handleSealed(Frame frame) {
        byte[] plaintext = inbound.open(frame.seq(), Base64Url.decode(frame.ct()));
        Transcript.ParsedMessage message = Transcript.parseMessage(plaintext);
        switch (message.type()) {
            case "auth.result" -> {
                sessionId = String.valueOf(message.payload().get("session_id"));
                sendSealed("auth.confirm", Map.of("session_id", sessionId));
                ready.complete(true);
            }
            case "verify.watch" -> {
                String code = String.valueOf(message.payload().get("code"));
                watchReceived.complete(code);
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("code", code);
                payload.put("subject", "10001");
                payload.put("scope", String.valueOf(message.payload().get("provider")));
                payload.put("display_name", "tester");
                payload.put("matched_at", System.currentTimeMillis() / 1000L);
                sendSealed("verify.matched", payload);
            }
            case "verify.matched.ack" ->
                    matchedAck.complete("true".equals(String.valueOf(message.payload().get("consumed"))));
            case "verify.cancel" -> logs.add("cancelled " + message.payload().get("code"));
            case "query.account.result", "query.lookup.result", "query.history.result",
                    "query.binding-history.result", "query.stats.result",
                    "query.accounts.list.result", "query.sessions.list.result", "query.audit.list.result",
                    "admin.account.set-status.result", "admin.account.reset-password.result",
                    "admin.account.delete.result", "admin.account.kick.result",
                    "admin.account.clear-session.result", "admin.binding.remove.result" ->
                    completeQuery(queryResults, message);
            case "error" -> {
                if (!completeQuery(queryErrors, message)) {
                    logs.add("error " + message.payload().get("code"));
                }
            }
            case "pong" -> logs.add("pong");
            default -> logs.add("sealed " + message.type());
        }
    }

    private static boolean completeQuery(
            java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>> map,
            Transcript.ParsedMessage message) {
        Object requestId = message.payload().get("request_id");
        if (requestId == null) {
            return false;
        }
        CompletableFuture<Map<String, Object>> future = map.get(String.valueOf(requestId));
        if (future == null) {
            return false;
        }
        future.complete(message.payload());
        return true;
    }

    /** 发送一条 {@code query.*} 请求，返回请求 id（用于关联响应）。 */
    String sendQuery(String type, Map<String, Object> payload) {
        String id = sendSealed(type, payload);
        queryResults.putIfAbsent(id, new CompletableFuture<>());
        queryErrors.putIfAbsent(id, new CompletableFuture<>());
        return id;
    }

    Map<String, Object> awaitQueryResult(String requestId, long seconds) throws Exception {
        return queryResults.get(requestId).get(seconds, TimeUnit.SECONDS);
    }

    Map<String, Object> awaitQueryError(String requestId, long seconds) throws Exception {
        return queryErrors.get(requestId).get(seconds, TimeUnit.SECONDS);
    }

    private void sendAuthHello() {
        helloId = nextId();
        clientNonce = Crypto.randomNonce();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("client_id", CLIENT_ID);
        payload.put("client_key_id", CLIENT_KEY_ID);
        payload.put("client_public_key_sha256", Hashes.sha256B64Url(edPublic));
        payload.put("client_ephemeral_key", Base64Url.encode(ephPublic));
        payload.put("client_nonce", clientNonce);
        payload.put("provider", PROVIDER);
        payload.put("capabilities", List.of("verify.v1"));
        sendPlain("auth.hello", helloId, Crypto.randomNonce(), payload, false);
    }

    private void sendPlain(String type, String id, String nonce, Map<String, Object> payload, boolean pairContext) {
        long ts = System.currentTimeMillis() / 1000L;
        String input = Transcript.signatureInput(pairContext ? "pair" : "auth", type, id, ts, nonce, payload,
                pairContext ? pairingCode : null);
        String sig = Base64Url.encode(Crypto.ed25519Sign(edPrivate, Crypto.utf8(input)));
        send(Frame.plain(type, id, ts, nonce, payload, sig).toJson());
    }

    private String sendSealed(String type, Map<String, Object> payload) {
        String id = nextId();
        long ts = System.currentTimeMillis() / 1000L;
        long seq = outbound.nextSeq();
        byte[] ct = outbound.seal(Transcript.messagePlaintext(type, id, ts, payload));
        send(Frame.sealed(seq, Base64Url.encode(ct)).toJson());
        return id;
    }

    private void verify(Frame frame, byte[] publicKey, boolean pairContext) {
        String input = Transcript.signatureInput(pairContext ? "pair" : "auth", frame.type(), frame.id(),
                frame.ts(), frame.nonce(), frame.payload(), pairContext ? pairingCode : null);
        if (!Crypto.ed25519Verify(Crypto.ed25519Public(publicKey), Crypto.utf8(input),
                Base64Url.decode(frame.sig()))) {
            throw new IllegalStateException("服务端签名校验失败：" + frame.type());
        }
    }

    private String nextId() {
        return "c_" + (++counter);
    }

    List<String> logs() {
        return logs;
    }

    String sessionId() {
        return sessionId;
    }

    boolean awaitReady(long seconds) throws Exception {
        return ready.get(seconds, TimeUnit.SECONDS);
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        if (!ready.isDone()) {
            ready.complete(false);
        }
    }

    @Override
    public void onError(Exception ex) {
        if (!failure.isDone()) {
            failure.complete(String.valueOf(ex));
        }
    }
}
