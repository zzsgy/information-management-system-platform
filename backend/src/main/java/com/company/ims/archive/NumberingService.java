package com.company.ims.archive;

import com.company.ims.shared.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;

/** 支持旧模板与分段规则；规则行锁串行化首次序列创建，防止并发重复。 */
@Service
public class NumberingService {
    /** 编号规则及流水存储。 */
    private final Db db;
    /** 注入数据库访问器。 */
    public NumberingService(Db db) { this.db = db; }
    /**
     * 在调用事务内生成编号，使用规则行锁串行化流水；预览只读，不消耗流水。
     * @param deptId 已完成业务范围授权的启用部门主键，用于读取部门编码和流水作用域
     * @param categoryId 启用档案分类主键，可指定独立编号规则
     * @param retentionId 可空保管期限主键，仅提供保管期限编码引用
     * @param preview 是否仅生成示例；正式编号必须与档案登记位于同一事务
     * @return 已校验非空、长度和占位符的完整档案编号
     */
    @Transactional
    public String generate(long deptId, long categoryId, Long retentionId, boolean preview) {
        // 分类必须存在，否则不能为无效档案生成编号。
        Map<String,Object> category = db.required("select * from archive_category where id=? and status=1", categoryId);
        // 部门编码参与编号，部门停用时禁止新增。
        Map<String,Object> department = db.required("select * from ims_department where id=? and status=1", deptId);
        // 优先使用分类绑定的规则，其次使用最高优先级启用规则。
        Map<String,Object> rule = category.get("numbering_rule_id") == null ? null : db.one("select * from numbering_rule where id=? and enabled=1", category.get("numbering_rule_id"));
        if (rule == null) rule = db.one("select * from numbering_rule where enabled=1 order by priority desc,id limit 1");
        if (rule == null) throw new BusinessException(409, "没有可用的编号规则，请管理员先配置");
        // 锁定规则可覆盖尚无序列行时的并发竞争。
        long ruleId = Input.id(rule.get("id"));
        if (!preview) rule = db.required("select * from numbering_rule where id=? for update", ruleId);
        // 同一规则按配置区分部门/分类作用域。
        String scope = switch (Objects.toString(rule.get("scope_type"))) {
            case "global" -> "global"; case "dept" -> "dept:" + deptId; case "category" -> "cat:" + categoryId;
            default -> "dept:" + deptId + ":cat:" + categoryId;
        };
        // 年/月周期按照服务端日历计算；NONE 表示不重置。
        LocalDate today = LocalDate.now();
        // 流水号重置周期键，年度、月份或全周期各自独立。
        String period = switch (Objects.toString(rule.get("reset_cycle"))) { case "MONTH" -> today.toString().substring(0,7); case "NONE" -> "all"; default -> Integer.toString(today.getYear()); };
        // 在规则锁保护下查找或创建作用域序列。
        Map<String,Object> sequence = db.one("select * from numbering_sequence where rule_id=? and scope_key=? and period_key=?", ruleId, scope, period);
        // 锁保护下计算的下一流水值，只有正式创建才持久化。
        long next = sequence == null ? 1 : ((Number)sequence.get("current_value")).longValue() + 1;
        if (!preview) {
            if (sequence == null) db.insert("numbering_sequence", Map.of("rule_id",ruleId,"scope_key",scope,"period_key",period,"current_value",next));
            else db.update("numbering_sequence", Input.id(sequence.get("id")), Map.of("current_value",next));
        }
        // 可引用字段使用实际业务编码，保管期限缺失时留空。
        Map<String,Object> retention = retentionId == null ? null : db.required("select * from retention_period where id=?", retentionId);
        // 从真实部门分类及日期构造的允许引用字段。
        Map<String,String> refs = Map.of("dept_code",department.get("dept_code").toString(),"category_code",category.get("category_code").toString(),"retention_code",retention==null?"":retention.get("code").toString(),"year",String.valueOf(today.getYear()),"month",String.format("%02d",today.getMonthValue()),"day",String.format("%02d",today.getDayOfMonth()));
        // 分段为空时兼容传统模板。
        String segments = Objects.toString(rule.get("segments"), "");
        if (!segments.isBlank()) {
            // JSON 段定义来自管理员配置，仍逐段校验类型与长度。
            List<?> parts = Json.read(segments, List.class);
            // 最终组合的档案编号，提交前仍校验完整性与唯一性。
            StringBuilder number = new StringBuilder();
            for (Object part : parts) {
                if (!(part instanceof Map<?,?> segment)) throw new BusinessException(400, "编号分段必须是对象");
                // 每段只支持固定文本、引用字段或流水号。
                String type = Objects.toString(segment.get("type"), "");
                // 经过字段类型检查后参与处理的业务值。
                String value = switch(type) { case "fixed" -> Objects.toString(segment.get("value"), ""); case "ref" -> refs.getOrDefault(Objects.toString(segment.get("ref")), ""); case "seq" -> Long.toString(next); default -> throw new BusinessException(400,"不支持的编号分段类型"); };
                // 配置的补位宽度，只补齐而不截断真实编号。
                int width = Input.integer(segment.get("length"),0,0,100);
                // 编号不足位数时使用的单个补位字符。
                String pad = Objects.toString(segment.get("pad"), "0");
                if (pad.length()!=1) throw new BusinessException(400,"编号补位字符必须为一个字符");
                if (width>0 && value.length()>width) throw new BusinessException(409,"编号字段或流水号超过配置长度，请调整规则；不会截断生成重复编号");
                number.append(pad.repeat(Math.max(0,width-value.length()))).append(value);
            }
            return checked(number.toString());
        }
        // 传统流水号长度为最小补零长度，超长时保留全部数字。
        int width = ((Number)rule.get("sequence_length")).intValue();
        // 按配置最小宽度补齐后的完整流水号文本。
        String padded = String.format("%0" + width + "d", next);
        return checked(rule.get("pattern").toString().replace("{DEPT}",refs.get("dept_code")).replace("{CATEGORY}",refs.get("category_code")).replace("{YEAR}",refs.get("year")).replace("{SEQ"+width+"}",padded).replace("{SEQ}",padded));
    }
    /**
     * 编号不能为空、过长或包含未解析占位符。
     * @param number 按规则组合完成但尚未通过最终校验的编号
     * @return 可写入档案记录的完整编号
     */
    private String checked(String number) {
        if(number.isBlank() || number.length()>100 || number.contains("{")) throw new BusinessException(400,"编号规则生成了无效编号");
        return number;
    }
}
