package com.company.ims.archive;

import com.company.ims.identity.*;
import com.company.ims.shared.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

/** 档案应用服务：所有入口复用数据范围、状态约束与审计事务。 */
@Service
public class ArchiveService {
    /** 允许通过普通登记表单修改的字段；生命周期状态不在其中。 */
    private static final String[] EDITABLE = {"archive_name","category_id","dept_id","security_level_id","retention_id","keyword","summary","physical_location","volume_no","page_count","file_type","owner_name","cabinet_no","column_no","layer_no","storage_type","copy_count","archive_date","validity_months","remark","archive_year","formation_date","filing_date"};
    /** 与数据库列宽对应的文本字段白名单，长正文也设置明确上限。 */
    private static final Map<String,Integer> TEXT_LIMITS = Map.ofEntries(
            Map.entry("archive_name",300),Map.entry("keyword",500),Map.entry("summary",100000),
            Map.entry("physical_location",300),Map.entry("volume_no",100),Map.entry("file_type",50),
            Map.entry("owner_name",100),Map.entry("cabinet_no",50),Map.entry("column_no",50),
            Map.entry("layer_no",50),Map.entry("storage_type",20),Map.entry("remark",100000));
    /** 联表显示字段保持与旧客户端一致。 */
    private static final String SELECT = "select a.*,d.dept_name,c.category_name,s.name security_name,r.name retention_name from archive a join ims_department d on d.id=a.dept_id join archive_category c on c.id=a.category_id left join security_level s on s.id=a.security_level_id left join retention_period r on r.id=a.retention_id";
    /** 数据访问与各项横切业务能力。 */
    private final Db db;
    /** 各入口共用的数据范围检查器。 */
    private final ArchiveAccess access;
    /** 带数据库行锁的档案编号分配器。 */
    private final NumberingService numbering;
    /** 与档案操作同事务的审计写入器。 */
    private final AuditService audit;
    /** 注入数据范围、编号和审计服务。 */
    public ArchiveService(Db db, ArchiveAccess access, NumberingService numbering, AuditService audit) {
        this.db=db; this.access=access; this.numbering=numbering; this.audit=audit;
    }
    /** 分页查询；过滤参数仅缩小当前用户可见范围，不能扩大授权。 */
    public Map<String,Object> list(Map<String,String> filters) {
        // 当前身份决定基础范围，前端不能自行指定绕过。
        Actor actor=PermissionGuard.require("archive.read");
        // 分页设置设定硬上限，兼容客户端的5000行导出请求。
        int page=Input.integer(filters.get("page"),1,1,1_000_000);
        // 本次读取的数量或内容字节数，受调用路径限制。
        int size=Input.integer(filters.get("page_size"),20,1,5000);
        // SQL 条件与参数独立维护，避免关键词拼入 SQL。
        StringBuilder where=new StringBuilder(" where a.deleted_at is null and ("+access.predicate(actor,"a")+")");
        // 与SQL占位符一一对应的绑定参数。
        List<Object> parameters=new ArrayList<>();
        if(filters.containsKey("keyword")&&!filters.get("keyword").isBlank()) {
            where.append(" and (lower(a.archive_name) like ? or lower(a.archive_no) like ? or lower(coalesce(a.keyword,'')) like ?)");
            // 用于大小写不敏感查询的绑定检索模式。
            String keyword="%"+filters.get("keyword").toLowerCase(Locale.ROOT)+"%";
            parameters.addAll(List.of(keyword,keyword,keyword));
        }
        for(String key:List.of("category_id","dept_id")) if(filters.containsKey(key)&&!filters.get(key).isBlank()) { where.append(" and a.").append(key).append("=?"); parameters.add(Input.id(filters.get(key))); }
        if(filters.containsKey("status")&&!filters.get("status").isBlank()) {where.append(" and a.status=?");parameters.add(filters.get("status"));}
        // 计数和返回列表使用同一授权表达式。
        long total=db.scalarLong("select count(*) from archive a"+where,parameters.toArray());
        parameters.add(size); parameters.add((long)(page-1)*size);
        // 通过权限范围过滤后的当前页结果。
        List<Map<String,Object>> items=db.list(SELECT+where+" order by a.id desc limit ? offset ?",parameters.toArray());
        items.forEach(this::addLatestUpdate);
        return Map.of("total",total,"items",items);
    }
    /** 读取详情时记录审计，同样受部门与共享范围约束。 */
    @Transactional
    public Map<String,Object> detail(long id) {
        access.requireRead(id);
        audit.record(CurrentUser.require().id(),"archive","view","archive",id,null);
        // 本次对外响应的字段集合，不包含内部存储凭据。
        Map<String,Object> result=db.required(SELECT+" where a.id=?",id);
        addLatestUpdate(result);
        return result;
    }
    /** 登记新档案；初始状态固定草稿，编号与审计同事务提交。 */
    @Transactional
    public Map<String,Object> create(Map<String,Object> body) {
        // 当前已认证且实时解析权限的操作主体。
        Actor actor=PermissionGuard.require("archive.write");
        // 已筛选的业务写入字段，与原始请求分离。
        Map<String,Object> values=Input.pick(body,EDITABLE);
        values.put("archive_name",Input.requiredText(body,"archive_name",300));
        values.put("dept_id",Input.id(body.get("dept_id")));
        values.put("category_id",Input.id(body.get("category_id")));
        access.requireDepartment(Input.id(values.get("dept_id")));
        validate(values);
        // 数据库中的档案编号方式，决定手工编号或自动分配。
        Map<String,Object> mode=db.one("select param_value from system_parameter where param_key='archive_no_mode'");
        // 最终组合的档案编号，提交前仍校验完整性与唯一性。
        String number=mode!=null&&"manual".equals(mode.get("param_value"))?Input.requiredText(body,"archive_no",100):numbering.generate(Input.id(values.get("dept_id")),Input.id(values.get("category_id")),values.get("retention_id")==null?null:Input.id(values.get("retention_id")),false);
        values.put("archive_no",number); values.put("creator_user_id",actor.id());values.put("status","draft");
        values.putIfAbsent("archive_year",LocalDate.now().getYear());
        values.putIfAbsent("copy_count",1);values.putIfAbsent("page_count",0);
        // 本次业务记录或引用对象的主键，供关联和审计使用。
        long id=db.insert("archive",values);
        writeChange(id,"create",Map.of(),values);
        return db.required(SELECT+" where a.id=?",id);
    }
    /**
     * 更新元数据并保留前后快照；销毁审批和待执行期间冻结内容，普通编辑不能改变状态。
     *
     * @param id 当前请求身份具有编辑范围的档案主键
     * @param body 待更新的业务字段，只接受白名单字段并校验归属和输入类型
     * @return 更新后的档案及分类、部门等展示信息
     */
    @Transactional
    public Map<String,Object> update(long id,Map<String,Object> body) {
        access.requireWrite(id);
        // 锁定后的修改前快照，用于状态校验和可追溯审计。
        Map<String,Object> before=db.required("select * from archive where id=? for update",id);
        access.requireNoActiveDestruction(id);
        if(body.containsKey("status")&&!Objects.equals(body.get("status"),before.get("status"))) throw new BusinessException(409,"档案状态只能通过归档、借阅或销毁流程修改");
        if(!Set.of("draft","archive").contains(before.get("status"))) throw new BusinessException(409,"当前状态不允许修改档案元数据");
        // 已筛选的业务写入字段，与原始请求分离。
        Map<String,Object> values=Input.pick(body,EDITABLE);
        if(values.containsKey("dept_id")) {
            // 收到跨部门写入共享不代表拥有原件归属，禁止转入自己部门后绕过共享限制。
            long targetDepartment=Input.id(values.get("dept_id"));
            // 只有原归属部门人员或获授权全域管理员可以发起部门变更。
            Actor actor=CurrentUser.require();
            if(targetDepartment!=Input.id(before.get("dept_id")) && actor.deptId()!=Input.id(before.get("dept_id")) && !actor.has("archive.manage.all"))
                throw new BusinessException(403,"共享编辑权限不能改变档案归属部门");
            // 不改变原部门的共享编辑仍可继续；真实部门变更须检查目标范围。
            if(targetDepartment!=Input.id(before.get("dept_id"))) access.requireDepartment(targetDepartment);
        }
        if(body.containsKey("archiver_user_id")) throw new BusinessException(400,"归档经办人由实际归档操作记录，不能手工指定");
        if(values.containsKey("physical_location")&&!Objects.equals(values.get("physical_location"),before.get("physical_location"))) PermissionGuard.require("archive.admin");
        if(values.containsKey("archive_name")) values.put("archive_name",Input.requiredText(body,"archive_name",300));
        // 到期日根据更新后完整日期上下文计算，不能让客户端直接指定。
        if(values.containsKey("archive_date")||values.containsKey("validity_months")) {
            values.putIfAbsent("archive_date",before.get("archive_date"));
            values.putIfAbsent("validity_months",before.get("validity_months"));
        }
        validate(values);
        values.put("updated_at",OffsetDateTime.now());
        db.update("archive",id,values);
        writeChange(id,"update",before,values);
        return db.required(SELECT+" where a.id=?",id);
    }
    /**
     * 只允许草稿归档；锁定后复核销毁冻结，防止状态入口绕过内容保护。
     *
     * @param id 当前请求身份具有编辑范围的草稿档案主键
     */
    @Transactional
    public void archive(long id) {
        access.requireWrite(id);
        db.required("select id from archive where id=? and deleted_at is null for update",id);
        access.requireNoActiveDestruction(id);
        if(db.execute("update archive set status='archive',archiver_user_id=?,filing_date=current_date,updated_at=current_timestamp where id=? and status='draft' and deleted_at is null",CurrentUser.require().id(),id)!=1) throw new BusinessException(409,"仅草稿档案可以执行归档");
        writeChange(id,"archive",Map.of("status","draft"),Map.of("status","archive","archiver_user_id",CurrentUser.require().id()));
    }
    /** 历史变更记录仍服从当前档案可见范围。 */
    public List<Map<String,Object>> changes(long id) {
        access.requireRead(id);
        // 数据库返回的范围内记录集合。
        List<Map<String,Object>> rows=db.list("select * from archive_update_log where archive_id=? order by id desc",id);
        rows.forEach(row->row.put("changed_fields",Json.read(row.get("changed_fields").toString(),List.class)));
        return rows;
    }
    /** 统计与列表使用相同数据范围，避免通过聚合数据泄露其他部门信息。 */
    public Map<String,Object> stats() {
        // 当前已认证且实时解析权限的操作主体。
        Actor actor=PermissionGuard.require("archive.read");
        // 服务端构建的数据范围与过滤表达式，不接受客户端SQL。
        String where=" where a.deleted_at is null and ("+access.predicate(actor,"a")+")";
        // 按同一可见范围汇总的生命周期状态计数。
        List<Map<String,Object>> statuses=db.list("select a.status,count(*) count from archive a"+where+" group by a.status");
        // 状态编码对应的中文展示名称。
        Map<String,String> labels=Map.of("draft","草稿","archive","已归档","borrowed","已借出","damaged","已损坏","destroyed","已销毁","locked","已锁定","destroy_pending","待销毁");
        statuses.forEach(row->row.put("label",labels.getOrDefault(row.get("status"),row.get("status").toString())));
        // 当前身份可见且即将到期的档案明细。
        List<Map<String,Object>> expiring=db.list(SELECT+where+" and a.expiry_date between ? and ? order by a.expiry_date limit 100",java.sql.Date.valueOf(LocalDate.now()),java.sql.Date.valueOf(LocalDate.now().plusDays(30)));
        return Map.of("total",db.scalarLong("select count(*) from archive a"+where),"by_status",statuses,"by_category",db.list("select c.category_name name,count(*) count from archive a join archive_category c on c.id=a.category_id"+where+" group by c.category_name"),"by_security",db.list("select coalesce(s.name,'未设置') name,count(*) count from archive a left join security_level s on s.id=a.security_level_id"+where+" group by s.name"),"expiring_soon",expiring,"expiring_count",db.scalarLong("select count(*) from archive a"+where+" and a.expiry_date between ? and ?",java.sql.Date.valueOf(LocalDate.now()),java.sql.Date.valueOf(LocalDate.now().plusDays(30))));
    }
    /** 授权列表仅由有共享管理权限的归属部门人员或全域管理员读取。 */
    public List<Map<String,Object>> grants(long id) { requireShare(id);return db.list("select * from archive_grant where archive_id=? order by id",id); }
    /** 仅返回共享所需主体标识与名称，不扩大用户管理接口权限。 */
    public List<Map<String,Object>> shareSubjects(String type) {
        PermissionGuard.require("archive.share");
        if("user".equals(type)) return db.list("select id,real_name name,username,dept_id from ims_user where account_status='active' and deleted_at is null order by id");
        if("dept".equals(type)) return db.list("select id,dept_name name,dept_name from ims_department where status=1 and deleted_at is null order by id");
        throw new BusinessException(400,"共享主体类型必须是 user 或 dept");
    }
    /**
     * 新增明确动作的共享授权，查询授权不会自动包含下载或修改。
     * @param id 当前身份有权管理共享的本部门或明确全范围档案主键
     * @param body 共享主体类型、主体主键、精确权限及可选带时区有效期
     * @return 新建共享记录，只有调用方自身拥有的动作才能转授
     */
    @Transactional
    public Map<String,Object> grant(long id,Map<String,Object> body) {
        requireShare(id);
        // 当前操作或编号分段的受控类型。
        String type=Input.text(body,"grantee_type");
        // 本次需要授予或校验的精确功能权限。
        String permission=Input.text(body,"permission_code");
        // 共享目标主体的稳定标识，需要确认真实存在。
        long grantee=Input.id(body.get("grantee_id"));
        if(!Set.of("user","dept").contains(type)||!Set.of("archive.read","archive.write","file.preview","file.download","file.upload","borrow.apply","borrow.approve","destroy.apply","destroy.approve","destroy.execute").contains(permission)) throw new BusinessException(400,"共享主体或权限类型不受支持");
        if(!CurrentUser.require().has(permission)) throw new BusinessException(403,"不能共享自己不具备的功能权限");
        db.required("select id from "+(type.equals("user")?"ims_user":"ims_department")+" where id=? and deleted_at is null",grantee);
        // 已筛选的业务写入字段，与原始请求分离。
        Map<String,Object> values=Input.pick(body,"expires_at");
        if(values.get("expires_at")!=null&&!values.get("expires_at").toString().isBlank()) {
            // 无效时间格式属于输入错误，不能变成服务端500。
            OffsetDateTime expiry;
            try { expiry=OffsetDateTime.parse(Input.text(values,"expires_at")); }
            catch (java.time.format.DateTimeParseException error) { throw new BusinessException(400,"授权到期时间必须为包含时区的ISO日期时间"); }
            if(!expiry.isAfter(OffsetDateTime.now())) throw new BusinessException(400,"授权到期时间必须晚于现在");
            values.put("expires_at",expiry);
        } else values.put("expires_at",null);
        values.putAll(Map.of("archive_id",id,"grantee_type",type,"grantee_id",grantee,"permission_code",permission,"granted_by",CurrentUser.require().id()));
        // 新增共享授权记录主键，供后续撤销和审计引用。
        long grantId=db.insert("archive_grant",values);
        audit.record(CurrentUser.require().id(),"archive","share","archive",id,Json.write(Map.of("grant_id",grantId,"permission",permission)));
        return db.required("select * from archive_grant where id=?",grantId);
    }
    /** 删除共享后下一次请求立即生效，不依赖客户端隐藏按钮。 */
    @Transactional
    public void revoke(long id,long grantId) {
        requireShare(id);
        if(db.execute("delete from archive_grant where id=? and archive_id=?",grantId,id)!=1) throw new BusinessException(404,"授权记录不存在");
        audit.record(CurrentUser.require().id(),"archive","revoke_share","archive",id,"撤销授权记录 "+grantId);
    }
    /** 共享管理不能通过收到的普通写入授权再次向外转授。 */
    private void requireShare(long id) {
        // 当前已认证且实时解析权限的操作主体。
        Actor actor=PermissionGuard.require("archive.share");
        // 当前操作的档案记录，用于归属与状态校验。
        Map<String,Object> archive=access.requireRead(id);
        if(Input.id(archive.get("dept_id"))!=actor.deptId()&&!actor.has("archive.manage.all")) throw new BusinessException(403,"只有归属部门或全域档案管理员可以管理共享");
    }
    /**
     * 对允许修改的字段作类型转换和引用校验。
     * @param values 已筛选的可写业务字段；原位转换日期、主键和数值并补算到期日
     */
    private void validate(Map<String,Object> values) {
        // 文本字段必须是真正字符串并符合列宽，避免驱动隐式转换或数据库异常。
        TEXT_LIMITS.forEach((field,limit)->{
            if(values.get(field)==null) return;
            // 经过字段类型检查后参与处理的业务值。
            String value=Input.text(values,field);
            if(value.length()>limit) throw new BusinessException(400,field+" 内容过长");
            values.put(field,value);
        });
        for(String field:List.of("category_id","dept_id","security_level_id","retention_id","archiver_user_id")) if(values.get(field)!=null) values.put(field,Input.id(values.get(field)));
        if(values.containsKey("category_id")&&values.get("category_id")==null) throw new BusinessException(400,"档案分类不能为空");
        if(values.containsKey("dept_id")&&values.get("dept_id")==null) throw new BusinessException(400,"档案所属部门不能为空");
        if(values.get("archive_year")!=null) values.put("archive_year",Input.integer(values.get("archive_year"),LocalDate.now().getYear(),1,9999));
        for(String field:List.of("archive_date","formation_date","filing_date")) if(values.containsKey(field)) values.put(field,Input.date(values.get(field)));
        if(values.containsKey("page_count")) values.put("page_count",Input.integer(values.get("page_count"),0,0,1_000_000));
        if(values.containsKey("copy_count")) values.put("copy_count",Input.integer(values.get("copy_count"),1,1,100_000));
        if(values.get("validity_months")!=null) values.put("validity_months",Input.integer(values.get("validity_months"),1,1,12000));
        if(values.containsKey("archive_date")||values.containsKey("validity_months")) values.put("expiry_date",values.get("archive_date")!=null&&values.get("validity_months")!=null?java.sql.Date.valueOf(((java.sql.Date)values.get("archive_date")).toLocalDate().plusMonths(((Number)values.get("validity_months")).intValue())):null);
        if(values.get("category_id")!=null) db.required("select id from archive_category where id=? and status=1",values.get("category_id"));
        if(values.get("security_level_id")!=null) db.required("select id from security_level where id=?",values.get("security_level_id"));
        if(values.get("retention_id")!=null) db.required("select id from retention_period where id=?",values.get("retention_id"));
    }
    /** 保存变更字段及前后快照，与业务修改共用事务。 */
    private void writeChange(long id,String action,Map<String,Object> before,Map<String,Object> after) {
        // 当前已认证且实时解析权限的操作主体。
        Actor actor=CurrentUser.require();
        // 只保留此次修改涉及字段的原值，形成可比较快照。
        Map<String,Object> oldValues=new LinkedHashMap<>();
        after.keySet().forEach(key->oldValues.put(key,before.get(key)));
        db.insert("archive_update_log",Map.of("archive_id",id,"operator_id",actor.id(),"operator_name",actor.realName(),"action",action,"changed_fields",Json.write(after.keySet()),"before_json",Json.write(oldValues),"after_json",Json.write(after)));
        audit.record(actor.id(),"archive",action,"archive",id,Json.write(after.keySet()));
    }
    /** 为旧客户端补充最近一次变更概览。 */
    private void addLatestUpdate(Map<String,Object> archive) {
        // 最近一次变更的展示摘要，不暴露完整历史内部字段。
        Map<String,Object> last=db.one("select operator_name,action,created_at,changed_fields from archive_update_log where archive_id=? order by id desc limit 1",archive.get("id"));
        if(last!=null) last.put("changed_fields",Json.read(last.get("changed_fields").toString(),List.class));
        archive.put("latest_update",last);
    }
}
