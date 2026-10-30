package io.github.railgun19457.velocityauth.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.railgun19457.velocityauth.bot.protocol.Base64Url;
import io.github.railgun19457.velocityauth.bot.protocol.Crypto;
import io.github.railgun19457.velocityauth.bot.protocol.Hashes;
import io.github.railgun19457.velocityauth.bot.protocol.Json;
import io.github.railgun19457.velocityauth.util.PluginLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 密钥存储（DESIGN 8.2 / 12.2）。
 * <ul>
 *   <li>{@code keys/identity.json} —— 插件自身 Ed25519 身份（首次启动生成）</li>
 *   <li>{@code keys/paired_clients.json} —— 已 pin 的 bot 公钥</li>
 * </ul>
 * 这两个文件都**不进版本库**，且绝不出现在日志里。
 */
public final class KeyStore {

    public static final String DEFAULT_KEY_ID = "srv-k1";

    /** {@code touch} 落盘的最小间隔（毫秒）。 */
    private static final long TOUCH_SAVE_INTERVAL_MILLIS = 60_000L;

    /** 复用同一个强随机源（生成配对码等）。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path directory;
    private final Path clientsFile;
    private final PluginLogger log;

    private final Map<String, Object> identity;
    // 可能被多条 bot 连接线程与命令线程并发访问，必须线程安全
    private final Map<String, PairedClient> clients = new ConcurrentHashMap<>();
    private volatile long lastTouchSaveAt;

    public record PairedClient(String clientId, String keyId, String provider, byte[] publicKey, String fingerprint,
                               long pairedAt, long lastSeen) {
    }

    private KeyStore(Path directory, Path clientsFile, Map<String, Object> identity, PluginLogger log) {
        this.directory = directory;
        this.clientsFile = clientsFile;
        this.identity = identity;
        this.log = log;
    }

    // ------------------------------------------------------------------ 加载

    public static KeyStore load(Path dataDirectory, PluginLogger log) throws IOException {
        Path directory = dataDirectory.resolve("keys");
        Files.createDirectories(directory);
        Path identityFile = directory.resolve("identity.json");
        Path clientsFile = directory.resolve("paired_clients.json");

        Map<String, Object> identity;
        if (Files.isRegularFile(identityFile)) {
            identity = Json.toMap(Json.parseObject(Files.readString(identityFile, StandardCharsets.UTF_8)));
        } else {
            identity = createIdentity(identityFile, log);
        }

        KeyStore store = new KeyStore(directory, clientsFile, identity, log);
        store.loadClients();
        return store;
    }

    private static Map<String, Object> createIdentity(Path file, PluginLogger log) throws IOException {
        var keyPair = Crypto.ed25519Generate();
        byte[] publicKey = Crypto.rawPublicKey(keyPair.getPublic());
        byte[] seed = ((java.security.interfaces.EdECPrivateKey) keyPair.getPrivate()).getBytes()
                .orElseThrow(() -> new IllegalStateException("无法导出 Ed25519 私钥"));

        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("server_id", "velocity-" + randomHex(8));
        identity.put("key_id", DEFAULT_KEY_ID);
        identity.put("private_seed", Base64Url.encode(seed));
        identity.put("public_key", Base64Url.encode(publicKey));
        identity.put("created_at", System.currentTimeMillis() / 1000L);

        Files.writeString(file, Json.write(Json.fromMap(identity)) + "\n", StandardCharsets.UTF_8);
        restrictPermissions(file);
        log.info("已生成插件 Ed25519 身份：{}（{}）", identity.get("server_id"), file.getFileName());
        return identity;
    }

    @SuppressWarnings("unchecked")
    private void loadClients() {
        clients.clear();
        if (!Files.isRegularFile(clientsFile)) {
            return;
        }
        try {
            JsonObject root = Json.parseObject(Files.readString(clientsFile, StandardCharsets.UTF_8));
            JsonArray array = root.getAsJsonArray("clients");
            if (array == null) {
                return;
            }
            for (var element : array) {
                JsonObject object = element.getAsJsonObject();
                PairedClient client = new PairedClient(
                        Json.string(object, "client_id"),
                        Json.string(object, "key_id"),
                        Json.string(object, "provider"),
                        Base64Url.decode(Json.string(object, "public_key")),
                        Json.string(object, "fingerprint"),
                        Json.longValue(object, "paired_at") == null ? 0L : Json.longValue(object, "paired_at"),
                        Json.longValue(object, "last_seen") == null ? 0L : Json.longValue(object, "last_seen"));
                clients.put(key(client.clientId(), client.keyId()), client);
            }
        } catch (Exception e) {
            log.error("读取 paired_clients.json 失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 身份

    public String serverId() {
        return String.valueOf(identity.get("server_id"));
    }

    public String keyId() {
        return String.valueOf(identity.get("key_id"));
    }

    public byte[] privateSeed() {
        return Base64Url.decode(String.valueOf(identity.get("private_seed")));
    }

    public byte[] publicKey() {
        return Base64Url.decode(String.valueOf(identity.get("public_key")));
    }

    public String fingerprint() {
        return Hashes.sha256B64Url(publicKey());
    }

    // ------------------------------------------------------------------ 已配对客户端

    public Optional<PairedClient> find(String clientId, String keyId) {
        return Optional.ofNullable(clients.get(key(clientId, keyId)));
    }

    public List<PairedClient> clients() {
        return new ArrayList<>(clients.values());
    }

    public synchronized void pair(PairedClient client) {
        clients.put(key(client.clientId(), client.keyId()), client);
        saveClients();
        log.info("已配对 bot 客户端 {}（{}）", client.clientId(), client.fingerprint());
    }

    public synchronized boolean revoke(String clientId) {
        boolean removed = clients.entrySet().removeIf(entry -> entry.getValue().clientId().equals(clientId));
        if (removed) {
            saveClients();
        }
        return removed;
    }

    public synchronized void touch(String clientId, String keyId) {
        PairedClient existing = clients.get(key(clientId, keyId));
        if (existing == null) {
            return;
        }
        long now = System.currentTimeMillis();
        clients.put(key(clientId, keyId), new PairedClient(existing.clientId(), existing.keyId(),
                existing.provider(), existing.publicKey(), existing.fingerprint(), existing.pairedAt(), now / 1000L));
        // 心跳（pong）很频繁：last_seen 只更新内存，落盘节流到最多每 60 秒一次，
        // 其余时间靠 pair/revoke/flush（关服）持久化，避免高频磁盘写入。
        if (now - lastTouchSaveAt >= TOUCH_SAVE_INTERVAL_MILLIS) {
            lastTouchSaveAt = now;
            saveClients();
        }
    }

    /** 把内存中的客户端状态强制落盘（关服时调用）。 */
    public synchronized void flush() {
        saveClients();
    }

    private void saveClients() {
        JsonArray array = new JsonArray();
        for (PairedClient client : clients.values()) {
            JsonObject object = new JsonObject();
            object.addProperty("client_id", client.clientId());
            object.addProperty("key_id", client.keyId());
            object.addProperty("provider", client.provider());
            object.addProperty("public_key", Base64Url.encode(client.publicKey()));
            object.addProperty("fingerprint", client.fingerprint());
            object.addProperty("paired_at", client.pairedAt());
            object.addProperty("last_seen", client.lastSeen());
            array.add(object);
        }
        JsonObject root = new JsonObject();
        root.add("clients", array);
        try {
            Files.writeString(clientsFile, Json.write(root) + "\n", StandardCharsets.UTF_8);
            restrictPermissions(clientsFile);
        } catch (IOException e) {
            log.error("写入 paired_clients.json 失败：" + e.getMessage());
        }
    }

    private static String key(String clientId, String keyId) {
        return clientId + "|" + keyId;
    }

    private static String randomHex(int length) {
        byte[] bytes = new byte[length / 2];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(length);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 尽力收紧文件权限（Windows 上不支持 POSIX 权限，忽略）。 */
    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / 非 POSIX 文件系统
        }
    }

    public Path directory() {
        return directory;
    }
}
