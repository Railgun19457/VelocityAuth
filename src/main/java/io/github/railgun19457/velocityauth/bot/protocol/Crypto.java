package io.github.railgun19457.velocityauth.bot.protocol;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.math.BigInteger;
import java.security.interfaces.XECPublicKey;

import javax.crypto.KeyAgreement;

/**
 * JDK 内置的 Ed25519 / X25519 封装（无需 BouncyCastle）。
 */
public final class Crypto {

    public static final int RAW_KEY_LENGTH = 32;

    /** 复用同一个强随机源，避免每次调用都重新播种（SecureRandom 构造开销大）。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    private Crypto() {
    }

    // ------------------------------------------------------------------ Ed25519

    /** 从 32 字节 seed 恢复 Ed25519 私钥。 */
    public static PrivateKey ed25519Private(byte[] seed) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
        } catch (Exception e) {
            throw new IllegalStateException("无法构造 Ed25519 私钥", e);
        }
    }

    /** 从 32 字节裸公钥构造 Ed25519 公钥（RFC 8032 编码：小端 y + 最高位 xOdd）。 */
    public static PublicKey ed25519Public(byte[] raw) {
        if (raw == null || raw.length != RAW_KEY_LENGTH) {
            throw new IllegalArgumentException("Ed25519 公钥必须为 32 字节");
        }
        try {
            byte[] yLittle = raw.clone();
            boolean xOdd = (yLittle[RAW_KEY_LENGTH - 1] & 0x80) != 0;
            yLittle[RAW_KEY_LENGTH - 1] &= 0x7F;
            byte[] yBig = new byte[RAW_KEY_LENGTH];
            for (int i = 0; i < RAW_KEY_LENGTH; i++) {
                yBig[i] = yLittle[RAW_KEY_LENGTH - 1 - i];
            }
            BigInteger y = new BigInteger(1, yBig);
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new EdECPublicKeySpec(NamedParameterSpec.ED25519, new EdECPoint(xOdd, y)));
        } catch (Exception e) {
            throw new IllegalStateException("无法构造 Ed25519 公钥", e);
        }
    }

    public static KeyPair ed25519Generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 Ed25519 密钥对", e);
        }
    }

    /** 取出裸公钥字节（32 字节）。 */
    public static byte[] rawPublicKey(PublicKey key) {
        if (key instanceof java.security.interfaces.EdECPublicKey edec) {
            EdECPoint point = edec.getPoint();
            byte[] yBig = point.getY().toByteArray();
            byte[] little = new byte[RAW_KEY_LENGTH];
            for (int i = 0; i < RAW_KEY_LENGTH; i++) {
                int index = yBig.length - 1 - i;
                little[i] = index >= 0 ? yBig[index] : 0;
            }
            if (point.isXOdd()) {
                little[RAW_KEY_LENGTH - 1] |= (byte) 0x80;
            }
            return little;
        }
        byte[] encoded = key.getEncoded();
        if (encoded == null || encoded.length < RAW_KEY_LENGTH) {
            throw new IllegalStateException("公钥编码长度异常");
        }
        byte[] raw = new byte[RAW_KEY_LENGTH];
        System.arraycopy(encoded, encoded.length - RAW_KEY_LENGTH, raw, 0, RAW_KEY_LENGTH);
        return raw;
    }

    public static byte[] ed25519Sign(PrivateKey privateKey, byte[] data) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(data);
            return signature.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 签名失败", e);
        }
    }

    public static boolean ed25519Verify(PublicKey publicKey, byte[] data, byte[] signatureBytes) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey);
            signature.update(data);
            return signature.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ X25519

    public static KeyPair x25519Generate() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 X25519 密钥对", e);
        }
    }

    /**
     * 从 32 字节裸私钥构造 X25519 私钥。
     * <p>
     * RFC 7748 的私钥在使用前需要 clamp；JDK 的 {@code XECPrivateKeySpec} 期望的是**已 clamp** 的
     * 标量（PKCS#8 存储形式），因此这里显式 clamp，保证与其它实现（Python {@code cryptography}）一致。
     */
    public static PrivateKey x25519Private(byte[] raw) {
        if (raw == null || raw.length != RAW_KEY_LENGTH) {
            throw new IllegalArgumentException("X25519 私钥必须为 32 字节");
        }
        byte[] clamped = raw.clone();
        clamped[0] &= (byte) 248;
        clamped[RAW_KEY_LENGTH - 1] &= (byte) 127;
        clamped[RAW_KEY_LENGTH - 1] |= (byte) 64;
        try {
            return KeyFactory.getInstance("X25519")
                    .generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, clamped));
        } catch (Exception e) {
            throw new IllegalStateException("无法构造 X25519 私钥", e);
        }
    }

    /** 从 32 字节裸公钥（u 坐标，RFC 7748 小端）构造 X25519 公钥。 */
    public static PublicKey x25519Public(byte[] raw) {
        if (raw == null || raw.length != RAW_KEY_LENGTH) {
            throw new IllegalArgumentException("X25519 公钥必须为 32 字节");
        }
        try {
            BigInteger u = new BigInteger(1, reverse(raw));
            return KeyFactory.getInstance("X25519")
                    .generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, u));
        } catch (Exception e) {
            throw new IllegalStateException("无法构造 X25519 公钥", e);
        }
    }

    /** 取出 X25519 裸公钥字节（32 字节，RFC 7748 小端 u 坐标）。 */
    public static byte[] rawX25519PublicKey(PublicKey key) {
        if (key instanceof XECPublicKey xec) {
            BigInteger u = xec.getU();
            byte[] be = u.toByteArray();
            byte[] out = new byte[RAW_KEY_LENGTH];
            int len = Math.min(be.length, RAW_KEY_LENGTH);
            System.arraycopy(be, be.length - len, out, RAW_KEY_LENGTH - len, len);
            return reverse(out);
        }
        throw new IllegalStateException("不是 X25519 公钥：" + key.getClass().getName());
    }

    private static byte[] reverse(byte[] input) {
        byte[] out = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            out[i] = input[input.length - 1 - i];
        }
        return out;
    }

    public static byte[] x25519SharedSecret(PrivateKey privateKey, PublicKey publicKey) {
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("X25519");
            agreement.init(privateKey);
            agreement.doPhase(publicKey, true);
            return agreement.generateSecret();
        } catch (Exception e) {
            throw new IllegalStateException("X25519 密钥协商失败", e);
        }
    }

    // ------------------------------------------------------------------ 随机

    public static byte[] randomBytes(int length) {
        byte[] out = new byte[length];
        RANDOM.nextBytes(out);
        return out;
    }

    /** 每帧随机 nonce（b64url(16B)）。 */
    public static String randomNonce() {
        return Base64Url.encode(randomBytes(16));
    }

    public static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
