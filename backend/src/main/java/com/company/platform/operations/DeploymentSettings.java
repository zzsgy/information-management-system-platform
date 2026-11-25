package com.company.platform.operations;

import com.company.platform.*;
import jakarta.annotation.PostConstruct;
import org.springframework.core.env.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import java.nio.file.*;
import java.util.*;

/** IT维护实际运行参数；秘密只指定私有文件位置，不经过网页传输或审计正文。 */
@Service
public class DeploymentSettings {
    private final Sql db;
    private final IdentityService identity;
    private final ConfigurableEnvironment env;
    private volatile Map<String,Object> current=Map.of();
    /** 字段描述同时约束API和表单，避免网页出现无法生效的配置项。 */
    public record Field(String key,String label,String type,String fallback) {}
    /** 默认开启，仅控制公盘网页上传的 ClamAV 检测。 */
    public static final String DRIVE_UPLOAD_VIRUS_SCAN="platform.scanner.drive-upload-virus-scan";
    /** 控制队列中的文件合并、校验和预览。 */
    public static final String FILE_WORKER_ENABLED="platform.file-worker.enabled";
    private static Field f(String key,String label,String type,String fallback){return new Field("platform."+key,label,type,fallback);}
    public static final Map<String,List<Field>> FIELDS=new LinkedHashMap<>();
    static {
        FIELDS.put("SCANNER",List.of(f("scanner.executable","ClamAV检测程序 clamscan.exe","file",""),f("scanner.database","官方病毒库目录（main、daily）","directory",""),f("scanner.temporary","检测临时目录","directory",""),f("scanner.timeout-seconds","检测超时（秒）","number","300"),new Field(FILE_WORKER_ENABLED,"启用后台文件处理","boolean","false"),new Field(DRIVE_UPLOAD_VIRUS_SCAN,"公盘上传病毒检测","boolean","true"),f("scan-intake.worker-enabled","启用扫描件检测处理","boolean","true")));
        FIELDS.put("STORAGE",List.of(f("storage.primary","原件主目录","directory",""),f("storage.secondary","原件副本目录","directory","")));
        FIELDS.put("READING",List.of(f("reading.java","Java程序 java.exe","file",""),f("reading.worker-jar","PDF隔离处理程序 JAR","file",""),f("reading.temporary","阅读临时目录","directory",""),f("reading.audit-root","阅读与交付回执目录","directory",""),f("export.primary","导出副本主目录","directory",""),f("export.secondary","导出副本次目录","directory",""),f("export.temporary","导出临时目录","directory","")));
        FIELDS.put("OFFICE",List.of(f("office.enabled","启用Office文档服务","boolean","false"),f("office.browser-origin","浏览器访问Office的地址","origin",""),f("office.server-origin","服务器访问Office的地址","origin",""),f("office.callback-origin","本平台回调地址","origin",""),f("office.expected-version","已验收的Office版本","text",""),f("office.inbox-key-file","请求签名密钥文件","file",""),f("office.outbox-key-file","回调签名密钥文件","file",""),f("office.temporary","Office候选文件临时目录","directory","")));
        FIELDS.put("PROTECTION",List.of(f("protection.mode","部署保护方式","mode","LEGACY"),f("protection.evidence-file","外部保护代理证据文件","file",""),f("protection.key-file","外部保护代理密钥文件","file",""),f("protection.node-id","外部保护节点编号","text",""),f("protection.backup-hours","单机备份最长间隔（小时）","number","24")));
        FIELDS.put("MAINTENANCE",List.of(f("operations.enabled","启用维护任务","boolean","false"),f("operations.probe-directory","维护检查临时目录","directory",""),f("maintenance.postgres-bin","PostgreSQL工具目录（bin）","directory",""),f("maintenance.backup-root","独立备份及恢复演练输出目录","directory",""),f("maintenance.restore-port","隔离恢复应用端口（不是数据库端口）","number","18599"),f("maintenance.restore-credentials-file","隔离恢复私有账号配置JSON","file",""),f("maintenance.node","Node程序 node.exe（独立执行器使用）","file",""),f("maintenance.bootstrap","本服务的私有启动配置JSON","file",""),f("maintenance.windows-service","Windows应用服务名称","service","")));
    }
    public DeploymentSettings(Sql db,IdentityService identity,ConfigurableEnvironment env){this.db=db;this.identity=identity;this.env=env;}
    private void authorize(Actor actor){var live=identity.background(actor.sessionId());if(!live.systemAdministrator())throw ApiFault.denied();live.require("PLATFORM.OPERATIONS_MANAGE");}
    @PostConstruct public void refresh(){
        var values=new LinkedHashMap<String,Object>();
        if(!"true".equalsIgnoreCase(System.getenv("IMS_PLATFORM_ISOLATED_RESTORE")))
        for(var row:db.rows("SELECT settings::text FROM ops.deployment_settings")) values.putAll(Crypto.JSON.readValue(row.get("settings").toString(),Map.class));
        current=Map.copyOf(values);
        env.getPropertySources().addFirst(new MapPropertySource("managed-deployment",values));
    }
    public String text(String key,String fallback){return Objects.toString(current.getOrDefault(key,fallback),"");}
    public boolean flag(String key,boolean fallback){return Boolean.parseBoolean(text(key,String.valueOf(fallback)));}
    public String effective(String key){return env.getProperty(key,"");}
    public Map<String,Object> list(Actor actor){authorize(actor);return Sql.map("items",db.rows("SELECT * FROM ops.deployment_settings ORDER BY id").stream().map(this::view).toList());}
    public Map<String,Object> get(Actor actor,UUID id){authorize(actor);return view(db.required("SELECT * FROM ops.deployment_settings WHERE id=?",id));}
    private Map<String,Object> view(Map<String,Object> row){
        var fields=FIELDS.get(row.get("section").toString());var values=new LinkedHashMap<String,Object>();
        for(var field:fields)values.put(field.key(),env.getProperty(field.key(),field.fallback()));
        return Sql.map("id",row.get("id"),"section",row.get("section"),"rowVersion",row.get("row_version"),"fields",fields,"values",values);
    }
    /** 完整分组保存；版本、再验证和审计由Commands共享事务保证。保存不冒充环境检查通过。 */
    public Map<String,Object> save(Commands.Context ctx,UUID id,Map<String,Object> body){
        authorize(ctx.actor());Inputs.only(body,"values","reason");Inputs.text(body,"reason",1000);
        var row=db.required("SELECT * FROM ops.deployment_settings WHERE id=? FOR UPDATE",id);Commands.version(ctx,row);
        if(!(body.get("values") instanceof Map<?,?> supplied))throw ApiFault.invalid("请填写配置字段");
        var fields=FIELDS.get(row.get("section").toString());
        if(!supplied.keySet().equals(fields.stream().map(Field::key).collect(java.util.stream.Collectors.toSet())))throw ApiFault.invalid("配置字段不完整或包含未知字段，请刷新页面");
        var values=new LinkedHashMap<String,Object>();
        for(var field:fields){
            String value=Objects.toString(supplied.get(field.key()),"").strip();
            if(value.length()>2048)throw ApiFault.invalid(field.label()+"过长");
            if(field.type().equals("boolean")&&!Set.of("true","false").contains(value))throw ApiFault.invalid(field.label()+"请选择启停状态");
            if(field.type().equals("number")){
                int min=field.key().endsWith("port")?1025:1,max=field.key().endsWith("port")?65535:field.key().endsWith("hours")?168:3600;
                Inputs.integer(value,0,min,max);
            }
            if(!value.isBlank()&&Set.of("file","directory").contains(field.type()))path(value,field.label());
            if(!value.isBlank()&&field.type().equals("origin"))OfficeOrigin.validate(value);
            if(field.type().equals("origin")&&value.endsWith("/"))value=value.substring(0,value.length()-1);
            if(field.type().equals("mode")&&!Set.of("LEGACY","SINGLE","EXTERNAL").contains(value))throw ApiFault.invalid("请选择部署保护方式");
            if(!value.isBlank()&&field.type().equals("service")&&!value.matches("[A-Za-z0-9_.-]{1,128}"))throw ApiFault.invalid("Windows服务名称仅支持字母、数字、下划线、点和短横线");
            values.put(field.key(),value);
        }
        if(row.get("section").equals("SCANNER")&&!values.get("platform.scanner.executable").toString().isBlank()){
            Path executable=path(values.get("platform.scanner.executable").toString(),"检测程序");
            if(!executable.getFileName().toString().matches("(?i)clamscan(\\.exe)?"))throw ApiFault.invalid("请选择clamscan检测程序");
            try{values.put("platform.scanner.executable-sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executable))));}
            catch(Exception failure){throw new ApiFault(400,"SCANNER_EXECUTABLE_UNAVAILABLE","无法读取检测程序，请检查文件及服务账号权限",failure);}
        }
        validateRoots(values);
        // 已有原件、分片或导出时必须先做离线迁移，不能把改路径误当作迁移完成。
        if(Set.of("STORAGE","READING").contains(row.get("section"))&&(db.count("SELECT count(*) FROM storage.object")>0||db.count("SELECT count(*) FROM storage.upload_session WHERE state IN ('OPEN','CHECK_PENDING')")>0))
            for(String key:List.of("platform.storage.primary","platform.storage.secondary","platform.export.primary","platform.export.secondary","platform.reading.audit-root"))
                if(values.containsKey(key)&&!values.get(key).equals(env.getProperty(key,"")))throw ApiFault.conflict("已有文件或回执，不能在线更换持久目录；请先按部署说明停机迁移并核验，原目录不会被移动或删除");
        db.update("UPDATE ops.deployment_settings SET settings=?::jsonb,row_version=row_version+1,changed_by=?,changed_at=CURRENT_TIMESTAMP WHERE id=?",Crypto.json(values),ctx.actor().userId(),id);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){refresh();}});
        return Sql.map("id",id,"rowVersion",((Number)row.get("row_version")).intValue()+1);
    }
    public static Path path(String value,String label){
        try{Path p=Path.of(value).normalize();if(!p.isAbsolute()||p.getParent()==null)throw new IllegalArgumentException();for(Path part=p;part!=null;part=part.getParent())if(Files.isSymbolicLink(part))throw new IllegalArgumentException();return p;}
        catch(RuntimeException failure){throw ApiFault.invalid(label+"必须为非磁盘根目录的绝对路径，且不能经过符号链接");}
    }
    private void validateRoots(Map<String,Object> update){
        var roots=new LinkedHashMap<String,Path>();
        for(var fields:FIELDS.values())for(var field:fields)if(field.type().equals("directory")&&!field.key().endsWith("postgres-bin")&&!field.key().equals("platform.scanner.database")){
            String value=Objects.toString(update.getOrDefault(field.key(),env.getProperty(field.key(),"")));if(!value.isBlank())roots.put(field.key(),path(value,field.label()));
        }
        for(String key:List.of("platform.security-ledger","platform.mailbox","platform.notifications.local-directory","platform.audit-storage.primary","platform.audit-storage.secondary")){
            String value=env.getProperty(key,"");if(!value.isBlank())roots.put(key,path(value,key));
        }
        var entries=new ArrayList<>(roots.entrySet());for(int i=0;i<entries.size();i++)for(int j=i+1;j<entries.size();j++)if(entries.get(i).getValue().startsWith(entries.get(j).getValue())||entries.get(j).getValue().startsWith(entries.get(i).getValue()))throw ApiFault.invalid("目录不能相同或互相包含："+entries.get(i).getKey()+" 与 "+entries.get(j).getKey());
    }
    /** 地址规则与Office适配器一致：正式入口HTTPS，本机隔离测试可HTTP。 */
    private static class OfficeOrigin {static void validate(String value){try{var uri=java.net.URI.create(value);if(uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!(uri.getPath().isEmpty()||uri.getPath().equals("/"))||!(uri.getScheme().equals("https")||uri.getScheme().equals("http")&&Set.of("localhost","127.0.0.1").contains(uri.getHost())))throw new IllegalArgumentException();}catch(RuntimeException e){throw ApiFault.invalid("Office地址必须为HTTPS来源地址，不包含账号、路径或查询参数");}}}
}
