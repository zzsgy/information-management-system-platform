package com.company.ims.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/** 参数化 JDBC 工具；表名和列名仅可来自服务端白名单，数据值始终绑定参数。 */
@Component
public class Db {
    /** 由 Spring 管理连接与事务的 JDBC 模板。 */
    private final JdbcTemplate jdbc;
    /** 注入数据库连接模板。 */
    public Db(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 查询多行，统一列名大小写和数据库时间类型，保持旧客户端 snake_case 字段。 */
    public List<Map<String,Object>> list(String sql, Object... parameters) {
        return jdbc.query(sql, (result, index) -> {
            // 当前行的列标签与值；使用有序映射使输出和审计快照稳定。
            Map<String,Object> row = new LinkedHashMap<>();
            // 元数据用于读取联表查询的别名。
            ResultSetMetaData metadata = result.getMetaData();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                // 时间字段归一为 ISO 文本，避免驱动私有类型进入响应。
                Object value = result.getObject(column);
                if (value instanceof Timestamp time) value = time.toLocalDateTime().toString();
                else if (value instanceof java.sql.Date date) value = date.toLocalDate().toString();
                else if (value instanceof java.time.temporal.TemporalAccessor) value = value.toString();
                row.put(metadata.getColumnLabel(column).toLowerCase(Locale.ROOT), value);
            }
            return row;
        }, parameters);
    }
    /** 查询可选单行；调用方必须使用唯一键或显式排序限制。 */
    public Map<String,Object> one(String sql, Object... parameters) {
        // 查询结果为空表示业务对象不存在。
        List<Map<String,Object>> rows = list(sql, parameters);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    /** 查询必需的业务对象，不存在时返回标准 404。 */
    public Map<String,Object> required(String sql, Object... parameters) {
        // 保留空值语义，禁止将不存在的记录当作空对象继续更新。
        Map<String,Object> row = one(sql, parameters);
        if (row == null) throw new BusinessException(404, "记录不存在");
        return row;
    }
    /** 执行写语句，返回受影响行数以支持并发状态检查。 */
    public int execute(String sql, Object... parameters) { return jdbc.update(sql, parameters); }
    /** 读取计数或序列；SQL 的结果必须为一个数字。 */
    public long scalarLong(String sql, Object... parameters) {
        // 聚合没有值时统一视为零。
        Long value = jdbc.queryForObject(sql, Long.class, parameters);
        return value == null ? 0 : value;
    }
    /** 插入已通过业务白名单的数据，并返回数据库生成的主键。 */
    public long insert(String table, Map<String,?> values) {
        checkIdentifier(table);
        values.keySet().forEach(Db::checkIdentifier);
        // 列和值保持同一顺序，避免 Map 遍历次序改变绑定关系。
        List<String> columns = new ArrayList<>(values.keySet());
        // 主键持有器接收数据库 identity 值。
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        // SQL 标识符由代码白名单提供，业务值不能拼接入 SQL。
        String sql = "insert into " + table + " (" + String.join(",", columns) + ") values (" + String.join(",", Collections.nCopies(columns.size(), "?")) + ")";
        jdbc.update(connection -> {
            // 仅请求 id，兼容 PostgreSQL 返回多列与 H2 返回单列的差异。
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int index = 0; index < columns.size(); index++) statement.setObject(index + 1, values.get(columns.get(index)));
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
    /** 只更新调用方明确提供的字段；空更新直接返回。 */
    public void update(String table, long id, Map<String,?> values) {
        if (values.isEmpty()) return;
        checkIdentifier(table);
        values.keySet().forEach(Db::checkIdentifier);
        // 参数列表最后一个位置固定为记录主键。
        List<Object> parameters = new ArrayList<>(values.values());
        parameters.add(id);
        execute("update " + table + " set " + values.keySet().stream().map(key -> key + "=?").collect(Collectors.joining(",")) + " where id=?", parameters.toArray());
    }
    /** 限制动态标识符形状；业务层仍必须先做字段白名单过滤。 */
    private static void checkIdentifier(String identifier) {
        if (!identifier.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("非法数据库标识符");
    }
}
