package com.company.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 初始与重置密码投递；本机箱保留开发兼容性，SMTP采用加密发件箱，HTTP不返回口令。 */
@Service
public class CredentialDelivery {
    /** 操作系统当前用户专有投递目录，既不在网页目录也不在 Git 内。 */
    private final Path root;
    private final com.company.platform.operations.MailGateway mail;
    private final com.company.platform.operations.AccountMessages messages;
    private final Sql db;
    /** 初始化投递适配器；目录必须是绝对路径并通过当前用户专有权限核验。 */
    public CredentialDelivery(@Value("${platform.mailbox}") String directory,com.company.platform.operations.MailGateway mail,com.company.platform.operations.AccountMessages messages,Sql db) { root=PrivateFiles.directory(directory);this.mail=mail;this.messages=messages;this.db=db; }
    /** 签发独立初始凭据；摘要绑定签发编号，重复重置为相同工号也不会触发摘要唯一性冲突。 */
    public void issue(UUID user,String employeeNo,String email) {
        db.update("UPDATE iam.credential_challenge SET consumed_at=CURRENT_TIMESTAMP WHERE user_id=? AND purpose='TEMP_PASSWORD' AND consumed_at IS NULL",user);
        UUID challenge=UUID.randomUUID();
        db.update("INSERT INTO iam.credential_challenge(id,user_id,purpose,credential_hash,expires_at,flow_id) VALUES(?,?,'TEMP_PASSWORD',?,CURRENT_TIMESTAMP+interval '24 hours',?)",challenge,user,Crypto.hash(challenge+":"+employeeNo),UUID.randomUUID());
        send(challenge,employeeNo,email,employeeNo);
    }
    /** 持久化测试邮件；事务回滚删除本次投递文件，业务不伪报已发公司邮箱。 */
    public void send(UUID challenge,String employeeNo,String email,String temporary) {
        if(mail.mode().equals("SMTP")){messages.credential(challenge,(UUID)db.required("SELECT user_id FROM iam.credential_challenge WHERE id=?",challenge).get("user_id"),challenge,"TEMP_PASSWORD",email,temporary,Instant.now().plusSeconds(86400));return;}
        Path output=root.resolve(challenge+".json");
        try {
            byte[] content=Crypto.json(Sql.map("employeeNo",employeeNo,"to",email,"temporaryPassword",temporary,"expiresAt",Instant.now().plusSeconds(86400).toString(),"delivery","LOCAL_DEVELOPMENT_ONLY")).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel=FileChannel.open(output,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                ByteBuffer buffer=ByteBuffer.wrap(content); while(buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            PrivateFiles.restrict(output);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /** 只有当前新建文件可随事务回滚清理，不扫描或删除其他投递。 */
                @Override public void afterCompletion(int status) { if (status!=STATUS_COMMITTED) try { Files.deleteIfExists(output); } catch (Exception ignored) { /* 凭据对应事务未提交，因此不能登录；管理员后续清理。 */ } }
            });
        } catch (Exception e) { throw ApiFault.protection(); }
    }
}
