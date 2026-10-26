package io.github.railgun19457.velocityauth.bot.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 读写。整数一律按 long 处理，避免被解析成 double 后规范化出错。
 */
public final class Json {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private Json() {
    }

    public static JsonObject parseObject(String text) {
        JsonElement element = JsonParser.parseString(text);
        if (!element.isJsonObject()) {
            throw new ProtocolException(ProtocolException.INTERNAL_ERROR, "帧必须是 JSON 对象");
        }
        return element.getAsJsonObject();
    }

    public static Map<String, Object> toMap(JsonObject object) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            out.put(entry.getKey(), toValue(entry.getValue()));
        }
        return out;
    }

    public static Object toValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                String raw = primitive.getAsString();
                if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                    return Long.parseLong(raw);
                }
                double d = primitive.getAsDouble();
                if (d == Math.rint(d)) {
                    return (long) d;
                }
                throw new ProtocolException(ProtocolException.INTERNAL_ERROR, "载荷不支持浮点数：" + raw);
            }
            return primitive.getAsString();
        }
        if (element.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                list.add(toValue(item));
            }
            return list;
        }
        throw new ProtocolException(ProtocolException.INTERNAL_ERROR, "载荷禁止嵌套对象");
    }

    public static JsonObject fromMap(Map<String, Object> map) {
        JsonObject object = new JsonObject();
        if (map == null) {
            return object;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            object.add(entry.getKey(), fromValue(entry.getValue()));
        }
        return object;
    }

    public static JsonElement fromValue(Object value) {
        if (value == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        if (value instanceof Boolean bool) {
            return new com.google.gson.JsonPrimitive(bool);
        }
        if (value instanceof Number number) {
            return new com.google.gson.JsonPrimitive(number);
        }
        if (value instanceof String text) {
            return new com.google.gson.JsonPrimitive(text);
        }
        if (value instanceof java.util.Collection<?> collection) {
            JsonArray array = new JsonArray();
            for (Object item : collection) {
                array.add(fromValue(item));
            }
            return array;
        }
        throw new ProtocolException(ProtocolException.INTERNAL_ERROR, "无法序列化的类型：" + value.getClass());
    }

    public static String write(JsonElement element) {
        return GSON.toJson(element);
    }

    /** 取字符串字段（缺失返回 null）。 */
    public static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsString();
    }

    public static Long longValue(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.getAsLong();
    }

    public static List<String> stringList(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            out.add(item.getAsString());
        }
        return out;
    }
}
