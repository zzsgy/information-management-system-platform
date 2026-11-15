package com.company.ims.archive;

import com.company.ims.identity.PermissionGuard;
import com.company.ims.shared.*;
import org.apache.commons.csv.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import java.io.StringReader;
import java.util.*;

/** CSV 导入兼容入口；每行独立事务，失败行不会影响已成功行。 */
@RestController
public class ArchiveImportController {
    /** 档案服务复用普通登记的权限和校验规则。 */
    private final ArchiveService archives;
    /** 引用数据名称查找器。 */
    private final Db db;
    /** 每条记录使用独立事务，避免异常污染后续行。 */
    private final TransactionTemplate transactions;
    /** 注入业务服务与事务管理器。 */
    public ArchiveImportController(ArchiveService archives,Db db,org.springframework.transaction.PlatformTransactionManager manager) {this.archives=archives;this.db=db;this.transactions=new TransactionTemplate(manager);}
    /**
     * 接收旧客户端上传的 CSV 文本，仅导入授权部门记录；总条数校验通过后才逐行提交。
     *
     * @param body 请求映射，content为至多一千万字符且至多5000条数据行的CSV文本
     * @return 成功和失败数量及最多50条失败原因，业务字段失败仅回滚该行
     * @throws Exception CSV解析或读取失败时拒绝本次输入；业务写入尚未开始
     */
    @PostMapping("/api/archives/import")
    public ApiResponse importCsv(@RequestBody Map<String,Object> body) throws Exception {
        PermissionGuard.require("archive.write");
        // 限制导入文本尺寸和条数，避免一次请求耗尽内存。
        String content=Input.requiredText(body,"content",10_000_000);
        if(content.startsWith("\uFEFF")) content=content.substring(1);
        // 逐行收集的可公开导入错误，不包含服务器内部异常文本。
        List<Map<String,Object>> errors=new ArrayList<>();
        // 已提交的成功数量，total同步统计本次输入行数。
        int created=0,total=0;
        try(CSVParser parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true).get().parse(new StringReader(content))) {
            // 在任何业务写入前解析并检查总行数，避免第5001行报错时前5000行已悄悄提交。
            List<CSVRecord> records=parser.getRecords();
            if(records.size()>5000) throw new BusinessException(400,"每次最多导入5000条档案");
            if(records.isEmpty()) throw new BusinessException(400,"未解析到任何数据行");
            for(CSVRecord record:records) {
                total++;
                try {
                    // 中文/英文列映射到同一登记对象。
                    Map<String,Object> data=new LinkedHashMap<>();
                    // 尚未通过结构校验的请求值。
                    Map<String,String> raw=record.toMap();
                    data.put("archive_name",pick(raw,"archive_name","档案名称"));
                    data.put("archive_no",pick(raw,"archive_no","档案编号"));
                    data.put("category_id",named("archive_category","category_name",pick(raw,"category","档案类别","category_name")));
                    data.put("dept_id",named("ims_department","dept_name",pick(raw,"department","部门","dept_name")));
                    // CSV中填写的密级名称，转换为唯一配置标识。
                    String security=pick(raw,"security","密级","security_level");
                    // CSV中填写的保管期限名称，转换为唯一配置标识。
                    String retention=pick(raw,"retention","保管期限");
                    if(!security.isBlank()) data.put("security_level_id",named("security_level","name",security));
                    if(!retention.isBlank()) data.put("retention_id",named("retention_period","name",retention));
                    // CSV中文列名到档案白名单字段的固定映射。
                    Map<String,String> fields=Map.ofEntries(Map.entry("file_type","文件类型"),Map.entry("owner_name","档案负责人"),Map.entry("cabinet_no","柜号"),Map.entry("column_no","列号"),Map.entry("layer_no","层号"),Map.entry("storage_type","保存类型"),Map.entry("copy_count","保存份数"),Map.entry("archive_date","存档日期"),Map.entry("validity_months","有效期"),Map.entry("remark","备注"));
                    fields.forEach((key,label)->{String value=pick(raw,key,label);if(!value.isBlank()) data.put(key,value);});
                    transactions.execute(status->archives.create(data));
                    created++;
                } catch(BusinessException error) {errors.add(Map.of("row",total,"error",error.getMessage()));}
                catch(RuntimeException error) {errors.add(Map.of("row",total,"error","字段格式或唯一约束冲突，请检查该行"));}
            }
        }
        return ApiResponse.ok(Map.of("total",total,"created",created,"failed",errors.size(),"errors",errors.stream().limit(50).toList()),"导入完成");
    }
    /**
     * 在多个列名中取首个非空值。
     * @param row 已解析的一行CSV列值
     * @param keys 按优先级排列的中英文兼容列名
     * @return 首个清理后的非空值，没有匹配时返回空串
     */
    private String pick(Map<String,String> row,String... keys) {for(String key:keys) if(row.get(key)!=null&&!row.get(key).isBlank()) return row.get(key).trim();return "";}
    /**
     * 名称必须唯一匹配，避免重名部门被分配到错误数据范围。
     * @param table 代码内固定的基础目录表名，不能来自用户输入
     * @param field 代码内固定的名称列，不能来自用户输入
     * @param name CSV中待解析的目录展示名称，使用绑定参数查询
     * @return 唯一匹配的目录记录主键
     */
    private long named(String table,String field,String name) {
        // 读取全部同名项以识别歧义，不能任取第一项决定部门归属。
        List<Map<String,Object>> matches=db.list("select id from "+table+" where "+field+"=?",name);
        if(matches.size()!=1) throw new BusinessException(400,name+" 不存在或名称不唯一");
        return Input.id(matches.getFirst().get("id"));
    }
}
