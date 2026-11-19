package com.company.platform.operations;

import com.company.platform.*;
import com.company.platform.storage.*;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;
import java.time.Instant;
import java.nio.file.*;
import java.util.*;

/** IT 专用服务配置；身份维护不依赖当前文件保护状态，以便在服务未就绪时修复配置。 */
@Service
public class ServiceAdministration {
    @org.springframework.beans.factory.annotation.Autowired private DeploymentDiagnostics diagnostics;
    private final Sql db;
    private final IdentityService identity;
    private final ServiceIdentities identities;
    private final FileScanEngine scanner;
    private final ServiceHealth health;
    private final Environment env;
    public ServiceAdministration(Sql db,IdentityService identity,ServiceIdentities identities,FileScanEngine scanner,ServiceHealth health,Environment env) {
        this.db=db;this.identity=identity;this.identities=identities;this.scanner=scanner;this.health=health;this.env=env;
    }
    /** 不能只持有运维权限，必须仍为有效的平台 IT 管理员。 */
    public void authorize(Actor actor) {
        var current=identity.background(actor.sessionId());
        if(!current.systemAdministrator()) throw ApiFault.denied();
        current.require("PLATFORM.OPERATIONS_MANAGE");
    }
    public Map<String,Object> list(Actor actor) {
        authorize(actor);
        return Sql.map("items",db.rows("SELECT * FROM iam.service_binding ORDER BY id").stream().map(this::view).toList(),
                "departments",db.rows("SELECT id,name FROM iam.department WHERE status='ACTIVE' ORDER BY name,id"));
    }
    public Map<String,Object> get(Actor actor,UUID id) {authorize(actor);return view(db.required("SELECT * FROM iam.service_binding WHERE id=?",id));}

    /** 与业务命令同事务保存身份和绑定；保留原身份引用，禁止删除历史执行主体。 */
    public Map<String,Object> save(Commands.Context ctx,UUID id,Map<String,Object> body) {
        authorize(ctx.actor());
        Inputs.only(body,"name","departmentIds","validUntil","status","identityVersion","reason");
        String name=Inputs.text(body,"name",128);Inputs.text(body,"reason",1000);
        String status=Inputs.choice(body.get("status"),"ACTIVE","DISABLED");
        Instant until;
        try {until=Instant.parse(Inputs.text(body,"validUntil",64));}
        catch(java.time.DateTimeException failure){throw ApiFault.invalid("服务授权截止时间格式不正确");}
        if("ACTIVE".equals(status)&&!until.isAfter(Instant.now()))throw ApiFault.invalid("启用服务时，授权截止时间必须晚于当前时间");
        if(!(body.get("departmentIds") instanceof List<?> values)||values.isEmpty()||values.size()>500)
            throw ApiFault.invalid("请至少选择一个获准部门，最多500个");
        var departments=values.stream().map(Inputs::uuid).distinct().toList();
        String scope="{"+String.join(",",departments.stream().map(UUID::toString).toList())+"}";
        if(db.count("SELECT count(*) FROM iam.department WHERE status='ACTIVE' AND id=ANY(?::uuid[])",scope)!=departments.size())
            throw ApiFault.invalid("所选部门已停用或不存在，请刷新后重试");
        var binding=db.required("SELECT * FROM iam.service_binding WHERE id=? FOR UPDATE",id);
        Commands.version(ctx,binding);
        var spec=spec(binding.get("slot").toString());
        var existing=identityRow(binding);
        int version=Inputs.integer(body.get("identityVersion"),0,0,Integer.MAX_VALUE);
        if(existing!=null && version!=((Number)existing.get("row_version")).intValue())
            throw ApiFault.conflict("服务身份已被修改，请刷新后重试");
        boolean compatible=existing!=null && spec.get("type").equals(existing.get("service_type")) && spec.get("module").equals(existing.get("allowed_module"));
        UUID service=compatible?(UUID)existing.get("id"):UUID.randomUUID();
        String actions="OFFICE".equals(spec.get("type"))?"{VIEW,EDIT}":"{FILE_CHECK}";
        if(compatible) db.update("UPDATE iam.service_identity SET name=?,department_scope=?::uuid[],allowed_actions=?::varchar[],valid_until=?,status=?,row_version=row_version+1 WHERE id=?",
                name,scope,actions,java.sql.Timestamp.from(until),status,service);
        else db.update("INSERT INTO iam.service_identity(id,service_type,name,allowed_module,department_scope,allowed_actions,credential_ref,valid_until,status) VALUES(?,?,?,?,?::uuid[],?::varchar[],?,?,?)",
                service,spec.get("type"),name,spec.get("module"),scope,actions,"server-configuration:"+binding.get("slot"),java.sql.Timestamp.from(until),status);
        db.update("UPDATE iam.service_binding SET service_identity_id=?,row_version=row_version+1,changed_by=?,changed_at=CURRENT_TIMESTAMP WHERE id=?",service,ctx.actor().userId(),id);
        return get(ctx.actor(),id);
    }
    /** 实测已保存的服务器检测配置；检查请求不携带程序参数或密钥。 */
    public Map<String,Object> check(Actor actor,UUID request) {
        authorize(actor);
        var checks=new ArrayList<Map<String,Object>>();Path sample=null;
        try {
            String temporary=env.getProperty("platform.scanner.temporary","");
            if(temporary.isBlank())throw SystemFailure.SCANNER_NOT_CONFIGURED.fault();
            sample=Files.createTempFile(PrivateFiles.directory(temporary),"ims-service-check-",".txt");
            PrivateFiles.restrict(sample);Files.writeString(sample,"IMS harmless service configuration check");
            if(scanner.scan(sample)!=ClamFileScanner.Verdict.CLEAN)throw SystemFailure.SCANNER_UNAVAILABLE.fault();
            checks.add(Sql.map("name","文件检测程序与病毒库","ready",true,"message","实际检测通过"));
        } catch(Exception failure) {
            var fault=failure instanceof ApiFault known?known:SystemFailure.SCANNER_TEMP_UNAVAILABLE.fault(failure);
            FailureDiagnostics.log(request,"SERVICE_CONFIGURATION_CHECK",fault);
            checks.add(Sql.map("name","文件检测程序与病毒库","ready",false,"message",fault.getMessage(),"errorCode",fault.code));
        } finally {if(sample!=null)try{Files.deleteIfExists(sample);}catch(java.io.IOException failure){FailureDiagnostics.log(request,"SERVICE_CHECK_CLEANUP",SystemFailure.SCANNER_TEMP_UNAVAILABLE.fault(failure));}}
        boolean worker=env.getProperty("platform.file-worker.enabled",Boolean.class,false);
        checks.add(Sql.map("name","后台文件处理器","ready",worker,"message",worker?"已启用":"请进入部署配置的文件检测页签启用后台文件处理器"));
        checks.addAll(diagnostics.check(request));
        checks.forEach(check->check.put("requestId",request));
        health.runtimeChecks(checks);
        health.probe();
        return Sql.map("checks",checks,"health",health.status(),"requestId",request);
    }
    private Map<String,Object> view(Map<String,Object> binding) {
        var row=identityRow(binding);var spec=spec(binding.get("slot").toString());
        boolean compatible=row!=null&&spec.get("type").equals(row.get("service_type"))&&spec.get("module").equals(row.get("allowed_module"));
        return Sql.map("id",binding.get("id"),"slot",binding.get("slot"),"label",spec.get("label"),"rowVersion",binding.get("row_version"),
                "identityVersion",row==null?0:row.get("row_version"),"configured",compatible,"name",compatible?row.get("name"):spec.get("label"),
                "departmentIds",compatible?row.get("department_scope"):List.of(),"validUntil",compatible?row.get("valid_until"):null,
                "status",compatible?row.get("status"):"ACTIVE","source",binding.get("service_identity_id")==null?"DEPLOYMENT":"MANAGED");
    }
    private Map<String,Object> identityRow(Map<String,Object> binding) {
        String configured=identities.configured(spec(binding.get("slot").toString()).get("property"));
        if(configured.isBlank())return null;
        try{return db.one("SELECT * FROM iam.service_identity WHERE id=?",UUID.fromString(configured));}
        catch(IllegalArgumentException invalid){return null;}
    }
    private Map<String,String> spec(String slot) {
        return switch(slot) {
            case "DRIVE_SCANNER" -> Map.of("type","FILE_CHECK","module","DRIVE","label","公盘文件检测","property","platform.scanner.service-id");
            case "ARCHIVE_SCANNER" -> Map.of("type","FILE_CHECK","module","ARCHIVE","label","档案文件检测","property","platform.scanner.archive-service-id");
            case "PLATFORM_SCANNER" -> Map.of("type","FILE_CHECK","module","PLATFORM","label","人员与台账文件检测","property","platform.scanner.platform-service-id");
            case "OFFICE" -> Map.of("type","OFFICE","module","DRIVE","label","Office预览与编辑","property","platform.office.service-id");
            default -> throw ApiFault.hidden();
        };
    }
}
