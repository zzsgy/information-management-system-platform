package com.company.platform;

import java.util.*;
import java.util.regex.Pattern;

/** V1.1 输入白名单；PATCH 区分未提供与显式 null，不静默截断业务数据。 */
public final class Inputs {
    /** 公司邮箱的既定域名；不得从前端提交值推断授权范围。 */
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@biolink\\.com", Pattern.CASE_INSENSITIVE);
    private Inputs() { }
    /** 拒绝未知字段；同样用于查询参数。 */
    public static void only(Map<String,?> body, String... names) {
        Set<String> allowed = Set.of(names);
        if (!allowed.containsAll(body.keySet())) throw ApiFault.invalid("请求包含不支持的字段");
    }
    /** 必填文本；空白密码仍允许，但普通业务文字去除首尾空白。 */
    public static String text(Map<String,?> body, String name, int max) {
        Object value = body.get(name);
        if (!(value instanceof String text) || text.trim().isEmpty() || text.length()>max) throw ApiFault.invalid(name+" 为空或超过长度限制");
        return text.trim();
    }
    /** 登录与旧密码验证兼容既有口令；自主设密使用 newPassword 校验。 */
    public static String password(Map<String,?> body, String name) {
        Object value=body.get(name);
        if (!(value instanceof String text) || text.isEmpty() || text.length()>4096) throw ApiFault.invalid("密码不能为空或超过传输上限");
        return text;
    }
    /** 新密码至少八个字符，同时包含英文字母和数字；不要求大小写组合或特殊符号。 */
    public static String newPassword(Map<String,?> body, String name) {
        String text=password(body,name);
        if(text.codePointCount(0,text.length())<8 || !Pattern.compile("[A-Za-z]").matcher(text).find() || !Pattern.compile("[0-9]").matcher(text).find())
            throw ApiFault.invalid("新密码至少8位，且须同时包含英文字母和数字");
        return text;
    }
    /** 严格标准 UUID；非法参数返回 400，不产生驱动异常。 */
    public static UUID uuid(Object value) {
        try { String s=Objects.toString(value, ""); UUID id=UUID.fromString(s); if (!id.toString().equals(s)) throw new IllegalArgumentException(); return id; }
        catch (IllegalArgumentException e) { throw ApiFault.invalid("对象标识必须为标准 UUID"); }
    }
    /** 空引用只在字段契约允许时使用。 */
    public static UUID nullableUuid(Object value) { return value==null ? null : uuid(value); }
    /** 公司邮箱规范化并核对固定域名。 */
    public static String email(Map<String,?> body) { String email=text(body,"companyEmail",254).toLowerCase(Locale.ROOT); if (!EMAIL.matcher(email).matches()) throw ApiFault.invalid("请填写 @biolink.com 公司邮箱"); return email; }
    /** 有界整数；禁止小数被 Number.intValue 静默截断。 */
    public static int integer(Object value, int fallback, int min, int max) {
        if (value==null) return fallback;
        try { int parsed=Integer.parseInt(value.toString()); if (parsed<min || parsed>max) throw new NumberFormatException(); return parsed; }
        catch (NumberFormatException e) { throw ApiFault.invalid("整数参数超出允许范围"); }
    }
    /** 受控枚举，不允许任意字符串变成业务状态。 */
    public static String choice(Object value, String... choices) { String s=Objects.toString(value, ""); if (!Set.of(choices).contains(s)) throw ApiFault.invalid("状态或类型不受支持"); return s; }
    /** 最多指定数量的对象数组；不接受字符串伪装为 JSON 对象。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String,Object>> objects(Object value, int max) {
        if (!(value instanceof List<?> list) || list.size()>max || list.stream().anyMatch(x -> !(x instanceof Map))) throw ApiFault.invalid("对象集合格式或数量不正确");
        return (List<Map<String,Object>>) value;
    }
}
