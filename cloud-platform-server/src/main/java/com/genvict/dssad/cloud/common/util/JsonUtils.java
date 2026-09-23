package com.genvict.dssad.cloud.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.List;
import java.util.Map;

/**
 * JSON 工具（基于 Jackson）。
 *
 * <p>关键策略：
 * <ul>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES = false}：对接方（监管平台/车端）版本升级时会新增字段，
 *       平台必须<b>前向兼容</b>，未知字段忽略而非报错，避免因对方加字段导致整条链路中断；</li>
 *   <li>单例 {@link ObjectMapper}，避免每次调用 new 造成性能浪费（ObjectMapper 线程安全）；</li>
 *   <li>解析失败统一包装为 {@link IllegalArgumentException}，由全局异常处理器兜底。</li>
 * </ul>
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .addModule(new JavaTimeModule())
            .build();

    private JsonUtils() {
    }

    /** 获取共享 ObjectMapper（需要 Spring 之外的场景时使用）。 */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** 对象 → JSON 字符串。 */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("对象序列化失败: " + value, e);
        }
    }

    /** JSON 字符串 → 指定类型。 */
    public static <T> T parse(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 反序列化失败: " + abbreviate(json), e);
        }
    }

    /**
     * JSON 字符串 → 泛型类型（如 {@code List<List<Double>>}）。
     *
     * <p>用于协议中「嵌套坐标数组」这类无法用 {@code Class<T>} 表达的场景：
     * {@code JsonUtils.parse(json, listOf(listOf(Double.class)))}
     */
    public static <T> T parse(String json, JavaType type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 反序列化失败: " + abbreviate(json), e);
        }
    }

    /** JSON 字符串 → 指定元素的列表。 */
    public static <T> List<T> parseList(String json, Class<T> elementType) {
        return parse(json, listOf(elementType));
    }

    /** 构造 {@code List<elementType>} 的 JavaType。 */
    public static JavaType listOf(Class<?> elementType) {
        return MAPPER.getTypeFactory().constructCollectionType(List.class, elementType);
    }

    /** 构造 {@code List<List<elementType>>} 的 JavaType（协议中大量使用 [[lat,lng],...] 结构）。 */
    public static JavaType listOfListOf(Class<?> elementType) {
        return MAPPER.getTypeFactory().constructCollectionType(List.class, listOf(elementType));
    }

    /** JSON 字符串 → JsonNode。 */
    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 解析失败: " + abbreviate(json), e);
        }
    }

    /** 对象 → JsonNode。 */
    public static JsonNode valueToTree(Object value) {
        return MAPPER.valueToTree(value);
    }

    /** 新建空对象节点。 */
    public static ObjectNode newObject() {
        return JsonNodeFactory.instance.objectNode();
    }

    /** Map → 指定类型。 */
    public static <T> T convert(Map<String, Object> source, Class<T> type) {
        return MAPPER.convertValue(source, type);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 512 ? text : text.substring(0, 512) + "...";
    }
}
