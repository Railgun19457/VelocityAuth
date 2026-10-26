package io.github.railgun19457.velocityauth.bot.protocol;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 签名输入与密封帧内层明文（PROTOCOL.md 3.3 / 7）。
 */
public final class Transcript {

    public static final String SIGNATURE_PREFIX = "VELOCITYAUTH-V1\n";
    public static final String MESSAGE_PREFIX = "VELOCITYAUTH-MSG-V1\n";

    private Transcript() {
    }

    /**
     * {@code signature_input}：
     * <pre>
     * VELOCITYAUTH-V1
     * context=&lt;pair|auth&gt;
     * type=&lt;消息类型&gt;
     * id=&lt;帧 id&gt;
     * ts=&lt;时间戳&gt;
     * nonce=&lt;nonce&gt;
     * code=&lt;配对码&gt;      ← 仅 context=pair 时存在
     * &lt;canonical(payload)&gt;
     * </pre>
     */
    public static String signatureInput(String context, String type, String id, long ts, String nonce,
                                        Map<String, Object> payload, String pairingCode) {
        StringBuilder sb = new StringBuilder(SIGNATURE_PREFIX.length() + 256);
        sb.append(SIGNATURE_PREFIX);
        sb.append("context=").append(context).append('\n');
        sb.append("type=").append(type).append('\n');
        sb.append("id=").append(id).append('\n');
        sb.append("ts=").append(ts).append('\n');
        sb.append("nonce=").append(nonce).append('\n');
        if (pairingCode != null) {
            sb.append("code=").append(pairingCode).append('\n');
        }
        sb.append(Canonical.of(payload));
        return sb.toString();
    }

    /** 密封帧内层明文（UTF-8 字节）。 */
    public static byte[] messagePlaintext(String type, String id, long ts, Map<String, Object> payload) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(MESSAGE_PREFIX);
        sb.append("type=").append(type).append('\n');
        sb.append("id=").append(id).append('\n');
        sb.append("ts=").append(ts).append('\n');
        sb.append(Canonical.of(payload));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 内层明文解析结果。 */
    public record ParsedMessage(String type, String id, long ts, Map<String, Object> payload) {
    }

    /**
     * 解析内层明文。格式固定为前 4 行头部 + canonical 形式的字段。
     */
    public static ParsedMessage parseMessage(byte[] plaintext) {
        String text = new String(plaintext, StandardCharsets.UTF_8);
        if (!text.startsWith(MESSAGE_PREFIX)) {
            throw new ProtocolException("invalid_message", "内层明文缺少前缀");
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        String type = null;
        String id = null;
        long ts = 0;
        for (String line : text.substring(MESSAGE_PREFIX.length()).split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new ProtocolException("invalid_message", "内层明文字段缺少 '='");
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            switch (key) {
                case "type" -> type = value;
                case "id" -> id = value;
                case "ts" -> ts = Long.parseLong(value);
                default -> payload.put(key, value);
            }
        }
        if (type == null || id == null) {
            throw new ProtocolException("invalid_message", "内层明文缺少 type/id");
        }
        return new ParsedMessage(type, id, ts, payload);
    }

    /** capabilities 的 sha256 指纹：sha256_b64u("\n".join(sorted(capabilities)))。 */
    public static String capabilitiesSha256(List<String> capabilities) {
        List<String> sorted = new java.util.ArrayList<>(capabilities);
        java.util.Collections.sort(sorted);
        return Hashes.sha256B64Url(String.join("\n", sorted).getBytes(StandardCharsets.UTF_8));
    }
}
