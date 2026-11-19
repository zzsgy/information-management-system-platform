package com.company.platform.storage;

import com.company.platform.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.concurrent.*;

/** ONLYOFFICE专用适配器：浏览器配置与服务器回调使用不同密钥，拒绝重定向和任意地址抓取。 */
@Component
public class OfficeGateway {
    /** 默认关闭；独立配置完整且实际版本匹配后才能使用。 */
    private final boolean enabled;
    // 原生多人检查点须单独验收；关闭只影响编辑中发布，最终保存仍由状态2回调确认。
    private final boolean checkpointEnabled;
    /** 浏览器入口、服务端命令/候选入口和本平台回调入口都是可信部署固定值。 */
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ServiceIdentities managedIdentities;
    private final String browserOrigin,serverOrigin,callbackOrigin,version,inboxPath,outboxPath,tempPath,serviceId;
    /** 在线处理上限独立于公盘5GiB上传，防止文档服务耗尽内存；单位字节。 */
    private final long maximum;
    /** 不跟随重定向，避免受信服务返回任意地址后继承服务器凭据。 */
    private final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    public OfficeGateway(@Value("${platform.office.enabled:false}") boolean enabled,@Value("${platform.office.browser-origin:}") String browserOrigin,
        @Value("${platform.office.server-origin:}") String serverOrigin,@Value("${platform.office.callback-origin:}") String callbackOrigin,
        @Value("${platform.office.expected-version:}") String version,@Value("${platform.office.inbox-key-file:}") String inboxPath,
        @Value("${platform.office.outbox-key-file:}") String outboxPath,@Value("${platform.office.temporary:}") String tempPath,
        @Value("${platform.office.service-id:}") String serviceId,@Value("${platform.office.max-bytes:268435456}") long maximum,@Value("${platform.office.checkpoint-enabled:false}") boolean checkpointEnabled) {
        this.enabled=enabled;this.checkpointEnabled=checkpointEnabled;this.browserOrigin=browserOrigin;this.serverOrigin=serverOrigin;this.callbackOrigin=callbackOrigin;this.version=version;this.inboxPath=inboxPath;this.outboxPath=outboxPath;this.tempPath=tempPath;this.serviceId=serviceId;this.maximum=maximum;
    }
    /** 仅返回非秘密可用性；未部署不产生虚假的编辑成功页面。 */
    public Map<String,Object> status() {return Sql.map("enabled",enabled,"state",enabled?"CONFIGURED":"NOT_CONFIGURED","maximumBytes",maximum,"expectedVersion",version,"checkpointEnabled",checkpointEnabled);}
    public boolean checkpointEnabled(){return checkpointEnabled;}
    public void requireCheckpoint(){if(!checkpointEnabled)throw new ApiFault(409,"OFFICE_CHECKPOINT_DISABLED","当前仅支持所有参与人退出后的最终保存，请关闭编辑并等待保存结果");}
    /** 启用前校验所有地址、独立密钥和空间限制；HTTP仅允许本机回环隔离演练。 */
    public void require() {
        if(!enabled)throw new ApiFault(503,"OFFICE_NOT_CONFIGURED","Office文档服务尚未配置");
        origin(browserOrigin);origin(serverOrigin);origin(callbackOrigin);
        if(version.isBlank()||maximum<1||maximum>5368709120L||Crypto.equal(secret(inboxPath),secret(outboxPath)))throw SystemFailure.OFFICE_CONFIGURATION_INVALID.fault();
        Inputs.uuid(managedIdentities==null?serviceId:managedIdentities.configured("platform.office.service-id"));
    }
    /** CSP仅添加配置中精确的编辑器来源，不开启通配脚本或任意iframe。 */
    public String browserOrigin() {if(!enabled)return "";origin(browserOrigin);return browserOrigin;}
    /** 稳定回调URL在编辑开始时固定，不能由浏览器覆盖。 */
    public String callback(UUID session){require();return callbackOrigin+"/integrations/onlyoffice/callback/"+session;}
    /** 源文件地址仍需要文档服务outbox签名；看到URL的浏览器也不能直接下载。 */
    public String source(UUID session){require();return callbackOrigin+"/integrations/onlyoffice/source/"+session;}
    /** 转换源与编辑源分别绑定固定版本，不向浏览器签发原件下载凭据。 */
    public String conversionSource(UUID conversion){require();return callbackOrigin+"/integrations/onlyoffice/conversion-source/"+conversion;}
    /** 档案转换使用独立取源上下文，不能用公盘转换ID读取档案。 */
    public String archiveConversionSource(UUID conversion){require();return callbackOrigin+"/integrations/onlyoffice/archive-source/"+conversion;}
    public UUID service(){require();return Inputs.uuid(managedIdentities==null?serviceId:managedIdentities.configured("platform.office.service-id"));}
    public long maximum(){return maximum;}
    /** 浏览器只取得签过名的固定配置，不取得任何签名密钥。 */
    public String sign(Map<String,Object> payload){require();return jwt(payload,secret(inboxPath));}
    /** 使用回调专用密钥验证原生JWT，完全采用已签名负载，不合并未签名JSON字段。 */
    @SuppressWarnings("unchecked")
    public Map<String,Object> nativePayload(String token,boolean header) {
        require();Map<String,Object> payload=verify(token,secret(outboxPath));Object nested=payload.get("payload");
        if(header){if(!(nested instanceof Map<?,?>))throw ApiFault.denied();return (Map<String,Object>)nested;}
        return payload;
    }
    /** 校验实际文档服务版本，防止部署升级后继续按未验收的回调协议工作。 */
    public void verifyVersion(){var result=command(Map.of("c","version"));if(!version.equals(result.get("version")))throw new ApiFault(503,"OFFICE_VERSION_MISMATCH","Office服务版本与已配置版本不一致");}
    /** 命令返回成功仅表示已接收，平台保存成功由后续回调和真实检测决定。 */
    @SuppressWarnings("unchecked") public Map<String,Object> command(Map<String,Object> payload) {
        require();try {var body=new LinkedHashMap<>(payload);body.put("token",sign(payload));var request=HttpRequest.newBuilder(URI.create(serverOrigin+"/command")).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Crypto.json(body))).build();
            var response=exchange(request,HttpResponse.BodyHandlers.ofByteArray(),65536,15);if(response.statusCode()!=200)throw SystemFailure.OFFICE_REQUEST_FAILED.fault();return Crypto.JSON.readValue(response.body(),Map.class);
        }catch(ApiFault fault){throw fault;}catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw new ApiFault(503,"OFFICE_UNAVAILABLE","Office服务请求失败，保存结果尚未确认，请先查询保存记录",failure);}
    }
    /** 固定key重试可接回文档服务已完成的结果；超时不能当作转换失败或原件损坏。 */
    @SuppressWarnings("unchecked") public Path convert(UUID conversion,String inputType,String outputType) throws IOException {
        return convert(conversion,inputType,outputType,conversionSource(conversion));
    }
    public Path convertArchive(UUID conversion,String inputType)throws IOException{return convert(conversion,inputType,"pdf",archiveConversionSource(conversion));}
    @SuppressWarnings("unchecked") private Path convert(UUID conversion,String inputType,String outputType,String source) throws IOException {
        require();String key="ims-convert-"+conversion;var body=Sql.map("async",false,"key",key,"filetype",inputType,"outputtype",outputType,"title","converted."+outputType,"url",source);body.put("token",sign(body));
        try {var request=HttpRequest.newBuilder(URI.create(serverOrigin+"/converter?shardkey="+key)).timeout(Duration.ofSeconds(120)).header("Content-Type","application/json").header("Accept","application/json").POST(HttpRequest.BodyPublishers.ofString(Crypto.json(body))).build();
            var response=exchange(request,HttpResponse.BodyHandlers.ofByteArray(),65536,120);if(response.statusCode()!=200)throw new ApiFault(503,"OFFICE_CONVERSION_UNAVAILABLE","Office转换服务暂不可用，请稍后重试");Map<String,Object> result=Crypto.JSON.readValue(response.body(),Map.class);
            if(result.get("error") instanceof Number error&&error.intValue()!=0)throw new ApiFault(422,"OFFICE_CONVERSION_FAILED","Office未能转换该文件，请检查格式、密码或原件内容");
            if(!Boolean.TRUE.equals(result.get("endConvert"))||!outputType.equals(result.get("fileType")))throw new ApiFault(503,"OFFICE_CONVERSION_PENDING","转换尚未确认完成，请稍后重试");return candidate(Inputs.text(result,"fileUrl",8192));
        }catch(InterruptedException failure){Thread.currentThread().interrupt();throw SystemFailure.OFFICE_REQUEST_FAILED.fault(failure);}
    }
    /** 下载限制同时覆盖正文大小与整体耗时，慢正文不能长期占住转换工作位。 */
    public Path candidate(String address) throws IOException {
        require();URI uri=URI.create(address),base=URI.create(serverOrigin);
        if(!Objects.equals(uri.getScheme(),base.getScheme())||!Objects.equals(uri.getHost(),base.getHost())||uri.getPort()!=base.getPort()||uri.getUserInfo()!=null||uri.getFragment()!=null||!uri.getPath().startsWith("/cache/files/")||uri.getPath().contains(".."))throw ApiFault.denied();
        Path directory=PrivateFiles.directory(tempPath),target=Files.createTempFile(directory,"office-",".candidate");PrivateFiles.restrict(target);boolean complete=false;
        try {var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).GET().build();var response=exchange(request,HttpResponse.BodyHandlers.ofFile(target),maximum,45);
            if(response.statusCode()!=200||Files.size(target)==0)throw SystemFailure.OFFICE_RESPONSE_INVALID.fault();complete=true;return target;
        }catch(InterruptedException failure){Thread.currentThread().interrupt();throw SystemFailure.OFFICE_REQUEST_FAILED.fault(failure);}finally{if(!complete)Files.deleteIfExists(target);}
    }
    /** 完成期限覆盖响应正文；超时取消网络订阅，不依赖只管响应头的request timeout。 */
    private <T> HttpResponse<T> exchange(HttpRequest request,HttpResponse.BodyHandler<T> handler,long limit,int seconds) throws IOException,InterruptedException {
        var pending=client.sendAsync(request,info->new LimitedBody<>(handler.apply(info),limit));
        try{return pending.get(seconds,TimeUnit.SECONDS);}catch(TimeoutException timeout){pending.cancel(true);throw new IOException("Office响应超时");}catch(ExecutionException failure){throw new IOException("Office响应未完成",failure.getCause());}catch(InterruptedException interrupted){pending.cancel(true);throw interrupted;}
    }
    /** 响应直接流入文件或有界JSON缓冲；超限时取消上游，避免先接收大文件再检查。 */
    private static final class LimitedBody<T> implements HttpResponse.BodySubscriber<T> {
        private final HttpResponse.BodySubscriber<T> target;private final long limit;private long received;private Flow.Subscription subscription;private boolean failed;
        LimitedBody(HttpResponse.BodySubscriber<T> target,long limit){this.target=target;this.limit=limit;}
        public CompletionStage<T> getBody(){return target.getBody();}
        public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;target.onSubscribe(subscription);}
        public void onNext(List<ByteBuffer> chunks){if(failed)return;for(var chunk:chunks)received+=chunk.remaining();if(received>limit){failed=true;subscription.cancel();target.onError(new IOException("Office响应超过体积限制"));return;}target.onNext(chunks);}
        public void onError(Throwable error){if(!failed){failed=true;target.onError(error);}}
        public void onComplete(){if(!failed)target.onComplete();}
    }
    /** 只接受无路径、无查询、无用户信息的固定来源，禁止把外部输入拼接到配置中。 */
    private void origin(String value){try{URI uri=URI.create(value);if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!uri.getPath().isEmpty()||!(uri.getScheme().equals("https")||(uri.getScheme().equals("http")&&Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost()))))throw SystemFailure.OFFICE_CONFIGURATION_INVALID.fault();}catch(IllegalArgumentException failure){throw SystemFailure.OFFICE_CONFIGURATION_INVALID.fault(failure);}}
    /** 密钥位于受限配置文件；禁用软链接，不记录内容。 */
    private String secret(String file){try{Path path=Path.of(file);if(!path.isAbsolute()||Files.isSymbolicLink(path)||Files.size(path)>4096)throw SystemFailure.OFFICE_CONFIGURATION_INVALID.fault();String value=Files.readString(path).strip();if(value.length()<32)throw SystemFailure.OFFICE_CONFIGURATION_INVALID.fault();return value;}catch(IOException|IllegalArgumentException failure){throw SystemFailure.OFFICE_CONFIGURATION_INVALID.fault(failure);}}
    /** 固定HS256，避免alg=none或算法混淆；时间声明存在时必须有效。 */
    @SuppressWarnings("unchecked") static Map<String,Object> verify(String token,String key) {
        try{if(token==null||token.length()>100000)throw ApiFault.denied();String[] parts=token.split("\\.",-1);if(parts.length!=3)throw ApiFault.denied();var decoder=Base64.getUrlDecoder();Map<String,Object> header=Crypto.JSON.readValue(new String(decoder.decode(parts[0]),StandardCharsets.UTF_8),Map.class);
            if(!"HS256".equals(header.get("alg"))||header.containsKey("crit")||!Crypto.equal(parts[2],signature(key,parts[0]+"."+parts[1])))throw ApiFault.denied();Map<String,Object> payload=Crypto.JSON.readValue(new String(decoder.decode(parts[1]),StandardCharsets.UTF_8),Map.class);long now=java.time.Instant.now().getEpochSecond();
            if(payload.containsKey("exp")&&(!(payload.get("exp") instanceof Number expiry)||expiry.longValue()<=now))throw ApiFault.denied();if(payload.containsKey("nbf")&&(!(payload.get("nbf") instanceof Number start)||start.longValue()>now))throw ApiFault.denied();return payload;
        }catch(ApiFault failure){throw failure;}catch(Exception invalid){throw ApiFault.denied();}
    }
    /** JWT只封装固定服务器生成的配置，不接受客户端自选算法。 */
    static String jwt(Map<String,Object> payload,String key){String value=encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}")+"."+encode(Crypto.json(payload));return value+"."+signature(key,value);}
    private static String encode(String value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    private static String signature(String key,String value){return Base64.getUrlEncoder().withoutPadding().encodeToString(HexFormat.of().parseHex(Crypto.hmac(key,value)));}
}
