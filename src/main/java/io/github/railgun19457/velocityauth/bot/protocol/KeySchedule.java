package io.github.railgun19457.velocityauth.bot.protocol;

/**
 * 密钥派生（PROTOCOL.md 6）。
 * <pre>
 * shared  = X25519(本地临时私钥, 对方临时公钥)
 * salt    = SHA-256("VELOCITYAUTH-V1-SALT" || client_nonce_raw || server_nonce_raw)
 * prk     = HMAC-SHA256(salt, shared)                 // HKDF-Extract
 * key_c2s = HKDF-Expand(prk, "velocityauth c2s", 32)
 * key_s2c = HKDF-Expand(prk, "velocityauth s2c", 32)
 * </pre>
 */
public final class KeySchedule {

    public static final byte[] SALT_PREFIX = "VELOCITYAUTH-V1-SALT".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    public static final byte[] INFO_C2S = "velocityauth c2s".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    public static final byte[] INFO_S2C = "velocityauth s2c".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private KeySchedule() {
    }

    public record SessionKeys(byte[] salt, byte[] prk, byte[] keyC2s, byte[] keyS2c) {
    }

    public static SessionKeys derive(byte[] clientNonceRaw, byte[] serverNonceRaw, byte[] sharedSecret) {
        byte[] saltInput = new byte[SALT_PREFIX.length + clientNonceRaw.length + serverNonceRaw.length];
        System.arraycopy(SALT_PREFIX, 0, saltInput, 0, SALT_PREFIX.length);
        System.arraycopy(clientNonceRaw, 0, saltInput, SALT_PREFIX.length, clientNonceRaw.length);
        System.arraycopy(serverNonceRaw, 0, saltInput, SALT_PREFIX.length + clientNonceRaw.length,
                serverNonceRaw.length);
        byte[] salt = Hashes.sha256(saltInput);
        byte[] prk = hkdfExtract(salt, sharedSecret);
        return new SessionKeys(salt, prk, hkdfExpand(prk, INFO_C2S, 32), hkdfExpand(prk, INFO_S2C, 32));
    }

    public static byte[] hkdfExtract(byte[] salt, byte[] ikm) {
        return Hashes.hmacSha256(salt, ikm);
    }

    /** RFC 5869 HKDF-Expand。 */
    public static byte[] hkdfExpand(byte[] prk, byte[] info, int length) {
        byte[] okm = new byte[length];
        byte[] block = new byte[0];
        int offset = 0;
        int counter = 1;
        while (offset < length) {
            byte[] input = new byte[block.length + info.length + 1];
            System.arraycopy(block, 0, input, 0, block.length);
            System.arraycopy(info, 0, input, block.length, info.length);
            input[input.length - 1] = (byte) counter;
            block = Hashes.hmacSha256(prk, input);
            int copy = Math.min(block.length, length - offset);
            System.arraycopy(block, 0, okm, offset, copy);
            offset += copy;
            counter++;
        }
        return okm;
    }
}
