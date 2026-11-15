package com.company.ims.shared;

import java.time.LocalDate;
import java.util.*;

/** 对兼容旧客户端的映射请求执行明确的类型和字段校验。 */
public final class Input {
    /** 工具类无需实例化。 */
    private Input() {}
    /**
     * 读取可选字符串，缺失时返回空串；禁止把对象或数组隐式保存成文本。
     *
     * @param body 已解析的请求字段映射
     * @param key 服务端指定的待校验字段名
     * @return 去除首尾空格的文本；缺失或空值返回空串
     */
    public static String text(Map<String,?> body, String key) {
        // 请求字段必须保持明确类型，避免Map.toString等非预期转换进入业务记录。
        Object value = body.get(key);
        if (value == null) return "";
        if (!(value instanceof String text)) throw new BusinessException(400, key + " 必须为字符串");
        return text.trim();
    }
    /**
     * 读取必填文本并限制长度，字段名用于错误上下文。
     *
     * @param body 已解析的请求字段映射
     * @param key 服务端指定的必填字段名
     * @param max 该业务字段允许的最大字符数
     * @return 非空且长度合规的清理后文本
     */
    public static String requiredText(Map<String,?> body, String key, int max) {
        // 清理首尾空格后的用户输入。
        String value = text(body, key);
        if (value.isEmpty() || value.length() > max) throw new BusinessException(400, key + " 必填且长度不能超过 " + max);
        return value;
    }
    /**
     * 读取正整数主键，拒绝小数、布尔值与非正数。
     * @param value 待校验的主键数值或十进制字符串
     * @return 严格解析的正整数主键
     */
    public static long id(Object value) {
        try {
            // 采用严格十进制解析，避免浮点数截断。
            long number = Long.parseLong(String.valueOf(value));
            if (number <= 0) throw new NumberFormatException();
            return number;
        } catch (RuntimeException error) { throw new BusinessException(400, "标识必须是正整数"); }
    }
    /**
     * 读取指定范围内的整数；空输入时采用业务默认值。
     * @param value 待解析的整数或十进制字符串
     * @param fallback 空输入时采用的默认值，仍需满足范围限制
     * @param min 允许的最小值，包含边界
     * @param max 允许的最大值，包含边界
     * @return 已通过上下界校验的整数
     */
    public static int integer(Object value, int fallback, int min, int max) {
        try {
            // 空字符串沿用客户端未填写的语义。
            int result = value == null || value.toString().isBlank() ? fallback : Integer.parseInt(value.toString());
            if (result < min || result > max) throw new NumberFormatException();
            return result;
        } catch (RuntimeException error) { throw new BusinessException(400, "整数值超出允许范围：" + min + "～" + max); }
    }
    /**
     * 复制允许写入的字段，防止用户通过请求注入状态、创建人等内部属性。
     * @param body 原始请求字段映射，不会被此方法修改
     * @param allowed 服务端定义的可写字段白名单
     * @return 仅包含已提交白名单字段的新有序映射，保留显式空值
     */
    public static Map<String,Object> pick(Map<String,?> body, String... allowed) {
        // 有序映射便于字段审计和参数化数据库写入。
        Map<String,Object> result = new LinkedHashMap<>();
        for (String key : allowed) if (body.containsKey(key)) result.put(key, body.get(key));
        return result;
    }
    /**
     * 将 ISO 日期转为 JDBC 日期；空值表示清空可选日期。
     * @param value 请求中的 YYYY-MM-DD 日期或空值
     * @return 可直接绑定数据库的日期，空输入返回空值
     */
    public static java.sql.Date date(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        try { return java.sql.Date.valueOf(LocalDate.parse(value.toString())); }
        catch (RuntimeException error) { throw new BusinessException(400, "日期必须采用 YYYY-MM-DD 格式"); }
    }
}
