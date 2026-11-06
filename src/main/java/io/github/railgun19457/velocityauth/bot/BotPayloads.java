package io.github.railgun19457.velocityauth.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.railgun19457.velocityauth.bot.protocol.Json;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 把结构化数据编码进扁平协议载荷。
 * <p>
 * 协议信封只允许扁平字段（PROTOCOL.md 3.1），因此列表 / 详情类响应统一放在一个
 * {@code data} 字段里，内容是**紧凑 JSON 字符串**。这样既不用改信封、签名与测试向量，
 * 又能在管理页侧直接 {@code JSON.parse} 使用。
 */
public final class BotPayloads {

    private BotPayloads() {
    }

    /** 把一组对象编码为 JSON 数组字符串。 */
    public static String encodeArray(List<? extends Map<String, Object>> rows) {
        JsonArray array = new JsonArray();
        for (Map<String, Object> row : rows) {
            array.add(toJson(row));
        }
        return Json.write(array);
    }

    /** 把单个对象编码为 JSON 对象字符串。 */
    public static String encodeObject(Map<String, Object> row) {
        return Json.write(toJson(row));
    }

    /** 递归编码（允许嵌套映射 / 集合），与信封的扁平约束无关——它只作用于字符串内部。 */
    private static JsonElement toJson(Object value) {
        if (value == null) {
            return com.google.gson.JsonNull.INSTANCE;
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (value instanceof String text) {
            return new JsonPrimitive(text);
        }
        if (value instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                object.add(String.valueOf(entry.getKey()), toJson(entry.getValue()));
            }
            return object;
        }
        if (value instanceof Collection<?> collection) {
            JsonArray array = new JsonArray();
            for (Object item : collection) {
                array.add(toJson(item));
            }
            return array;
        }
        return new JsonPrimitive(value.toString());
    }
}
