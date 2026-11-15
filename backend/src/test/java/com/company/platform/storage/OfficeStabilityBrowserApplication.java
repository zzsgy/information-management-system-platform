package com.company.platform.storage;
import com.company.platform.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 仅本批合成环境使用；无害ClamAV签名和合成保护事实绝不进入发布JAR。 */
public class OfficeStabilityBrowserApplication {
 public static void main(String[] args)throws Exception{
  if(!System.getenv("IMS_PLATFORM_DB_URL").matches("jdbc:postgresql://127\\.0\\.0\\.1:55467/ims_platform_dev_[0-9]+"))throw new IllegalStateException("只允许本批隔离库");
  System.setProperty("spring.config.name","platform");
  var context=org.springframework.boot.SpringApplication.run(new Class[]{PlatformApplication.class,DriveReadIntegrationTest.NativeScanner.class},args);
  var db=context.getBean(Sql.class);var timer=Executors.newSingleThreadScheduledExecutor();
  timer.scheduleAtFixedRate(()->{try{
   Instant now=Instant.now();var body=Sql.map("instanceId",db.required("SELECT id FROM platform_meta.instance").get("id").toString(),"nodeId",System.getenv("IMS_PLATFORM_NODE_ID"),"epoch",1,"issuedAt",now.toString(),"expiresAt",now.plusSeconds(60).toString(),"mode","NORMAL","oldPrimaryFenced",true,"replicaVerifiedAt",now.toString(),"backupId",UUID.randomUUID().toString(),"backupProtectedAt",now.toString(),"writableModules",List.of("DRIVE","ARCHIVE","PLATFORM"));
   body.put("signature",Crypto.hmac(Files.readString(Path.of(System.getenv("IMS_PLATFORM_PROTECTION_KEY"))),Crypto.json(body)));
   Path target=Path.of(System.getenv("IMS_PLATFORM_PROTECTION_EVIDENCE")),next=target.resolveSibling("stability-evidence.next");Files.writeString(next,Crypto.json(body));Files.move(next,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
  }catch(Exception failure){System.err.println("合成保护事实更新失败："+failure.getClass().getSimpleName());}},0,10,TimeUnit.SECONDS);
  Runtime.getRuntime().addShutdownHook(new Thread(timer::shutdownNow));
 }
}
