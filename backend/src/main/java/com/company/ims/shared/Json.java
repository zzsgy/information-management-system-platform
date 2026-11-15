package com.company.ims.shared;

import tools.jackson.databind.json.JsonMapper;

/** 在数据库文本列中保存结构化快照，避免业务层依赖 JSON 库细节。 */
public final class Json {
    /** 可安全复用的 JSON 编解码器，日期使用标准 ISO 表示。 */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    /** 工具类不允许实例化。 */
    private Json() {}
    /** 将业务对象转为 JSON 文本。 */
    public static String write(Object value) { return MAPPER.writeValueAsString(value); }
    /** 按目标类型读取 JSON；输入损坏时由调用方决定如何报告。 */
    public static <T> T read(String text, Class<T> type) { return MAPPER.readValue(text, type); }
}
