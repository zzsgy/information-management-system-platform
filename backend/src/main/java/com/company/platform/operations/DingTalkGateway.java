package com.company.platform.operations;

import com.company.platform.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** 钉钉仅用于技术告警，固定模板不包含工号、档案题名、凭据或附件。 */
@Component
public class DingTalkGateway {
    private final String endpoint,secret,portal;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public DingTalkGateway(Environment env){endpoint=env.getProperty("platform.messaging.dingtalk.webhook","");secret=env.getProperty("platform.messaging.dingtalk.secret","");portal=env.getProperty("platform.messaging.portal-url","https://127.0.0.1:18443/platform.html");
        if(!endpoint.isBlank()){URI uri=URI.create(endpoint);boolean local=env.getProperty("platform.messaging.dingtalk.local-test",Boolean.class,false)&&env.getProperty("platform.isolation-confirmed",Boolean.class,false)&&"http".equals(uri.getScheme())&&Set.of("127.0.0.1","localhost","::1").contains(uri.getHost());if(!local&&!("https".equals(uri.getScheme())&&"oapi.dingtalk.com".equals(uri.getHost())&&"/robot/send".equals(uri.getPath())))throw new IllegalArgumentException("钉钉地址必须为官方机器人接口");if(secret.isBlank())throw new IllegalArgumentException("钉钉机器人必须配置加签密钥");}}
    public String mode(){return endpoint.isBlank()?"DISABLED":"DINGTALK";}
    /** 业务响应errcode为0才确认接受；未知网络结果不盲目重发。 */
    public void send(UUID id){send(id,"LOGIN_LOCKS");}
    /** 告警类别来自服务端枚举，机器人正文不带用户、档案或处理意见。 */
    public void send(UUID id,String kind){if(endpoint.isBlank())throw new DeliveryFailure(false);try{
        String timestamp=Long.toString(System.currentTimeMillis()),signature=Base64.getEncoder().encodeToString(HexFormat.of().parseHex(Crypto.hmac(secret,timestamp+"\n"+secret)));
        URI uri=URI.create(endpoint+(endpoint.contains("?")?"&":"?")+"timestamp="+timestamp+"&sign="+URLEncoder.encode(signature,StandardCharsets.UTF_8));
        String category=switch(kind){case "AUDIT_CAPACITY" -> "日志容量预警";case "OPS_PROTECTION" -> "保护检查异常";case "OPS_REPLICA" -> "副本校验异常";case "OPS_BACKUP" -> "独立备份失败";case "OPS_RESTORE" -> "恢复演练失败";case "OPS_TASK" -> "维护任务结果待核对";case "BULK_DOWNLOAD" -> "批量下载";case "BULK_PRINT" -> "批量打印";case "ACCESS_DENIED" -> "连续越权访问";default -> "账号重复锁定";};
        String body=Crypto.json(Sql.map("msgtype","text","text",Sql.map("content","信息管理系统安全告警\n级别：WARN\n类别："+category+"\n请由IT进入平台核对。\n"+portal+(kind.startsWith("OPS_")?"#/operations-console":"#/security")+"\n事件："+id)));
        var response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if(response.statusCode()!=200||response.body().length()>16384)throw new DeliveryFailure(true);
        if(Crypto.JSON.readTree(response.body()).path("errcode").asInt(-1)!=0)throw new DeliveryFailure(false);
    }catch(DeliveryFailure e){throw e;}catch(java.net.ConnectException e){throw new DeliveryFailure(false);}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new DeliveryFailure(true);}}
}
