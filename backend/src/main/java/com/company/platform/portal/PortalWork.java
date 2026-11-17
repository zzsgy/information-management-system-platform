package com.company.platform.portal;

import com.company.platform.*;
import com.company.platform.operations.BusinessNotifications;
import com.company.platform.archive.ArchiveAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 员工反馈、待办和消息聚合；只引用当前业务来源，不建立第二份审批状态。 */
@Service
public class PortalWork {
    /** 所有聚合均在数据库分页，身份写锁与现有管理事务一致。 */
    private final Sql db;
    private final IdentityService identity;
    private final Audit audit;
    private final BusinessNotifications notifications;
    /** 实物待办理复用档案当前职责，不由平台管理员身份推断。 */
    private final ArchiveAuthority authority;
    public PortalWork(Sql db,IdentityService identity,Audit audit,BusinessNotifications notifications,ArchiveAuthority authority){this.db=db;this.identity=identity;this.audit=audit;this.notifications=notifications;this.authority=authority;}

    /** 员工反馈不需要管理再验证；同一账号、操作号和内容只创建一次。 */
    @Transactional
    public Map<String,Object> submit(Actor actor,UUID operation,UUID request,Map<String,Object> body){
        Inputs.only(body,"category","content");String category=Inputs.choice(body.get("category"),"SUGGESTION","ISSUE","OTHER"),content=Inputs.text(body,"content",1000);
        identity.lockActor(actor);String digest=Crypto.hash(Crypto.json(Sql.map("category",category,"content",content)));
        var prior=db.one("SELECT id,request_digest FROM portal.feedback WHERE applicant_id=? AND operation_id=?",actor.userId(),operation);
        if(prior!=null){if(!Crypto.equal(digest,prior.get("request_digest").toString()))throw ApiFault.conflict("同一操作号不能用于不同反馈");return feedback(actor,(UUID)prior.get("id"),false);}
        UUID id=UUID.randomUUID();db.update("INSERT INTO portal.feedback(id,applicant_id,category,content,operation_id,request_digest) VALUES(?,?,?,?,?,?)",id,actor.userId(),category,content,operation,digest);
        audit.event(actor.userId(),"PLATFORM","PORTAL_FEEDBACK",id,"SUBMIT_FEEDBACK","SUCCEEDED",request);
        return feedback(actor,id,false);
    }
    /** 员工只看本人，IT可分页办理；分类状态在入口校验一次。 */
    public Map<String,Object> feedbackList(Actor actor,Map<String,String> query,boolean manage){
        Inputs.only(query,"page","pageSize","state","category","applicant","createdFrom","createdTo");if(manage)actor.require("PLATFORM.FEEDBACK_MANAGE");
        String from=" FROM portal.feedback f JOIN iam.user_account u ON u.id=f.applicant_id WHERE "+(manage?"true":"f.applicant_id=?");
        List<Object> args=new ArrayList<>();if(!manage)args.add(actor.userId());
        if(query.containsKey("state")){from+=" AND f.state=?";args.add(Inputs.choice(query.get("state"),"OPEN","PROCESSING","RESOLVED"));}
        from+=new ListConditions(query,args)
            .choice("category","f.category","SUGGESTION","ISSUE","OTHER")
            .contains("applicant","concat_ws(' ',u.name,u.employee_no)").dates("created","f.created_at").sql();
        return page("SELECT f.id,f.category,f.state,f.created_at,f.row_version,u.name AS applicant_name",from,"f.created_at DESC,f.id",args,query,"id,category,state,created_at,row_version,applicant_name");
    }
    /** 详情仅申请人或获准IT读取，答复不自动标记已读。 */
    public Map<String,Object> feedback(Actor actor,UUID id,boolean manage){
        if(manage)actor.require("PLATFORM.FEEDBACK_MANAGE");
        var row=manage?db.required("SELECT f.*,u.name AS applicant_name FROM portal.feedback f JOIN iam.user_account u ON u.id=f.applicant_id WHERE f.id=?",id):db.required("SELECT f.*,u.name AS applicant_name FROM portal.feedback f JOIN iam.user_account u ON u.id=f.applicant_id WHERE f.id=? AND f.applicant_id=?",id,actor.userId());
        var result=Sql.view(row,"id,category,content,state,created_at,row_version,applicant_name");
        result.put("replies",db.rows("SELECT id,body,author_name,created_at,read_at FROM portal.feedback_reply WHERE feedback_id=? ORDER BY created_at,id",id).stream().map(r->Sql.view(r,"id,body,author_name,created_at,read_at")).toList());return result;
    }
    /** IT答复和办理状态在同一管理事务写入；每条答复自然形成一条本人消息。 */
    public Map<String,Object> reply(Commands.Context ctx,UUID id,Map<String,Object> body){
        Inputs.only(body,"body","state");String text=Inputs.text(body,"body",2000),state=Inputs.choice(body.get("state"),"PROCESSING","RESOLVED");
        var row=db.required("SELECT * FROM portal.feedback WHERE id=? FOR UPDATE",id);Commands.version(ctx,row);
        if("RESOLVED".equals(row.get("state")))throw ApiFault.conflict("该反馈已办结，请刷新查看处理结果");
        db.update("INSERT INTO portal.feedback_reply(id,feedback_id,body,author_id,author_name) SELECT ?,?,?,id,name FROM iam.user_account WHERE id=?",UUID.randomUUID(),id,text,ctx.actor().userId());
        db.update("UPDATE portal.feedback SET state=?,row_version=row_version+1 WHERE id=?",state,id);return feedback(ctx.actor(),id,true);
    }
    /** 联合收件箱采用同一排序和分页；业务消息继续受原来源有效性函数控制。 */
    public Map<String,Object> inbox(Actor actor,Map<String,String> query){
        Inputs.only(query,"page","pageSize","unreadOnly","createdFrom","createdTo");boolean unread=query.containsKey("unreadOnly")&&Inputs.choice(query.get("unreadOnly"),"true","false").equals("true");
        var args=new ArrayList<Object>();String sources=messageSources(actor,args);
        String from=" FROM ("+sources+") n WHERE "+(unread?"n.state='UNREAD'":"true")+new ListConditions(query,args).dates("created","n.created_at").sql();
        return page("SELECT n.*",from,"n.created_at DESC,n.id",args,query,"id,source_kind,source_id,kind,state,created_at,content");
    }
    /** 消息打开时重新核验来源并标记本人已读；链接只由服务端固定路由构成。 */
    @Transactional
    public Map<String,Object> readMessage(Actor actor,UUID id){
        identity.lockActor(actor);
        var reply=db.one("SELECT r.feedback_id FROM portal.feedback_reply r JOIN portal.feedback f ON f.id=r.feedback_id WHERE r.id=? AND f.applicant_id=? FOR UPDATE OF r",id,actor.userId());
        if(reply!=null){db.update("UPDATE portal.feedback_reply SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP) WHERE id=?",id);return Sql.map("entry","#/feedback?id="+reply.get("feedback_id"));}
        var meeting = db.one("""
                UPDATE portal.meeting_message SET read_at=COALESCE(read_at,CURRENT_TIMESTAMP)
                WHERE id=? AND user_id=? RETURNING meeting_id,item_id,kind,content
                """, id,actor.userId());
        if(meeting!=null){
            var item=meeting.get("item_id");
            String entry=item!=null?"#/meetings?item="+item:"MEETING_REMOVED".equals(meeting.get("kind"))
                ?"#/meetings":"#/meetings?id="+meeting.get("meeting_id");
            return Sql.map("entry",entry,"content",meeting.get("content"));
        }
        notifications.read(actor,id);
        var row=db.required("SELECT source_kind,source_id FROM ops.business_notification WHERE id=? AND user_id=?",id,actor.userId());
        String type=row.get("source_kind").toString();UUID source=(UUID)row.get("source_id");String entry=switch(type){
            case "WORKFLOW_STEP"->"#/workflow?step="+source;
            case "WORKFLOW_INSTANCE"->"#/workflow?step="+db.required("SELECT id FROM workflow.step WHERE instance_id=? ORDER BY sequence_no DESC LIMIT 1",source).get("id");
            case "PHYSICAL_NOTICE"->"#/workflow?tab=physical&loan="+db.required("SELECT loan_id FROM archive.physical_notice WHERE id=?",source).get("loan_id");
            case "ELECTRONIC_REMINDER"->"#/workflow?batch="+db.required("SELECT batch_id FROM ops.inbox_notification WHERE id=?",source).get("batch_id");
            case "CATALOG_CHANGE","RETENTION"->"#/catalog";
            case "EVIDENCE_DECISION"->"#/workflow?step="+db.required("SELECT step_id FROM workflow.decision WHERE id=?",source).get("step_id");
            default->"#/workflow";
        };return Sql.map("entry",entry);
    }
    /** 待办来自现有合法节点、凭据队列、反馈和本人到期事项，计数与列表共用筛选。 */
    public Map<String,Object> tasks(Actor actor,Map<String,String> query){
        Inputs.only(query,"page","pageSize","category","keyword","listedFrom","listedTo");
        var args=new ArrayList<Object>();
        String category=query.getOrDefault("category","");
        boolean completed=category.equals("COMPLETED");
        // 完整列表保留已办，工作台预览在分页前只取未完成的事项。
        boolean all=category.isEmpty();
        boolean recent=category.equals("RECENT");
        String sources=taskSources(actor,args);
        if((all||completed)&&actor.actions().contains("ARCHIVE.ACCESS")){
            // 以实际提交的决定识别已办，详情仍沿用节点当前授权。
            sources+=" UNION ALL "+"""
                    SELECT s.id, CASE w.business_type
                        WHEN 'BORROW' THEN '借阅审批' WHEN 'REGISTRATION' THEN '归档审批'
                        WHEN 'ACCESS_POLICY' THEN '档案授权审批' WHEN 'CATALOG_CHANGE' THEN '目录变更审批'
                        ELSE '审批申请' END,
                      'COMPLETED',NULL::timestamptz,'#/workflow?step='||s.id,
                      'SYSTEM',s.state,NULL::timestamptz,s.completed_at
                    FROM workflow.decision d
                    JOIN workflow.step s ON s.id=d.step_id
                    JOIN workflow.instance w ON w.id=s.instance_id
                    WHERE d.operator_id=? AND d.decision_mode='ONLINE'
                      AND s.assignee_id=d.operator_id
                      AND workflow.assignment_valid(s.assignment_type,s.assignment_ref,s.assignee_id)
                    """;
            args.add(actor.userId());
        }
        String from=" FROM ("+sources+") t WHERE true";
        if(category.equals("ASSIGNED")) from+=" AND t.source='ASSIGNED' AND t.category<>'COMPLETED'";
        else if(recent) from+=" AND t.category<>'COMPLETED'";
        else if(!all){
            from+=" AND t.category=?";
            args.add(Inputs.choice(category,"APPROVAL","HANDLING","PERSONAL","COMPLETED"));
        }
        from+=new ListConditions(query,args).contains("keyword","t.title")
            .dates("listed",completed?"t.completed_at":"coalesce(t.completed_at,t.created_at,t.due_at)").sql();
        // 已办按办结时间，未办按创建或来源时间；审批节点的提醒时间比进入待办晚一天。
        String order=recent
            ?"COALESCE(t.created_at,CASE WHEN t.source='SYSTEM' AND t.category='APPROVAL' THEN t.due_at-interval '24 hours' ELSE t.due_at END) DESC NULLS LAST,t.id"
            :completed?"t.completed_at DESC NULLS LAST,t.id"
            :all?"(t.category='COMPLETED'),t.due_at NULLS LAST,coalesce(t.completed_at,t.created_at) DESC NULLS LAST,t.id"
            :"t.due_at NULLS LAST,t.created_at DESC NULLS LAST,t.id";
        return page("SELECT t.*",from,order,args,query,"id,title,category,due_at,entry,source,state,created_at,completed_at");
    }
    /** 首页摘要使用数据库聚合，不下载全部待办或收件箱到内存。 */
    public Map<String,Object> summary(Actor actor){
        var args=new ArrayList<Object>();String tasks=taskSources(actor,args);
        var counts=db.required("SELECT count(*) FILTER(WHERE category='APPROVAL') AS approval,count(*) FILTER(WHERE category IN ('HANDLING','PERSONAL','ASSIGNED')) AS handling FROM ("+tasks+") t",args.toArray());
        args.clear();String messages=messageSources(actor,args);
        long unread=db.count("SELECT count(*) FROM ("+messages+") n WHERE n.state='UNREAD'",args.toArray());
        return Sql.map("todoSummary",counts,"unreadCount",unread);
    }
    /** 固定SQL分支只接收服务端权限和绑定标识，不接收客户端表名或表达式。 */
    private String taskSources(Actor actor,List<Object> args){
        List<String> parts=new ArrayList<>();parts.add("SELECT NULL::uuid AS id,''::text AS title,''::text AS category,NULL::timestamptz AS due_at,''::text AS entry WHERE false");
        if(actor.actions().contains("ARCHIVE.ACCESS")){
            parts.add("SELECT s.id,'审批申请','APPROVAL',s.due_at,'#/workflow?step='||s.id FROM workflow.step s JOIN workflow.instance w ON w.id=s.instance_id WHERE s.assignee_id=? AND s.state='PENDING' AND w.state='PENDING' AND w.current_step_id=s.id AND workflow.assignment_valid(s.assignment_type,s.assignment_ref,s.assignee_id)");args.add(actor.userId());
            parts.add("SELECT DISTINCT q.id,'电子借阅即将到期','HANDLING',g.valid_until,'#/workflow?batch='||q.batch_id FROM archive.access_grant g JOIN archive.borrow_request q ON q.id=g.source_request_id WHERE g.user_id=? AND g.source_type='BORROW' AND g.state='ACTIVE' AND g.valid_until>CURRENT_TIMESTAMP AND g.valid_until<(date_trunc('day',CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai') AT TIME ZONE 'Asia/Shanghai')+interval '2 days' AND archive.reminder_current(q.id,g.user_id,g.valid_until)");args.add(actor.userId());
            parts.add("SELECT l.id,'原件归还提醒','HANDLING',l.due_at,'#/workflow?tab=physical&loan='||l.id FROM archive.physical_loan l WHERE l.borrower_id=? AND l.state='IN_USE' AND l.reserved_at IS NULL AND l.due_at<CURRENT_TIMESTAMP+interval '2 days'");args.add(actor.userId());
            parts.add("""
                SELECT l.id,'纸质借阅已到期','HANDLING',l.due_at,'#/workflow?tab=physical&loan='||l.id
                FROM archive.physical_loan l
                WHERE l.reserved_at IS NOT NULL AND l.state='IN_USE' AND l.due_at<=CURRENT_TIMESTAMP
                  AND archive.paper_due_recipient(l.id,?)
                """);args.add(actor.userId());
            if(actor.actions().contains("ARCHIVE.PHYSICAL_REVIEW")&&authority.role(actor,"SUPER_ADMIN",null)){
                parts.add("SELECT r.id,'核验原件归还','HANDLING',r.submitted_at,'#/workflow?tab=physical&loan='||r.loan_id FROM archive.physical_return r JOIN archive.physical_loan l ON l.id=r.loan_id LEFT JOIN workflow.instance w ON w.id=r.workflow_instance_id WHERE r.state='PENDING' AND (l.reserved_at IS NULL OR (w.state='APPROVED' AND r.registered_by IS NOT NULL AND r.registered_by<>?)) AND l.state='IN_USE' AND l.borrower_id<>?");args.add(actor.userId());args.add(actor.userId());
            }
            boolean all=authority.role(actor,"SUPER_ADMIN",null);UUID department=Inputs.uuid(actor.user().get("departmentId"));
            if(actor.actions().contains("ARCHIVE.PHYSICAL_MANAGE")&&(all||authority.role(actor,"DEPARTMENT_ARCHIVIST",department))){
                parts.add("""
                    SELECT q.id,'交付已批准原件','HANDLING',b.submitted_at,'#/workflow?tab=physical&view=requests&request='||q.id
                    FROM archive.borrow_request q JOIN archive.borrow_batch b ON b.id=q.batch_id
                    WHERE b.form='PAPER' AND q.state='APPROVED' AND q.applicant_id<>? AND (? OR q.owner_department_id=?)
                      AND EXISTS (
                        SELECT 1 FROM archive.paper_request_item s
                        LEFT JOIN LATERAL (
                          SELECT l.* FROM archive.paper_loan_scope scope JOIN archive.physical_loan l ON l.id=scope.loan_id
                          WHERE scope.request_id=q.id AND l.physical_item_id=s.physical_item_id
                        ) l ON true
                        WHERE s.request_id=q.id AND (l.id IS NULL OR
                          (l.request_id=q.id AND l.state='IN_USE' AND l.reserved_at IS NOT NULL
                            AND l.actual_delivery_at IS NULL
                            AND NOT EXISTS(SELECT 1 FROM archive.physical_return r WHERE r.loan_id=l.id AND r.state='PENDING')
                            AND ((l.pickup_by IS NULL AND l.due_at>CURRENT_TIMESTAMP) OR (l.pickup_by<>? AND (
                              (l.pickup_role='ARCHIVIST' AND ?) OR (l.pickup_role='ARCHIVE_ADMIN' AND EXISTS(
                                SELECT 1 FROM iam.user_role a WHERE a.user_id=? AND a.module='ARCHIVE'
                                  AND a.role_code='DEPARTMENT_ARCHIVIST' AND a.department_id=q.owner_department_id
                                  AND workflow.assignment_valid('ROLE',a.id,a.user_id)
                              ))
                            )))
                          ))
                      )
                    """);args.addAll(List.of(actor.userId(),all,department,actor.userId(),all,actor.userId()));
                parts.add("""
                    SELECT r.id,'登记纸质归还','HANDLING',r.submitted_at,'#/workflow?tab=physical&loan='||l.id
                    FROM archive.physical_return r JOIN workflow.instance w ON w.id=r.workflow_instance_id
                    JOIN archive.physical_loan l ON l.id=r.loan_id JOIN archive.box b ON b.id=l.box_id
                    WHERE r.state='PENDING' AND w.state='APPROVED' AND r.registered_by IS NULL
                      AND l.state='IN_USE' AND l.borrower_id<>? AND b.department_id=?
                      AND EXISTS(SELECT 1 FROM iam.user_role a WHERE a.user_id=? AND a.module='ARCHIVE'
                        AND a.role_code='DEPARTMENT_ARCHIVIST' AND a.department_id=b.department_id
                        AND workflow.assignment_valid('ROLE',a.id,a.user_id))
                    """);args.addAll(List.of(actor.userId(),department,actor.userId()));
            }
        }
        if(actor.actions().contains("PLATFORM.FEEDBACK_MANAGE"))parts.add("SELECT id,'处理员工反馈','HANDLING',created_at,'#/portal-admin?tab=feedback&id='||id FROM portal.feedback WHERE state<>'RESOLVED'");
        if(actor.actions().contains("PLATFORM.EVIDENCE_RECORD"))parts.add("SELECT id,'核验同意凭据','HANDLING',created_at,'#/evidence?id='||id FROM workflow.evidence_submission WHERE state='PENDING'");
        args.addAll(List.of(actor.userId(),actor.userId(),actor.userId()));
        String employeeTasks = "SELECT t.*,'SYSTEM'  AS source,NULL::text AS state,NULL::timestamptz AS created_at,NULL::timestamptz AS completed_at FROM ("+String.join(" UNION ALL ",parts)+") t UNION ALL "+"""
                SELECT id,title,
                  CASE WHEN state='COMPLETED' THEN 'COMPLETED'
                       WHEN creator_id=assignee_id THEN 'PERSONAL'
                       WHEN creator_id=? AND state='SUBMITTED' THEN 'APPROVAL' ELSE 'ASSIGNED' END,
                  NULL::timestamptz,'#/todos?item='||id,
                  CASE WHEN creator_id=assignee_id THEN 'PERSONAL' ELSE 'ASSIGNED' END,state,created_at,completed_at
                FROM portal.employee_todo WHERE state<>'DELETED' AND (creator_id=? OR assignee_id=?)
                """;
        // 每人每场会议复用受邀关系 ID，阶段变化时只更新这一条待办的状态。
        args.addAll(List.of(actor.userId(),actor.userId(),actor.userId(),actor.userId()));
        return employeeTasks + """
                UNION ALL
                SELECT a.id,'会议待办 · '||m.subject,
                    CASE WHEN stage.pre_pending OR f.pending_due IS NOT NULL THEN 'HANDLING' ELSE 'COMPLETED' END,
                    COALESCE(f.pending_due,f.last_due,m.actual_started_at),
                    '#/meetings?id='||m.id,'MEETING',
                    CASE WHEN f.pending_due<CURRENT_TIMESTAMP THEN 'OVERDUE'
                         WHEN f.pending_due IS NOT NULL THEN 'MEETING_FEEDBACK_PENDING'
                         WHEN stage.pre_pending AND a.reply='PENDING' THEN 'MEETING_REPLY_PENDING'
                         WHEN stage.pre_pending THEN 'MEETING_MATERIALS_PENDING'
                         WHEN m.cancelled_at IS NOT NULL THEN 'MEETING_CANCELLED'
                         WHEN f.last_due IS NOT NULL THEN 'MEETING_FEEDBACK_DONE'
                         WHEN m.minutes_published_at IS NOT NULL THEN 'MEETING_MINUTES_AVAILABLE'
                         WHEN a.reply='PENDING' THEN 'INVITE_EXPIRED'
                         WHEN materials.missing THEN 'MATERIAL_EXPIRED'
                         ELSE a.reply END,
                    GREATEST(a.invited_at,f.activity_at),
                    CASE WHEN NOT stage.pre_pending AND f.pending_due IS NULL THEN
                        GREATEST(a.replied_at,materials.latest_at,m.cancelled_at,m.actual_started_at,m.minutes_published_at,f.activity_at) END
                FROM portal.meeting_attendee a JOIN portal.meeting_booking m ON m.id=a.meeting_id
                CROSS JOIN LATERAL (
                    SELECT CASE WHEN m.material_types IS NULL THEN
                             a.material_required AND (m.kind='WEEKLY' OR a.reply<>'DECLINED') AND a.material_at IS NULL
                           ELSE count(*) FILTER (WHERE x.submitted_at IS NULL)>0 END AS missing,
                           GREATEST(a.material_at,max(x.submitted_at)) AS latest_at
                    FROM unnest(m.material_types) k(kind)
                    LEFT JOIN portal.meeting_material x ON x.attendee_id=a.id AND x.kind=k.kind
                ) materials
                CROSS JOIN LATERAL (
                    SELECT m.cancelled_at IS NULL AND m.actual_started_at IS NULL
                        AND (a.reply='PENDING' OR materials.missing) AS pre_pending
                ) stage
                CROSS JOIN LATERAL (
                    SELECT min(i.due_at) FILTER (WHERE i.state IN ('PENDING','RETURNED')) AS pending_due,
                           max(i.due_at) AS last_due,
                           max(COALESCE((i.history->-1->>'at')::timestamptz,i.created_at)) AS activity_at
                    FROM portal.meeting_item i WHERE i.meeting_id=m.id AND i.assignee_id=a.user_id
                ) f
                WHERE a.user_id=? AND a.removed_at IS NULL
                UNION ALL
                SELECT i.id,'会后反馈 · '||left(i.content,120),
                    CASE WHEN i.state IN ('PENDING','RETURNED') THEN 'HANDLING' ELSE 'COMPLETED' END,
                    i.due_at,'#/meetings?item='||i.id,'MEETING',
                    CASE WHEN i.state IN ('PENDING','RETURNED') AND i.due_at<CURRENT_TIMESTAMP THEN 'OVERDUE' ELSE i.state END,
                    i.created_at,CASE WHEN i.state IN ('SUBMITTED','CONFIRMED') THEN (i.history->-1->>'at')::timestamptz END
                FROM portal.meeting_item i WHERE i.assignee_id=?
                    AND NOT EXISTS (SELECT 1 FROM portal.meeting_attendee a
                        WHERE a.meeting_id=i.meeting_id AND a.user_id=i.assignee_id AND a.removed_at IS NULL)
                UNION ALL
                SELECT md5('meeting-review:'||i.id)::uuid,'确认会后反馈 · '||left(i.content,120),'APPROVAL',
                    NULL::timestamptz,'#/meetings?item='||i.id,'MEETING',i.state,(i.history->-1->>'at')::timestamptz,NULL
                FROM portal.meeting_item i JOIN portal.meeting_booking m ON m.id=i.meeting_id
                WHERE m.organizer_id=? AND i.state='SUBMITTED'
                UNION ALL
                SELECT md5('meeting-organizer:'||m.id)::uuid,CASE WHEN m.minutes_published_at IS NULL THEN '发布会议纪要 · ' ELSE '确认会议办结 · ' END||m.subject,
                    'HANDLING',NULL::timestamptz,'#/meetings?id='||m.id,'MEETING',NULL,
                    COALESCE(m.minutes_published_at,m.actual_ended_at),NULL
                FROM portal.meeting_booking m
                WHERE m.organizer_id=? AND m.cancelled_at IS NULL AND m.closed_at IS NULL
                    AND m.actual_ended_at IS NOT NULL AND (m.minutes_published_at IS NULL OR NOT EXISTS(
                        SELECT 1 FROM portal.meeting_item i WHERE i.meeting_id=m.id AND i.state<>'CONFIRMED'))
                """;
    }
    /** 反馈答复不依赖档案入口；档案来源在权限撤销后从联合收件箱实时消失。 */
    private String messageSources(Actor actor,List<Object> args){
        String sql="SELECT r.id,'FEEDBACK_REPLY'::text AS source_kind,r.feedback_id AS source_id,'FEEDBACK_REPLIED'::text AS kind,CASE WHEN r.read_at IS NULL THEN 'UNREAD' ELSE 'READ' END AS state,r.created_at,NULL::text AS content FROM portal.feedback_reply r JOIN portal.feedback f ON f.id=r.feedback_id WHERE f.applicant_id=?";args.add(actor.userId());
        sql += """
                 UNION ALL SELECT id,'MEETING',COALESCE(item_id,meeting_id),kind,
                 CASE WHEN read_at IS NULL THEN 'UNREAD' ELSE 'READ' END,created_at,content
                 FROM portal.meeting_message WHERE user_id=?
                """;
        args.add(actor.userId());
        if(actor.actions().contains("ARCHIVE.ACCESS")){sql+=" UNION ALL SELECT n.id,n.source_kind,n.source_id,n.kind,n.state,n.created_at,NULL::text FROM ops.business_notification n WHERE n.user_id=? AND n.state<>'CANCELLED' AND ops.notification_current(n.id,?)";args.add(actor.userId());args.add(actor.userId());}return sql;
    }
    /** 本服务各列表共享有界分页；调用方已校验查询字段及排序白名单。 */
    private Map<String,Object> page(String select,String from,String order,List<Object> args,Map<String,String> query,String fields){
        int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),10,1,100);long total=db.count("SELECT count(*)"+from,args.toArray());args.add(size);args.add((page-1)*size);
        return Sql.map("items",db.rows(select+from+" ORDER BY "+order+" LIMIT ? OFFSET ?",args.toArray()).stream().map(r->Sql.view(r,fields)).toList(),"page",page,"pageSize",size,"total",total);
    }
}
