package com.company.platform.intake;

import com.company.platform.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** 扫描专用身份：签名绑定方法、路径、时刻、随机数与完整正文摘要，不具备员工登录能力。 */
@Service
public class ScanIdentity {
    /** 密钥目录只由服务配置提供，客户端不能指定磁盘路径。 */
    private final String directory;private final Sql db;
    public ScanIdentity(Sql db,@Value("${platform.scan-intake.key-directory:}") String directory){this.db=db;this.directory=directory;}
    /** 为 IT 新建设备生成专用身份，秘密仅存私有文件，部署人员通过受控渠道配置代理。 */
    public UUID provision(UUID department,String name){
        UUID id=UUID.randomUUID();Path key=root().resolve(id+".key");
        try{Files.writeString(key,Crypto.token(),StandardOpenOption.CREATE_NEW);PrivateFiles.restrict(key);}catch(Exception failure){throw SystemFailure.SCAN_KEY_UNAVAILABLE.fault(failure);}
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCompletion(int state){if(state==STATUS_ROLLED_BACK)try{Files.deleteIfExists(key);}catch(Exception ignored){/* 无数据库身份的孤立高熵密钥不能用于接入。 */}}});
        db.update("INSERT INTO iam.service_identity(id,service_type,name,allowed_module,department_scope,allowed_actions,credential_ref,valid_until) VALUES(?,'SCAN_INTAKE',?,'ARCHIVE',ARRAY[?::uuid],ARRAY['SCAN_UPLOAD','SCAN_RECEIPT'],?,CURRENT_TIMESTAMP+interval '1 year')",id,name,department,id+".key");return id;
    }
    /** 独立事务消耗随机数，即使后续业务失败也不能重放同一个签名；业务重试另用随机数及原业务键。 */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public UUID authenticate(String service,String timestamp,String nonce,String method,String path,String bodyHash,String signature){
        try{
            UUID id=Inputs.uuid(service),random=Inputs.uuid(nonce);long epoch=Long.parseLong(timestamp);if(epoch<Instant.now().getEpochSecond()-300||epoch>Instant.now().getEpochSecond()+300)throw invalid();
            var row=active(id,false);String key=key(row);String expected=Crypto.hmac(key,method+"\n"+path+"\n"+timestamp+"\n"+nonce+"\n"+bodyHash);if(!Crypto.equal(expected,signature))throw invalid();
            db.update("DELETE FROM iam.service_nonce WHERE (service_identity_id,nonce) IN (SELECT service_identity_id,nonce FROM iam.service_nonce WHERE expires_at<CURRENT_TIMESTAMP LIMIT 1000)");
            if(db.count("SELECT count(*) FROM iam.service_nonce WHERE service_identity_id=?",id)>3000)throw new ApiFault(429,"RATE_LIMITED","扫描请求过于频繁");
            if(db.update("INSERT INTO iam.service_nonce(service_identity_id,nonce,expires_at) VALUES(?,?,to_timestamp(?)+interval '5 minutes') ON CONFLICT DO NOTHING",id,random,epoch)==0)throw invalid();return id;
        }catch(ApiFault failure){throw failure;}catch(Exception failure){throw invalid();}
    }
    /** 上传每一阶段都重读身份状态和有效期，停用后旧签名也不能继续。 */
    public Map<String,Object> active(UUID id,boolean lock){var row=db.one("SELECT * FROM iam.service_identity WHERE id=? AND service_type='SCAN_INTAKE' AND allowed_module='ARCHIVE' AND 'SCAN_UPLOAD'=ANY(allowed_actions) AND 'SCAN_RECEIPT'=ANY(allowed_actions) AND status='ACTIVE' AND valid_until>CURRENT_TIMESTAMP"+(lock?" FOR SHARE":""),id);if(row==null)throw invalid();return row;}
    /** 固定目录与简单随机文件名避免 credential_ref 形成路径穿越。 */
    private String key(Map<String,Object> identity){try{String ref=(String)identity.get("credential_ref");if(!ref.matches("[a-f0-9-]{36}\\.key"))throw invalid();Path key=root().resolve(ref);if(Files.isSymbolicLink(key)||Files.size(key)>128)throw invalid();String secret=Files.readString(key).trim();if(secret.length()<43)throw invalid();return secret;}catch(Exception failure){throw invalid();}}
    /** 配置缺失时默认拒绝设备接收，普通员工功能仍可运行。 */
    private Path root(){if(directory.isBlank())throw SystemFailure.SCAN_KEY_UNAVAILABLE.fault();try{return PrivateFiles.directory(directory);}catch(Exception failure){throw SystemFailure.SCAN_KEY_UNAVAILABLE.fault(failure);}}
    /** 设备错误不区分是否存在某个服务标识。 */
    private ApiFault invalid(){return new ApiFault(401,"SERVICE_IDENTITY_INVALID","扫描服务身份或签名无效");}
}
