package com.company.ims.workflow;

import com.company.ims.shared.BusinessException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** 审批与业务接口输入边界：只接受明确字段和类型，不把任意请求列写入数据库。 */
final class WorkflowInput {
    /** 工具类不允许实例化。 */
    private WorkflowInput() { }

    /** 构造可包含空值的数据库字段映射；参数必须成对给出。 */
    static Map<String,Object> fields(Object... pairs) {
        // LinkedHashMap 保持字段顺序，便于数据库参数与审计调试。
        Map<String,Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put((String)pairs[index], pairs[index + 1]);
        }
        return result;
    }

    /** 读取可空字符串；拒绝对象/数组及超长文本，必填文本不接受空白。 */
    static String text(Map<String,?> body, String key, int max, boolean required) {
        // 请求值必须原本是文本，不能依赖 Object.toString 隐式转换。
        Object value = body.get(key);
        if (value == null) {
            if (required) throw bad("缺少字段：" + key);
            return null;
        }
        if (!(value instanceof String)) throw bad(key + " 必须为文本");
        String result = ((String)value).trim(); // 规范化界面输入的首尾空白。
        if (result.length() > max || (required && result.isBlank())) throw bad(key + " 为空或超过长度限制 " + max);
        return result;
    }

    /** 读取有限集合中的字符串，缺省时使用业务默认值。 */
    static String choice(Map<String,?> body, String key, String fallback, Set<String> allowed) {
        String value = text(body,key,100,false); // 经过长度和类型检查的候选值。
        if (value == null) value = fallback;
        if (!allowed.contains(value)) throw bad(key + " 不受支持");
        return value;
    }

    /** 验证数值是精确整数，避免小数截断成另一个档案或用户ID。 */
    static long number(Object value, String name) {
        if (value == null) throw bad("缺少字段：" + name);
        try {
            // 精确解析 JSON Number 与字符串形式ID；拒绝浮点截断和溢出。
            return new java.math.BigDecimal(value.toString()).longValueExact();
        } catch (RuntimeException failure) {
            throw bad(name + " 必须是整数");
        }
    }

    /** 读取可空正整数ID。 */
    static Long optionalId(Map<String,?> body, String key) {
        if (body.get(key) == null) return null;
        long value = number(body.get(key),key); // 外键必须为正整数。
        if (value < 1) throw bad(key + " 必须大于零");
        return value;
    }

    /** 读取有上下界的整数选项；空值使用缺省值。 */
    static int integer(Map<String,?> body, String key, int fallback, int min, int max) {
        long value = body.get(key) == null ? fallback : number(body.get(key),key); // 先按long校验避免溢出。
        if (value < min || value > max) throw bad(key + " 超出范围");
        return (int)value;
    }

    /** 读取布尔审批结果，不接受任意非空字符串作为true。 */
    static boolean bool(Map<String,?> body, String key) {
        if (!(body.get(key) instanceof Boolean value)) throw bad(key + " 必须是布尔值");
        return value;
    }

    /** 读取最多200个不同档案ID并排序；排序同时确定行锁顺序。 */
    static List<Long> archiveIds(Map<String,?> body) {
        if (!(body.get("archive_ids") instanceof List<?> values) || values.isEmpty() || values.size() > 200)
            throw bad("请选择1至200份档案");
        Set<Long> ids = new TreeSet<>(); // 排序、去重后用于批量档案加锁和精确范围校验。
        for (Object value : values) {
            long id = number(value,"archive_ids"); // 逐个验证正整数ID。
            if (id < 1 || !ids.add(id)) throw bad("档案ID必须为正整数且不能重复");
        }
        return List.copyOf(ids);
    }

    /** 校验预计归还日，空值表示未指定；过期日期不允许用于新借阅。 */
    static LocalDate expectedDate(Map<String,?> body) {
        String value = text(body,"expected_return_date",10,false); // 客户端使用ISO日期。
        if (value == null || value.isBlank()) return null;
        try {
            LocalDate date = LocalDate.parse(value); // 仅接受yyyy-MM-dd。
            if (date.isBefore(LocalDate.now())) throw bad("预计归还日期不能早于今天");
            return date;
        } catch (DateTimeParseException failure) {
            throw bad("预计归还日期格式必须为yyyy-MM-dd");
        }
    }

    /** 返回输入不合法业务异常，由统一异常处理器生成400响应。 */
    static BusinessException bad(String message) { return new BusinessException(400,message); }
}
