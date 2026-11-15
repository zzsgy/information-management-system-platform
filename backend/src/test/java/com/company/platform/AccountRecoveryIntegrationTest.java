package com.company.platform;

import com.company.platform.operations.*;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.env.MockEnvironment;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import javax.net.ssl.*;
import java.security.KeyStore;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** 新建隔离PostgreSQL与真实SMTP/HTTP协议接收器，直接调用正式服务，不复制业务判定。 */
@EnabledIfEnvironmentVariable(named="IMS_ACCOUNT_INTEGRATION",matches="yes")
@SpringBootTest(classes=PlatformApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"spring.config.name=platform","server.ssl.enabled=false","platform.messaging.schedule-enabled=false","platform.notifications.enabled=true","platform.notifications.schedule-enabled=false","platform.file-worker.enabled=false","platform.scan-intake.worker-enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountRecoveryIntegrationTest {
    private static final SmtpSink SMTP=new SmtpSink();
    private static final RobotSink ROBOT=new RobotSink();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p){p.add("platform.messaging.mail-mode",()->"SMTP");p.add("platform.messaging.smtp.host",()->"127.0.0.1");p.add("platform.messaging.smtp.port",()->SMTP.port());p.add("platform.messaging.smtp.security",()->"LOCAL_TEST");p.add("platform.messaging.smtp.from",()->"service@biolink.com");p.add("platform.messaging.dingtalk.webhook",()->"http://127.0.0.1:"+ROBOT.port()+"/robot/send?access_token=isolated-test");p.add("platform.messaging.dingtalk.secret",()->"isolated-robot-secret");p.add("platform.messaging.dingtalk.local-test",()->true);}
    @Autowired Sql db;@Autowired IdentityService identity;@Autowired AccountRecovery recovery;@Autowired AccountMessages messages;@Autowired Commands commands;@Autowired DirectoryService directory;@Autowired BusinessMessageTransport business;@Autowired AccountController controller;
    private Actor admin;private UUID uid;private String no,password,source;
    /** 只允许本批指定端口，绝不从旧预览复制数据或连接业务库。 */
    @BeforeAll void guard(){assertTrue(System.getenv("IMS_PLATFORM_DB_URL").matches("jdbc:postgresql://127\\.0\\.0\\.1:(55461|55475)/ims_platform_dev_[0-9]+"));}
    @BeforeEach void setup(){db.update("UPDATE ops.account_message SET state='CANCELLED',payload=NULL WHERE state<>'SENT'");SMTP.mode="NORMAL";ROBOT.error=0;SMTP.messages.clear();ROBOT.bodies.clear();source="test-"+UUID.randomUUID();admin=identity.authenticate((String)identity.login(Sql.map("employeeNo",System.getenv("IMS_PLATFORM_ADMIN_NO"),"password",System.getenv("IMS_PLATFORM_ADMIN_PASSWORD")),UUID.randomUUID(),source).get("token"));uid=UUID.randomUUID();no="TEST-"+uid;password=Crypto.token();db.update("INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required) VALUES(?,?,?,?,?,?,false)",uid,no,"账号隔离验收",admin.user().get("departmentId"),no.toLowerCase(Locale.ROOT)+"@biolink.com",Crypto.password(password));}
    private UUID request(){return (UUID)recovery.request(Sql.map("employeeNo",no),source,UUID.randomUUID()).get("flowId");}
    private String sendAndCode(){messages.tick();return SMTP.messages.stream().filter(m->m.to.contains(no.toLowerCase(Locale.ROOT))).map(m->m.text).map(t->Pattern.compile("验证码：(\\d{6})").matcher(t)).filter(Matcher::find).map(m->m.group(1)).reduce((a,b)->b).orElseThrow();}
    private void complete(UUID flow,String code){recovery.complete(Sql.map("flowId",flow.toString(),"code",code,"newPassword","Recovered8"),source,UUID.randomUUID());}
    private void ageResend(UUID flow){db.update("UPDATE iam.password_recovery SET sent_at=CURRENT_TIMESTAMP-interval '61 seconds' WHERE id=?",flow);}
    private Commands.Context context(String type,UUID id,String action,Integer version){var proof=identity.reauthenticate(admin,Sql.map("password",System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"),"purpose","MANAGE","objectIds",List.of(id.toString()),"scopeItems",List.of(Sql.map("objectType",type,"objectId",id.toString(),"actions",List.of(action)))),UUID.randomUUID());return new Commands.Context(admin,UUID.randomUUID(),UUID.randomUUID(),"TEST "+action+" "+id,proof.get("verificationToken").toString(),version);}
    private Map<String,Object> reset(Commands.Context ctx){return commands.run(ctx,Sql.map("reason","合成账号恢复验收"),"USER",uid,"RESET_PASSWORD",()->ctx.actor().require("PLATFORM.USERS_MANAGE"),()->recovery.reset(ctx,uid,Sql.map("reason","合成账号恢复验收")),key->Sql.map("objectId",key,"state","SUCCEEDED"));}
    private String state(UUID id){return db.required("SELECT state FROM ops.account_message WHERE id=?",id).get("state").toString();}
    private Map<String,Object> pending(UUID flow){return db.required("SELECT * FROM ops.account_message WHERE challenge_id=? ORDER BY created_at DESC,id LIMIT 1",flow);}

    @Test void recoveryConsumesCodeAndRevokesOldSession(){String old=identity.login(Sql.map("employeeNo",no,"password",password),UUID.randomUUID(),source).get("token").toString();UUID flow=request();String code=sendAndCode();complete(flow,code);assertThrows(ApiFault.class,()->identity.authenticate(old));assertThrows(CredentialFault.class,()->complete(flow,code));assertNotNull(identity.login(Sql.map("employeeNo",no,"password","Recovered8"),UUID.randomUUID(),source));assertNull(db.required("SELECT payload FROM ops.account_message WHERE challenge_id=?",flow).get("payload"));}
    @Test void knownUnknownDisabledAndRateLimitedHaveSameAcceptanceShape(){var known=recovery.request(Sql.map("employeeNo",no),source,UUID.randomUUID());var missing=recovery.request(Sql.map("employeeNo","absent-"+UUID.randomUUID()),source,UUID.randomUUID());assertEquals(known.keySet(),missing.keySet());assertEquals(known.get("state"),missing.get("state"));db.update("UPDATE iam.user_account SET status='DISABLED' WHERE id=?",uid);assertEquals(known,recovery.request(Sql.map("employeeNo",no),source,UUID.randomUUID()));assertFalse(Crypto.json(known).contains("@"));assertEquals(0,db.count("SELECT count(*) FROM ops.account_message WHERE challenge_id=?",missing.get("flowId")));}
    @Test void resendInvalidatesOldCodeWithoutResettingFiveFailures(){UUID flow=request();String old=sendAndCode();String wrong=old.equals("000000")?"111111":"000000";for(int i=0;i<2;i++)assertThrows(CredentialFault.class,()->complete(flow,wrong));ageResend(flow);recovery.request(Sql.map("employeeNo",no,"flowId",flow.toString()),source,UUID.randomUUID());String next=sendAndCode();assertEquals(2,db.count("SELECT failures FROM iam.password_recovery WHERE id=?",flow));if(!old.equals(next))assertThrows(CredentialFault.class,()->complete(flow,old));while(db.count("SELECT failures FROM iam.password_recovery WHERE id=?",flow)<5)assertThrows(CredentialFault.class,()->complete(flow,next.equals("000000")?"111111":"000000"));assertThrows(CredentialFault.class,()->complete(flow,next));ageResend(flow);long before=db.count("SELECT count(*) FROM ops.account_message WHERE challenge_id=?",flow);recovery.request(Sql.map("employeeNo",no,"flowId",flow.toString()),source,UUID.randomUUID());assertEquals(before,db.count("SELECT count(*) FROM ops.account_message WHERE challenge_id=?",flow));}
    @Test void resendIntervalHourAndDayCapsUseSharedDatabase(){UUID flow=request();request();assertEquals(1,db.count("SELECT count(*) FROM ops.recovery_send_event WHERE account_bucket=(SELECT account_bucket FROM iam.password_recovery WHERE id=?)",flow));for(int i=0;i<6;i++){ageResend(flow);request();}assertEquals(5,db.count("SELECT count(*) FROM ops.recovery_send_event WHERE account_bucket=(SELECT account_bucket FROM iam.password_recovery WHERE id=?)",flow));db.update("UPDATE ops.recovery_send_event SET created_at=CURRENT_TIMESTAMP-interval '61 minutes' WHERE account_bucket=(SELECT account_bucket FROM iam.password_recovery WHERE id=?)",flow);for(int i=0;i<6;i++){ageResend(flow);request();}assertEquals(10,db.count("SELECT count(*) FROM ops.recovery_send_event WHERE account_bucket=(SELECT account_bucket FROM iam.password_recovery WHERE id=?)",flow));}
    @Test void expiryEmailChangeAndDisableCloseAccess(){UUID flow=request();String code=sendAndCode();db.update("UPDATE iam.user_account SET company_email=? WHERE id=?","changed-"+uid+"@biolink.com",uid);assertThrows(CredentialFault.class,()->complete(flow,code));db.update("UPDATE iam.password_recovery SET expires_at=CURRENT_TIMESTAMP-interval '1 second' WHERE id=?",flow);assertThrows(CredentialFault.class,()->complete(flow,code));messages.tick();assertNotEquals("SUCCEEDED",state((UUID)pending(flow).get("id")));}
    @Test void resetIsIdempotentAndTemporaryPasswordIsOneUse(){String old=identity.login(Sql.map("employeeNo",no,"password",password),UUID.randomUUID(),source).get("token").toString();UUID flow=request();var ctx=context("USER",uid,"RESET_PASSWORD",1);reset(ctx);reset(ctx);assertEquals(1,db.count("SELECT count(*) FROM ops.account_message WHERE user_id=? AND kind='TEMP_PASSWORD'",uid));assertThrows(ApiFault.class,()->identity.authenticate(old));messages.tick();String temporary=SMTP.messages.stream().filter(m->m.to.contains(no.toLowerCase(Locale.ROOT))&&m.text.contains("临时密码：")).map(m->m.text.split("临时密码：")[1].split("\\s")[0]).findFirst().orElseThrow();var session=identity.login(Sql.map("employeeNo",no,"password",temporary),UUID.randomUUID(),source);Actor user=identity.authenticate(session.get("token").toString());assertTrue(user.setupRequired());identity.password(user,Sql.map("temporaryPassword",temporary,"newPassword","Recovered8"),true,UUID.randomUUID());assertThrows(CredentialFault.class,()->identity.login(Sql.map("employeeNo",no,"password",temporary),UUID.randomUUID(),source));assertNotNull(db.required("SELECT closed_at FROM iam.password_recovery WHERE id=?",flow).get("closed_at"));}
    @Test void resetRequiresAdministratorAndFreshVersion(){Actor employee=identity.authenticate(identity.login(Sql.map("employeeNo",no,"password",password),UUID.randomUUID(),source).get("token").toString());var fake=new Commands.Context(employee,UUID.randomUUID(),UUID.randomUUID(),"TEST RESET",null,1);assertThrows(ApiFault.class,()->reset(fake));var stale=context("USER",uid,"RESET_PASSWORD",99);assertThrows(ApiFault.class,()->reset(stale));assertEquals(0,db.count("SELECT count(*) FROM ops.account_message WHERE user_id=?",uid));}
    @Test void smtpRejectRetriesThreeTimesAndUnknownDoesNotRepeat(){UUID flow=request();SMTP.mode="REJECT";Map<String,Object> task=messages.claim();UUID id=(UUID)task.get("id");messages.deliver(task);assertEquals("RETRY",state(id));for(int i=0;i<3;i++){db.update("UPDATE ops.account_message SET next_run_at=CURRENT_TIMESTAMP WHERE id=?",id);messages.deliver(messages.claim());}assertEquals("MANUAL",state(id));assertEquals(4,db.count("SELECT attempts FROM ops.account_message WHERE id=?",id));ageResend(flow);request();SMTP.mode="UNKNOWN";task=messages.claim();id=(UUID)task.get("id");messages.deliver(task);assertEquals("MANUAL",state(id));assertEquals("DELIVERY_UNKNOWN",db.required("SELECT last_error FROM ops.account_message WHERE id=?",id).get("last_error"));assertTrue(messages.claim()==null,"不应重复领取消息");}
    @Test void workerLeaseLossAndConcurrentClaimDoNotDuplicate(){UUID flow=request();var work=messages.claim();assertNotNull(work);assertTrue(messages.claim()==null,"不应重复领取消息");UUID id=(UUID)work.get("id");db.update("UPDATE ops.account_message SET lease_until=CURRENT_TIMESTAMP-interval '1 second' WHERE id=?",id);assertTrue(messages.claim()==null,"不应重复领取消息");assertEquals("MANUAL",state(id));messages.deliver(work);assertEquals(0,SMTP.messages.size());}
    @Test void lockoutAlertsUseSignedRobotAndHourlyDedup(){for(int round=0;round<4;round++){for(int attempt=0;attempt<5;attempt++)assertThrows(CredentialFault.class,()->identity.login(Sql.map("employeeNo",no,"password","wrong"),UUID.randomUUID(),source));assertThrows(CredentialFault.class,()->identity.login(Sql.map("employeeNo",no,"password",password),UUID.randomUUID(),source));db.update("UPDATE ops.auth_throttle SET window_start=CURRENT_TIMESTAMP-interval '16 minutes',blocked_until=NULL WHERE blocked_until IS NOT NULL");}assertEquals(1,db.count("SELECT count(*) FROM ops.account_message WHERE user_id=? AND kind='LOGIN_LOCKS'",uid));messages.tick();assertEquals(1,ROBOT.bodies.size());assertFalse(ROBOT.bodies.getFirst().contains(no));assertTrue(ROBOT.signatureValid);}
    @Test void businessMailUsesMinimalTemplateAndRequiresTlsByDefault(){business.send(new BusinessMessageTransport.Message(UUID.randomUUID(),UUID.randomUUID(),uid,no.toLowerCase(Locale.ROOT)+"@biolink.com","TASK_AVAILABLE","WORKFLOW_STEP",UUID.randomUUID()));assertEquals("SMTP",business.channel());assertFalse(business.replaySafe());assertTrue(SMTP.messages.getFirst().text.contains("https://"));assertFalse(SMTP.messages.getFirst().text.contains("验证码"));MockEnvironment env=new MockEnvironment().withProperty("platform.mailbox",System.getenv("IMS_PLATFORM_MAILBOX_ROOT")).withProperty("platform.messaging.mail-mode","SMTP").withProperty("platform.messaging.smtp.host","127.0.0.1").withProperty("platform.messaging.smtp.port",Integer.toString(SMTP.port())).withProperty("platform.messaging.smtp.from","service@biolink.com");MailGateway secure=new MailGateway(env);int before=SMTP.messages.size();assertThrows(DeliveryFailure.class,()->secure.send(UUID.randomUUID(),"test@biolink.com","TLS验收","不得降级",Map.of()));assertEquals(before,SMTP.messages.size());}
    @Test void ciphertextAndAdministrativeQueueNeverExposeCode(){UUID flow=request();var task=pending(flow);String encrypted=task.get("payload").toString();assertTrue(encrypted.contains(":"));String code=sendAndCode();String queue=Crypto.json(messages.queue(admin,Map.of()));assertFalse(queue.contains(code));assertFalse(queue.contains(encrypted));assertFalse(queue.contains("@biolink.com"));assertFalse(queue.contains("password_hash"));}
    @Test void concurrentRecoveryRequestsShareOneCodeAndClaim() throws Exception {
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var starts=pool.invokeAll(List.<Callable<UUID>>of(this::request,this::request));
            assertEquals(starts.get(0).get(),starts.get(1).get());
            assertEquals(1,db.count("SELECT count(*) FROM ops.account_message WHERE user_id=?",uid));
            var claims=pool.invokeAll(List.<Callable<Map<String,Object>>>of(messages::claim,messages::claim));
            assertEquals(1,claims.stream().filter(f->{try{return f.get()!=null;}catch(Exception e){throw new RuntimeException(e);}}).count());
        }
    }
    @Test void manualRetryPreservesTotalAttemptsAndCannotReviveExpiredCode(){
        UUID flow=request();SMTP.mode="REJECT";UUID id=(UUID)pending(flow).get("id");
        for(int i=0;i<4;i++){db.update("UPDATE ops.account_message SET next_run_at=CURRENT_TIMESTAMP WHERE id=?",id);messages.deliver(messages.claim());}
        int version=(int)db.count("SELECT row_version FROM ops.account_message WHERE id=?",id);var ctx=context("NOTIFICATION_DELIVERY",id,"RETRY_ACCOUNT_MESSAGE",version);Map<String,Object> body=Sql.map("reason","核实本机拒绝后重试");
        commands.run(ctx,body,"NOTIFICATION_DELIVERY",id,"RETRY_ACCOUNT_MESSAGE",()->admin.require("PLATFORM.USERS_MANAGE"),()->messages.retry(ctx,id,body),key->Sql.map("objectId",key));
        SMTP.mode="NORMAL";messages.tick();assertEquals("SENT",state(id));assertEquals(5,db.count("SELECT attempts FROM ops.account_message WHERE id=?",id));
    }
    @Test void realTlsMailAcceptsTrustedLocalCertificate() throws Exception {
        // 只在测试作用域信任本批生成的localhost证书，正式适配器仍使用默认信任链与主机名校验。
        KeyStore keys=KeyStore.getInstance("PKCS12");char[] pass=System.getenv("IMS_PLATFORM_TLS_PASSWORD").toCharArray();
        try(var input=Files.newInputStream(Path.of(System.getenv("IMS_PLATFORM_TLS_KEYSTORE")))){keys.load(input,pass);}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(keys,pass);TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        SSLContext local=SSLContext.getInstance("TLS");local.init(km.getKeyManagers(),tm.getTrustManagers(),null);SSLContext original=SSLContext.getDefault();SSLContext.setDefault(local);
        try(var sink=new SmtpSink(local)){
            MockEnvironment env=new MockEnvironment().withProperty("platform.mailbox",System.getenv("IMS_PLATFORM_MAILBOX_ROOT")).withProperty("platform.messaging.mail-mode","SMTP").withProperty("platform.messaging.smtp.host","127.0.0.1").withProperty("platform.messaging.smtp.port",Integer.toString(sink.port())).withProperty("platform.messaging.smtp.security","TLS").withProperty("platform.messaging.smtp.from","service@biolink.com");
            String receipt=new MailGateway(env).send(UUID.randomUUID(),"test@biolink.com","TLS专项","本机加密传输验收",Map.of());assertTrue(receipt.startsWith("SMTP:"));assertEquals(1,sink.messages.size());
        }finally{SSLContext.setDefault(original);Arrays.fill(pass,'\0');}
    }
    @Test void changingBoundMailboxClosesPreviousRecovery() {
        UUID flow=request();String code=sendAndCode();var ctx=context("USER",uid,"UPDATE_USER",1);Map<String,Object> body=Sql.map("companyEmail","changed-"+uid+"@biolink.com");
        commands.run(ctx,body,"USER",uid,"UPDATE_USER",()->admin.require("PLATFORM.USERS_MANAGE"),()->directory.updateUser(ctx,uid,body),directory::user);
        assertNotNull(db.required("SELECT closed_at FROM iam.password_recovery WHERE id=?",flow).get("closed_at"));assertThrows(CredentialFault.class,()->complete(flow,code));
    }
    @AfterAll void close(){SMTP.close();ROBOT.server.stop(0);}

    /** 本机协议接收器仅实现SMTP服务器应答；发送实现仍使用正式JavaMail。 */
    static final class SmtpSink implements AutoCloseable {
        final ServerSocket server;final List<Mail> messages=new CopyOnWriteArrayList<>();volatile String mode="NORMAL";
        SmtpSink(){this(null);}
        SmtpSink(SSLContext ssl){try{server=ssl==null?new ServerSocket(0,20,InetAddress.getLoopbackAddress()):ssl.getServerSocketFactory().createServerSocket(0,20,InetAddress.getLoopbackAddress());Thread.ofPlatform().daemon().start(()->{while(!server.isClosed())try{Socket socket=server.accept();Thread.ofPlatform().daemon().start(()->receive(socket));}catch(IOException ignored){}});}catch(IOException e){throw new UncheckedIOException(e);}}
        int port(){return server.getLocalPort();}
        void receive(Socket socket){try(socket){socket.setSoTimeout(15000);var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));var writer=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8),true);writer.print("220 localhost isolated SMTP\r\n");writer.flush();String line;while((line=reader.readLine())!=null){String command=line.toUpperCase(Locale.ROOT);if(command.startsWith("EHLO")||command.startsWith("HELO"))writer.print("250 localhost\r\n");else if(command.startsWith("MAIL")&&mode.equals("REJECT"))writer.print("550 isolated rejection\r\n");else if(command.equals("DATA")){writer.print("354 End with dot\r\n");writer.flush();StringBuilder raw=new StringBuilder();while((line=reader.readLine())!=null&&!line.equals("."))raw.append(line.startsWith("..")?line.substring(1):line).append("\r\n");MimeMessage message=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(raw.toString().getBytes(StandardCharsets.UTF_8)));messages.add(new Mail(message.getAllRecipients()[0].toString(),message.getContent().toString()));if(mode.equals("UNKNOWN"))return;writer.print("250 accepted\r\n");}else if(command.equals("QUIT")){writer.print("221 bye\r\n");writer.flush();return;}else writer.print("250 OK\r\n");writer.flush();}}catch(Exception ignored){/* 故障注入主动断连，不打印凭据或邮件原文。 */}}
        public void close(){try{server.close();}catch(IOException ignored){}}
    }
    record Mail(String to,String text){}
    /** 本机钉钉接收器校验真实签名、固定安全模板和业务结果码。 */
    static final class RobotSink {
        final HttpServer server;final List<String> bodies=new CopyOnWriteArrayList<>();volatile boolean signatureValid;volatile int error;
        RobotSink(){try{server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);server.createContext("/robot/send",exchange->{Map<String,String> query=new HashMap<>();for(String pair:exchange.getRequestURI().getRawQuery().split("&")){String[] p=pair.split("=",2);query.put(p[0],URLDecoder.decode(p[1],StandardCharsets.UTF_8));}String expected=Base64.getEncoder().encodeToString(HexFormat.of().parseHex(Crypto.hmac("isolated-robot-secret",query.get("timestamp")+"\nisolated-robot-secret")));signatureValid=Crypto.equal(expected,query.get("sign"));bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] result=("{\"errcode\":"+(signatureValid?error:310000)+"}").getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,result.length);exchange.getResponseBody().write(result);exchange.close();});server.start();}catch(IOException e){throw new UncheckedIOException(e);}}
        int port(){return server.getAddress().getPort();}
    }
}
