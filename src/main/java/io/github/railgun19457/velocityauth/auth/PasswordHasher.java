package io.github.railgun19457.velocityauth.auth;

import at.favre.lib.crypto.bcrypt.BCrypt;

import java.nio.charset.StandardCharsets;

/**
 * bcrypt 封装。密码单向哈希，插件无法得知明文（ADR-9）。
 */
public final class PasswordHasher {

    /**
     * bcrypt 只使用前 72 字节输入，超出部分会被**静默忽略**。
     * 因此必须显式拒绝超长密码，避免"看似更长实则等价"。
     */
    public static final int MAX_PASSWORD_BYTES = 72;

    private final int cost;

    public PasswordHasher(int cost) {
        this.cost = cost;
    }

    public int cost() {
        return cost;
    }

    public String hash(String password) {
        return BCrypt.withDefaults().hashToString(cost, password.toCharArray());
    }

    /** 密码的 UTF-8 字节数是否在 bcrypt 可用范围内（见 {@link #MAX_PASSWORD_BYTES}）。 */
    public static boolean withinByteLimit(String password) {
        return password != null
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_PASSWORD_BYTES;
    }

    /** 校验失败（哈希损坏等）一律返回 false，不抛异常。 */
    public boolean verify(String password, String hash) {
        if (password == null || hash == null || hash.isEmpty()) {
            return false;
        }
        try {
            return BCrypt.verifyer().verify(password.toCharArray(), hash).verified;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
