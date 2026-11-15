package com.company.platform;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** IT 审计查询与分职责安全处置；查询、导出共用同一授权谓词，绝不返回业务正文或凭据。 */
@Service
public class AuditSecurity {
    private final Sql db;private final IdentityService identity;private final Audit audit;
    /** 网页与CSV共用的字段白名单，不包含幂等载荷和目录快照。 */
    private static final String FIELDS="id,occurred_at,actor_id,module,resource_type,resource_id,action,result,request_id,severity";
    /** 页面额外显示账号，审计主键仍用来关联原记录。 */
    private static final String PAGE_FIELDS=FIELDS+",actor_employee_no";
    public AuditSecurity(Sql db,IdentityService identity,Audit audit){this.db=db;this.identity=identity;this.audit=audit;}
    /** 查询计划只含服务端SQL和入口已验证的绑定值。 */
    private record Plan(String source,String where,List<Object> values,boolean interaction){}
    /** 实时身份用于服务调用和HTTP两种入口，已撤销会话不能复用导出票据。 */
    private Actor live(Actor actor){return identity.background(actor.sessionId());}
    /** 只有平台 IT 管理员可查询、翻页、统计或导出操作记录。 */
    private Actor auditAdministrator(Actor subject){
        Actor actor=live(subject);
        if(!actor.systemAdministrator())throw ApiFault.denied();
        return actor;
    }
    /** 管理员可按模块及操作人筛选，导出另行检查模块权限。 */
    private Plan plan(Actor actor,Map<String,String> query,Instant cutoff,boolean export){
        Inputs.only(query,"module","actorId","employee","resourceType","resourceId","from","to","result","requestId","pageSize","kind");
        String module=Inputs.choice(query.getOrDefault("module","PLATFORM"),"PLATFORM","DRIVE","ARCHIVE");
        if(export)actor.require(module+".AUDIT_EXPORT");
        String kind=Inputs.choice(query.getOrDefault("kind","BUSINESS"),"BUSINESS","INTERACTION");boolean interaction=kind.equals("INTERACTION");
        if(interaction&&!module.equals("PLATFORM"))throw ApiFault.invalid("界面意图属于平台审计");
        Instant from=Instant.parse(Inputs.text(query,"from",64)),to=Instant.parse(Inputs.text(query,"to",64));
        if(!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(366))>0)throw ApiFault.invalid("请查询一年以内的有效时间范围");
        String source=interaction?"(SELECT id,occurred_at,user_id AS actor_id,'PLATFORM' AS module,control AS resource_type,NULL::uuid AS resource_id,page||':'||action AS action,'INTENT' AS result,request_id,'INFO' AS severity,occurred_at AS ingested_at,NULL::varchar AS authorization_source FROM ops.ui_interaction) e":"ops.audit_event e";
        StringBuilder where=new StringBuilder(" WHERE e.occurred_at>=? AND e.occurred_at<? AND e.ingested_at<=? AND e.module=?");
        List<Object> args=new ArrayList<>(List.of(java.sql.Timestamp.from(from),java.sql.Timestamp.from(to),java.sql.Timestamp.from(cutoff),module));
        if(!interaction)where.append(" AND e.record_kind<>'IDEMPOTENCY_RECEIPT'");
        for(String name:List.of("actorId","resourceId","requestId"))if(query.containsKey(name)){where.append(" AND e."+name.replaceAll("([A-Z])","_$1").toLowerCase(Locale.ROOT)+"=?");args.add(Inputs.uuid(query.get(name)));}
        for(String name:List.of("resourceType","result"))if(query.containsKey(name)){where.append(" AND e."+name.replaceAll("([A-Z])","_$1").toLowerCase(Locale.ROOT)+"=?");args.add(Inputs.text(query,name,64));}
        // 按当前员工资料匹配操作人，查询、计数和导出共用同一条件。
        where.append(new ListConditions(query,args).match("employee",
            "e.actor_id IN (SELECT u.id FROM iam.user_account u WHERE concat_ws(' ',u.name,u.employee_no) ILIKE ?)").sql());
        return new Plan(source,where.toString(),args,interaction);
    }
    /** 时间和编号同向降序，游标直接定位索引范围；多取一条判断后续，不做深OFFSET扫描。 */
    private List<Map<String,Object>> rows(Plan plan,int size,String time,UUID id,List<UUID> ids,boolean showAccount){
        var args=new ArrayList<>(plan.values());
        String after="";
        if(ids!=null){
            after=" AND e.id=ANY(?::uuid[])";
            args.add(uuidArray(ids));
        }else if(id!=null){
            after=" AND (e.occurred_at,e.id)<(?::timestamptz,?::uuid)";
            args.add(time);
            args.add(id);
        }
        args.add(size);
        String fields=Arrays.stream(FIELDS.split(",")).map(f->"e."+f).collect(java.util.stream.Collectors.joining(","));
        String pageSql="SELECT "+fields+(showAccount?",e.authorization_source":"")+" FROM "+plan.source()+plan.where()+after+" ORDER BY e.occurred_at DESC,e.id DESC LIMIT ?";
        // 只对已经授权并分页的记录解析账号，导出沿用原字段。
        if(showAccount)pageSql="SELECT p.*,u.employee_no AS actor_employee_no FROM ("+pageSql+
            ") p LEFT JOIN iam.user_account u ON u.id=p.actor_id ORDER BY p.occurred_at DESC,p.id DESC";
        return db.rows(pageSql,args.toArray());
    }
    /** 编号均来自数据库已授权查询，数组作为参数绑定，不能成为SQL文本。 */
    private String uuidArray(List<UUID> ids){return ids.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",","{","}"));}
    /** 游标和导出共用本人十分钟查询票据，每次翻页重新授权；总数只有显式请求时才统计。 */
    @SuppressWarnings("unchecked")
    @Transactional public Map<String,Object> query(Actor subject,Map<String,String> query,UUID request){
        Actor actor=auditAdministrator(subject);Map<String,String> filters;Instant cutoff;UUID id;String time=null;UUID anchor=null;UUID currentCursor;List<UUID> savedIds=null;UUID next=null;
        boolean count=Inputs.choice(query.getOrDefault("includeTotal","false"),"true","false").equals("true");
        if(query.containsKey("cursor")){
            Inputs.only(query,"cursor","includeTotal");currentCursor=Inputs.uuid(query.get("cursor"));var saved=db.required("SELECT c.query_id,c.anchor_time,c.anchor_id,c.page_ids,c.next_cursor_id,q.filters::text,q.cutoff FROM ops.audit_page_cursor c JOIN ops.audit_export q ON q.id=c.query_id WHERE c.id=? AND q.user_id=? AND q.expires_at>CURRENT_TIMESTAMP FOR UPDATE OF c",currentCursor,actor.userId());
            id=(UUID)saved.get("query_id");filters=Crypto.JSON.readValue(saved.get("filters").toString(),Map.class);cutoff=Instant.parse(saved.get("cutoff").toString());time=Objects.toString(saved.get("anchor_time"),null);anchor=(UUID)saved.get("anchor_id");
            savedIds=(List<UUID>)saved.get("page_ids");next=(UUID)saved.get("next_cursor_id");
        }else{
            Inputs.integer(query.get("page"),1,1,1);filters=new LinkedHashMap<>(query);filters.remove("page");filters.remove("includeTotal");cutoff=Instant.parse(db.required("SELECT clock_timestamp() AS point").get("point").toString());id=UUID.randomUUID();currentCursor=UUID.randomUUID();
        }
        Plan plan=plan(actor,filters,cutoff,false);int size=Inputs.integer(filters.get("pageSize"),20,1,100);
        if(!query.containsKey("cursor")){db.update("INSERT INTO ops.audit_export(id,user_id,filters,cutoff) VALUES(?,?,?::jsonb,?)",id,actor.userId(),Crypto.json(filters),java.sql.Timestamp.from(cutoff));db.update("INSERT INTO ops.audit_page_cursor(id,query_id) VALUES(?,?)",currentCursor,id);}
        List<Map<String,Object>> page;
        if(savedIds==null){
            var found=rows(plan,size+1,time,anchor,null,true);boolean more=found.size()>size;page=found.subList(0,Math.min(size,found.size()));
            if(more){var last=page.getLast();next=UUID.randomUUID();db.update("INSERT INTO ops.audit_page_cursor(id,query_id,anchor_time,anchor_id) VALUES(?,?,?::timestamptz,?)",next,id,last.get("occurred_at"),last.get("id"));}
            db.update("UPDATE ops.audit_page_cursor SET page_ids=?::uuid[],next_cursor_id=? WHERE id=?",uuidArray(page.stream().map(row->(UUID)row.get("id")).toList()),next,currentCursor);
        }else{
            // 保存的是页内编号而非授权结果；延迟提交的新记录不挤入旧页，撤权后原编号仍会被过滤。
            page=rows(plan,size,null,null,savedIds,true);
        }
        Long total=count?db.count("SELECT count(*) FROM "+plan.source()+plan.where(),plan.values().toArray()):null;
        audit.event(actor.userId(),filters.getOrDefault("module","PLATFORM"),"AUDIT_QUERY",id,"AUDIT_QUERY","SUCCEEDED",request);
        return Sql.map("items",page.stream().map(r->{var item=Sql.view(r,PAGE_FIELDS);item.put("operationLabel",AuditOperationLabels.describe(r));return item;}).toList(),"pageSize",size,"total",total,"hasNext",next!=null,"queryId",id,"cursor",currentCursor,"nextCursor",next,"cutoff",cutoff.toString());
    }
    /** 显式统计原查询的当前可见总数；翻页不调用此接口，撤权和票据过期后拒绝。 */
    public Map<String,Object> count(Actor subject,UUID id){Actor actor=auditAdministrator(subject);Plan plan=ticketPlan(actor,id,false);return Sql.map("total",db.count("SELECT count(*) FROM "+plan.source()+plan.where(),plan.values().toArray()));}
    /** 只允许票据持有人按原查询导出，新的权限与部门范围在下载时再次计算。 */
    @SuppressWarnings("unchecked") private Plan ticketPlan(Actor actor,UUID id,boolean export){
        var row=db.required("SELECT filters::text,cutoff FROM ops.audit_export WHERE id=? AND user_id=? AND expires_at>CURRENT_TIMESTAMP",id,actor.userId());
        Map<String,String> filters=Crypto.JSON.readValue(row.get("filters").toString(),Map.class);
        return plan(actor,filters,Instant.parse(row.get("cutoff").toString()),export);
    }
    /** Commands先消费本人再验证；返回票据ID以支持同操作号重放，查询权限不能隐式获得导出能力。 */
    public Map<String,Object> authorizeExport(Actor subject,UUID id){Actor actor=auditAdministrator(subject);ticketPlan(actor,id,true);return Sql.map("id",id);}
    /** 同步有界CSV交付；最多一万行，超限明确拒绝，避免悄悄截断或无限占用堆。 */
    @Transactional public byte[] export(Actor subject,UUID id,UUID request){
        Actor actor=auditAdministrator(subject);Plan plan=ticketPlan(actor,id,true);
        if(db.count("SELECT count(*) FROM ops.audit_event WHERE actor_id=? AND resource_type='AUDIT_EXPORT' AND resource_id=? AND action='EXPORT_AUDIT' AND result='SUCCEEDED'",actor.userId(),id)==0)throw ApiFault.denied();
        var rows=rows(plan,10001,null,null,null,false);if(rows.size()>10000)throw ApiFault.invalid("导出超过一万条，请缩小查询范围后重试");
        StringBuilder csv=new StringBuilder("\ufeff记录编号,发生时间,操作者编号,模块,对象类别,对象编号,动作,结果,请求编号,级别\r\n");
        for(var row:rows){for(String field:FIELDS.split(",")){String value=Objects.toString(row.get(field),"");if(value.matches("^[=+@\\-].*"))value="'"+value;csv.append('"').append(value.replace("\"","\"\"")).append("\",");}csv.setLength(csv.length()-1);csv.append("\r\n");}
        audit.event(actor.userId(),"PLATFORM","AUDIT_EXPORT",id,"EXPORT_RESPONSE_PREPARED","SUCCEEDED",request);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
    /** 只收枚举意图，不接收用户输入、控件文字、页面参数或客户端自报的成功结果。 */
    @Transactional public void interaction(Actor actor,Map<String,Object> body){
        Inputs.only(body,"id","requestId","page","action","control");UUID id=Inputs.uuid(body.get("id")),request=Inputs.uuid(body.get("requestId"));
        String page=Inputs.choice(body.get("page"),"workspace","announcements","feedback","tasks","drive","archive","workflow","physical","evidence","audit","security","users","departments","roles","config","other");
        String action=Inputs.choice(body.get("action"),"ACTIVATE","SUBMIT","CHANGE","EXPAND","NAVIGATE","CANCEL"),control=Inputs.choice(body.get("control"),"BUTTON","LINK","FORM","SELECT","SEARCH","INPUT","DETAILS","DIALOG","ROUTE");
        // 每人一分钟最多300条意图，限制伪造客户端占满审计；业务实际结果不受该上限影响。
        if(db.count("SELECT count(*) FROM (SELECT 1 FROM ops.ui_interaction WHERE user_id=? AND occurred_at>CURRENT_TIMESTAMP-interval '1 minute' LIMIT 300) recent",actor.userId())>=300)throw new ApiFault(429,"INTERACTION_RATE_LIMIT","界面操作过于频繁，请稍后重试");
        db.update("INSERT INTO ops.ui_interaction(id,user_id,request_id,page,action,control,occurred_at) VALUES(?,?,?,?,?,?,clock_timestamp())",id,actor.userId(),request,page,action,control);
    }
    /** 限流只阻止对应动作；本人安全退出、消息查看和IT处置不受影响。 */
    public void requireAvailable(UUID user,String kind){if(db.count("SELECT count(*) FROM ops.security_limit WHERE user_id=? AND kind=? AND blocked_until>CURRENT_TIMESTAMP",user,kind)>0)throw new ApiFault(429,"SECURITY_RATE_LIMIT","该类操作暂时受限，请十分钟后重试或联系IT核对");}
    /** 不含秘密的有界告警队列及固定规则，时间由数据库统一计算。 */
    public Map<String,Object> overview(Actor subject,Map<String,String> query){
        Actor actor=live(subject);actor.require("PLATFORM.SECURITY_MANAGE");
        Inputs.only(query,"page","state","employee","kind","createdFrom","createdTo","notificationState");
        int page=Inputs.integer(query.get("page"),1,1,100000);
        String state=Inputs.choice(query.getOrDefault("state","OPEN"),"ALL","OPEN","REVIEWING","RESOLVED");
        var args=new ArrayList<Object>();
        String where=" WHERE true";
        if(!state.equals("ALL")){where+=" AND a.state=?";args.add(state);}
        where+=new ListConditions(query,args).contains("employee","concat_ws(' ',u.name,u.employee_no)")
            .choice("kind","a.kind","AUDIT_CAPACITY","OPS_PROTECTION","OPS_REPLICA","OPS_BACKUP","OPS_RESTORE","OPS_TASK","LOGIN_LOCKS","ACCESS_DENIED","BULK_DOWNLOAD","BULK_PRINT")
            .choice("notificationState","m.state","PENDING","RUNNING","RETRY","MANUAL","SENT","CANCELLED")
            .dates("created","a.created_at").sql();
        String from=" FROM ops.security_alert a JOIN iam.user_account u ON u.id=a.user_id LEFT JOIN ops.account_message m ON m.id=a.id";
        long total=db.count("SELECT count(*)"+from+where,args.toArray());
        args.add((page-1)*20);
        var rows=db.rows("SELECT a.*,u.name AS user_name,l.blocked_until,m.state AS message_state"+from+" LEFT JOIN ops.security_limit l ON l.alert_id=a.id"+where+" ORDER BY a.created_at DESC,a.id LIMIT 20 OFFSET ?",args.toArray());
        return Sql.map("items",rows.stream().map(r->Sql.view(r,"id,user_id,user_name,kind,observed_count,threshold_count,created_at,state,row_version,blocked_until,message_state")).toList(),"total",total,"page",page,"retention",Sql.view(db.required("SELECT * FROM ops.audit_retention"),"interaction_years,business_years,row_version"));
    }
    /** 处置历史只读，意见不进入机器人通知；已完成告警仍可核查原处理人和恢复动作。 */
    public List<Map<String,Object>> history(Actor subject,UUID id){live(subject).require("PLATFORM.SECURITY_MANAGE");db.required("SELECT id FROM ops.security_alert WHERE id=?",id);return db.rows("SELECT d.*,u.name AS operator_name FROM ops.security_disposition d JOIN iam.user_account u ON u.id=d.operator_id WHERE alert_id=? ORDER BY occurred_at,id LIMIT 100",id).stream().map(r->Sql.view(r,"id,operator_id,operator_name,state,reason,released,occurred_at")).toList();}
    /** 只回收失效临时查询票据，不删除审计证据；有界清理避免锁住大批记录。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=300000,initialDelay=300000)
    public void expireTickets(){db.update("DELETE FROM ops.audit_export WHERE id IN (SELECT id FROM ops.audit_export WHERE expires_at<CURRENT_TIMESTAMP-interval '1 day' ORDER BY expires_at LIMIT 5000)");}
    /** 处置追加证据；解除限流需要本人再验证和版本一致，账号停用仍走既有人事管理。 */
    public Map<String,Object> dispose(Commands.Context ctx,UUID id,Map<String,Object> body){Inputs.only(body,"state","reason","release");String state=Inputs.choice(body.get("state"),"REVIEWING","RESOLVED"),reason=Inputs.text(body,"reason",1000);if(!(body.get("release") instanceof Boolean release))throw ApiFault.invalid("请选择是否解除限流");
        var row=db.required("SELECT * FROM ops.security_alert WHERE id=? FOR UPDATE",id);Commands.version(ctx,row);
        if("RESOLVED".equals(row.get("state")))throw ApiFault.conflict("告警已处理完成");
        db.update("INSERT INTO ops.security_disposition VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",UUID.randomUUID(),id,ctx.actor().userId(),state,reason,release);
        if(release)db.update("UPDATE ops.security_limit SET blocked_until=CURRENT_TIMESTAMP WHERE alert_id=?",id);
        db.update("UPDATE ops.security_alert SET state=?,row_version=row_version+1 WHERE id=?",state,id);return Sql.map("id",id);
    }
    /** 保留期只允许延长；档案和历史证据不受这两个年限缩短，本期没有清理接口。 */
    public Map<String,Object> retention(Commands.Context ctx,Map<String,Object> body){Inputs.only(body,"interactionYears","businessYears");int interaction=Inputs.integer(body.get("interactionYears"),3,3,100),business=Inputs.integer(body.get("businessYears"),3,3,100);var row=db.required("SELECT * FROM ops.audit_retention FOR UPDATE");Commands.version(ctx,row);
        if(interaction<((Number)row.get("interaction_years")).intValue()||business<((Number)row.get("business_years")).intValue())throw ApiFault.invalid("已生效的保留年限不能缩短");
        db.update("UPDATE ops.audit_retention SET interaction_years=?,business_years=?,row_version=row_version+1",interaction,business);return Sql.map("id",ApiController.COLLECTION);
    }
}
