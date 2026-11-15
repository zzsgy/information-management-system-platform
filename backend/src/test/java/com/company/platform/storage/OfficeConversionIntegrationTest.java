package com.company.platform.storage;

import com.company.platform.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 必须连接真实本机文档服务与新隔离库；测试检测器仅替换无害签名库，仍执行原生进程。 */
@EnabledIfEnvironmentVariable(named="IMS_OFFICE_CONVERSION_INTEGRATION",matches="yes")
@SpringBootTest(classes={PlatformApplication.class,DriveReadIntegrationTest.NativeScanner.class},webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties={"spring.config.name=platform","platform.file-worker.enabled=false","platform.scan-worker.enabled=false"})
@TestMethodOrder(MethodOrderer.MethodName.class)
class OfficeConversionIntegrationTest {
    @Autowired com.company.platform.archive.ArchiveChecklists checklists; @Autowired com.company.platform.archive.ArchiveCatalogService catalog;
    @Autowired Sql db;@Autowired IdentityService identity;@Autowired DriveAccessService access;@Autowired DriveDocumentService documents;
    @Autowired UploadService uploads;@Autowired UploadAssembly assembly;@Autowired FileInspectionService inspection;@Autowired DriveLifecycle lifecycle;
    @Autowired OfficeConversions conversions;@Autowired ControlledReadService reading;@Autowired OfficeGateway gateway;@Autowired OfficeSessions officeSessions;@Autowired OfficeReview officeReview;
    /** 真实文档服务将常见WPS/Office格式转为受控阅读PDF，原件独立保留。 */
    @ParameterizedTest @ValueSource(strings={"docx","xlsx","pptx","doc","xls","ppt"}) void archiveChecklistOfficeDocuments(String extension)throws Exception{
        UUID dept=Inputs.uuid(admin.user().get("departmentId"));UUID scanner=Inputs.uuid(System.getenv("IMS_PLATFORM_SCANNER_ARCHIVE_SERVICE_ID"));
        db.update("INSERT INTO iam.service_identity(id,service_type,name,allowed_module,department_scope,allowed_actions,credential_ref,valid_until) VALUES(?,'FILE_CHECK','档案Office隔离检测','ARCHIVE',?::uuid[],'{FILE_CHECK}','test-only',CURRENT_TIMESTAMP+interval '1 day') ON CONFLICT(id) DO UPDATE SET department_scope=EXCLUDED.department_scope,valid_until=EXCLUDED.valid_until",scanner,"{"+dept+"}");
        UUID template=(UUID)db.required("SELECT id FROM archive.directory_template WHERE template_key='COMMON_BOX_LEDGER' AND state='ACTIVE'").get("id");String date=java.time.LocalDate.now().toString();
        var box=catalog.box(context(admin,"ARCHIVE_COLLECTION",ApiController.COLLECTION,"CREATE_BOX",null),Sql.map("departmentId",dept.toString(),"title","合成档案文档"+extension,"ownerId",admin.userId().toString(),"categoryCode","COMMON","carrierType","ELECTRONIC","classification","GENERAL","archiveDate",date,"retentionCode","10","numberMonth",date.substring(0,7)+"-01","keywords",List.of("合成验收"),"templateVersionId",template.toString(),"extensionValues",Map.of()));UUID boxId=(UUID)box.get("id");
        UUID list=archiveUpload(boxId,"ARCHIVE_CHECKLIST","清单.xlsx",ArchiveChecklistWorkbookTest.workbook(false,List.of(List.of("1","2025.4.17","合成文档","45","纸质四十五份"))));var preview=checklists.preview(admin,boxId,list);checklists.importChecklist(context(admin,"ARCHIVE_BOX",boxId,"IMPORT_CHECKLIST",((Number)preview.get("rowVersion")).intValue()),boxId,Sql.map("uploadSessionId",list.toString(),"digest",preview.get("digest")));
        var record=db.required("SELECT * FROM archive.record WHERE box_id=?",boxId);UUID recordId=(UUID)record.get("id"),upload=archiveUpload(recordId,"ARCHIVE_ATTACHMENT","1_合成文档."+extension,Files.readAllBytes(sample(extension)));checklists.bind(context(admin,"ARCHIVE_BOX",boxId,"UPLOAD_CHECKLIST_DOCUMENTS",((Number)db.required("SELECT row_version FROM archive.box WHERE id=?",boxId).get("row_version")).intValue()),boxId,Sql.map("items",List.of(Sql.map("recordId",recordId.toString(),"uploadSessionId",upload.toString(),"expectedVersion",record.get("row_version")))));
        if(extension.equals("docx")){DriveReadIntegrationTest.NativeScanner.DOWN.set(true);try{assertThrows(ApiFault.class,()->checklists.prepare(admin,recordId));assertEquals("FAILED",db.required("SELECT state FROM archive.electronic_version WHERE record_id=?",recordId).get("state"));}finally{DriveReadIntegrationTest.NativeScanner.DOWN.set(false);}}
        checklists.prepare(admin,recordId);var version=db.required("SELECT * FROM archive.electronic_version WHERE record_id=?",recordId);assertEquals("READY",version.get("state"));assertNotNull(version.get("pdf_version_id"));assertEquals(1,db.count("SELECT count(*) FROM archive.electronic_version WHERE record_id=?",recordId));assertTrue(db.count("SELECT page_count FROM archive.pdf_version WHERE id=?",version.get("pdf_version_id"))>0);assertEquals(hash(Files.readAllBytes(sample(extension))),db.required("SELECT sha256 FROM storage.object WHERE id=?",version.get("original_object_id")).get("sha256"));
    }
    private UUID archiveUpload(UUID target,String purpose,String name,byte[] bytes)throws Exception{UUID request=UUID.randomUUID();UUID upload=(UUID)uploads.create(admin,Sql.map("module","ARCHIVE","purpose",purpose,"businessContextId",target.toString(),"originalName",name,"expectedSize",bytes.length,"expectedSha256",hash(bytes),"expectedMediaType","application/octet-stream"),UUID.randomUUID(),request).get("uploadId");uploads.part(admin,upload,1,"bytes 0-"+(bytes.length-1)+"/"+bytes.length,bytes.length,hash(bytes),new ByteArrayInputStream(bytes),request);UUID job=(UUID)uploads.complete(admin,upload,Sql.map("partCount",1,"finalSha256",hash(bytes)),UUID.randomUUID(),request).get("jobId");assembly.assemble(admin,job,request);inspection.inspect(admin,job,request);return upload;}

    private Actor admin,leader,staff;private UUID department,folder,delegation;private String password,leaderNo,staffNo;private ScheduledExecutorService evidenceTimer;

    @BeforeEach void fixture()throws Exception{
        assertTrue(System.getenv("IMS_PLATFORM_DB_URL").matches("jdbc:postgresql://127\\.0\\.0\\.1:55450/ims_platform_dev_[0-9]+"));assertEquals("18456",System.getenv("IMS_PLATFORM_PORT"));
        DriveReadIntegrationTest.NativeScanner.DOWN.set(false);DriveReadIntegrationTest.NativeScanner.scanStarted=null;DriveReadIntegrationTest.NativeScanner.scanRelease=null;
        admin=login(System.getenv("IMS_PLATFORM_ADMIN_NO"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));evidence();evidenceTimer=Executors.newSingleThreadScheduledExecutor();evidenceTimer.scheduleAtFixedRate(()->{try{evidence();}catch(Exception failure){throw new IllegalStateException(failure);}},10,10,TimeUnit.SECONDS);
        department=UUID.randomUUID();db.update("INSERT INTO iam.department(id,code,name) VALUES(?,?,?)",department,"O"+department,"Office联调部门"+department.toString().substring(0,8));password=Crypto.token();leaderNo="OL"+UUID.randomUUID();staffNo="OS"+UUID.randomUUID();
        UUID leaderId=person(leaderNo,"Office负责人","DEPARTMENT_MANAGER");person(staffNo,"Office协作员工","STAFF");db.update("INSERT INTO iam.department_manager(id,department_id,user_id,duty_type,valid_from,change_reason,changed_by) VALUES(?,?,?,'DEPARTMENT_MANAGER',CURRENT_TIMESTAMP,'Office本机隔离联调',?)",UUID.randomUUID(),department,leaderId,admin.userId());
        folder=(UUID)access.folder(context(admin,"DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_FOLDER",null),Sql.map("zone","DEPARTMENT_SHARED","departmentId",department,"name","Office联调资料"+UUID.randomUUID())).get("id");UUID role=(UUID)db.required("SELECT id FROM iam.role WHERE module='DRIVE' AND code='DEPARTMENT_MANAGER'").get("id");
        delegation=(UUID)access.delegate(context(admin,"DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_DELEGATION",null),Sql.map("departmentId",department,"folderId",folder,"roleId",role,"allowedActions",List.of("VIEW","DOWNLOAD","UPLOAD","EDIT","DELETE","MOVE","RESTORE","PURGE","FOLDER_MANAGE","COLLABORATE"),"reason","Office真实服务联调")).get("id");
        db.update("INSERT INTO iam.service_identity(id,service_type,name,allowed_module,department_scope,allowed_actions,credential_ref,valid_until) VALUES(?,'FILE_CHECK','Office隔离检测','DRIVE',?::uuid[],'{FILE_CHECK}','test-only',CURRENT_TIMESTAMP+interval '1 day') ON CONFLICT(id) DO UPDATE SET department_scope=EXCLUDED.department_scope,valid_until=EXCLUDED.valid_until",Inputs.uuid(System.getenv("IMS_PLATFORM_SCANNER_SERVICE_ID")),"{"+department+"}");
        db.update("INSERT INTO iam.service_identity(id,service_type,name,allowed_module,department_scope,allowed_actions,credential_ref,valid_until) VALUES(?,'OFFICE','ONLYOFFICE本机联调','DRIVE',?::uuid[],'{VIEW,EDIT}','isolated-only',CURRENT_TIMESTAMP+interval '1 day') ON CONFLICT(id) DO UPDATE SET department_scope=EXCLUDED.department_scope,allowed_actions=EXCLUDED.allowed_actions,valid_until=EXCLUDED.valid_until",gateway.service(),"{"+department+"}");
        leader=login(leaderNo,password);staff=login(staffNo,password);gateway.verifyVersion();
    }
    @AfterEach void finish(){if(evidenceTimer!=null)evidenceTimer.shutdownNow();DriveReadIntegrationTest.NativeScanner.DOWN.set(false);DriveReadIntegrationTest.NativeScanner.scanStarted=null;DriveReadIntegrationTest.NativeScanner.scanRelease=null;}
    private UUID person(String no,String name,String role){UUID id=UUID.randomUUID();db.update("INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required) VALUES(?,?,?,?,?,?,false)",id,no,name,department,no.toLowerCase(Locale.ROOT)+"@biolink.com",Crypto.password(password));UUID roleId=(UUID)db.required("SELECT id FROM iam.role WHERE module='DRIVE' AND code=?",role).get("id");db.update("INSERT INTO iam.user_role(id,user_id,role_id,department_id,valid_from,assigned_by,module,role_code) VALUES(?,?,?,?,CURRENT_TIMESTAMP,?,'DRIVE',?)",UUID.randomUUID(),id,roleId,department,admin.userId(),role);return id;}
    private Actor login(String no,String secret){return identity.authenticate((String)identity.login(Sql.map("employeeNo",no,"password",secret),UUID.randomUUID(),"office-real-integration").get("token"));}
    private Commands.Context context(Actor actor,String type,UUID id,String action,Integer version){var proof=identity.reauthenticate(actor,Sql.map("password",actor.userId().equals(admin.userId())?System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"):password,"purpose","MANAGE","objectIds",List.of(id.toString()),"scopeItems",List.of(Sql.map("objectType",type,"objectId",id,"actions",List.of(action)))),UUID.randomUUID());return new Commands.Context(actor,UUID.randomUUID(),UUID.randomUUID(),"TEST OFFICE "+action,(String)proof.get("verificationToken"),version);}
    private void evidence()throws Exception{Path key=Path.of(System.getenv("IMS_PLATFORM_PROTECTION_KEY"));if(!Files.exists(key)){Files.writeString(key,Crypto.token(),StandardOpenOption.CREATE_NEW);PrivateFiles.restrict(key);}Instant now=Instant.now();var body=Sql.map("instanceId",db.required("SELECT id FROM platform_meta.instance").get("id").toString(),"nodeId",System.getenv("IMS_PLATFORM_NODE_ID"),"epoch",1,"issuedAt",now.toString(),"expiresAt",now.plusSeconds(60).toString(),"mode","NORMAL","oldPrimaryFenced",true,"replicaVerifiedAt",now.toString(),"backupId",UUID.randomUUID().toString(),"backupProtectedAt",now.toString(),"writableModules",List.of("DRIVE","ARCHIVE","PLATFORM"));body.put("signature",Crypto.hmac(Files.readString(key),Crypto.json(body)));Path finalFile=Path.of(System.getenv("IMS_PLATFORM_PROTECTION_EVIDENCE")),next=finalFile.resolveSibling("office-test-evidence.next");Files.writeString(next,Crypto.json(body));Files.move(next,finalFile,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
    private Path sample(String extension){return Path.of(System.getenv("IMS_OFFICE_TEST_SAMPLES")).resolve("synthetic."+extension);}
    private String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private UUID publish(String extension)throws Exception{Path path=sample(extension);byte[] content=Files.readAllBytes(path);String digest=hash(content),name=(Set.of("doc","xls","ppt").contains(extension)?"旧格式联调原件.":"联调原件.")+extension;UUID request=UUID.randomUUID();UUID upload=(UUID)uploads.create(leader,Sql.map("module","DRIVE","businessContextId",folder,"originalName",name,"expectedSize",content.length,"expectedSha256",digest,"expectedMediaType","application/octet-stream","purpose","DRIVE_FILE"),UUID.randomUUID(),request).get("uploadId");uploads.part(leader,upload,1,"bytes 0-"+(content.length-1)+"/"+content.length,content.length,digest,new ByteArrayInputStream(content),request);UUID job=(UUID)uploads.complete(leader,upload,Sql.map("partCount",1,"finalSha256",digest),UUID.randomUUID(),request).get("jobId");assembly.assemble(leader,job,request);inspection.inspect(leader,job,request);return (UUID)documents.publish(leader,Sql.map("folderId",folder,"uploadSessionId",upload,"name",name),UUID.randomUUID(),request).get("id");}
    private UUID version(UUID document){return (UUID)db.required("SELECT current_version_id FROM drive.document WHERE id=?",document).get("current_version_id");}
    private UUID preview(Actor actor,UUID document){UUID job=(UUID)reading.create(actor,document,Map.of(),UUID.randomUUID(),UUID.randomUUID()).get("jobId");reading.prepare(actor,job,UUID.randomUUID());return Inputs.uuid(db.required("SELECT business_ref FROM ops.job WHERE id=?",job).get("business_ref"));}
    private void deny(Actor person,UUID document,String action){access.grant(context(leader,"FOLDER",folder,"CREATE_GRANT",null),Sql.map("subjectId",person.userId(),"resourceType","DOCUMENT","resourceId",document,"action",action,"effect","DENY","delegationId",delegation,"reason","Office联调独立权限拒绝"));}
    private void legacy(String extension)throws Exception{UUID document=publish(extension),original=version(document);var ctx=context(leader,"DRIVE_DOCUMENT",document,"EDIT",1);var body=Sql.map("sourceVersionId",original,"reason","保留原件并转换编辑格式");var result=conversions.convert(ctx,document,body);assertTrue(((String)result.get("name")).endsWith("."+extension+"x"));UUID converted=version(document);assertNotEquals(original,converted);assertEquals(converted,conversions.convert(ctx,document,body).get("currentVersionId"));assertEquals(2L,db.count("SELECT count(*) FROM drive.document_version WHERE document_id=?",document));
        var old=lifecycle.download(leader,document,original);assertTrue(((String)old.get("document_name")).endsWith("."+extension));assertEquals(hash(Files.readAllBytes(sample(extension))),old.get("sha256"));
        var restored=lifecycle.restoreVersion(context(leader,"DRIVE_DOCUMENT",document,"EDIT",2),document,Sql.map("sourceVersionId",original,"reason","验证恢复旧原件格式"));assertTrue(((String)restored.get("name")).endsWith("."+extension));assertEquals(old.get("sha256"),lifecycle.download(leader,document,null).get("sha256"));
        var modern=lifecycle.restoreVersion(context(leader,"DRIVE_DOCUMENT",document,"EDIT",3),document,Sql.map("sourceVersionId",converted,"reason","验证恢复转换后格式"));assertTrue(((String)modern.get("name")).endsWith("."+extension+"x"));assertEquals(4L,db.count("SELECT count(*) FROM drive.document_version WHERE document_id=?",document));}
    @Test void docConversionPreservesOriginalAndHistory()throws Exception{legacy("doc");}
    @Test void xlsConversionPreservesOriginalAndHistory()throws Exception{legacy("xls");}
    @Test void pptConversionPreservesOriginalAndHistory()throws Exception{legacy("ppt");}
    @Test void viewOnlyCannotConvertEditableVersion()throws Exception{UUID document=publish("doc");assertEquals(403,assertThrows(ApiFault.class,()->conversions.convert(new Commands.Context(staff,UUID.randomUUID(),UUID.randomUUID(),"TEST CONVERT",null,1),document,Sql.map("sourceVersionId",version(document),"reason","无编辑权"))).status);assertEquals(0L,db.count("SELECT count(*) FROM drive.office_conversion WHERE document_id=?",document));}
    @Test void officePreviewDoesNotGrantOriginalDownload()throws Exception{for(String extension:List.of("docx","xlsx","pptx")){UUID document=publish(extension);deny(staff,document,"DOWNLOAD");staff=login(staffNo,password);UUID preview=preview(staff,document);assertEquals("READY",reading.manifest(staff,preview).get("state"));byte[] png=reading.page(staff,preview,1,UUID.randomUUID());assertArrayEquals(new byte[]{(byte)137,80,78,71},Arrays.copyOf(png,4));assertEquals(403,assertThrows(ApiFault.class,()->lifecycle.download(staff,document,null)).status);UUID derived=(UUID)db.required("SELECT render_object_id FROM storage.preview_session WHERE id=?",preview).get("render_object_id");assertEquals(derived,conversions.previewPdf(staff,document,version(document),UUID.randomUUID()));}}
    @Test void expiredPreviewIdentityReportsCauseAndRecoversWithoutChangingOriginal()throws Exception{
        UUID document=publish("docx"),original=version(document);
        UUID job=(UUID)reading.create(staff,document,Map.of(),UUID.randomUUID(),UUID.randomUUID()).get("jobId");
        db.update("UPDATE iam.service_identity SET valid_until=CURRENT_TIMESTAMP-interval '1 second' WHERE id=?",gateway.service());
        try{
            assertEquals("SERVICE_IDENTITY_EXPIRED",assertThrows(ApiFault.class,()->reading.prepare(staff,job,UUID.randomUUID())).code);
            assertEquals("SERVICE_IDENTITY_EXPIRED",db.required("SELECT error_code FROM ops.job WHERE id=?",job).get("error_code"));
            assertEquals(original,version(document));
            assertEquals(0L,db.count("SELECT count(*) FROM drive.office_conversion WHERE document_id=?",document));
        }finally{db.update("UPDATE iam.service_identity SET valid_until=CURRENT_TIMESTAMP+interval '1 year' WHERE id=?",gateway.service());}
        UUID ready=preview(staff,document);assertEquals("READY",reading.manifest(staff,ready).get("state"));
        assertTrue(reading.page(staff,ready,1,UUID.randomUUID()).length>0);assertEquals(original,version(document));
        assertEquals(1L,db.count("SELECT count(*) FROM drive.document_version WHERE document_id=?",document));
    }
    private UUID editableSession()throws Exception{
        UUID document=publish("docx");
        lifecycle.collaboration(context(leader,"DRIVE_DOCUMENT",document,"COLLABORATE",1),document,Sql.map("collaborationEnabled",true,"reason","隔离会话关闭验收"));
        return (UUID)officeSessions.open(leader,document,Map.of("baseVersionId",version(document)),UUID.randomUUID()).get("sessionId");
    }
    @Test @SuppressWarnings("unchecked") void documentHistoryPagesKeepPeopleAndAllRecords()throws Exception{
        UUID document=publish("docx"),original=version(document);
        assertEquals("Office负责人",documents.detail(leader,document).get("uploadedByName"));
        assertEquals("Office负责人",((List<Map<String,Object>>)lifecycle.versions(leader,document,Map.of()).get("items")).getFirst().get("createdByName"));
        db.update("INSERT INTO drive.edit_session(id,document_id,base_version_id,last_version_id,edit_epoch,editor_key,state,created_by,created_at,expires_at,next_save_seq) SELECT gen_random_uuid(),?,?,?,1,'history-'||gen_random_uuid(),'CLOSED',?,CURRENT_TIMESTAMP-interval '1 second'*n,CURRENT_TIMESTAMP+interval '1 day',2 FROM generate_series(1,251) n",document,original,original,leader.userId());
        db.update("INSERT INTO drive.edit_participant(session_id,user_id,login_session_id,auth_version,state,joined_at) SELECT id,?,?,?,'LEFT',created_at FROM drive.edit_session WHERE document_id=?",leader.userId(),leader.sessionId(),leader.authVersion(),document);
        db.update("INSERT INTO drive.edit_participant(session_id,user_id,login_session_id,auth_version,state,joined_at) SELECT id,?,?,?,'LEFT',created_at+interval '1 second' FROM drive.edit_session WHERE document_id=? ORDER BY created_at DESC,id DESC LIMIT 2",staff.userId(),staff.sessionId(),staff.authVersion(),document);
        db.update("INSERT INTO drive.save_receipt(id,session_id,save_seq,operation_id,state,requested_by,created_at) SELECT gen_random_uuid(),id,1,gen_random_uuid(),'NO_CHANGES',?,created_at+interval '2 seconds' FROM drive.edit_session WHERE document_id=?",leader.userId(),document);
        Set<Object> sessions=new HashSet<>(),receipts=new HashSet<>();
        for(int page=1;page<=26;page++){
            var history=officeReview.list(leader,document,Map.of("sessionPage",""+page,"receiptPage",""+page,"pageSize","10"));
            assertEquals(251L,history.get("sessionTotal"));assertEquals(251L,history.get("receiptTotal"));assertEquals(original,history.get("currentVersionId"));assertEquals(false,history.get("hasPending"));
            var sessionRows=(List<Map<String,Object>>)history.get("sessions");var receiptRows=(List<Map<String,Object>>)history.get("receipts");assertEquals(page==26?1:10,sessionRows.size());assertEquals(sessionRows.size(),receiptRows.size());
            for(var row:sessionRows){assertTrue(sessions.add(row.get("id")));assertEquals("Office负责人",row.get("createdByName"));var people=(List<Map<String,Object>>)row.get("participants");assertFalse(people.isEmpty());assertTrue(people.stream().allMatch(p->p.get("joinedAt")!=null&&p.get("name")!=null));assertFalse(row.containsKey("editorKey"));}
            for(var row:receiptRows){assertTrue(receipts.add(row.get("id")));assertEquals("Office负责人",row.get("requestedByName"));assertNotNull(row.get("createdAt"));}
        }
        assertEquals(251,sessions.size());assertEquals(251,receipts.size());
        var own=officeReview.list(staff,document,Map.of());assertEquals(2L,own.get("sessionTotal"));assertEquals(2L,own.get("receiptTotal"));
        assertEquals(400,assertThrows(ApiFault.class,()->officeReview.list(leader,document,Map.of("pageSize","101"))).status);
        assertEquals(400,assertThrows(ApiFault.class,()->officeReview.list(leader,document,Map.of("sessionPage","0"))).status);
        deny(staff,document,"VIEW");staff=login(staffNo,password);
        assertEquals(403,assertThrows(ApiFault.class,()->officeReview.list(staff,document,Map.of())).status);
        Path fixture=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")).resolve("history-browser-fixture.private.json");
        Files.writeString(fixture,Crypto.json(Sql.map("leaderNo",leaderNo,"password",password,"folderId",folder,"documentId",document,"documentName",documents.detail(leader,document).get("name"))));PrivateFiles.restrict(fixture);
    }
    @Test @SuppressWarnings("unchecked") void saveHistoryShowsPublishedAuthorAndResolution()throws Exception{
        UUID session=editableSession();var batch=db.required("SELECT * FROM drive.edit_session WHERE id=?",session);UUID document=(UUID)batch.get("document_id"),original=version(document);
        var doc=db.required("SELECT * FROM drive.document WHERE id=?",document);UUID object=(UUID)db.required("SELECT object_id FROM drive.document_version WHERE id=?",original).get("object_id");
        UUID saved=lifecycle.version(staff,doc,object,original,"OFFICE_SAVE");
        db.update("INSERT INTO drive.save_receipt(id,session_id,save_seq,operation_id,state,requested_by,published_version_id) VALUES(?,?,1,?,'SAVED',?,?)",UUID.randomUUID(),session,UUID.randomUUID(),staff.userId(),saved);
        db.update("INSERT INTO drive.save_receipt(id,session_id,save_seq,operation_id,state,requested_by,resolved_by,resolved_at,resolution) VALUES(?,?,2,?,'RESOLVED',?,?,CURRENT_TIMESTAMP,'DISCARD')",UUID.randomUUID(),session,UUID.randomUUID(),staff.userId(),leader.userId());
        var history=officeReview.list(leader,document,Map.of());var receipts=(List<Map<String,Object>>)history.get("receipts");
        var published=receipts.stream().filter(r->"SAVED".equals(r.get("state"))).findFirst().orElseThrow();
        assertEquals("Office协作员工",published.get("requestedByName"));assertEquals(2,((Number)published.get("versionNo")).intValue());assertNotNull(published.get("publishedAt"));
        var resolved=receipts.stream().filter(r->"RESOLVED".equals(r.get("state"))).findFirst().orElseThrow();assertEquals("Office负责人",resolved.get("resolvedByName"));assertNotNull(resolved.get("resolvedAt"));assertEquals("DISCARD",resolved.get("resolution"));
        assertEquals("Office负责人",documents.detail(leader,document).get("uploadedByName"));
        assertEquals("Office协作员工",((List<Map<String,Object>>)lifecycle.versions(leader,document,Map.of()).get("items")).getFirst().get("createdByName"));
        var older=officeReview.list(leader,document,Map.of("sessionPage","2","receiptPage","2","pageSize","1"));assertTrue((Boolean)older.get("hasPending"));assertEquals(saved,older.get("currentVersionId"));
    }
    @Test void noChangeCallbackClosesSessionAfterReloginAndIsIdempotent()throws Exception{
        UUID session=editableSession();var row=db.required("SELECT * FROM drive.edit_session WHERE id=?",session);UUID document=(UUID)row.get("document_id"),original=version(document);
        leader=login(leaderNo,password);
        assertEquals("UNKNOWN",officeSessions.detail(leader,session).get("state"));
        var payload=Sql.map("key",row.get("editor_key"),"status",4);
        assertEquals(0,officeSessions.callback(session,payload));assertEquals(0,officeSessions.callback(session,payload));
        assertEquals("CLOSED",officeSessions.detail(leader,session).get("state"));assertEquals(0L,db.count("SELECT count(*) FROM drive.edit_participant WHERE session_id=? AND state='ACTIVE'",session));
        assertEquals(original,version(document));assertEquals(1L,db.count("SELECT count(*) FROM drive.document_version WHERE document_id=?",document));
        assertEquals(1L,db.count("SELECT count(*) FROM ops.audit_event WHERE resource_id=? AND action='OFFICE_CLOSED_NO_CHANGES'",session));
        assertNotEquals(session,officeSessions.open(leader,document,Map.of("baseVersionId",original),UUID.randomUUID()).get("sessionId"));
    }
    @ParameterizedTest @ValueSource(strings={"REQUESTED","RECEIVING","UNKNOWN"})
    void noChangeCallbackCannotDismissUnconfirmedSave(String state)throws Exception{
            UUID session=editableSession();var row=db.required("SELECT * FROM drive.edit_session WHERE id=?",session);UUID receipt=UUID.randomUUID();
            db.update("INSERT INTO drive.save_receipt(id,session_id,save_seq,operation_id,state,requested_by) VALUES(?,?,1,?,?,?)",receipt,session,UUID.randomUUID(),state,leader.userId());
            officeSessions.callback(session,Sql.map("key",row.get("editor_key"),"status",4));
            assertEquals("UNKNOWN",db.required("SELECT state FROM drive.edit_session WHERE id=?",session).get("state"));
            assertEquals(state,db.required("SELECT state FROM drive.save_receipt WHERE id=?",receipt).get("state"));
    }
    @Test void noChangeCallbackDoesNotRestoreRevokedSessionOrRights()throws Exception{
        UUID session=editableSession();var row=db.required("SELECT * FROM drive.edit_session WHERE id=?",session);UUID document=(UUID)row.get("document_id");
        db.update("UPDATE drive.edit_participant SET state='REVOKED' WHERE session_id=?",session);
        db.update("UPDATE iam.user_account SET status='DISABLED' WHERE id=?",leader.userId());
        officeSessions.callback(session,Sql.map("key",row.get("editor_key"),"status",4));
        assertEquals("REVOKED",db.required("SELECT state FROM drive.edit_session WHERE id=?",session).get("state"));
        assertEquals("REVOKED",db.required("SELECT state FROM drive.edit_participant WHERE session_id=?",session).get("state"));
        assertThrows(ApiFault.class,()->officeSessions.open(leader,document,Map.of("baseVersionId",version(document)),UUID.randomUUID()));
    }
    @Test void previewRevocationStopsExistingPages()throws Exception{UUID document=publish("docx"),preview=preview(staff,document);assertTrue(reading.page(staff,preview,1,UUID.randomUUID()).length>0);deny(staff,document,"VIEW");assertThrows(ApiFault.class,()->reading.page(staff,preview,1,UUID.randomUUID()));assertThrows(ApiFault.class,()->reading.manifest(staff,preview));}
    @Test void originalAndDerivedReplicaFailuresStopPreview()throws Exception{UUID document=publish("docx"),preview=preview(staff,document);var row=db.required("SELECT object_id,render_object_id FROM storage.preview_session WHERE id=?",preview);for(String field:List.of("object_id","render_object_id")){var object=db.required("SELECT storage_key FROM storage.object WHERE id=?",row.get(field));Path secondary=Path.of(System.getenv("IMS_PLATFORM_STORAGE_SECONDARY")).resolve((String)object.get("storage_key"));assertTrue(secondary.toAbsolutePath().normalize().startsWith(Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")).toAbsolutePath().normalize()));byte[] before=Files.readAllBytes(secondary);try{Files.write(secondary,new byte[]{1},StandardOpenOption.APPEND);assertThrows(ApiFault.class,()->reading.page(staff,preview,1,UUID.randomUUID()));}finally{Files.write(secondary,before);}}}
    @Test void unavailableScannerCannotPublishConvertedVersion()throws Exception{UUID document=publish("doc"),original=version(document);var ctx=context(leader,"DRIVE_DOCUMENT",document,"EDIT",1);var body=Sql.map("sourceVersionId",original,"reason","检测不可用验证");DriveReadIntegrationTest.NativeScanner.DOWN.set(true);assertEquals("SCANNER_UNAVAILABLE",assertThrows(ApiFault.class,()->conversions.convert(ctx,document,body)).code);assertEquals(original,version(document));assertEquals(1L,db.count("SELECT count(*) FROM drive.document_version WHERE document_id=?",document));DriveReadIntegrationTest.NativeScanner.DOWN.set(false);conversions.convert(ctx,document,body);assertNotEquals(original,version(document));}
    @Test void zzDesktopFixture()throws Exception{String viewerNo="OV"+UUID.randomUUID();UUID viewerId=person(viewerNo,"Office仅阅读员工","STAFF");Map<String,Object> fixture=Sql.map("baseUrl","https://127.0.0.1:18456","leaderNo",leaderNo,"staffNo",staffNo,"viewerNo",viewerNo,"password",password,"folderId",folder,"departmentId",department);Map<String,Object> items=new LinkedHashMap<>();for(String extension:List.of("docx","xlsx","pptx","doc","xls","ppt","png")){UUID id=publish(extension);fixture.put(extension.equals("png")?"imageId":extension+"Id",id);items.put(extension,Sql.map("id",id,"name",db.required("SELECT name FROM drive.document WHERE id=?",id).get("name")));if(extension.equals("docx")){lifecycle.collaboration(context(leader,"DRIVE_DOCUMENT",id,"COLLABORATE",1),id,Sql.map("collaborationEnabled",true,"reason","真实双人编辑联调"));access.grant(context(leader,"FOLDER",folder,"CREATE_GRANT",null),Sql.map("subjectId",staff.userId(),"resourceType","DOCUMENT","resourceId",id,"action","EDIT","effect","ALLOW","delegationId",delegation,"reason","真实双人协作联调"));}}
        access.grant(context(leader,"FOLDER",folder,"CREATE_GRANT",null),Sql.map("subjectId",viewerId,"resourceType","FOLDER","resourceId",folder,"action","DOWNLOAD","effect","DENY","delegationId",delegation,"reason","仅阅读员工不可下载原件"));fixture.put("documents",items);Path path=Path.of(System.getenv("IMS_DRIVE_TEST_ROOT")).resolve("browser-fixture.private.json");Files.writeString(path,Crypto.json(fixture));PrivateFiles.restrict(path);}
}
