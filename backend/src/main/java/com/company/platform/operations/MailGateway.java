package com.company.platform.operations;

import com.company.platform.*;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 公司邮件通道；只接受平台生成的纯文本，不加载员工提供的HTML或远程附件。 */
@Component
public class MailGateway {
    /** 模式明确区分本机收件箱和SMTP服务端接受，配置秘密不通过管理接口返回。 */
    private final String mode,from,portal,localRoot;
    private final JavaMailSenderImpl sender;
    public MailGateway(Environment env) {
        mode=env.getProperty("platform.messaging.mail-mode","LOCAL");
        if(!Set.of("LOCAL","SMTP").contains(mode))throw new IllegalArgumentException("邮件模式无效");
        from=env.getProperty("platform.messaging.smtp.from","");portal=env.getProperty("platform.messaging.portal-url","https://127.0.0.1:18443/platform.html");
        localRoot=env.getRequiredProperty("platform.mailbox");sender=new JavaMailSenderImpl();
        if(!portal.matches("https://[^\\s\\r\\n]+"))throw new IllegalArgumentException("消息入口必须是HTTPS地址");
        if(mode.equals("SMTP")) {
            String host=env.getRequiredProperty("platform.messaging.smtp.host"),security=env.getProperty("platform.messaging.smtp.security","STARTTLS");
            boolean localTest=security.equals("LOCAL_TEST")&&Boolean.parseBoolean(env.getProperty("platform.isolation-confirmed","false"))&&Set.of("127.0.0.1","localhost","::1").contains(host);
            if(!Set.of("STARTTLS","TLS").contains(security)&&!localTest)throw new IllegalArgumentException("SMTP必须启用TLS，本机测试仅允许回环地址");
            Inputs.email(Sql.map("companyEmail",from));sender.setHost(host);sender.setPort(env.getProperty("platform.messaging.smtp.port",Integer.class,587));
            sender.setUsername(env.getProperty("platform.messaging.smtp.username",""));sender.setPassword(env.getProperty("platform.messaging.smtp.password",""));sender.setDefaultEncoding("UTF-8");
            Properties p=sender.getJavaMailProperties();p.setProperty("mail.smtp.auth",Boolean.toString(!sender.getUsername().isBlank()));
            p.setProperty("mail.smtp.starttls.enable",Boolean.toString(security.equals("STARTTLS")));p.setProperty("mail.smtp.starttls.required",Boolean.toString(security.equals("STARTTLS")));
            p.setProperty("mail.smtp.ssl.enable",Boolean.toString(security.equals("TLS")));p.setProperty("mail.smtp.ssl.checkserveridentity","true");
            for(String option:List.of("connectiontimeout","timeout","writetimeout"))p.setProperty("mail.smtp."+option,"10000");
            p.setProperty("mail.smtp.quitwait","false");p.setProperty("mail.debug","false");
        }
    }
    public String mode(){return mode.equals("LOCAL")?"LOCAL_DEVELOPMENT_ONLY":"SMTP";}
    public String portal(){return portal;}
    /** 凭据邮件仅在私有本机箱保留明文用于隔离验收；正式通道交由SMTP发送。 */
    public String send(UUID id,String address,String subject,String text,Map<String,Object> localFields) {
        if(mode.equals("LOCAL")) {
            try { Path file=PrivateFiles.directory(localRoot).resolve(id+".json");var data=new LinkedHashMap<>(localFields);data.put("to",address);data.put("delivery","LOCAL_DEVELOPMENT_ONLY");
                Files.writeString(file,Crypto.json(data),StandardOpenOption.CREATE_NEW);PrivateFiles.restrict(file);return "LOCAL:"+id;
            }catch(Exception failure){throw new DeliveryFailure(false);}
        }
        try {
            // Message-ID只用于运维核对；SMTP不承诺按此字段精确去重。
            MimeMessage message=new MimeMessage(sender.getSession()) { @Override protected void updateMessageID() throws MessagingException {setHeader("Message-ID","<"+id+"@ims.biolink.com>");} };
            message.setFrom(new InternetAddress(from));message.setRecipient(Message.RecipientType.TO,new InternetAddress(address));
            message.setSubject(subject,"UTF-8");message.setText(text,"UTF-8");message.setSentDate(new Date());message.saveChanges();sender.send(message);return "SMTP:"+id;
        }catch(Exception failure){
            // 明确连接失败可重试；进入SMTP会话后的发送异常可能已被对端接受。
            List<Throwable> errors=new ArrayList<>();errors.add(failure);boolean rejected=false;
            for(int i=0;i<errors.size()&&i<16;i++){Throwable cause=errors.get(i);if(cause instanceof java.net.ConnectException||cause instanceof java.net.UnknownHostException||cause instanceof AuthenticationFailedException)rejected=true;if(cause instanceof SMTPSendFailedException smtp&&smtp.getReturnCode()>=400&&smtp.getReturnCode()<600)rejected=true;if(cause.getCause()!=null)errors.add(cause.getCause());if(cause instanceof org.springframework.mail.MailSendException send)errors.addAll(Arrays.asList(send.getMessageExceptions()));if(cause instanceof MessagingException message&&message.getNextException()!=null)errors.add(message.getNextException());}
            throw new DeliveryFailure(!rejected);
        }
    }
}
