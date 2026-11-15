package com.company.platform.operations;

import com.company.platform.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 本机演练适配器：私有文件刷盘和稳定回执，不声称已经发送公司邮件。 */
public final class LocalBusinessMessages implements BusinessMessageTransport {
    /** 根目录仅由受信部署配置给出；实例绑定阻止不同数据库共用投递箱。 */
    private final Path root;
    private final UUID instance;
    /** 初始化受限目录，不能指向网页根目录或符号链接。 */
    public LocalBusinessMessages(String directory,UUID instance){
        this.instance=Objects.requireNonNull(instance);Path configured=Path.of(directory);
        if(directory.isBlank()||!configured.isAbsolute())throw new IllegalArgumentException("业务投递箱必须是绝对路径");
        for(Path part=configured;part!=null;part=part.getParent())if(Files.isSymbolicLink(part))throw new IllegalArgumentException("业务投递箱不允许链接");
        root=PrivateFiles.directory(directory);
    }
    /** 文件名只来自服务端UUID；重复发送核对原字节，绝不覆盖已存在的不同结果。 */
    @Override public synchronized String send(Message message){
        try{
            if(message.address()==null||message.address().length()>254||!message.address().matches("[^\\s@]+@[^\\s@]+"))throw ApiFault.protection();
            writeOnce(root.resolve("instance"),instance.toString().getBytes(StandardCharsets.UTF_8));
            // 使用固定安全提示，不允许调用者把完整题名或正文混进消息。
            byte[] bytes=Crypto.json(Sql.map("deliveryId",message.deliveryId(),"notificationId",message.notificationId(),"recipientId",message.recipientId(),"to",message.address(),"kind",message.kind(),"sourceKind",message.sourceKind(),"sourceId",message.sourceId(),"message","您有一项业务状态更新，请登录工作台查看当前获授权内容。","delivery","LOCAL_DEVELOPMENT_ONLY")).getBytes(StandardCharsets.UTF_8);
            writeOnce(root.resolve(message.deliveryId()+".json"),bytes);return "LOCAL:"+message.deliveryId();
        }catch(Exception failure){throw new IllegalStateException("LOCAL_DELIVERY_UNAVAILABLE");}
    }
    /** 先刷盘再返回；既有不完整文件会拒绝成功并进入人工处理，不被悄悄改写。 */
    private void writeOnce(Path target,byte[] content)throws Exception{
        for(Path part=target;part!=null;part=part.getParent())if(Files.isSymbolicLink(part))throw ApiFault.protection();
        try(FileChannel channel=FileChannel.open(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){ByteBuffer bytes=ByteBuffer.wrap(content);while(bytes.hasRemaining())channel.write(bytes);channel.force(true);PrivateFiles.restrict(target);}
        catch(FileAlreadyExistsException duplicate){if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)||Files.size(target)!=content.length||!Arrays.equals(Files.readAllBytes(target),content))throw ApiFault.protection();}
    }
    /** 正式适配器可实现同一接口替换；默认关闭，不会自动向公司邮箱发送。 */
    @Configuration(proxyBeanMethods=false)
    public static class Adapters {
        @Bean @ConditionalOnMissingBean(BusinessMessageTransport.class)
        BusinessMessageTransport businessMessages(Sql db,MailGateway mail,@Value("${platform.notifications.enabled:false}") boolean enabled,@Value("${platform.notifications.local-directory:}") String directory){
            if(!enabled)return message->{throw ApiFault.protection();};
            if(mail.mode().equals("SMTP"))return new BusinessMessageTransport(){
                @Override public String channel(){return "SMTP";}
                @Override public boolean replaySafe(){return false;}
                @Override public String send(Message message){return mail.send(message.deliveryId(),message.address(),"信息管理系统事项提醒","您有一项业务状态更新，请登录工作台消息通知查看。\n"+mail.portal()+"#/home",Map.of());}
            };
            // 空库实例由ApplicationRunner引导，首次真正投递时再绑定，避免构造期读取尚未建立的实例。
            return new BusinessMessageTransport(){
                private LocalBusinessMessages delegate;
                @Override public synchronized String send(Message message){if(delegate==null)delegate=new LocalBusinessMessages(directory,(UUID)db.required("SELECT id FROM platform_meta.instance").get("id"));return delegate.send(message);}
            };
        }
    }
}
