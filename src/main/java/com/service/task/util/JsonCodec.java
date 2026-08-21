package com.service.task.util;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * JSON 编解码 + 幂等请求内容的规范化（canonicalize）工具。
 */
@Component
public class JsonCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public JsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("json serialize failed", e);
        }
    }

    public Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("json parse failed: " + json, e);
        }
    }

    public <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("json parse failed: " + json, e);
        }
    }

    /**
     * 把任意 JSON 结构（Map/List/标量）转成确定性字符串：
     * 对象 key 递归排序，从而“相同内容、不同字段顺序”的请求得到相同哈希。
     */
    public String canonicalize(Object value) {
        StringBuilder sb = new StringBuilder();
        writeCanonical(sb, value);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void writeCanonical(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> sorted = new TreeMap<>();
            rawMap.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(e.getKey()).append("\":");
                writeCanonical(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Collection<?> collection) {
            sb.append('[');
            boolean first = true;
            for (Object item : new ArrayList<>(collection)) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeCanonical(sb, item);
            }
            sb.append(']');
        } else if (value instanceof String s) {
            sb.append('"').append(s).append('"');
        } else {
            sb.append(String.valueOf(value));
        }
    }

    public static Map<String, Object> emptyMapIfNull(Map<String, Object> map) {
        return map == null ? Collections.emptyMap() : map;
    }
}
