package com.company.platform.operations;

import com.company.platform.*;
import com.company.platform.storage.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** 单机保护使用真实双副本与完整备份；外部主备模式继续核验独立签名证据。 */
@Service
public class ManagedProtection {
    private final DeploymentSettings settings;private final StorageFiles files;private final Sql db;private final ObjectProvider<SignedProtectionGate> legacy;
    private SignedProtectionGate external;private String externalConfig="";
    public ManagedProtection(DeploymentSettings settings,StorageFiles files,Sql db,ObjectProvider<SignedProtectionGate> legacy){this.settings=settings;this.files=files;this.db=db;this.legacy=legacy;}
    public boolean configured(){return !mode().equals("LEGACY");}
    private String mode(){return settings.text("platform.protection.mode","LEGACY");}
    private synchronized SignedProtectionGate gate(){
        String evidence=settings.effective("platform.protection.evidence-file"),key=settings.effective("platform.protection.key-file"),node=settings.effective("platform.protection.node-id"),configuration=evidence+"\n"+key+"\n"+node;
        if(evidence.isBlank()||key.isBlank()||node.isBlank())throw new ApiFault(503,"PROTECTION_EXTERNAL_NOT_CONFIGURED","外部保护缺少证据文件、密钥文件或节点编号，请进入部署配置");
        if(external==null||!configuration.equals(externalConfig)){external=new SignedProtectionGate(db,evidence,key,node);externalConfig=configuration;}return external;
    }
    public void readable(String module){if(mode().equals("SINGLE")){files.get();return;}gate().requireReadable(module);}
    public void writable(String module,String action){
        if(!mode().equals("SINGLE")){gate().requireWritable(module,action);return;}
        files.get();
        var backup=db.one("SELECT id,verified_at,retain_until FROM ops.backup_point ORDER BY verified_at DESC LIMIT 1");
        int hours=Integer.parseInt(settings.text("platform.protection.backup-hours","24"));
        if(backup==null||Instant.parse(backup.get("verified_at").toString()).isBefore(Instant.now().minusSeconds(hours*3600L))||Instant.parse(backup.get("retain_until").toString()).isBefore(Instant.now()))throw new ApiFault(503,"LOCAL_BACKUP_REQUIRED","单机保护需要有效的完整备份；请在运维与保护中配置执行器并完成首次备份或更新过期备份");
        String root=settings.effective("platform.maintenance.backup-root");
        try{
            Path directory=Path.of(root),manifest=directory.resolve(backup.get("id").toString()).resolve("manifest.json");
            if(root.isBlank()||!Files.isRegularFile(manifest)||!Files.isRegularFile(manifest.resolveSibling("database.dump")))throw new IllegalStateException();
            if(Files.getFileStore(directory).equals(Files.getFileStore(Path.of(settings.effective("platform.storage.primary")))))throw new ApiFault(503,"BACKUP_VOLUME_NOT_INDEPENDENT","备份目录与原件位于同一卷，请配置其他磁盘或独立备份存储");
        }catch(ApiFault fault){throw fault;}catch(Exception failure){throw new ApiFault(503,"LOCAL_BACKUP_UNAVAILABLE","已登记备份在当前配置目录下不可访问，请检查备份盘、目录和服务账号权限",failure);}
    }
    public Map<String,Object> status(){
        if(!configured()){var gate=legacy.getIfUnique();return gate==null?Sql.map("ready",false,"mode","NOT_CONFIGURED","message","请在部署配置选择单机保护或外部主备保护"):gate.status();}
        try{writable("DRIVE","CHECK");return Sql.map("ready",true,"mode",mode(),"message",mode().equals("SINGLE")?"单机双副本与有效完整备份已核验；不代表双机高可用":"外部保护证据有效");}
        catch(ApiFault fault){return Sql.map("ready",false,"mode",mode(),"errorCode",fault.code,"message",fault.getMessage());}
    }
}
