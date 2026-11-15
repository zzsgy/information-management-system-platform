package com.company.platform;

import java.util.List;
import java.util.Map;

/** 为列表的计数和分页共用绑定条件。SQL 表达式由调用方固定，用户输入只作为绑定值。 */
public final class ListConditions {
    private final Map<String, String> query;
    private final List<Object> args;
    private final StringBuilder conditions = new StringBuilder();

    public ListConditions(Map<String, String> query, List<Object> args) {
        this.query = query;
        this.args = args;
    }

    /** 搜索框按字面匹配，输入的百分号和下划线也作为普通字符。 */
    public ListConditions contains(String key, String expression) {
        return match(key, expression + " ILIKE ?");
    }

    /** 支持在已有授权范围内关联搜索，例如按经办人的姓名或工号查找。 */
    public ListConditions match(String key, String predicate) {
        String value = query.get(key);
        if (value == null || value.isBlank()) return this;
        String keyword = Inputs.text(query, key, 200);
        return add(predicate, "%" + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
    }

    /** 下拉选项在入口核验枚举，空值表示不添加限制。 */
    public ListConditions choice(String key, String expression, String... allowed) {
        String value = query.get(key);
        return value == null || value.isEmpty() ? this : add(expression + "=?", Inputs.choice(value, allowed));
    }

    /** 布尔筛选直接复用列表展示的判断表达式。 */
    public ListConditions flag(String key, String expression) {
        String value = query.get(key);
        return value == null || value.isEmpty() ? this : add("coalesce(("+expression+"),false)=?", Boolean.valueOf(Inputs.choice(value,"true","false")));
    }

    /** 使用时间戳的半开区间，保留时间列直接使用索引的能力。 */
    public ListConditions dates(String key, String column) {
        var dates = ListDateRange.parse(query, key + "From", key + "To");
        if (dates.from() != null) add(column + ">=?", dates.from());
        if (dates.until() != null) add(column + "<?", dates.until());
        return this;
    }

    private ListConditions add(String predicate, Object value) {
        conditions.append(" AND ").append(predicate);
        args.add(value);
        return this;
    }

    /** 追加到既有权限范围之后，计数和分页必须使用同一份条件。 */
    public String sql() {
        return conditions.toString();
    }
}
