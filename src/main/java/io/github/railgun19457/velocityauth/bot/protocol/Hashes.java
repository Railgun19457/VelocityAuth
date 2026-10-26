package io.github.railgun19457.velocityauth.bot.protocol;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 摘要工具。
 */
public final class Hashes {

    private Hashes() {
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    public static String sha256B64Url(byte[] data) {
        return Base64Url.encode(sha256(data));
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("JDK 缺少 HmacSHA256", e);
        }
    }

    /** 恒定时间比较。 */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a, b);
    }
}
