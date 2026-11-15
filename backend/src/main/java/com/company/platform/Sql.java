package com.company.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.util.*;

/** 小型参数化查询边界；标识符只能来自代码白名单，业务值一律使用绑定参数。 */
@Component
public class Sql {
    /** 共享有界连接池，事务由 Spring 统一管理。 */
    private final JdbcTemplate jdbc;
    /** 注入查询模板；设置秒级超时，避免慢查询长期占用连接。 */
    public Sql(JdbcTemplate jdbc) { this.jdbc = jdbc; this.jdbc.setQueryTimeout(10); }
    /** 查询内部行，保留 snake_case；只有显式 DTO 投影可对外返回。 */
    public List<Map<String,Object>> rows(String sql, Object... args) {
        return jdbc.query(sql, (rs, index) -> {
            // 按数据库列标签组织内部字段，时间统一 UTC 字符串。
            Map<String,Object> row = new LinkedHashMap<>();
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                Object value = rs.getObject(i);
                if (value instanceof Timestamp timestamp) value = timestamp.toInstant().toString();
                // SQL数组和日期转换为稳定业务值，不能把JDBC连接包装对象送进目录快照或HTTP。
                if (value instanceof java.sql.Date date) value=date.toLocalDate().toString();
                if (value instanceof java.sql.Array array) {Object items=array.getArray();value=items instanceof Object[] elements?Arrays.asList(elements):items;array.free();}
                row.put(meta.getColumnLabel(i), value);
            }
            return row;
        }, args);
    }
    /** 读取可选唯一记录；调用者保证查询条件或 LIMIT 的明确性。 */
    public Map<String,Object> one(String sql, Object... args) { var rows = rows(sql, args); return rows.isEmpty() ? null : rows.getFirst(); }
    /** 获取必须可见的记录，缺失统一 404。 */
    public Map<String,Object> required(String sql, Object... args) { var row = one(sql, args); if (row == null) throw ApiFault.hidden(); return row; }
    /** 执行写操作或加锁语句，返回实际影响行数。 */
    public int update(String sql, Object... args) { return jdbc.update(sql, args); }
    /** 统计查询不把所有行加载到应用内存。 */
    public long count(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    /** 把指定列显式转换成 camelCase DTO，避免 SELECT * 泄露安全字段。 */
    public static Map<String,Object> view(Map<String,Object> row, String fields) {
        Map<String,Object> result = new LinkedHashMap<>(); // 仅字段白名单可进入响应。
        for (String field : fields.split(",")) {
            StringBuilder key = new StringBuilder(); boolean upper = false;
            for (char c : field.toCharArray()) { if (c == '_') upper = true; else { key.append(upper ? Character.toUpperCase(c) : c); upper = false; } }
            result.put(key.toString(), row.get(field));
        }
        return result;
    }
    /** 允许空值的短响应构造器；传入的键由服务端固定。 */
    public static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0; i<pairs.length; i+=2) result.put((String)pairs[i], pairs[i+1]);
        return result;
    }
}
