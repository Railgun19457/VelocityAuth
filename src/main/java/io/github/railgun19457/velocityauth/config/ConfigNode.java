package io.github.railgun19457.velocityauth.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 配置树上的一个节点。所有读取都带默认值，并对类型不匹配抛出带字段路径的 {@link ConfigException}。
 * <p>
 * 相比直接做 Bean 绑定，手动读取的好处是可以给出精确的字段路径与取值范围提示（见 IMPLEMENTATION.md 5.7-23）。
 */
public final class ConfigNode {

    private final String path;
    private final Map<String, Object> map;

    private ConfigNode(String path, Map<String, Object> map) {
        this.path = path;
        this.map = map == null ? Collections.emptyMap() : map;
    }

    public static ConfigNode root(Map<String, Object> map) {
        return new ConfigNode("", map);
    }

    public String path() {
        return path;
    }

    public boolean has(String key) {
        return map.containsKey(key);
    }

    /** 子节点；不存在时返回空节点，使调用方可以继续用默认值。 */
    @SuppressWarnings("unchecked")
    public ConfigNode section(String key) {
        Object value = map.get(key);
        String childPath = childPath(key);
        if (value == null) {
            return new ConfigNode(childPath, Collections.emptyMap());
        }
        if (!(value instanceof Map)) {
            throw new ConfigException(childPath, "应为配置段（映射），实际为 " + typeName(value));
        }
        return new ConfigNode(childPath, (Map<String, Object>) value);
    }

    public String string(String key, String def) {
        Object value = map.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof String s) {
            return s;
        }
        throw new ConfigException(childPath(key), "应为字符串，实际为 " + typeName(value));
    }

    public String stringRequired(String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new ConfigException(childPath(key), "缺少必填项");
        }
        if (value instanceof String s && !s.isEmpty()) {
            return s;
        }
        throw new ConfigException(childPath(key), "应为非空字符串");
    }

    public int intValue(String key, int def) {
        Object value = map.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        throw new ConfigException(childPath(key), "应为整数，实际为 " + typeName(value));
    }

    public boolean bool(String key, boolean def) {
        Object value = map.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw new ConfigException(childPath(key), "应为布尔值 true/false，实际为 " + typeName(value));
    }

    public List<String> stringList(String key, List<String> def) {
        Object value = map.get(key);
        if (value == null) {
            return def;
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object element : list) {
                if (element == null) {
                    continue;
                }
                out.add(String.valueOf(element));
            }
            return out;
        }
        throw new ConfigException(childPath(key), "应为字符串列表，实际为 " + typeName(value));
    }

    /**
     * 读取枚举式取值（大小写与连字符不敏感），非法值抛出带候选列表的异常。
     */
    public String enumValue(String key, String def, String... allowed) {
        String raw = string(key, def);
        if (raw == null) {
            return null;
        }
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(raw.trim())) {
                return candidate;
            }
        }
        throw new ConfigException(childPath(key),
                "取值 \"" + raw + "\" 不合法，可选值：" + String.join(" | ", allowed));
    }

    private String childPath(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static String typeName(Object value) {
        if (value instanceof Map) {
            return "映射";
        }
        if (value instanceof List) {
            return "列表";
        }
        return value.getClass().getSimpleName();
    }
}
