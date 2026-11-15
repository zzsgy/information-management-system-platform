package com.company.platform.archive;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** 纸质原件主数据与统一锁序；维护位置、整盒交付、分件交付及归还都先锁盒。 */
@Service
public class PhysicalItems {
    /** 实物授权不从电子正文权推导。 */
    private final Sql db;private final ArchiveAuthority authority;private final ArchiveCommands commands;
    public PhysicalItems(Sql db,ArchiveAuthority authority,ArchiveCommands commands){this.db=db;this.authority=authority;this.commands=commands;}
    /** 由目录创建事务调用，只有实际纸质或混合载体才登记原件。 */
    public void register(String type,UUID id,String location){
        if(location==null||location.isBlank()||location.length()>512)throw ApiFault.invalid("纸质或混合档案必须登记原件位置");
        var row=db.required("SELECT * FROM "+(type.equals("BOX")?"archive.box":"archive.record")+" WHERE id=?",id);UUID box=type.equals("BOX")?id:(UUID)row.get("box_id");
        if("ELECTRONIC".equals(row.get("carrier_type")))throw ApiFault.invalid("电子载体不能登记虚构纸质原件");
        db.update("INSERT INTO archive.physical_item(id,box_id,record_id,object_type,barcode,location) VALUES(?,?,?,?,?,?)",UUID.randomUUID(),box,type.equals("BOX")?null:id,type,"PHY-"+row.get(type.equals("BOX")?"box_no":"file_no"),location);
        if(type.equals("RECORD"))db.update("UPDATE archive.box SET physical_file_count=physical_file_count+1,row_version=row_version+1 WHERE id=?",box);
    }
    /** 任何实物操作按盒UUID升序加锁，再按原件UUID升序锁行，禁止相反锁序。 */
    public List<Map<String,Object>> lock(Collection<UUID> ids){
        if(ids.isEmpty()||ids.size()>50)throw ApiFault.invalid("实际原件清单最多50项");String encoded=Crypto.json(ids.stream().map(UUID::toString).toList());
        var boxes=db.rows("SELECT DISTINCT box_id FROM archive.physical_item WHERE id IN (SELECT jsonb_array_elements_text(?::jsonb)::uuid) ORDER BY box_id",encoded);
        for(var box:boxes)db.required("SELECT id FROM archive.box WHERE id=? FOR UPDATE",box.get("box_id"));
        var rows=db.rows("SELECT p.*,b.department_id,b.state AS box_state,b.current_revision_id AS box_revision_id,r.current_revision_id,r.current_pdf_version_id,r.state AS record_state,COALESCE(r.title,b.title) AS title,b.classification FROM archive.physical_item p JOIN archive.box b ON b.id=p.box_id LEFT JOIN archive.record r ON r.id=p.record_id WHERE p.id IN (SELECT jsonb_array_elements_text(?::jsonb)::uuid) ORDER BY p.id FOR UPDATE OF p",encoded);if(rows.size()!=new HashSet<>(ids).size())throw ApiFault.hidden();return rows;
    }
    /** 已登记实物清单先按目录可见性过滤；普通员工不读取库房位置或其他借阅人身份。 */
    public Map<String,Object> list(Actor actor,Map<String,String> query){
        actor.require("ARCHIVE.ACCESS");Inputs.only(query,"boxId","recordId","page","pageSize","available","keyword","objectType");int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);String keyword=query.getOrDefault("keyword","");if(keyword.length()>200)throw ApiFault.invalid("检索条件过长");
        String where=" FROM archive.physical_item p JOIN archive.box b ON b.id=p.box_id LEFT JOIN archive.record r ON r.id=p.record_id WHERE ((? AND (? OR b.department_id=?)) OR (p.object_type='RECORD' AND archive.visibility(r.id,?,false)<>'HIDDEN') OR (p.object_type='BOX' AND EXISTS(SELECT 1 FROM archive.record m WHERE m.box_id=b.id AND m.state='PUBLISHED' AND archive.visibility(m.id,?,false)<>'HIDDEN') AND NOT EXISTS(SELECT 1 FROM archive.record m WHERE m.box_id=b.id AND m.state='PUBLISHED' AND archive.visibility(m.id,?,false)='HIDDEN')))";
        boolean manage=actor.actions().contains("ARCHIVE.PHYSICAL_MANAGE")&&actor.actions().contains("ARCHIVE.CATALOG_MANAGE"),all=authority.role(actor,"SUPER_ADMIN",null);UUID department=Inputs.uuid(actor.user().get("departmentId"));manage=manage&&(all||authority.role(actor,"DEPARTMENT_ARCHIVIST",department));
        List<Object> args=new ArrayList<>(List.of(manage,all,department,actor.userId(),actor.userId(),actor.userId()));if(query.containsKey("boxId")){where+=" AND p.box_id=?";args.add(Inputs.uuid(query.get("boxId")));}if(query.containsKey("recordId")){where+=" AND p.record_id=?";args.add(Inputs.uuid(query.get("recordId")));}
        // 非管理人员仅能检索批准安全题名和已获权字段，避免完整题名参与越权命中。
        where+=" AND (((? AND (? OR b.department_id=?)) OR (p.object_type='RECORD' AND archive.visibility(r.id,?,false)='METADATA')) AND p.barcode ILIKE ? OR CASE WHEN ? AND (? OR b.department_id=?) THEN COALESCE(r.title,b.title) WHEN p.object_type='RECORD' THEN CASE WHEN archive.visibility(r.id,?,false)='METADATA' THEN r.title ELSE coalesce(r.safe_title,r.file_no) END ELSE '可申请的整盒原件' END ILIKE ?)";
        String match="%"+keyword.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";args.addAll(List.of(manage,all,department,actor.userId(),match,manage,all,department,actor.userId(),match));
        String occupied="EXISTS(SELECT 1 FROM archive.physical_loan l JOIN archive.physical_item used ON used.id=l.physical_item_id WHERE l.box_id=p.box_id AND l.state='IN_USE' AND (p.object_type='BOX' OR used.object_type='BOX' OR used.id=p.id))";
        if(query.containsKey("available")){Inputs.choice(query.get("available"),"true","false");where+=" AND "+(query.get("available").equals("true")?"NOT ":"")+occupied;}
        where+=new ListConditions(query,args)
            .choice("objectType","p.object_type","BOX","RECORD").sql();
        long total=db.count("SELECT count(*)"+where,args.toArray());args.add(size);args.add((page-1)*size);
        var rows=db.rows("SELECT p.*,b.department_id,b.box_no,b.state AS box_state,r.state AS record_state,r.file_no,b.safe_title AS box_safe_title,r.safe_title AS record_safe_title,COALESCE(r.title,b.title) AS title,CASE WHEN p.record_id IS NULL THEN 'TITLE_ONLY' ELSE archive.visibility(r.id,?::uuid,false) END AS representation,"+occupied+" AS occupied"+where+" ORDER BY p.created_at DESC,p.id LIMIT ? OFFSET ?",prepend(actor.userId(),args));
        final boolean allowedManage=manage;return Sql.map("items",rows.stream().map(row->{boolean ownManage=allowedManage&&(all||department.equals(row.get("department_id")));var result=ownManage||"METADATA".equals(row.get("representation"))?Sql.view(row,"id,box_id,record_id,object_type,barcode,quantity,row_version,occupied,box_no,file_no"):Sql.view(row,"id,object_type,occupied");result.put("label",ownManage||"METADATA".equals(row.get("representation"))?row.get("title"):Objects.toString((row.get("record_id")==null?"可申请的整盒原件":row.get("record_safe_title")),Objects.toString(row.get("file_no"),"已批准检索的原件")));result.put("location",ownManage?row.get("location"):null);result.put("canManage",ownManage);result.put("canApply",actor.actions().contains("ARCHIVE.PHYSICAL_BORROW")&&"PUBLISHED".equals(row.get("box_state"))&&(row.get("record_id")==null||"PUBLISHED".equals(row.get("record_state"))));return result;}).toList(),"page",page,"pageSize",size,"total",total);
    }
    /** 位置变更保留事件；存在整盒或本件借出时禁止改变位置。 */
    public Map<String,Object> location(Commands.Context context,UUID id,Map<String,Object> body){
        Inputs.only(body,"location","reason");String location=Inputs.text(body,"location",512);Inputs.text(body,"reason",1000);
        return commands.run(context,body,"PHYSICAL_ITEM",id,"MOVE_PHYSICAL",true,()->{context.actor().require("ARCHIVE.PHYSICAL_MANAGE");var item=db.required("SELECT b.department_id FROM archive.physical_item p JOIN archive.box b ON b.id=p.box_id WHERE p.id=?",id);authority.requireManage(context.actor(),(UUID)item.get("department_id"));},()->{
            var item=lock(List.of(id)).getFirst();if(context.version()==null||context.version()!=((Number)item.get("row_version")).intValue())throw ApiFault.conflict("实物位置记录已变化");assertFree(item);db.update("UPDATE archive.physical_item SET location=?,row_version=row_version+1 WHERE id=?",location,id);event(id,null,context.actor(),"LOCATION_CHANGED",Sql.map("from",item.get("location"),"to",location,"reason",body.get("reason")));return Sql.map("id",id,"location",location);
        },key->Sql.view(db.required("SELECT id,location,row_version FROM archive.physical_item WHERE id=?",key),"id,location,row_version"));
    }
    /** 已借出整盒与分件互斥；调用者必须已经持有对应盒行锁。 */
    public void assertFree(Map<String,Object> item){if(db.count("SELECT count(*) FROM archive.physical_loan l JOIN archive.physical_item p ON p.id=l.physical_item_id WHERE l.box_id=? AND l.state='IN_USE' AND (?='BOX' OR p.object_type='BOX' OR p.id=?)",item.get("box_id"),item.get("object_type"),item.get("id"))>0)throw new ApiFault(409,"PHYSICAL_ITEM_OCCUPIED_OR_APPROVAL_REQUIRED","整盒或相关分件仍被占用，不能再次交付或移动");}
    /** 记录实际经办人与业务字段，不写入密码、文件路径或正文。 */
    public void event(UUID item,UUID loan,Actor actor,String type,Map<String,Object> details){event(item,loan,actor.userId(),type,details);}
    /** 审批完成事件直接记录真实操作者UUID，不创建虚构会话。 */
    public void event(UUID item,UUID loan,UUID actor,String type,Map<String,Object> details){db.update("INSERT INTO archive.loan_event(id,loan_id,physical_item_id,actor_id,event_type,details) VALUES(?,?,?,?,?,?::jsonb)",UUID.randomUUID(),loan,item,actor,type,Crypto.json(details));}
    /** 将SELECT中的主体参数放在筛选参数前，不拼接用户输入到SQL结构。 */
    private Object[] prepend(Object first,List<Object> remaining){var values=new ArrayList<Object>();values.add(first);values.addAll(remaining);return values.toArray();}
}
