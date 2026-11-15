package com.company.platform.archive;

import com.company.platform.*;
import java.util.*;

/** 保存类型沿用载体代码；纸质标签独立保存在扩展值和历史快照中。 */
public final class ArchivePreservation {
    private ArchivePreservation() { }
    /** 入口限制标签为两个固定选项，允许原件和复印件同时存在。 */
    public static List<String> tags(Object input) {
        if (!(input instanceof List<?> values) || values.size()>2 ||
                values.stream().anyMatch(value -> !(value instanceof String) || !List.of("纸质原件","纸质复印件").contains(value)) ||
                new HashSet<>(values).size()!=values.size()) throw ApiFault.invalid("纸质标签只能选择纸质原件、纸质复印件");
        return values.stream().map(String.class::cast).toList();
    }
    /** 新登记及纸质信息变更必须明确标签；不替历史档案推断原件身份。 */
    public static void normalize(String carrier, Map<String,Object> values) {
        String label=switch(carrier){case "PAPER" -> "纸质";case "ELECTRONIC" -> "电子";case "MIXED" -> "纸质+电子";default -> throw ApiFault.invalid("保存类型无效");};
        List<String> tags=tags(values.getOrDefault("paperTags",List.of()));
        if (!carrier.equals("ELECTRONIC") && tags.isEmpty()) throw ApiFault.invalid("保存类型含纸质，请至少绑定一个纸质标签");
        if (carrier.equals("ELECTRONIC") && !tags.isEmpty()) throw ApiFault.invalid("电子保存类型不能绑定纸质标签");
        if (values.containsKey("preservationType")) values.put("preservationType",label);
        values.put("paperTags",tags);
    }
}
