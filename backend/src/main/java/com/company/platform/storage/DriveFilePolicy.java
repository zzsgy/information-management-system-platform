package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;

/** 公盘真实对象授权：目录范围、当前负责人、有效委托和个人拒绝共同决定内容动作。 */
@Component
public class DriveFilePolicy implements StorageDomainPolicy {
    private final Sql db;
    public DriveFilePolicy(Sql db) { this.db=db; }
    /** 仅承接公盘文件；档案与流程不会因共用存储而获得此策略的允许。 */
    @Override public boolean supports(String module,String purpose) { return module.equals("DRIVE") && Set.of("DRIVE_FILE","DRIVE_VERSION").contains(purpose); }
    /** 上传全生命周期重验原目录的上传权，取消及状态查询也不绕过当前访问范围。 */
    @Override public void require(Actor actor,String module,String purpose,UUID context,String action) {
        actor.require("DRIVE.ACCESS");
        if(purpose.equals("DRIVE_VERSION")) requireDocument(actor,context,"EDIT"); else requireFolder(actor,context,"UPLOAD");
    }
    /** 返回仍有效的目录及祖先；在提交事务中加共享行锁，使同时撤权和停用有明确先后。 */
    public List<Map<String,Object>> ancestors(UUID id) {
        var rows=db.rows("WITH RECURSIVE path AS (SELECT *,0 AS depth FROM drive.folder WHERE id=? UNION ALL SELECT f.*,p.depth+1 FROM drive.folder f JOIN path p ON p.parent_id=f.id WHERE p.depth<128) SELECT * FROM path ORDER BY depth",id);
        if(rows.isEmpty()) throw ApiFault.hidden();
        if(rows.size()>128 || rows.stream().anyMatch(r->!r.get("state").equals("ACTIVE"))) throw ApiFault.hidden();
        if(rows.stream().map(r->r.get("id")).distinct().count()!=rows.size()) throw ApiFault.protection();
        if(TransactionSynchronizationManager.isActualTransactionActive()) {
            for(var row:rows) db.rows("SELECT id FROM drive.folder WHERE id=? FOR SHARE",row.get("id"));
        }
        return rows;
    }
    /** 按对象和动作核验公盘权限，管理资格只提供默认查看和下载。 */
    public Map<String,Object> requireFolder(Actor actor,UUID id,String action) {
        actor.require("DRIVE.ACCESS"); var path=ancestors(id);
        if(!Boolean.TRUE.equals(db.required("SELECT drive.can_folder(?,?,?) AS allowed",actor.userId(),id,action).get("allowed"))) throw ApiFault.denied();
        return path.getFirst();
    }
    /** 文档读取还核对文档本身的明确拒绝、版本和当前目录状态。 */
    public Map<String,Object> requireDocument(Actor actor,UUID id,String action) {
        actor.require("DRIVE.ACCESS");
        var doc=db.required("SELECT * FROM drive.document WHERE id=? AND status=?"+lock(),id,Set.of("RESTORE","PURGE").contains(action)?"RECYCLED":"ACTIVE");
        ancestors((UUID)doc.get("folder_id"));
        if(!Boolean.TRUE.equals(db.required("SELECT drive.can_document(?,?,?) AS allowed",actor.userId(),id,action).get("allowed"))) throw ApiFault.denied();
        return doc;
    }
    /** 委托绑定部门负责人角色与当前负责人关系，不把历史授权人误当现任职责。 */
    public Map<String,Object> managerDelegation(Actor actor,List<Map<String,Object>> path,String action) {
        for(var folder:path) {
            if(folder.get("department_id")==null) continue;
            var delegated=db.one("SELECT d.* FROM drive.delegation d JOIN iam.user_role ur ON ur.role_id=d.role_id AND ur.department_id=d.department_id JOIN iam.role r ON r.id=ur.role_id JOIN iam.department_manager m ON m.user_id=ur.user_id AND m.department_id=d.department_id WHERE d.folder_id=? AND d.department_id=? AND d.state='ACTIVE' AND ?=ANY(d.allowed_actions) AND ur.user_id=? AND ur.module='DRIVE' AND ur.role_code='DEPARTMENT_MANAGER' AND ur.state='ACTIVE' AND r.status='ACTIVE' AND ur.valid_from<=CURRENT_TIMESTAMP AND (ur.valid_until IS NULL OR ur.valid_until>CURRENT_TIMESTAMP) AND m.duty_type='DEPARTMENT_MANAGER' AND m.valid_from<=CURRENT_TIMESTAMP AND (m.valid_until IS NULL OR m.valid_until>CURRENT_TIMESTAMP)"+lock("d","ur","m"),folder.get("id"),path.getFirst().get("department_id"),action,actor.userId());
            if(delegated!=null) return delegated;
        }
        return null;
    }
    /** 预检仅作读取，提交事务才能持有共享锁；不在收流期间占用数据库锁。 */
    private String lock(String... aliases) { return TransactionSynchronizationManager.isActualTransactionActive()?" FOR SHARE"+(aliases.length==0?"":" OF "+String.join(",",aliases)):""; }
}
