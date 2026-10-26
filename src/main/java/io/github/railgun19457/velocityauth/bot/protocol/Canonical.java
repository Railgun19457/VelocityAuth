package io.github.railgun19457.velocityauth.bot.protocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 载荷规范化（PROTOCOL.md 3.2）。
 * <pre>
 * canonical = 对 payload 的 key 按 UTF-8 字节序升序排列，逐行输出 "key=value\n"
 * </pre>
 * 只允许扁平字段：字符串、整数、布尔、null、字符串数组。
 */
public final class Canonical {

    private Canonical() {
    }

    public static String of(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        // TreeMap 的 String 自然序对 ASCII key 与 UTF-8 字节序一致
        Map<String, Object> sorted = new TreeMap<>(payload);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            sb.append(entry.getKey()).append('=').append(render(entry.getValue())).append('\n');
        }
        return sb.toString();
    }

    /** 值渲染规则，必须与 gen_vectors.py 的 {@code _render()} 一致。 */
    public static String render(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return String.valueOf(((Number) value).longValue());
        }
        if (value instanceof Number number) {
            // 协议不允许浮点；整数形式的浮点按整数渲染
            double d = number.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
            throw new IllegalArgumentException("载荷不支持浮点数：" + value);
        }
        if (value instanceof String text) {
            return text.replace("\r", " ").replace("\n", " ");
        }
        if (value instanceof Collection<?> collection) {
            List<String> rendered = new ArrayList<>(collection.size());
            for (Object item : collection) {
                rendered.add(render(item));
            }
            rendered.sort(String::compareTo);
            return String.join(",", rendered);
        }
        throw new IllegalArgumentException("载荷不支持的类型：" + value.getClass().getName());
    }
}
