package io.github.railgun19457.velocityauth.bot;

import com.google.gson.JsonObject;
import io.github.railgun19457.velocityauth.bot.protocol.Base64Url;
import io.github.railgun19457.velocityauth.bot.protocol.Crypto;
import io.github.railgun19457.velocityauth.bot.protocol.Frame;
import io.github.railgun19457.velocityauth.bot.protocol.Hashes;
import io.github.railgun19457.velocityauth.bot.protocol.Json;
import io.github.railgun19457.velocityauth.bot.protocol.KeySchedule;
import io.github.railgun19457.velocityauth.bot.protocol.ProtocolException;
import io.github.railgun19457.velocityauth.bot.protocol.Sealer;
import io.github.railgun19457.velocityauth.bot.protocol.Transcript;
import io.github.railgun19457.velocityauth.util.PluginLogger;
import org.java_websocket.WebSocket;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单条 bot 连接的状态机（PROTOCOL.md 4 / 5 / 8）。
 */
public final class BotSession {

    public enum State {
        AWAIT_PAIR_HELLO,
        AWAIT_PAIR_PROVE,
        AWAIT_AUTH_HELLO,
        AWAIT_AUTH_PROVE,
        READY,
        CLOSED
    }

    public static final String CAPABILITY_VERIFY = "verify.v1";
    public static final String CAPABILITY_QUERY = "query.v1";
    public static final String CAPABILITY_ADMIN = "admin.v1";

    private final WebSocket connection;
    private final KeyStore keyStore;
    private final PairingService pairingService;
    private final BotCallbacks callbacks;
    private final PluginLogger log;
    private final List<String> serverCapabilities;

    private final Set<String> seenIds = new HashSet<>();
    private final AtomicLong frameCounter = new AtomicLong();

    private State state = State.AWAIT_PAIR_HELLO;
    private String sessionId;
    private String clientId;
    private String clientKeyId;
    /** bot 服务的渠道 id（握手声明）。 */
    private String provider;
    /** bot 上报的监听位置展示串（可选）。 */
    private String scopes;

    // ---- 配对上下文 ----
    private String pairingId;
    private String pairingCode;
    private String helloId;
    private String helloNonce;
    private byte[] clientPublicKey;
    private List<String> clientCapabilities = List.of();
    private String pairChallengeId;
    private String pairServerNonce;
    private volatile long pairChallengeExpiresAt;

    // ---- 会话上下文 ----
    private byte[] clientEphemeralKey;
    private String clientNonce;
    private String authChallengeId;
    private String authServerNonce;
    private volatile long authChallengeExpiresAt;
    private PrivateKey serverEphemeralPrivate;
    private byte[] serverEphemeralPublic;
    private volatile Sealer.Outbound outbound;
    private volatile Sealer.Inbound inbound;
    private volatile long lastActivityAt = System.currentTimeMillis();

    public BotSession(WebSocket connection, KeyStore keyStore, PairingService pairingService,
                      List<String> serverCapabilities, BotCallbacks callbacks, PluginLogger log) {
        this.connection = connection;
        this.keyStore = keyStore;
        this.pairingService = pairingService;
        this.serverCapabilities = List.copyOf(serverCapabilities);
        this.callbacks = callbacks;
        this.log = log;
    }

    public interface BotCallbacks {
        void onReady(BotSession session);

        void onClosed(BotSession session);

        /** @return 是否消费了该验证码 */
        boolean onVerifyMatched(String providerId, String code, String subject, String scope, String displayName);

        /** 处理一条 {@code query.*} 业务消息；返回 {@code null} 表示查询接口未启用。 */
        default QueryResponse onQuery(String type, Map<String, Object> payload) {
            return null;
        }

        /**
         * 处理一条 {@code admin.*} 管理消息；返回 {@code null} 表示管理接口未启用，
         * 或该客户端未被授权（两者对 bot 都表现为 {@code admin_disabled}）。
         *
         * @param clientId 发起请求的已配对客户端 id
         * @param remoteIp bot 连接的远端地址（仅用于审计）
         */
        default QueryResponse onAdmin(String type, Map<String, Object> payload, String clientId, String remoteIp) {
            return null;
        }

        /** 握手帧允许的时间偏差（秒）。 */
        default int clockSkewSeconds() {
            return 120;
        }
    }

    // ------------------------------------------------------------------ 入口

    public void handleMessage(String text) {
        if (state == State.CLOSED) {
            return;
        }
        lastActivityAt = System.currentTimeMillis();
        try {
            Frame frame = Frame.parse(text);
            if (frame.sealed()) {
                handleSealed(frame);
            } else {
                handlePlain(frame);
            }
        } catch (ProtocolException e) {
            fail(e.code(), e.getMessage());
        } catch (Exception e) {
            log.warn("bot 连接处理异常：{}", e.toString());
            fail(ProtocolException.INTERNAL_ERROR, "internal error");
        }
    }

    private void handlePlain(Frame frame) {
        if (state == State.READY) {
            throw new ProtocolException(ProtocolException.INTERNAL_ERROR, "握手完成后必须使用密封帧");
        }
        requireFreshId(frame);
        requireFreshTimestamp(frame);

        switch (state) {
            case AWAIT_PAIR_HELLO -> {
                if (!"pair.hello".equals(frame.type())) {
                    if ("auth.hello".equals(frame.type())) {
                        handleAuthHello(frame);
                    } else {
                        throw new ProtocolException(ProtocolException.INTERNAL_ERROR,
                                "期望 pair.hello，收到 " + frame.type());
                    }
                } else {
                    handlePairHello(frame);
                }
            }
            case AWAIT_PAIR_PROVE -> {
                if (!"pair.prove".equals(frame.type())) {
                    throw new ProtocolException(ProtocolException.INTERNAL_ERROR,
                            "期望 pair.prove，收到 " + frame.type());
                }
                handlePairProve(frame);
            }
            case AWAIT_AUTH_HELLO -> {
                if (!"auth.hello".equals(frame.type())) {
                    throw new ProtocolException(ProtocolException.INTERNAL_ERROR,
                            "期望 auth.hello，收到 " + frame.type());
                }
                handleAuthHello(frame);
            }
            case AWAIT_AUTH_PROVE -> {
                if (!"auth.prove".equals(frame.type())) {
                    throw new ProtocolException(ProtocolException.INTERNAL_ERROR,
                            "期望 auth.prove，收到 " + frame.type());
                }
                handleAuthProve(frame);
            }
            default -> throw new ProtocolException(ProtocolException.INTERNAL_ERROR, "状态异常");
        }
    }

    private void requireFreshId(Frame frame) {
        if (!seenIds.add(frame.id())) {
            throw new ProtocolException(ProtocolException.INTERNAL_ERROR, "帧 id 重复：" + frame.id());
        }
    }

    private void requireFreshTimestamp(Frame frame) {
        long skew = Math.abs(System.currentTimeMillis() / 1000L - frame.ts());
        int allowed = callbacks.clockSkewSeconds();
        if (skew > allowed) {
            throw new ProtocolException(ProtocolException.SIGNATURE_INVALID,
                    "帧时间偏差过大（" + skew + "s > " + allowed + "s）");
        }
    }

    // ------------------------------------------------------------------ 配对

    private void handlePairHello(Frame frame) {
        String requestedPairingId = frame.str("pairing_id");
        if (requestedPairingId == null) {
            throw new ProtocolException(ProtocolException.PAIRING_MISSING, "缺少 pairing_id");
        }
        Optional<PairingService.Pairing> pairing = pairingService.find(requestedPairingId);
        if (pairing.isEmpty()) {
            throw new ProtocolException(ProtocolException.PAIRING_MISSING, "配对记录不存在或已过期");
        }
        PairingService.Pairing record = pairing.get();

        String clientPublicKeyRaw = frame.str("client_public_key");
        if (clientPublicKeyRaw == null) {
            throw new ProtocolException(ProtocolException.SIGNATURE_INVALID, "缺少 client_public_key");
        }
        byte[] publicKey;
        try {
            publicKey = Base64Url.decode(clientPublicKeyRaw);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(ProtocolException.SIGNATURE_INVALID, "client_public_key 不是合法 base64url");
        }
        if (publicKey.length != Crypto.RAW_KEY_LENGTH) {
            throw new ProtocolException(ProtocolException.SIGNATURE_INVALID, "client_public_key 长度必须为 32 字节");
        }

        String input = Transcript.signatureInput("pair", frame.type(), frame.id(), frame.ts(), frame.nonce(),
                frame.payload(), record.code());
        if (!verifySignature(publicKey, input, frame.sig())) {
            pairingService.registerFailure(record.pairingId());
            throw new ProtocolException(ProtocolException.SIGNATURE_INVALID, "pair.hello 验签失败");
        }

        this.pairingId = record.pairingId();
        this.pairingCode = record.code();
        this.helloId = frame.id();
        this.helloNonce = frame.nonce();
        this.clientId = frame.str("client_id");
        this.clientKeyId = frame.str("client_key_id");
        this.clientPublicKey = publicKey;
        this.clientCapabilities = frame.strings("capabilities");
        this.provider = frame.str("provider");
        this.scopes = frame.str("scopes");
        this.state = State.AWAIT_PAIR_PROVE;

        String challengeId = "ch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String serverNonce = Crypto.randomNonce();
        long expiresAt = System.currentTimeMillis() / 1000L + 600L;
        this.pairChallengeId = challengeId;
        this.pairServerNonce = serverNonce;
        this.pairChallengeExpiresAt = expiresAt;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pairing_id", pairingId);
        payload.put("challenge_id", challengeId);
        payload.put("expires_at", expiresAt);
        payload.put("server_id", keyStore.serverId());
        payload.put("server_key_id", keyStore.keyId());
        payload.put("server_public_key", Base64Url.encode(keyStore.publicKey()));
        payload.put("server_nonce", serverNonce);
        payload.put("selected_v", Frame.VERSION);
        payload.put("capabilities", serverCapabilities);
        sendPlain("pair.challenge", payload, pairingCode);
    }

    private void handlePairProve(Frame frame) {
        // frame.ts() 已是 unix 秒；challenge 过期时间同样是秒，直接比较即可
        if (frame.ts() > pairChallengeExpiresAt) {
            pairingService.consume(pairingId);
            throw new ProtocolException(ProtocolException.PAIRING_EXPIRED, "配对 challenge 已过期");
        }
        String input = Transcript.signatureInput("pair", frame.type(), frame.id(), frame.ts(), frame.nonce(),
                frame.payload(), pairingCode);
        if (!verifySignature(clientPublicKey, input, frame.sig())) {
            boolean withinLimit = pairingService.registerFailure(pairingId);
            throw new ProtocolException(
                    withinLimit ? ProtocolException.SIGNATURE_INVALID : ProtocolException.PAIRING_ATTEMPTS_EXCEEDED,
                    "pair.prove 验签失败");
        }

        requireEquals(frame.str("pairing_id"), pairingId, "pairing_id");
        requireEquals(frame.str("challenge_id"), pairChallengeId, "challenge_id");
        requireEquals(frame.str("hello_id"), helloId, "hello_id");
        requireEquals(frame.str("client_id"), clientId, "client_id");
        requireEquals(frame.str("client_key_id"), clientKeyId, "client_key_id");
        requireEquals(frame.str("client_public_key_sha256"), Hashes.sha256B64Url(clientPublicKey),
                "client_public_key_sha256");
        requireEquals(frame.str("server_id"), keyStore.serverId(), "server_id");
        requireEquals(frame.str("server_key_id"), keyStore.keyId(), "server_key_id");
        requireEquals(frame.str("server_public_key_sha256"), keyStore.fingerprint(),
                "server_public_key_sha256");
        requireEquals(frame.str("client_nonce"), helloNonce, "client_nonce");
        requireEquals(frame.str("server_nonce"), pairServerNonce, "server_nonce");
        requireEquals(frame.str("client_capabilities_sha256"),
                Transcript.capabilitiesSha256(clientCapabilities), "client_capabilities_sha256");
        requireEquals(frame.str("server_capabilities_sha256"),
                Transcript.capabilitiesSha256(serverCapabilities), "server_capabilities_sha256");
        Long selectedV = frame.number("selected_v");
        if (selectedV == null || selectedV != Frame.VERSION) {
            throw new ProtocolException(ProtocolException.VERSION_UNSUPPORTED, "selected_v 不匹配");
        }

        String fingerprint = Hashes.sha256B64Url(clientPublicKey);
        keyStore.pair(new KeyStore.PairedClient(clientId, clientKeyId, provider, clientPublicKey, fingerprint,
                System.currentTimeMillis() / 1000L, System.currentTimeMillis() / 1000L));
        pairingService.consume(pairingId);

        String newSessionId = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("pairing_id", pairingId);
        payload.put("session_id", newSessionId);
        payload.put("client_key_id", clientKeyId);
        payload.put("client_key_fingerprint", fingerprint);
        payload.put("server_key_id", keyStore.keyId());
        payload.put("server_key_fingerprint", keyStore.fingerprint());
        payload.put("server_time", System.currentTimeMillis() / 1000L);
        payload.put("selected_v", Frame.VERSION);
        payload.put("capabilities", serverCapabilities);
        sendPlain("pair.complete", payload, pairingCode);

        log.info("bot 配对完成：client_id={} key_id={}", clientId, clientKeyId);
        this.state = State.AWAIT_AUTH_HELLO;
        this.pairingCode = null;
    }

    // ------------------------------------------------------------------ 会话认证

    private void handleAuthHello(Frame frame) {
        String requestedClientId = frame.str("client_id");
        String requestedKeyId = frame.str("client_key_id");
        String fingerprint = frame.str("client_public_key_sha256");
        if (requestedClientId == null || requestedKeyId == null || fingerprint == null) {
            throw new ProtocolException(ProtocolException.SIGNATURE_INVALID, "auth.hello 字段缺失");
        }
        Optional<KeyStore.PairedClient> paired = keyStore.find(requestedClientId, requestedKeyId);
        if (paired.isEmpty() || !paired.get().fingerprint().equals(fingerprint)) {
            throw new ProtocolException(ProtocolException.KEY_NOT_PAIRED, "client_key_id 未配对或指纹不匹配");
        }
        byte[] publicKey = paired.get().publicKey();

        String input = Transcript.signatureInput("auth", frame.type(), frame.id(), frame.ts(), frame.nonce(),
                frame.payload(), null);
        if (!verifySignature(publicKey, input, frame.sig())) {
            throw new ProtocolException(ProtocolException.SIGNATURE_INVALID, "auth.hello 验签失败");
        }

        String ephemeral = frame.str("client_ephemeral_key");
        if (ephemeral == null) {
            throw new ProtocolException(ProtocolException.SIGNATURE_INVALID, "缺少 client_ephemeral_key");
        }
        byte[] ephemeralRaw = Base64Url.decode(ephemeral);
        if (ephemeralRaw.length != Crypto.RAW_KEY_LENGTH) {
            throw new ProtocolException(ProtocolException.SIGNATURE_INVALID, "client_ephemeral_key 长度非法");
        }

        this.clientId = requestedClientId;
        this.clientKeyId = requestedKeyId;
        this.clientPublicKey = publicKey;
        this.clientEphemeralKey = ephemeralRaw;
        this.clientNonce = frame.str("client_nonce");
        this.clientCapabilities = frame.strings("capabilities");
        this.provider = frame.str("provider");
        this.scopes = frame.str("scopes");
        this.helloId = frame.id();

        var ephemeralPair = Crypto.x25519Generate();
        this.serverEphemeralPrivate = ephemeralPair.getPrivate();
        this.serverEphemeralPublic = Crypto.rawX25519PublicKey(ephemeralPair.getPublic());

        String challengeId = "ch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String serverNonce = Crypto.randomNonce();
        long expiresAt = System.currentTimeMillis() / 1000L + 60L;
        this.authChallengeId = challengeId;
        this.authServerNonce = serverNonce;
        this.authChallengeExpiresAt = expiresAt;
        this.state = State.AWAIT_AUTH_PROVE;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("challenge_id", challengeId);
        payload.put("expires_at", expiresAt);
        payload.put("server_id", keyStore.serverId());
        payload.put("server_key_id", keyStore.keyId());
        payload.put("server_public_key_sha256", keyStore.fingerprint());
        payload.put("server_ephemeral_key", Base64Url.encode(serverEphemeralPublic));
        payload.put("server_nonce", serverNonce);
        payload.put("selected_v", Frame.VERSION);
        payload.put("capabilities", serverCapabilities);
        sendPlain("auth.challenge", payload, null);
    }

    private void handleAuthProve(Frame frame) {
        // frame.ts() 已是 unix 秒；challenge 过期时间同样是秒，直接比较即可
        if (frame.ts() > authChallengeExpiresAt) {
            throw new ProtocolException(ProtocolException.SIGNATURE_INVALID, "auth challenge 已过期");
        }
        String input = Transcript.signatureInput("auth", frame.type(), frame.id(), frame.ts(), frame.nonce(),
                frame.payload(), null);
        if (!verifySignature(clientPublicKey, input, frame.sig())) {
            throw new ProtocolException(ProtocolException.SIGNATURE_INVALID, "auth.prove 验签失败");
        }

        requireEquals(frame.str("challenge_id"), authChallengeId, "challenge_id");
        requireEquals(frame.str("hello_id"), helloId, "hello_id");
        requireEquals(frame.str("client_id"), clientId, "client_id");
        requireEquals(frame.str("client_key_id"), clientKeyId, "client_key_id");
        requireEquals(frame.str("client_public_key_sha256"), Hashes.sha256B64Url(clientPublicKey),
                "client_public_key_sha256");
        requireEquals(frame.str("client_ephemeral_key"), Base64Url.encode(clientEphemeralKey),
                "client_ephemeral_key");
        requireEquals(frame.str("server_id"), keyStore.serverId(), "server_id");
        requireEquals(frame.str("server_key_id"), keyStore.keyId(), "server_key_id");
        requireEquals(frame.str("server_public_key_sha256"), keyStore.fingerprint(),
                "server_public_key_sha256");
        requireEquals(frame.str("server_ephemeral_key"), Base64Url.encode(serverEphemeralPublic),
                "server_ephemeral_key");
        requireEquals(frame.str("client_nonce"), clientNonce, "client_nonce");
        requireEquals(frame.str("server_nonce"), authServerNonce, "server_nonce");
        requireEquals(frame.str("client_capabilities_sha256"),
                Transcript.capabilitiesSha256(clientCapabilities), "client_capabilities_sha256");
        requireEquals(frame.str("server_capabilities_sha256"),
                Transcript.capabilitiesSha256(serverCapabilities), "server_capabilities_sha256");
        Long selectedV = frame.number("selected_v");
        if (selectedV == null || selectedV != Frame.VERSION) {
            throw new ProtocolException(ProtocolException.VERSION_UNSUPPORTED, "selected_v 不匹配");
        }

        byte[] shared = Crypto.x25519SharedSecret(serverEphemeralPrivate,
                Crypto.x25519Public(clientEphemeralKey));
        byte[] clientNonceRaw = Base64Url.decode(clientNonce);
        byte[] serverNonceRaw = Base64Url.decode(authServerNonce);
        KeySchedule.SessionKeys keys = KeySchedule.derive(clientNonceRaw, serverNonceRaw, shared);

        this.outbound = new Sealer.Outbound(keys.keyS2c(), 0L);
        this.inbound = new Sealer.Inbound(keys.keyC2s(), 0L);
        this.sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("session_id", sessionId);
        payload.put("server_time", System.currentTimeMillis() / 1000L);
        payload.put("capabilities", serverCapabilities);
        sendSealed("auth.result", payload);
    }

    // ------------------------------------------------------------------ 业务消息

    private void handleSealed(Frame frame) {
        if (state != State.READY && state != State.AWAIT_AUTH_PROVE) {
            throw new ProtocolException(ProtocolException.DECRYPT_FAILED, "尚未完成握手");
        }
        byte[] plaintext = inbound.open(frame.seq(), Base64Url.decode(frame.ct()));
        Transcript.ParsedMessage message = Transcript.parseMessage(plaintext);

        if (state == State.AWAIT_AUTH_PROVE) {
            if (!"auth.confirm".equals(message.type())) {
                throw new ProtocolException(ProtocolException.INTERNAL_ERROR,
                        "期望 auth.confirm，收到 " + message.type());
            }
            requireEquals(String.valueOf(message.payload().get("session_id")), sessionId, "session_id");
            this.state = State.READY;
            keyStore.touch(clientId, clientKeyId);
            log.info("bot 会话已建立：client_id={} session_id={}", clientId, sessionId);
            callbacks.onReady(this);
            return;
        }

        switch (message.type()) {
            case "ping" -> sendSealed("pong", Map.of());
            case "pong" -> keyStore.touch(clientId, clientKeyId);
            case "verify.watch.ack" -> log.debug("bot 已登记验证码：accepted={}",
                    message.payload().get("accepted"));
            case "verify.matched" -> handleVerifyMatched(message);
            case "query.account", "query.lookup", "query.history", "query.binding-history", "query.stats",
                    "query.accounts.list", "query.sessions.list", "query.audit.list" ->
                    handleQuery(message);
            case "admin.account.set-status", "admin.account.reset-password", "admin.account.delete",
                    "admin.account.kick", "admin.account.clear-session", "admin.binding.remove" ->
                    handleAdmin(message);
            case "error" -> log.warn("bot 报告错误：{} {}",
                    message.payload().get("code"), message.payload().get("message"));
            default -> log.debug("忽略未知业务消息：{}", message.type());
        }
    }

    private void handleVerifyMatched(Transcript.ParsedMessage message) {
        String code = String.valueOf(message.payload().get("code"));
        String subject = str(message.payload().get("subject"));
        String scope = str(message.payload().get("scope"));
        String displayName = str(message.payload().get("display_name"));

        boolean consumed = callbacks.onVerifyMatched(provider, code, subject, scope, displayName);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("consumed", consumed);
        sendSealed("verify.matched.ack", payload);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private void handleQuery(Transcript.ParsedMessage message) {
        QueryResponse response = callbacks.onQuery(message.type(), message.payload());
        if (response == null) {
            sendSealed("error", Map.of("code", "query_disabled", "message", "查询接口未启用",
                    "request_id", message.id()));
            return;
        }
        respond(message, response);
    }

    private void handleAdmin(Transcript.ParsedMessage message) {
        QueryResponse response = callbacks.onAdmin(message.type(), message.payload(), clientId, remoteAddress());
        if (response == null) {
            sendSealed("error", Map.of("code", "admin_disabled", "message", "管理接口未启用或该客户端未获授权",
                    "request_id", message.id()));
            return;
        }
        respond(message, response);
    }

    /** 统一把 {@link QueryResponse} 回写为 {@code <type>.result} 或密封 {@code error}。 */
    private void respond(Transcript.ParsedMessage message, QueryResponse response) {
        if (response.isError()) {
            sendSealed("error", Map.of("code", response.errorCode(),
                    "message", response.errorMessage() == null ? "" : response.errorMessage(),
                    "request_id", message.id()));
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>(response.payload());
        payload.put("request_id", message.id());
        sendSealed(message.type() + ".result", payload);
    }

    // ------------------------------------------------------------------ 发送

    /** 发送握手帧（{@code signingCode} 非空表示 context=pair）。 */
    public synchronized void sendPlain(String type, Map<String, Object> payload, String signingCode) {
        String id = "s_" + frameCounter.incrementAndGet();
        String nonce = Crypto.randomNonce();
        long ts = System.currentTimeMillis() / 1000L;
        String input = Transcript.signatureInput(signingCode != null ? "pair" : "auth", type, id, ts, nonce,
                payload, signingCode);
        String sig = Base64Url.encode(Crypto.ed25519Sign(
                Crypto.ed25519Private(keyStore.privateSeed()), Crypto.utf8(input)));
        Frame frame = Frame.plain(type, id, ts, nonce, payload, sig);
        send(frame.toJson());
    }

    /** 发送密封业务帧。 */
    public synchronized void sendSealed(String type, Map<String, Object> payload) {
        if (outbound == null) {
            throw new IllegalStateException("会话密钥尚未建立");
        }
        String id = "s_" + frameCounter.incrementAndGet();
        long ts = System.currentTimeMillis() / 1000L;
        byte[] plaintext = Transcript.messagePlaintext(type, id, ts, payload);
        long seq = outbound.nextSeq();
        byte[] ct = outbound.seal(plaintext);
        send(Frame.sealed(seq, Base64Url.encode(ct)).toJson());
    }

    private void send(String text) {
        if (connection.isOpen()) {
            connection.send(text);
        }
    }

    private void fail(String code, String message) {
        log.warn("bot 握手/协议失败（{}）：{}", code, message);
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("code", code);
            payload.addProperty("message", message);
            JsonObject frame = new JsonObject();
            frame.addProperty("v", Frame.VERSION);
            frame.addProperty("kind", Frame.KIND_PLAIN);
            frame.addProperty("type", "error");
            String id = "s_" + frameCounter.incrementAndGet();
            String nonce = Crypto.randomNonce();
            long ts = System.currentTimeMillis() / 1000L;
            frame.addProperty("id", id);
            frame.addProperty("ts", ts);
            frame.addProperty("nonce", nonce);
            frame.add("payload", payload);
            // 错误帧同样带签名（能签就签），便于 bot 侧统一校验
            try {
                String context = pairingCode != null ? "pair" : "auth";
                Map<String, Object> payloadMap = new LinkedHashMap<>();
                payloadMap.put("code", code);
                payloadMap.put("message", message);
                String input = Transcript.signatureInput(context, "error", id, ts, nonce, payloadMap,
                        pairingCode);
                frame.addProperty("sig", Base64Url.encode(Crypto.ed25519Sign(
                        Crypto.ed25519Private(keyStore.privateSeed()), Crypto.utf8(input))));
            } catch (RuntimeException ignored) {
                // 签名失败不影响错误上报
            }
            if (connection.isOpen()) {
                connection.send(Json.write(frame));
            }
        } catch (RuntimeException ignored) {
            // 关闭前尽力而为
        }
        close();
    }

    public void close() {
        this.state = State.CLOSED;
        try {
            if (connection.isOpen()) {
                connection.close(1002, "protocol error");
            }
        } catch (RuntimeException ignored) {
            // ignore
        }
        callbacks.onClosed(this);
    }

    // ------------------------------------------------------------------ 查询

    private static boolean verifySignature(byte[] publicKey, String input, String signature) {
        if (signature == null) {
            return false;
        }
        try {
            return Crypto.ed25519Verify(Crypto.ed25519Public(publicKey), Crypto.utf8(input),
                    Base64Url.decode(signature));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void requireEquals(String actual, String expected, String field) {
        if (actual == null || !actual.equals(expected)) {
            throw new ProtocolException(ProtocolException.SIGNATURE_INVALID, "字段不匹配：" + field);
        }
    }

    public State state() {
        return state;
    }

    public boolean ready() {
        return state == State.READY;
    }

    public String clientId() {
        return clientId;
    }

    /** bot 服务的渠道 id（握手声明；可能为空）。 */
    public String provider() {
        return provider;
    }

    /** bot 上报的监听位置展示串（可能为空）。 */
    public String scopes() {
        return scopes;
    }

    public String sessionId() {
        return sessionId;
    }

    public String remoteAddress() {
        return connection.getRemoteSocketAddress() == null ? "?"
                : connection.getRemoteSocketAddress().toString();
    }

    public long lastActivityAt() {
        return lastActivityAt;
    }

    public WebSocket connection() {
        return connection;
    }
}
