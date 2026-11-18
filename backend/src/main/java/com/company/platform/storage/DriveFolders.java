package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** 目录结构仅IT和受委托负责人维护；普通员工的文件动作不产生目录管理权。 */
@Service
public class DriveFolders {
    private final Sql db;private final DriveFilePolicy policy;private final Commands commands;private final StorageBoundaries protection;
    public DriveFolders(Sql db,DriveFilePolicy policy,Commands commands,StorageBoundaries protection){this.db=db;this.policy=policy;this.commands=commands;this.protection=protection;}
    public Map<String,Object> change(Commands.Context ctx,UUID id,Map<String,Object> body,String action) {
        Inputs.only(body,action.equals("RENAME_FOLDER")?new String[]{"name","reason"}:new String[]{"reason"});Inputs.text(body,"reason",1000);
        String name=action.equals("RENAME_FOLDER")?Inputs.text(body,"name",128):null;if(name!=null)DriveLifecycle.name(body);
        return commands.run(ctx,body,"FOLDER",id,action,()->authorize(ctx.actor(),id,action),()->{
            var row=db.required("SELECT * FROM drive.folder WHERE id=? FOR UPDATE",id);Commands.version(ctx,row);
            switch(action){
                case "RENAME_FOLDER"->{active(row);db.update("UPDATE drive.folder SET name=?,name_key=? WHERE id=?",name,DriveLifecycle.nameKey(name),id);}
                case "RECYCLE_FOLDER"->{active(row);empty(id);db.update("UPDATE drive.folder SET state='RECYCLED',deleted_at=CURRENT_TIMESTAMP,deleted_by=? WHERE id=?",ctx.actor().userId(),id);}
                case "RESTORE_FOLDER"->{recycled(row);if(row.get("parent_id")!=null)policy.ancestors((UUID)row.get("parent_id"));db.update("UPDATE drive.folder SET state='ACTIVE',deleted_at=NULL,deleted_by=NULL WHERE id=?",id);}
                case "PURGE_FOLDER"->{recycled(row);empty(id);if(db.count("SELECT count(*) FROM drive.folder WHERE id=? AND deleted_at<=CURRENT_TIMESTAMP-interval '30 days'",id)!=1)throw ApiFault.conflict("目录仍处于30天回收保留期");db.update("UPDATE drive.folder SET state='PURGED' WHERE id=?",id);}
                default->throw ApiFault.invalid("未知目录操作");
            }
            db.update("UPDATE drive.folder SET row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",id);return view(id);
        },this::view);
    }
    /** 非空目录不级联回收，历史版本、在途上传和子目录都必须先明确处置。 */
    private void empty(UUID id){if(db.count("SELECT count(*) FROM drive.folder WHERE parent_id=? AND state<>'PURGED'",id)>0||db.count("SELECT count(*) FROM drive.document WHERE folder_id=?",id)>0||db.count("SELECT count(*) FROM storage.upload_session WHERE module='DRIVE' AND business_context_id=? AND state NOT IN ('CANCELLED','REJECTED','COMPLETE')",id)>0)throw ApiFault.conflict("目录包含子目录、文件历史或在途上传，不能回收或清理");}
    /** 回收目录沿用当前管理范围，名称、回收日期和保留状态与文件使用相同查询口径。 */
    public Map<String,Object> recycledFolders(Actor actor,Map<String,String> query){
        actor.require("DRIVE.ACCESS");Inputs.only(query,"page","pageSize","keyword","deletedFrom","deletedTo","retention");int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),20,1,100);
        String keyword=query.getOrDefault("keyword","");if(keyword.length()>200)throw ApiFault.invalid("名称超过长度限制");
        String retention=Inputs.choice(query.getOrDefault("retention",""),"","RETAINED","ELAPSED");var dates=ListDateRange.parse(query,"deletedFrom","deletedTo");
        String where=" FROM drive.folder f WHERE state='RECYCLED' AND ((folder_level='TOP' AND ?) OR (folder_level='CHILD' AND drive.manager_scope(?,parent_id,'FOLDER_MANAGE'))) AND position(lower(?) in lower(name))>0 AND (?::timestamptz IS NULL OR deleted_at>=?) AND (?::timestamptz IS NULL OR deleted_at<?) AND (?='' OR (deleted_at<=CURRENT_TIMESTAMP-interval '30 days')=(?='ELAPSED'))";
        Object[] args={it(actor),actor.userId(),keyword,dates.from(),dates.from(),dates.until(),dates.until(),retention,retention};long total=db.count("SELECT count(*)"+where,args);
        List<Object> parameters=new ArrayList<>(Arrays.asList(args));parameters.add(size);parameters.add((page-1)*size);
        return Sql.map("items",db.rows("SELECT f.*,deleted_at<=CURRENT_TIMESTAMP-interval '30 days' AS retention_elapsed"+where+" ORDER BY deleted_at DESC,id LIMIT ? OFFSET ?",parameters.toArray()).stream().map(r->Sql.view(r,"id,parent_id,name,zone,department_id,state,row_version,deleted_at,retention_elapsed")).toList(),"page",page,"pageSize",size,"total",total,"hasNext",(long)page*size<total);
    }
    /** IT 的顶层目录配置只更新目录记录；永久清理与委托下的子目录操作仍检查存储保护。 */
    private void authorize(Actor actor,UUID id,String action){
        actor.require("DRIVE.ACCESS");
        var row=db.required("SELECT * FROM drive.folder WHERE id=?",id);
        if("TOP".equals(row.get("folder_level"))){
            if(!it(actor))throw ApiFault.denied();
            if(!"PURGE_FOLDER".equals(action))return;
        }else if(policy.managerDelegation(actor,policy.ancestors((UUID)row.get("parent_id")),"FOLDER_MANAGE")==null){
            throw ApiFault.denied();
        }
        protection.requireWritable("DRIVE",action);
    }
    private boolean it(Actor actor){return actor.systemAdministrator() || actor.actions().contains("DRIVE.ROLES_MANAGE")&&actor.roles().stream().anyMatch(r->"DRIVE".equals(r.get("module"))&&"IT_ADMIN".equals(r.get("code")));}
    private Map<String,Object> view(UUID id){return Sql.view(db.required("SELECT * FROM drive.folder WHERE id=?",id),"id,parent_id,name,zone,department_id,state,row_version,deleted_at");}
    private void active(Map<String,Object> row){if(!"ACTIVE".equals(row.get("state")))throw ApiFault.conflict("目录不在正常状态");}
    private void recycled(Map<String,Object> row){if(!"RECYCLED".equals(row.get("state")))throw ApiFault.conflict("目录不在回收站");}
}
