package io.github.railgun19457.velocityauth.bot;

import io.github.railgun19457.velocityauth.bot.protocol.Base64Url;
import io.github.railgun19457.velocityauth.bot.protocol.Crypto;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 配对码管理（PROTOCOL.md 4.1）。
 * <p>
 * 高熵（≥128 bit）、一次性、TTL、限次，**只存内存**，绝不写入日志。
 */
public final class PairingService {

    /** 配对码熵：24 随机字节 → base64url 32 字符 ≈ 192 bit。 */
    private static final int CODE_BYTES = 24;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pairing> pairings = new LinkedHashMap<>();

    public static final class Pairing {
        private final String pairingId;
        private final String code;
        private final long createdAt;
        private final long expiresAt;
        private final int maxAttempts;
        private int attempts;

        private Pairing(String pairingId, String code, long createdAt, long expiresAt, int maxAttempts) {
            this.pairingId = pairingId;
            this.code = code;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.maxAttempts = maxAttempts;
        }

        public String pairingId() {
            return pairingId;
        }

        public String code() {
            return code;
        }

        public long createdAt() {
            return createdAt;
        }

        public long expiresAt() {
            return expiresAt;
        }

        public int attempts() {
            return attempts;
        }

        public int maxAttempts() {
            return maxAttempts;
        }

        public long remainingSeconds() {
            return Math.max(0, (expiresAt - System.currentTimeMillis()) / 1000L);
        }

        public boolean expired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }

    public record Created(String pairingId, String code, int ttlSeconds) {
    }

    public Created create(int ttlSeconds, int maxAttempts) {
        return createWithCode(null, ttlSeconds, maxAttempts);
    }

    /**
     * 以指定配对码创建配对记录（用于无控制台的部署：服主在 config.yml 里预置 bootstrap-code）。
     * 码同样只存内存、一次性、TTL、限次。
     */
    public Created createWithCode(String code, int ttlSeconds, int maxAttempts) {
        String pairingId = newPairingId();
        String actualCode = code == null || code.isBlank()
                ? Base64Url.encode(randomBytes(CODE_BYTES))
                : code;
        long now = System.currentTimeMillis();
        pairings.put(pairingId, new Pairing(pairingId, actualCode, now, now + ttlSeconds * 1000L, maxAttempts));
        return new Created(pairingId, actualCode, ttlSeconds);
    }

    /** 取配对记录；已过期/已消费视为不存在。 */
    public Optional<Pairing> find(String pairingId) {
        Pairing pairing = pairings.get(pairingId);
        if (pairing == null) {
            return Optional.empty();
        }
        if (pairing.expired()) {
            pairings.remove(pairingId);
            return Optional.empty();
        }
        return Optional.of(pairing);
    }

    public void consume(String pairingId) {
        pairings.remove(pairingId);
    }

    /** 记录一次失败；返回是否仍在允许范围内。 */
    public boolean registerFailure(String pairingId) {
        Pairing pairing = pairings.get(pairingId);
        if (pairing == null) {
            return false;
        }
        pairing.attempts++;
        if (pairing.attempts >= pairing.maxAttempts) {
            pairings.remove(pairingId);
            return false;
        }
        return true;
    }

    public boolean revoke(String pairingId) {
        return pairings.remove(pairingId) != null;
    }

    public List<Pairing> list() {
        purgeExpired();
        return new ArrayList<>(pairings.values());
    }

    public int size() {
        purgeExpired();
        return pairings.size();
    }

    public void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Pairing>> it = pairings.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAt <= now) {
                it.remove();
            }
        }
    }

    private String newPairingId() {
        return "pair_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private byte[] randomBytes(int length) {
        byte[] out = new byte[length];
        random.nextBytes(out);
        return out;
    }

    /** 仅供内部使用：生成一个随机 nonce（握手用）。 */
    static String randomNonce() {
        return Crypto.randomNonce();
    }
}
