package com.company.platform.operations;

import com.company.platform.*;
import com.company.platform.storage.*;
import com.company.platform.archive.ExportFiles;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.*;

/** 实测只使用无害探针；失败保留具体原因和请求号，不用保存状态冒充健康。 */
@Service
public class DeploymentDiagnostics {
    private final DeploymentSettings settings;private final StorageFiles files;private final ReadingRuntime reading;private final ExportFiles exports;private final OfficeGateway office;private final ManagedProtection protection;private final Sql db;private final DeliveryReceipts receipts;
    private final LocalOfficePreview localPreview;
    private final FileScanEngine scanner;
    public DeploymentDiagnostics(DeploymentSettings settings,StorageFiles files,ReadingRuntime reading,ExportFiles exports,OfficeGateway office,ManagedProtection protection,Sql db,DeliveryReceipts receipts,LocalOfficePreview localPreview,FileScanEngine scanner){this.receipts=receipts;this.settings=settings;this.files=files;this.reading=reading;this.exports=exports;this.office=office;this.protection=protection;this.db=db;this.localPreview=localPreview;this.scanner=scanner;}
    public List<Map<String,Object>> check(UUID request){
        var checks=new ArrayList<Map<String,Object>>();
        test(checks,"原件双目录","STORAGE",request,()->files.get());
        test(checks,"阅读处理程序与回执目录","READING",request,()->{
            String temp=settings.effective("platform.reading.temporary");if(temp.isBlank())throw new ApiFault(503,"READING_NOT_CONFIGURED","请设置阅读临时目录");
            Path probe=Files.createTempFile(PrivateFiles.directory(temp),"ims-render-check-",".pdf");
            try{PrivateFiles.restrict(probe);Files.writeString(probe,OperationsConsole.probeDocument(),java.nio.charset.StandardCharsets.US_ASCII);String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(probe)));if(reading.withClient(client->client.pageCount(probe,digest))!=1)throw SystemFailure.RENDER_OUTPUT_INVALID.fault();}finally{Files.deleteIfExists(probe);}
            String root=settings.effective("platform.reading.audit-root");if(root.isBlank())throw new ApiFault(503,"RECEIPT_STORAGE_UNAVAILABLE","未配置阅读与交付回执目录");
            receipts.verifyStorage();writable(root);return null;
        });
        test(checks,"导出双目录与临时目录","READING",request,()->{exports.get();exports.temporary();return null;});
        test(checks,"Office连接","OFFICE",request,()->{if(Boolean.TRUE.equals(office.status().get("enabled")))office.verifyVersion();return null;});
        if(!settings.effective(DeploymentSettings.LOCAL_OFFICE_EXECUTABLE).isBlank())
            test(checks,"公盘 Office 文档预览","READING",request,()->{
                Path pdf=localPreview.probe(localPreview.configuration());
                try {
                    if(!"application/pdf".equals(FileMediaCheck.detect(pdf,"probe.pdf","application/pdf")))
                        throw SystemFailure.LOCAL_OFFICE_CONVERSION_FAILED.fault();
                    var verdict=scanner.scan(pdf);
                    if(verdict==ClamFileScanner.Verdict.UNAVAILABLE)throw SystemFailure.SCANNER_UNAVAILABLE.fault();
                    if(verdict!=ClamFileScanner.Verdict.CLEAN)throw SystemFailure.FILE_CHECK_FAILED.fault();
                    String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pdf)));
                    if(reading.withClient(client->client.renderPage(pdf,digest,1,120)).length==0)
                        throw SystemFailure.RENDER_OUTPUT_INVALID.fault();
                    return null;
                } finally { Files.deleteIfExists(pdf); }
            });
        var state=protection.status();checks.add(Sql.map("name","存储保护","section","PROTECTION","ready",state.get("ready"),"message",state.get("message"),"errorCode",state.get("errorCode"),"requestId",request));
        test(checks,"独立维护执行器","MAINTENANCE",request,()->{
            if(!Boolean.parseBoolean(settings.effective("platform.operations.enabled")))throw new ApiFault(503,"OPERATIONS_DISABLED","请启用维护任务");
            String bin=settings.effective("platform.maintenance.postgres-bin");for(String name:List.of("psql","pg_dump","pg_restore","createdb")){
                Path program=Path.of(bin).resolve(name+(System.getProperty("os.name").startsWith("Windows")?".exe":""));if(!Files.isRegularFile(program))throw new ApiFault(503,"MAINTENANCE_TOOL_MISSING","PostgreSQL工具目录中缺少"+name);
            }
            String backup=settings.effective("platform.maintenance.backup-root");if(backup.isBlank())throw new ApiFault(503,"BACKUP_NOT_CONFIGURED","请设置独立备份输出目录");writable(backup);
            if(Files.getFileStore(Path.of(backup)).equals(Files.getFileStore(Path.of(settings.effective("platform.storage.primary")))))throw new ApiFault(503,"BACKUP_VOLUME_NOT_INDEPENDENT","独立备份目录不能与原件位于同一卷");
            Path node=Path.of(settings.effective("platform.maintenance.node")),bootstrap=Path.of(settings.effective("platform.maintenance.bootstrap"));
            if(!Files.isRegularFile(node)||!Files.isRegularFile(bootstrap))throw new ApiFault(503,"MAINTENANCE_AGENT_NOT_CONFIGURED","请配置Node程序和本服务私有启动配置，并按操作说明安装独立维护执行器");
            var heartbeat=db.one("SELECT seen_at,state FROM ops.maintenance_agent WHERE id=true");
            if(heartbeat==null||java.time.Instant.parse(heartbeat.get("seen_at").toString()).isBefore(java.time.Instant.now().minusSeconds(180)))throw new ApiFault(503,"MAINTENANCE_AGENT_OFFLINE","维护执行器没有最近心跳；请在部署电脑运行Install-OperationsAgent.ps1完成一次安装，或检查Windows计划任务");
            if(!"READY".equals(heartbeat.get("state")))throw new ApiFault(503,"MAINTENANCE_AGENT_NOT_READY","维护执行器尚未就绪，请查看独立执行器的私有日志");return null;
        });
        test(checks,"隔离恢复实例配置","MAINTENANCE",request,()->{
            String configured=settings.effective("platform.maintenance.restore-credentials-file");
            if(configured.isBlank())throw new ApiFault(503,"RESTORE_NOT_CONFIGURED","请创建恢复实例私有账号JSON，并在备份与维护中填写文件位置；模板可从安装包说明复制");
            var config=Crypto.JSON.readValue(Files.readString(Path.of(configured)),Map.class);
            for(String key:List.of("IMS_RESTORE_DB_URL","IMS_RESTORE_DB_USER","IMS_RESTORE_DB_PASSWORD","IMS_RESTORE_APP_USER","IMS_RESTORE_APP_PASSWORD"))if(Objects.toString(config.get(key),"").isBlank())throw new ApiFault(503,"RESTORE_NOT_CONFIGURED","恢复实例私有JSON缺少字段："+key);
            var target=java.net.URI.create(config.get("IMS_RESTORE_DB_URL").toString().replaceFirst("^jdbc:",""));
            var source=java.net.URI.create(settings.effective("spring.datasource.url").replaceFirst("^jdbc:",""));
            if(!Set.of("localhost","127.0.0.1").contains(target.getHost())||!"/postgres".equals(target.getPath())||(target.getPort()<0?5432:target.getPort())==(source.getPort()<0?5432:source.getPort()))throw new ApiFault(503,"RESTORE_TARGET_INVALID","隔离恢复必须使用另一个本机PostgreSQL实例的postgres维护库，数据库端口须与源库不同");
            try(var socket=new java.net.Socket()){socket.connect(new java.net.InetSocketAddress(target.getHost(),target.getPort()<0?5432:target.getPort()),2000);}return null;
        });
        return checks;
    }
    private void writable(String value)throws Exception{Path root=PrivateFiles.directory(value),probe=Files.createTempFile(root,"ims-access-check-",".tmp");try{PrivateFiles.restrict(probe);Files.writeString(probe,"IMS check");}finally{Files.deleteIfExists(probe);}}
    private void test(List<Map<String,Object>> checks,String name,String section,UUID request,java.util.concurrent.Callable<?> work){
        try{work.call();checks.add(Sql.map("name",name,"section",section,"ready",true,"message",name.equals("Office连接")&&!Boolean.TRUE.equals(office.status().get("enabled"))?"未启用（可选服务），不影响其他配置":"实际检查通过","requestId",request));}
        catch(Exception failure){var fault=failure instanceof ApiFault known?known:new ApiFault(503,"DEPLOYMENT_CHECK_FAILED",name+"检查失败，请检查该组所填路径、程序安装及服务账号权限",failure);FailureDiagnostics.log(request,"DEPLOYMENT_CHECK_"+section,fault);checks.add(Sql.map("name",name,"section",section,"ready",false,"message",fault.getMessage(),"errorCode",fault.code,"requestId",request));}
    }
}
