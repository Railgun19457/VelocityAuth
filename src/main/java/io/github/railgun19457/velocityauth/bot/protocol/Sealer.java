package io.github.railgun19457.velocityauth.bot.protocol;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 密封帧的加密与解密（PROTOCOL.md 7）。
 * <pre>
 * nonce(12B) = epoch(4B, 大端) || seq(8B, 大端)
 * AAD        = "VELOCITYAUTH-SEAL-V1\nv=1\nseq=&lt;seq&gt;\n"
 * ct         = AES-256-GCM(key, nonce, plaintext, AAD)   // 密文 || 16B tag
 * </pre>
 */
public final class Sealer {

    private static final int TAG_BITS = 128;
    private static final byte[] AAD_PREFIX = "VELOCITYAUTH-SEAL-V1\nv=1\nseq=".getBytes(StandardCharsets.UTF_8);

    private Sealer() {
    }

    public static byte[] nonce(long epoch, long seq) {
        byte[] nonce = new byte[12];
        nonce[0] = (byte) (epoch >>> 24);
        nonce[1] = (byte) (epoch >>> 16);
        nonce[2] = (byte) (epoch >>> 8);
        nonce[3] = (byte) epoch;
        for (int i = 0; i < 8; i++) {
            nonce[4 + i] = (byte) (seq >>> (8 * (7 - i)));
        }
        return nonce;
    }

    public static byte[] aad(long seq) {
        byte[] suffix = ("\n").getBytes(StandardCharsets.UTF_8);
        byte[] seqBytes = Long.toString(seq).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[AAD_PREFIX.length + seqBytes.length + suffix.length];
        System.arraycopy(AAD_PREFIX, 0, out, 0, AAD_PREFIX.length);
        System.arraycopy(seqBytes, 0, out, AAD_PREFIX.length, seqBytes.length);
        System.arraycopy(suffix, 0, out, AAD_PREFIX.length + seqBytes.length, suffix.length);
        return out;
    }

    public static byte[] encrypt(byte[] key, long epoch, long seq, byte[] plaintext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce(epoch, seq)));
            cipher.updateAAD(aad(seq));
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException("密封帧加密失败", e);
        }
    }

    /** 解密失败返回 null（调用方转为 decrypt_failed）。 */
    public static byte[] decrypt(byte[] key, long epoch, long seq, byte[] ciphertext) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce(epoch, seq)));
            cipher.updateAAD(aad(seq));
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            return null;
        }
    }

    /** 发送方向：seq 从 0 开始严格递增。 */
    public static final class Outbound {
        private final byte[] key;
        private final long epoch;
        private long seq;

        public Outbound(byte[] key, long epoch) {
            this.key = key;
            this.epoch = epoch;
        }

        public long nextSeq() {
            return seq;
        }

        public byte[] seal(byte[] plaintext) {
            byte[] ct = encrypt(key, epoch, seq, plaintext);
            seq++;
            return ct;
        }
    }

    /** 接收方向：seq &lt;= lastSeq 一律拒绝（防重放）。 */
    public static final class Inbound {
        private final byte[] key;
        private final long epoch;
        private long lastSeq = -1;

        public Inbound(byte[] key, long epoch) {
            this.key = key;
            this.epoch = epoch;
        }

        public long lastSeq() {
            return lastSeq;
        }

        public byte[] open(long seq, byte[] ciphertext) {
            if (seq <= lastSeq) {
                throw new ProtocolException(ProtocolException.REPLAY_DETECTED, "seq 重放：" + seq);
            }
            byte[] plaintext = decrypt(key, epoch, seq, ciphertext);
            if (plaintext == null) {
                throw new ProtocolException(ProtocolException.DECRYPT_FAILED, "密封帧解密失败（seq=" + seq + "）");
            }
            lastSeq = seq;
            return plaintext;
        }
    }
}
