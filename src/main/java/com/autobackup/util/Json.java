package com.autobackup.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JSON 解析/序列化工具(Jackson). */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {}

    public static JsonNode parse(String text) {
        try {
            return MAPPER.readTree(text == null || text.isBlank() ? "{}" : text);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 解析失败: " + truncate(text), e);
        }
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
