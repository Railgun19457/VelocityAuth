package io.github.railgun19457.velocityauth.bot.protocol;

import java.util.Base64;

/**
 * base64url（无 padding），与测试向量中的 {@code b64u()} 完全一致。
 */
public final class Base64Url {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private Base64Url() {
    }

    public static String encode(byte[] data) {
        return ENCODER.encodeToString(data);
    }

    /** 容忍缺少 padding 的输入（协议要求无 padding）。 */
    public static byte[] decode(String text) {
        if (text == null) {
            throw new IllegalArgumentException("base64url 输入为空");
        }
        String value = text.trim();
        int remainder = value.length() % 4;
        if (remainder == 2) {
            value = value + "==";
        } else if (remainder == 3) {
            value = value + "=";
        } else if (remainder == 1) {
            throw new IllegalArgumentException("base64url 长度非法");
        }
        return DECODER.decode(value);
    }
}
