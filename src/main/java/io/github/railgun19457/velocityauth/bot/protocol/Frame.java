package io.github.railgun19457.velocityauth.bot.protocol;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 帧封套（PROTOCOL.md 2）。
 * <ul>
 *   <li>{@code kind = "plain"}：握手帧，明文 + Ed25519 签名</li>
 *   <li>{@code kind = "sealed"}：业务帧，AES-256-GCM 密文</li>
 * </ul>
 */
public final class Frame {

    public static final int VERSION = 1;
    public static final String KIND_PLAIN = "plain";
    public static final String KIND_SEALED = "sealed";

    private final String kind;
    private final String type;
    private final String id;
    private final long ts;
    private final String nonce;
    private final Map<String, Object> payload;
    private final String sig;
    private final long seq;
    private final String ct;

    private Frame(String kind, String type, String id, long ts, String nonce, Map<String, Object> payload,
                  String sig, long seq, String ct) {
        this.kind = kind;
        this.type = type;
        this.id = id;
        this.ts = ts;
        this.nonce = nonce;
        this.payload = payload;
        this.sig = sig;
        this.seq = seq;
        this.ct = ct;
    }

    public static Frame plain(String type, String id, long ts, String nonce, Map<String, Object> payload,
                              String sig) {
        return new Frame(KIND_PLAIN, type, id, ts, nonce,
                payload == null ? Map.of() : new LinkedHashMap<>(payload), sig, 0, null);
    }

    public static Frame sealed(long seq, String ct) {
        return new Frame(KIND_SEALED, null, null, 0, null, Map.of(), null, seq, ct);
    }

    public static Frame parse(String text) {
        JsonObject object = Json.parseObject(text);
        String kind = Json.string(object, "kind");
        if (kind == null) {
            throw new ProtocolException(ProtocolException.INTERNAL_ERROR, "帧缺少 kind");
        }
        Long version = Json.longValue(object, "v");
        if (version == null || version != VERSION) {
            throw new ProtocolException(ProtocolException.VERSION_UNSUPPORTED,
                    "不支持的协议版本：" + version);
        }
        if (KIND_SEALED.equals(kind)) {
            Long seq = Json.longValue(object, "seq");
            String ct = Json.string(object, "ct");
            if (seq == null || ct == null) {
                throw new ProtocolException(ProtocolException.INTERNAL_ERROR, "密封帧缺少 seq/ct");
            }
            return sealed(seq, ct);
        }
        if (!KIND_PLAIN.equals(kind)) {
            throw new ProtocolException(ProtocolException.INTERNAL_ERROR, "未知帧类型：" + kind);
        }
        String type = Json.string(object, "type");
        String id = Json.string(object, "id");
        Long ts = Json.longValue(object, "ts");
        String nonce = Json.string(object, "nonce");
        if (type == null || id == null || ts == null || nonce == null) {
            throw new ProtocolException(ProtocolException.INTERNAL_ERROR, "握手帧缺少必要字段");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        JsonObject payloadObject = object.getAsJsonObject("payload");
        if (payloadObject != null) {
            payload = Json.toMap(payloadObject);
        }
        return plain(type, id, ts, nonce, payload, Json.string(object, "sig"));
    }

    public String toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("v", VERSION);
        object.addProperty("kind", kind);
        if (KIND_SEALED.equals(kind)) {
            object.addProperty("seq", seq);
            object.addProperty("ct", ct);
        } else {
            object.addProperty("type", type);
            object.addProperty("id", id);
            object.addProperty("ts", ts);
            object.addProperty("nonce", nonce);
            object.add("payload", Json.fromMap(payload));
            if (sig != null) {
                object.addProperty("sig", sig);
            }
        }
        return Json.write(object);
    }

    public String kind() {
        return kind;
    }

    public boolean sealed() {
        return KIND_SEALED.equals(kind);
    }

    public String type() {
        return type;
    }

    public String id() {
        return id;
    }

    public long ts() {
        return ts;
    }

    public String nonce() {
        return nonce;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    public String sig() {
        return sig;
    }

    public long seq() {
        return seq;
    }

    public String ct() {
        return ct;
    }

    /** 载荷中的字符串字段。 */
    public String str(String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public Long number(String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public boolean bool(String key, boolean fallback) {
        Object value = payload.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    public java.util.List<String> strings(String key) {
        Object value = payload.get(key);
        if (value instanceof java.util.List<?> list) {
            java.util.List<String> out = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                out.add(item == null ? "" : String.valueOf(item));
            }
            return out;
        }
        return java.util.List.of();
    }
}
