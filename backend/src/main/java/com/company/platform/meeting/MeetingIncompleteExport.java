package com.company.platform.meeting;

import com.company.platform.*;
import com.company.platform.intake.ImportWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 发起人的未完成事项清单；当前会议与跨会议汇总使用同一快照及完成口径。 */
@Service
public class MeetingIncompleteExport {
    private final Sql db;
    private final MeetingBookings bookings;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));
    private static final Map<String,String> MATERIALS=Map.of("REPORT","上周工作内容","WEEKLY_PLAN","本周工作计划","COORDINATION","协调事宜","TOPICS","会议议题");
    private static final List<String> COLUMNS=List.of("subject|会议名称","meetingTime|会议时间","department|部门","name|姓名","employeeNo|工号","stage|环节","content|具体事项","deadline|截止时间","state|当前状态","overdue|逾期情况","nextName|下一步处理人","nextNo|处理人工号","exportedAt|导出时间");
    public MeetingIncompleteExport(Sql db,MeetingBookings bookings){this.db=db;this.bookings=bookings;}
    /** 只允许本人发起的会议，不继承总经理、平台IT或受邀人员的查看范围；取消会议不再追责。 */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public byte[] download(Actor actor,UUID meeting){
        if(meeting!=null)db.required("SELECT id FROM portal.meeting_booking WHERE id=? AND organizer_id=?",meeting,actor.userId());
        Instant now=Instant.parse(db.required("SELECT CURRENT_TIMESTAMP AS at").get("at").toString());
        var rows=rows(actor,meeting,now);
        String scope=meeting==null?"本人发起的全部会议":"当前会议";
        String note=scope+" · 导出时间："+TIME.format(now)+"（北京时间） · 共"+rows.size()+"项；已取消、已移出、未要求或尚未触发的项目不统计；待发起人确认不算员工未反馈。";
        return ImportWorkbook.meetingStatusWorkbook("会议未完成情况",note,COLUMNS,rows);
    }
    /** 按会议读取实际材料和事项，避免把邀请回复直接当作材料完成状态。 */
    private List<Map<String,Object>> rows(Actor actor,UUID selected,Instant now){
        var result=new ArrayList<Map<String,Object>>();
        for(var meeting:db.rows("""
                SELECT m.*,u.name AS organizer_name,u.employee_no AS organizer_no
                FROM portal.meeting_booking m JOIN iam.user_account u ON u.id=m.organizer_id
                WHERE m.organizer_id=? AND (?::uuid IS NULL OR m.id=?) AND m.cancelled_at IS NULL
                ORDER BY m.starts_at DESC,m.id
                """,actor.userId(),selected,selected)){
            UUID id=(UUID)meeting.get("id");
            Instant cutoff=Instant.parse(Objects.toString(meeting.get("actual_started_at"),meeting.get("starts_at").toString()));
            var materials=new HashMap<UUID,Set<String>>();
            for(var material:db.rows("""
                    SELECT a.user_id,m.kind FROM portal.meeting_attendee a JOIN portal.meeting_material m ON m.attendee_id=a.id
                    WHERE a.meeting_id=? AND a.removed_at IS NULL
                    """,id))materials.computeIfAbsent((UUID)material.get("user_id"),key->new HashSet<>()).add(material.get("kind").toString());
            var reading=Boolean.TRUE.equals(meeting.get("share_all"))?bookings.readingMaterials(id):List.<Map<String,Object>>of();
            for(var person:db.rows("""
                    SELECT a.*,u.name,u.employee_no,d.name AS department_name
                    FROM portal.meeting_attendee a JOIN iam.user_account u ON u.id=a.user_id
                    JOIN iam.department d ON d.id=u.department_id
                    WHERE a.meeting_id=? AND a.removed_at IS NULL ORDER BY u.employee_no,u.id
                    """,id)){
                UUID user=(UUID)person.get("user_id");
                if("PENDING".equals(person.get("reply")))add(result,meeting,person,"参会回复","是否参加会议",cutoff,"未回复",false,now);
                if(MeetingBookings.eligibleForMaterials(meeting,person)){
                    if(meeting.get("material_types") instanceof List<?> kinds){
                        for(Object kind:kinds)if(!materials.getOrDefault(user,Set.of()).contains(kind.toString()))
                            add(result,meeting,person,"会前填写",MATERIALS.get(kind.toString()),cutoff,"未提交",false,now);
                    }else if(person.get("material_at")==null)add(result,meeting,person,"会前填写","会前材料（原版合并填报）",cutoff,"未提交",false,now);
                }
                if(!"DECLINED".equals(person.get("reply"))){
                    String revision=MeetingBookings.readingRevision(reading,user);
                    if(revision!=null&&(!revision.equals(person.get("read_material_revision"))||person.get("read_material_at")==null))
                        add(result,meeting,person,"会前资料确认","确认阅读当前版本的共享会前资料",cutoff,"待确认阅读",false,now);
                }
            }
            if(meeting.get("minutes_published_at")==null||!Boolean.TRUE.equals(meeting.get("track_follow_up")))continue;
            for(var item:db.rows("""
                    SELECT i.*,u.name,u.employee_no,d.name AS department_name
                    FROM portal.meeting_item i JOIN iam.user_account u ON u.id=i.assignee_id
                    JOIN iam.department d ON d.id=u.department_id
                    WHERE i.meeting_id=? AND i.removed_at IS NULL AND i.state<>'CONFIRMED' ORDER BY i.due_at,i.id
                    """,id)){
                boolean review="SUBMITTED".equals(item.get("state"));
                String state=review?"待发起人确认":"RETURNED".equals(item.get("state"))?"退回待补充":"未提交反馈";
                add(result,meeting,item,review?"反馈确认":"会后待办与反馈",item.get("content").toString(),Instant.parse(item.get("due_at").toString()),state,review,now);
            }
        }
        return result;
    }
    /** 原任务截止时间保留；没有另设审核期限，不能把员工已反馈的事项判为发起人审核逾期。 */
    private void add(List<Map<String,Object>> rows,Map<String,Object> meeting,Map<String,Object> person,String stage,String content,Instant due,String state,boolean review,Instant now){
        rows.add(Sql.map("subject",meeting.get("subject"),"meetingTime",TIME.format(Instant.parse(meeting.get("starts_at").toString())),
                "department",person.get("department_name"),"name",person.get("name"),"employeeNo",person.get("employee_no"),
                "stage",stage,"content",content,"deadline",TIME.format(due),"state",state,
                "overdue",review?"不适用（未设置确认期限）":now.isAfter(due)?"已逾期":"待完成（未到期）",
                "nextName",review?meeting.get("organizer_name"):person.get("name"),"nextNo",review?meeting.get("organizer_no"):person.get("employee_no"),"exportedAt",TIME.format(now)));
    }
}
