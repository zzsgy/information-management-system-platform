package com.company.platform;

import com.company.platform.archive.*;
import com.company.platform.storage.DriveAccessService;
import com.company.platform.storage.DriveDocumentService;
import com.company.platform.storage.DriveFolders;
import com.company.platform.workflow.OrganizationDuties;
import com.company.platform.workflow.PersonnelContinuity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 仅在本批新建库验证真实 IAM、组织与对象权限；不连接日常预览，不使用权限替身。 */
@EnabledIfEnvironmentVariable(named="IMS_SYSTEM_ADMIN_INTEGRATION",matches="yes")
@SpringBootTest(classes=PlatformApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,properties={"spring.config.name=platform","server.ssl.enabled=false","platform.messaging.schedule-enabled=false","platform.notifications.schedule-enabled=false","platform.file-worker.enabled=false","platform.scan-intake.worker-enabled=false","platform.operations.schedule-enabled=false"})
class SystemAdministratorIntegrationTest {
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry properties) {
        if(!System.getenv("IMS_PLATFORM_DB_URL").matches("jdbc:postgresql://127\\.0\\.0\\.1:(55473|55448)/ims_platform_dev_[0-9]+")) throw new IllegalStateException("仅允许角色或导入模块的独立验收库");
        properties.add("platform.protection.enabled",()->false);
    }
    @Autowired Sql db;
    @Autowired IdentityService identity;
    @Autowired DirectoryService directory;
    @Autowired Commands commands;
    @Autowired ArchiveAuthority archive;
    @Autowired ArchiveCatalogService catalog;
    @Autowired DriveAccessService driveAccess;
    @Autowired DriveDocumentService driveDocuments;
    @Autowired DriveFolders driveFolders;
    @Autowired OrganizationDuties organizationDuties;
    @Autowired PersonnelContinuity continuity;
    private Actor admin;
    @BeforeEach void login() {admin=login(System.getenv("IMS_PLATFORM_ADMIN_NO"),System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"));}
    private Actor login(String number,String password) {return identity.authenticate((String)identity.login(Sql.map("employeeNo",number,"password",password),UUID.randomUUID(),"isolated-"+UUID.randomUUID()).get("token"));}
    private UUID person() {
        UUID id=UUID.randomUUID();
        db.update("INSERT INTO iam.user_account(id,employee_no,name,department_id,company_email,password_hash,password_setup_required) VALUES(?,?,?,?,?,?,false)",id,id.toString(),"隔离验证员工",admin.user().get("departmentId"),id+"@biolink.com",Crypto.password("test-password"));
        return id;
    }
    private UUID role(String module,String code) {return (UUID)db.required("SELECT id FROM iam.role WHERE module=? AND code=?",module,code).get("id");}
    private Commands.Context context(UUID target,int version) {
        return context("USER",target,"ASSIGN_ROLES",version);
    }
    private Commands.Context context(String type,UUID target,String action,int version) {
        return context(admin,System.getenv("IMS_PLATFORM_ADMIN_PASSWORD"),type,target,action,version);
    }
    private Commands.Context context(Actor actor,String password,String type,UUID target,String action,int version) {
        String token=(String)identity.reauthenticate(actor,Sql.map("password",password,"purpose","MANAGE","objectIds",List.of(target),"scopeItems",List.of(Sql.map("objectType",type,"objectId",target,"actions",List.of(action)))),UUID.randomUUID()).get("verificationToken");
        return new Commands.Context(actor,UUID.randomUUID(),UUID.randomUUID(),"PUT isolated "+action,token,version);
    }
    private Map<String,Object> assign(Commands.Context context,UUID target,List<Map<String,Object>> roles) {
        var body=Sql.map("roles",roles,"reason","隔离验收","approvalRef","IT-20261030");
        return assign(context,target,body);
    }
    private Map<String,Object> assign(Commands.Context context,UUID target,Map<String,Object> body) {
        return commands.run(context,body,"USER",target,"ASSIGN_ROLES",()->context.actor().require("PLATFORM.ROLES_MANAGE"),()->directory.assignRoles(context,target,body),id->directory.userRoles(context.actor(),id));
    }
    /** 操作人通过真实角色权限取得管理能力，不依赖正在撤销的 IT 或超级管理员身份。 */
    private Actor scopedManager() {
        UUID employee=person();
        for(String module:List.of("PLATFORM","ARCHIVE")) {
            UUID role=UUID.randomUUID();String code="LAST_ADMIN_TEST_"+role;
            db.update("INSERT INTO iam.role(id,module,code,name) VALUES(?,?,?,?)",role,module,code,"管理员撤权验收操作人");
            db.update("INSERT INTO iam.role_permission(role_id,permission_id,scope_policy,effect,changed_by) SELECT ?,id,'COMPANY','ALLOW',? FROM iam.permission WHERE module=? AND code IN (?,?)",role,admin.userId(),module,module+".ROLES_MANAGE","PLATFORM.USERS_MANAGE");
            db.update("INSERT INTO iam.user_role(id,user_id,role_id,valid_from,assigned_by,module,role_code) VALUES(?,?,?,CURRENT_TIMESTAMP,?,?,?)",UUID.randomUUID(),employee,role,admin.userId(),module,code);
        }
        return login(employee.toString(),"test-password");
    }
    /** 调整仅在标注了回滚事务的测试中可见，不改变隔离库既有管理员任职。 */
    private UUID soleAdministrator(String module) {
        String code=module.equals("PLATFORM")?"IT_ADMIN":"SUPER_ADMIN";UUID employee=person();
        db.update("UPDATE iam.user_role SET state='ENDED' WHERE module=? AND role_code=? AND state='ACTIVE'",module,code);
        db.update("INSERT INTO iam.user_role(id,user_id,role_id,valid_from,assigned_by,module,role_code) VALUES(?,?,?,CURRENT_TIMESTAMP,?,?,?)",UUID.randomUUID(),employee,role(module,code),admin.userId(),module,code);
        assertEquals(1,db.count("SELECT count(*) FROM iam.user_role WHERE module=? AND role_code=? AND state='ACTIVE'",module,code));
        return employee;
    }
    @Test @Transactional void lastArchiveAdministratorRoleCanOnlyBeRevokedByPlatformIt() {
        Actor operator=scopedManager();
        UUID target=soleAdministrator("ARCHIVE");Actor subject=login(target.toString(),"test-password");
        var body=Sql.map("roles",List.of());
        var unauthorized=context(operator,"test-password","USER",target,"ASSIGN_ROLES",subject.authVersion());
        assertEquals(403,assertThrows(ApiFault.class,()->assign(unauthorized,target,body)).status);
        assertNotNull(identity.background(subject.sessionId()));
        var ctx=context(target,subject.authVersion());
        assign(ctx,target,body);assign(ctx,target,body);
        assertEquals(0,db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND state='ACTIVE'",target));
        assertEquals(1,db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND state='ENDED'",target));
        assertThrows(ApiFault.class,()->identity.background(subject.sessionId()));
        assertEquals(1,db.count("SELECT count(*) FROM ops.audit_event WHERE request_id=? AND record_kind='BUSINESS_EVENT' AND action='ASSIGN_ROLES' AND module='ARCHIVE'",ctx.request()));
    }
    /** 顶层目录的行政维护不依赖存储保护；子目录与永久清理仍执行保护检查。 */
    @Test @Transactional void driveItCanMaintainTopLevelDirectoryWithoutStorageProtection() {
        UUID employee=person();
        assign(context(employee,1),employee,List.of(Sql.map("roleId",role("PLATFORM","MEMBER")),
                Sql.map("roleId",role("DRIVE","IT_ADMIN"))));
        Actor driveIt=login(employee.toString(),"test-password");
        assertFalse(driveIt.systemAdministrator());
        UUID department=UUID.randomUUID();
        db.update("INSERT INTO iam.department(id,code,name) VALUES(?,?,?)",department,"DRIVE-"+department,"公盘隔离验收");
        var body=Sql.map("zone","DEPARTMENT_SHARED","departmentId",department.toString(),"name","公盘隔离验收");
        var adminContext=context(driveIt,"test-password","DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_FOLDER",1);
        UUID folder=(UUID)driveAccess.folder(adminContext,body).get("id");
        assertEquals(1,db.count("SELECT count(*) FROM drive.folder WHERE id=? AND parent_id IS NULL",folder));
        assertEquals(1,db.count("SELECT count(*) FROM ops.audit_event WHERE request_id=? AND record_kind='BUSINESS_EVENT' AND action='CREATE_FOLDER'",adminContext.request()));

        var reason=Sql.map("reason","隔离验收");
        var renamed=driveFolders.change(context(driveIt,"test-password","FOLDER",folder,"RENAME_FOLDER",1),folder,
                Sql.map("name","更名后的公盘目录","reason","隔离验收"),"RENAME_FOLDER");
        assertEquals("更名后的公盘目录",renamed.get("name"));
        var recycled=driveFolders.change(context(driveIt,"test-password","FOLDER",folder,"RECYCLE_FOLDER",2),folder,reason,"RECYCLE_FOLDER");
        assertEquals("RECYCLED",recycled.get("state"));
        var purge=context(driveIt,"test-password","FOLDER",folder,"PURGE_FOLDER",3);
        assertEquals("PROTECTION_NOT_READY",assertThrows(ApiFault.class,()->driveFolders.change(purge,folder,reason,"PURGE_FOLDER")).code);
        var restored=driveFolders.change(context(driveIt,"test-password","FOLDER",folder,"RESTORE_FOLDER",3),folder,reason,"RESTORE_FOLDER");
        assertEquals("ACTIVE",restored.get("state"));

        var child=Sql.map("parentId",folder.toString(),"zone","DEPARTMENT_SHARED","departmentId",department.toString(),"name","子目录");
        var childContext=context("DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_FOLDER",1);
        assertEquals("PROTECTION_NOT_READY",assertThrows(ApiFault.class,()->driveAccess.folder(childContext,child)).code);
        UUID staffId=person();
        assign(context(staffId,1),staffId,List.of(Sql.map("roleId",role("DRIVE","STAFF"))));
        Actor staff=login(staffId.toString(),"test-password");
        var staffContext=context(staff,"test-password","DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_FOLDER",1);
        assertEquals(403,assertThrows(ApiFault.class,()->driveAccess.folder(staffContext,body)).status);
        var staffRecycle=context(staff,"test-password","FOLDER",folder,"RECYCLE_FOLDER",4);
        assertEquals(403,assertThrows(ApiFault.class,()->driveFolders.change(staffRecycle,folder,reason,"RECYCLE_FOLDER")).status);
    }
    /** 委托建立与撤销只写管理记录，仍要求 IT 权限和正确的委托版本。 */
    @Test @Transactional void driveItCanConfigureDelegationWithoutStorageProtection() {
        UUID operator=person(),staffId=person(),managerId=person(),department=UUID.randomUUID();
        assign(context(operator,1),operator,List.of(Sql.map("roleId",role("DRIVE","IT_ADMIN"))));
        assign(context(staffId,1),staffId,List.of(Sql.map("roleId",role("DRIVE","STAFF"))));
        Actor driveIt=login(operator.toString(),"test-password"),staff=login(staffId.toString(),"test-password");
        db.update("INSERT INTO iam.department(id,code,name) VALUES(?,?,?)",department,"DRIVE-"+department,"委托验收部门");
        db.update("UPDATE iam.user_account SET department_id=? WHERE id=?",department,managerId);
        UUID managerRole=role("DRIVE","DEPARTMENT_MANAGER");
        assign(context(managerId,1),managerId,List.of(Sql.map("roleId",managerRole,"departmentId",department)));
        db.update("INSERT INTO iam.department_manager(id,department_id,user_id,duty_type,valid_from,change_reason,changed_by) VALUES(?,?,?,'DEPARTMENT_MANAGER',CURRENT_TIMESTAMP,'委托验收',?)",
                UUID.randomUUID(),department,managerId,admin.userId());
        Actor manager=login(managerId.toString(),"test-password");
        var folderBody=Sql.map("zone","DEPARTMENT_SHARED","departmentId",department,"name","委托验收目录");
        UUID folder=(UUID)driveAccess.folder(context("DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_FOLDER",1),folderBody).get("id");
        var query=Map.of("folderId",folder.toString());
        assertEquals(false,driveDocuments.documents(manager,query).get("folderCanUpload"));
        var body=Sql.map("departmentId",department,"folderId",folder,"roleId",managerRole,
                "allowedActions",List.of("UPLOAD","FOLDER_MANAGE"),"reason","委托验收");
        var create=context(driveIt,"test-password","DRIVE_COLLECTION",ApiController.COLLECTION,"CREATE_DELEGATION",1);
        UUID delegation=(UUID)driveAccess.delegate(create,body).get("id");
        assertEquals("ACTIVE",db.required("SELECT state FROM drive.delegation WHERE id=?",delegation).get("state"));
        assertEquals(true,driveDocuments.documents(manager,query).get("folderCanUpload"));
        assertEquals(false,driveDocuments.documents(driveIt,query).get("folderCanUpload"));
        var staffRevoke=context(staff,"test-password","DRIVE_DELEGATION",delegation,"REVOKE_DELEGATION",1);
        assertEquals(403,assertThrows(ApiFault.class,()->driveAccess.revokeDelegation(staffRevoke,delegation)).status);
        var stale=context(driveIt,"test-password","DRIVE_DELEGATION",delegation,"REVOKE_DELEGATION",2);
        assertEquals(409,assertThrows(ApiFault.class,()->driveAccess.revokeDelegation(stale,delegation)).status);
        assertEquals("ACTIVE",db.required("SELECT state FROM drive.delegation WHERE id=?",delegation).get("state"));
        var revoke=context(driveIt,"test-password","DRIVE_DELEGATION",delegation,"REVOKE_DELEGATION",1);
        assertEquals("REVOKED",driveAccess.revokeDelegation(revoke,delegation).get("state"));
        assertEquals(false,driveDocuments.documents(login(managerId.toString(),"test-password"),query).get("folderCanUpload"));
        assertEquals(2,db.count("SELECT count(*) FROM ops.audit_event WHERE request_id IN (?,?) AND record_kind='BUSINESS_EVENT' AND result='SUCCEEDED'",create.request(),revoke.request()));
    }
    /** 公盘员工能选择尚未建立共享目录的有效部门，人员名册仍受管理权限限制。 */
    @Test @Transactional void driveSharedFilterUsesActiveOrganizationDepartments() {
        UUID employee=person(),active=UUID.randomUUID(),disabled=UUID.randomUUID();
        String prefix="公盘筛选验收"+active;
        db.update("INSERT INTO iam.department(id,code,name,status) VALUES(?,?,?,'ACTIVE'),(?,?,?,'DISABLED')",
                active,"FILTER-"+active,prefix,disabled,"FILTER-"+disabled,prefix+"停用");
        assign(context(employee,1),employee,List.of(Sql.map("roleId",role("PLATFORM","MEMBER")),Sql.map("roleId",role("DRIVE","STAFF"))));
        Actor staff=login(employee.toString(),"test-password"),outsider=login(person().toString(),"test-password");
        var options=Map.of("projection","SELECTOR","purpose","DRIVE_SHARED_FILTER","keyword",prefix);

        var rows=(List<?>)directory.departments(staff,options).get("items");
        assertEquals(1,rows.size());
        var selected=(Map<?,?>)((Map<?,?>)rows.getFirst()).get("selector");
        assertEquals(active,selected.get("id"));
        assertEquals(prefix,selected.get("name"));
        assertEquals(403,assertThrows(ApiFault.class,()->directory.departments(outsider,options)).status);
        assertEquals(403,assertThrows(ApiFault.class,()->directory.departments(staff,Map.of())).status);
        assertEquals(403,assertThrows(ApiFault.class,()->directory.users(staff,options)).status);
    }
    /** 模块管理员可继续办理公盘及档案业务，管理中心角色及组织配置需平台 IT 任职。 */
    @Test @Transactional void moduleAdministratorsKeepBusinessActionsWithoutCentralConfiguration() {
        UUID employee=person();
        assign(context(employee,1),employee,List.of(Sql.map("roleId",role("PLATFORM","MEMBER")),
                Sql.map("roleId",role("DRIVE","IT_ADMIN")),Sql.map("roleId",role("ARCHIVE","SUPER_ADMIN"))));
        Actor operator=login(employee.toString(),"test-password");
        assertFalse(operator.systemAdministrator());
        assertTrue(operator.actions().containsAll(Set.of("DRIVE.ACCESS","DRIVE.ROLES_MANAGE","ARCHIVE.ACCESS","ARCHIVE.PHYSICAL_REVIEW")));
        assertTrue(directory.managedModules(operator).isEmpty());
        for(String module:List.of("PLATFORM","DRIVE","ARCHIVE")) {
            var query=Map.of("module",module);
            assertEquals(403,assertThrows(ApiFault.class,()->directory.roles(operator,query)).status);
            assertEquals(403,assertThrows(ApiFault.class,()->directory.permissions(operator,query)).status);
            assertFalse(directory.roles(admin,query).isEmpty());
        }
        assertEquals(403,assertThrows(ApiFault.class,()->directory.role(operator,role("DRIVE","IT_ADMIN"))).status);
        assertEquals(403,assertThrows(ApiFault.class,()->directory.userRoles(operator,employee)).status);
        assertEquals(403,assertThrows(ApiFault.class,()->organizationDuties.read(operator)).status);
        assertTrue(organizationDuties.read(admin).containsKey("duties"));
    }
    @Test @Transactional void lastAdministratorAccountCanBeDisabledWithExistingAuthority() {
        Actor operator=scopedManager();
        for(String module:List.of("PLATFORM","ARCHIVE")) {
            UUID target=soleAdministrator(module);Actor subject=login(target.toString(),"test-password");int version=(int)db.count("SELECT row_version FROM iam.user_account WHERE id=?",target);
            var ctx=context(operator,"test-password","USER",target,"USER_STATUS",version);var body=Sql.map("status","DISABLED","reason","已确认取消最后管理员保护的隔离验收");
            commands.run(ctx,body,"USER",target,"USER_STATUS",()->operator.require("PLATFORM.USERS_MANAGE"),()->directory.status(ctx,target,body),id->Sql.map("objectId",id));
            assertEquals("DISABLED",db.required("SELECT status FROM iam.user_account WHERE id=?",target).get("status"));
            assertEquals(0,db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND state='ACTIVE'",target));
            assertThrows(ApiFault.class,()->identity.background(subject.sessionId()));
        }
    }
    @Test @Transactional void lastAdministratorCanTransferWithoutAdministratorCountGate() {
        Actor operator=scopedManager();UUID department=(UUID)db.required("SELECT id FROM iam.department WHERE code='QA'").get("id");
        for(String module:List.of("PLATFORM","ARCHIVE")) {
            UUID target=soleAdministrator(module);Actor subject=login(target.toString(),"test-password");int version=(int)db.count("SELECT row_version FROM iam.user_account WHERE id=?",target);
            var ctx=context(operator,"test-password","USER",target,"CHANGE_DEPARTMENT",version);
            continuity.transfer(ctx,target,Sql.map("targetDepartmentId",department,"approvalRef","ISOLATED-TRANSFER","handoverNote","验证转岗按既有权限回收任职"));
            assertEquals(department,db.required("SELECT department_id FROM iam.user_account WHERE id=?",target).get("department_id"));
            assertEquals(0,db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND state='ACTIVE'",target));
            assertEquals(1,db.count("SELECT count(*) FROM iam.department_change WHERE user_id=?",target));
            assertThrows(ApiFault.class,()->identity.background(subject.sessionId()));
        }
    }
    @Test @Transactional void ordinaryEmployeeStillCannotRevokeOrDisableAdministrator() {
        UUID target=soleAdministrator("PLATFORM"),employee=person();Actor operator=login(employee.toString(),"test-password");
        var roles=context(operator,"test-password","USER",target,"ASSIGN_ROLES",1);
        assertEquals(403,assertThrows(ApiFault.class,()->assign(roles,target,Sql.map("roles",List.of()))).status);
        var status=context(operator,"test-password","USER",target,"USER_STATUS",1);var body=Sql.map("status","DISABLED","reason","无权限请求");
        assertEquals(403,assertThrows(ApiFault.class,()->commands.run(status,body,"USER",target,"USER_STATUS",()->operator.require("PLATFORM.USERS_MANAGE"),()->directory.status(status,target,body),id->Sql.map("objectId",id))).status);
        assertEquals("ACTIVE",db.required("SELECT status FROM iam.user_account WHERE id=?",target).get("status"));
        assertEquals(1,db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND state='ACTIVE'",target));
    }
    @Test @Transactional void itCanSaveOwnUnchangedRolesWithoutLosingSession() {
        UUID target=soleAdministrator("PLATFORM");Actor subject=login(target.toString(),"test-password");
        var before=db.required("SELECT row_version,auth_version FROM iam.user_account WHERE id=?",target);
        assign(context(subject,"test-password","USER",target,"ASSIGN_ROLES",subject.authVersion()),target,Sql.map("roles",assignedRoles(target)));
        assertEquals(before,db.required("SELECT row_version,auth_version FROM iam.user_account WHERE id=?",target));
        assertTrue(identity.background(subject.sessionId()).systemAdministrator());
    }
    @Test @Transactional void namedItCanUseExplicitBusinessRoles() {
        UUID target=soleAdministrator("PLATFORM");Actor subject=login(target.toString(),"test-password");
        var roles=new ArrayList<>(assignedRoles(target));roles.add(Sql.map("roleId",role("ARCHIVE","STAFF")));roles.add(Sql.map("roleId",role("DRIVE","STAFF")));
        assign(context(subject,"test-password","USER",target,"ASSIGN_ROLES",subject.authVersion()),target,Sql.map("roles",roles));
        assertThrows(ApiFault.class,()->identity.background(subject.sessionId()));
        Actor updated=login(target.toString(),"test-password");assertTrue(updated.systemAdministrator());assertTrue(updated.actions().contains("ARCHIVE.ACCESS"));assertFalse(updated.sharedItBorrower());
        assertEquals(3,db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND state='ACTIVE'",target));
    }
    @Test @Transactional void itCanRevokeOwnItIdentityAndOldSessionStops() {
        UUID target=soleAdministrator("PLATFORM");Actor subject=login(target.toString(),"test-password");
        assign(context(subject,"test-password","USER",target,"ASSIGN_ROLES",subject.authVersion()),target,Sql.map("roles",List.of(Sql.map("roleId",role("PLATFORM","MEMBER")))));
        assertThrows(ApiFault.class,()->identity.background(subject.sessionId()));
        Actor updated=login(target.toString(),"test-password");assertFalse(updated.systemAdministrator());assertFalse(updated.actions().contains("PLATFORM.ROLES_MANAGE"));
        assertEquals(0,db.count("SELECT CASE WHEN iam.system_administrator(?) THEN 1 ELSE 0 END",target));
    }
    @Test @Transactional void itCanConfigureAnOrdinaryRoleTheyCurrentlyUse() {
        UUID target=soleAdministrator("PLATFORM"),custom=UUID.randomUUID();String code="IT_USED_"+custom;
        db.update("INSERT INTO iam.role(id,module,code,name) VALUES(?,'PLATFORM',?,?)",custom,code,"IT 同时使用的普通角色");
        db.update("INSERT INTO iam.user_role(id,user_id,role_id,valid_from,assigned_by,module,role_code) VALUES(?,?,?,CURRENT_TIMESTAMP,?,'PLATFORM',?)",UUID.randomUUID(),target,custom,admin.userId(),code);
        Actor subject=login(target.toString(),"test-password");var ctx=context(subject,"test-password","ROLE",custom,"ROLE_PERMISSIONS",1);var body=Sql.map("permissions",List.of());
        commands.run(ctx,body,"ROLE",custom,"ROLE_PERMISSIONS",()->directory.checkModule(subject,"PLATFORM",false),()->directory.rolePermissions(ctx,custom,body),id->Sql.map("objectId",id));
        assertEquals(2,db.count("SELECT row_version FROM iam.role WHERE id=?",custom));assertThrows(ApiFault.class,()->identity.background(subject.sessionId()));
        assertEquals(subject.actions(),login(target.toString(),"test-password").actions());
    }
    @Test @Transactional void nonItManagerStillCannotAssignOrConfigureTheirOwnRoles() {
        Actor operator=scopedManager();var roles=context(operator,"test-password","USER",operator.userId(),"ASSIGN_ROLES",operator.authVersion());
        assertEquals(403,assertThrows(ApiFault.class,()->assign(roles,operator.userId(),Sql.map("roles",List.of(Sql.map("roleId",role("PLATFORM","IT_ADMIN")))))).status);
        UUID used=(UUID)operator.roles().stream().filter(item->item.get("module").equals("PLATFORM")).findFirst().orElseThrow().get("id");
        var ctx=context(operator,"test-password","ROLE",used,"ROLE_PERMISSIONS",1);var body=Sql.map("permissions",List.of());
        assertEquals(403,assertThrows(ApiFault.class,()->commands.run(ctx,body,"ROLE",used,"ROLE_PERMISSIONS",()->directory.checkModule(operator,"PLATFORM",false),()->directory.rolePermissions(ctx,used,body),id->Sql.map("objectId",id))).status);
        assertEquals(1,db.count("SELECT row_version FROM iam.role WHERE id=?",used));assertFalse(identity.background(operator.sessionId()).systemAdministrator());
    }
    /** 每个用例独立部门，使用正式角色分配建立已有档案员身份。 */
    private UUID clerk() {
        UUID employee=person(),department=UUID.randomUUID();
        db.update("INSERT INTO iam.department(id,code,name,parent_id) VALUES(?,?,?,?)",department,"R"+department.toString().substring(0,8),"角色差异验收部门",admin.user().get("departmentId"));db.update("UPDATE iam.user_account SET department_id=? WHERE id=?",department,employee);
        assign(context(employee,1),employee,List.of(Sql.map("roleId",role("PLATFORM","MEMBER")),Sql.map("roleId",role("DRIVE","STAFF")),Sql.map("roleId",role("ARCHIVE","DEPARTMENT_ARCHIVIST"),"departmentId",department)));
        return employee;
    }
    private List<Map<String,Object>> assignedRoles(UUID user) {
        return db.rows("SELECT role_id,department_id FROM iam.user_role WHERE user_id=? AND state='ACTIVE' ORDER BY id",user).stream().map(row->Sql.map("roleId",row.get("role_id"),"departmentId",row.get("department_id"))).toList();
    }
    /** 重现完整表单仅修改公盘：不要求交接，空白依据可提交，其他任职及审计模块保持准确。 */
    @Test void driveOnlyChangePreservesExistingArchivistAndPlatformAssignments() {
        UUID employee=clerk();var before=db.rows("SELECT * FROM iam.user_role WHERE user_id=? AND module<>'DRIVE' ORDER BY id",employee);Actor subject=login(employee.toString(),"test-password");
        var roles=new ArrayList<>(assignedRoles(employee));roles.removeIf(item->item.get("roleId").equals(role("DRIVE","STAFF")));roles.add(Sql.map("roleId",role("DRIVE","DEPARTMENT_MANAGER"),"departmentId",subject.user().get("departmentId")));
        var ctx=context(employee,subject.authVersion());var body=Sql.map("roles",roles);assign(ctx,employee,body);assign(ctx,employee,body);
        assertEquals(before,db.rows("SELECT * FROM iam.user_role WHERE user_id=? AND module<>'DRIVE' ORDER BY id",employee));assertEquals(1,db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND module='DRIVE' AND state='ACTIVE' AND role_code='DEPARTMENT_MANAGER' AND approval_ref IS NULL",employee));assertEquals(1,db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND module='DRIVE' AND state='ENDED'",employee));assertThrows(ApiFault.class,()->identity.background(subject.sessionId()));
        assertEquals(1,db.count("SELECT count(*) FROM ops.audit_event WHERE request_id=? AND record_kind='BUSINESS_EVENT' AND action='ASSIGN_ROLES' AND module='DRIVE'",ctx.request()));assertEquals(0,db.count("SELECT count(*) FROM ops.audit_event WHERE request_id=? AND record_kind='BUSINESS_EVENT' AND action='ASSIGN_ROLES' AND module<>'DRIVE'",ctx.request()));
    }
    @Test void unchangedRoleSetKeepsSessionsVersionsAndAssignmentHistory() {
        UUID employee=clerk();Actor subject=login(employee.toString(),"test-password");var assignments=db.rows("SELECT * FROM iam.user_role WHERE user_id=? ORDER BY id",employee);var user=db.required("SELECT row_version,auth_version FROM iam.user_account WHERE id=?",employee);var ctx=context(employee,subject.authVersion());
        assign(ctx,employee,Sql.map("roles",assignedRoles(employee).reversed()));
        assertEquals(assignments,db.rows("SELECT * FROM iam.user_role WHERE user_id=? ORDER BY id",employee));assertEquals(user,db.required("SELECT row_version,auth_version FROM iam.user_account WHERE id=?",employee));assertNotNull(identity.background(subject.sessionId()));assertEquals(0,db.count("SELECT count(*) FROM ops.audit_event WHERE request_id=? AND record_kind='BUSINESS_EVENT' AND action='ASSIGN_ROLES'",ctx.request()));
    }
    @Test void addingArchiveStaffRetainsTheUnchangedArchivistAssignment() {
        UUID employee=clerk();var original=db.required("SELECT * FROM iam.user_role WHERE user_id=? AND role_code='DEPARTMENT_ARCHIVIST' AND state='ACTIVE'",employee);var roles=new ArrayList<>(assignedRoles(employee));roles.add(Sql.map("roleId",role("ARCHIVE","STAFF")));
        assign(context(employee,(int)db.count("SELECT auth_version FROM iam.user_account WHERE id=?",employee)),employee,roles);
        assertEquals(original,db.required("SELECT * FROM iam.user_role WHERE id=?",original.get("id")));assertEquals(2,db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND module='ARCHIVE' AND state='ACTIVE'",employee));
    }
    @Test void removingArchivistStillRequiresTheDedicatedHandover() {
        UUID employee=clerk();var before=db.rows("SELECT * FROM iam.user_role WHERE user_id=? ORDER BY id",employee);var roles=new ArrayList<>(assignedRoles(employee));roles.removeIf(item->item.get("roleId").equals(role("ARCHIVE","DEPARTMENT_ARCHIVIST")));
        var error=assertThrows(ApiFault.class,()->assign(context(employee,(int)db.count("SELECT auth_version FROM iam.user_account WHERE id=?",employee)),employee,roles));assertEquals(409,error.status);assertTrue(error.getMessage().contains("专用交接流程"));assertEquals(before,db.rows("SELECT * FROM iam.user_role WHERE user_id=? ORDER BY id",employee));
    }
    @Test void ordinaryRoleChangesNeedNoManualEvidenceAndStillValidateLegacyInput() {
        UUID employee=person();assign(context(employee,1),employee,Sql.map("roles",List.of(Sql.map("roleId",role("DRIVE","STAFF"))),"reason","  ","approvalRef",""));
        var roles=List.of(Sql.map("roleId",role("DRIVE","IT_ADMIN")));int version=(int)db.count("SELECT auth_version FROM iam.user_account WHERE id=?",employee);
        assertThrows(ApiFault.class,()->assign(context(employee,version),employee,Sql.map("roles",roles,"reason","长".repeat(1001))));assertThrows(ApiFault.class,()->assign(context(employee,version),employee,Sql.map("roles",roles,"approvalRef",123)));assertEquals(1,db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND state='ACTIVE' AND role_code='STAFF'",employee));
        assign(context(employee,version),employee,Sql.map("roles",List.of(Sql.map("roleId",role("PLATFORM","MEMBER")))));
        int nextVersion=(int)db.count("SELECT auth_version FROM iam.user_account WHERE id=?",employee);
        assign(context(employee,nextVersion),employee,Sql.map("roles",List.of(Sql.map("roleId",role("ARCHIVE","STAFF")))));
        assertEquals(1,db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND state='ACTIVE' AND module='ARCHIVE' AND role_code='STAFF'",employee));
    }
    @Test void rolePermissionEditingNeedsNoManualEvidence() {
        UUID id=UUID.randomUUID();db.update("INSERT INTO iam.role(id,module,code,name) VALUES(?,'PLATFORM',?,?)",id,"VERIFY_"+id,"隔离角色权限验收");
        UUID permission=(UUID)db.required("SELECT id FROM iam.permission WHERE module='PLATFORM' ORDER BY id LIMIT 1").get("id");
        var body=Sql.map("permissions",List.of(Sql.map("permissionId",permission,"scopePolicy",Sql.map("code","COMPANY"),"effect","ALLOW")));
        var ctx=context("ROLE",id,"ROLE_PERMISSIONS",1);
        commands.run(ctx,body,"ROLE",id,"ROLE_PERMISSIONS",()->admin.require("PLATFORM.ROLES_MANAGE"),()->directory.rolePermissions(ctx,id,body),target->Sql.map("objectId",target));
        assertEquals(1,db.count("SELECT count(*) FROM iam.role_permission WHERE role_id=? AND permission_id=? AND effect='ALLOW'",id,permission));
    }
    @Test void fixedCompanyAndNineDepartments() {
        assertEquals("BLK27",directory.departments(admin,Map.of()).get("companyCode"));
        String[][] expected={{"AD","行政部"},{"DHS/MEP","安全环保部"},{"YF","研发部"},{"SC","生产运营部"},{"SD","仓储部"},{"QC","质量检定部"},{"QA","质量保证部"},{"PD","采购部"},{"ED","设备项目部"}};
        for(var item:expected) assertEquals(1,db.count("SELECT count(*) FROM iam.department d JOIN iam.department p ON p.id=d.parent_id WHERE d.code=? AND d.name=? AND d.status='ACTIVE' AND p.code='ROOT'",item[0],item[1]));
    }
    @Test void sharedItManagesPlatformAndCannotPerformArchiveBusiness() {
        assertTrue(admin.systemAdministrator());
        assertTrue(admin.sharedItBorrower());
        assertTrue(admin.actions().containsAll(Set.of("PLATFORM.USERS_MANAGE","PLATFORM.EVIDENCE_RECORD","ARCHIVE.ROLES_MANAGE","ARCHIVE.PHYSICAL_BORROW","DRIVE.ACCESS")));
        assertTrue(admin.actions().contains("PLATFORM.ANNOUNCEMENTS_MANAGE"));
        assertFalse(archive.role(admin,"SUPER_ADMIN",null));
        assertThrows(ApiFault.class, () -> catalog.options(admin));
        assertEquals(0,db.count("SELECT CASE WHEN archive.catalog_manager(?,?) THEN 1 ELSE 0 END",admin.userId(),admin.user().get("departmentId")));
        var roles=new ArrayList<>(assignedRoles(admin.userId()));
        roles.add(Sql.map("roleId",role("ARCHIVE","SUPER_ADMIN")));
        assertEquals(400,assertThrows(ApiFault.class,()->assign(context(admin.userId(),admin.authVersion()),admin.userId(),roles)).status);
    }
    @Test void canAssignFirstArchiveClerkAndReplayWithoutDuplicates() {
        UUID employee=person(),department=UUID.randomUUID();var ctx=context(employee,1);
        // 每轮独立任职范围，重复运行不会占用上一轮唯一部门档案员名额。
        db.update("INSERT INTO iam.department(id,code,name,parent_id) VALUES(?,?,?,?)",department,"T"+department.toString().substring(0,8),"隔离任职部门",admin.user().get("departmentId"));
        db.update("UPDATE iam.user_account SET department_id=? WHERE id=?",department,employee);
        var roles=List.of(Sql.map("roleId",role("PLATFORM","MEMBER")),Sql.map("roleId",role("DRIVE","STAFF")),Sql.map("roleId",role("ARCHIVE","DEPARTMENT_ARCHIVIST"),"departmentId",department));
        assign(ctx,employee,roles);assign(ctx,employee,roles);
        assertEquals(3,db.count("SELECT count(*) FROM iam.user_role WHERE user_id=? AND state='ACTIVE'",employee));
    }
    @Test void conflictingDriveRolesFailClearlyAndRollback() {
        UUID employee=person();var ctx=context(employee,1);
        ApiFault failure=assertThrows(ApiFault.class,()->assign(ctx,employee,List.of(Sql.map("roleId",role("DRIVE","STAFF")),Sql.map("roleId",role("DRIVE","IT_ADMIN")))));
        assertEquals(409,failure.status);assertTrue(failure.getMessage().contains("一个有效公盘角色"));
        assertEquals(0,db.count("SELECT count(*) FROM iam.user_role WHERE user_id=?",employee));
    }
    @Test void normalEmployeeDoesNotBecomeSystemAdministrator() {
        UUID employee=person();Actor staff=login(employee.toString(),"test-password");
        assertFalse(staff.systemAdministrator());assertFalse(archive.canManage(staff,(UUID)admin.user().get("departmentId")));
        assertThrows(ApiFault.class,()->directory.roles(staff,Map.of("module","ARCHIVE")));
        assertEquals(0,db.count("SELECT CASE WHEN iam.system_administrator(?) THEN 1 ELSE 0 END",employee));
    }
    @Test void endingItRoleRevokesOldSessionAndSqlAuthority() {
        UUID employee=person();assign(context(employee,1),employee,List.of(Sql.map("roleId",role("PLATFORM","IT_ADMIN"))));
        Actor second=login(employee.toString(),"test-password");assertTrue(second.systemAdministrator());
        assign(context(employee,second.authVersion()),employee,List.of(Sql.map("roleId",role("PLATFORM","MEMBER"))));
        assertThrows(ApiFault.class,()->identity.background(second.sessionId()));
        assertEquals(0,db.count("SELECT CASE WHEN iam.system_administrator(?) THEN 1 ELSE 0 END",employee));
        assertFalse(login(employee.toString(),"test-password").systemAdministrator());
    }
    @Autowired com.company.platform.storage.DriveFilePolicy drive;
    @Test @Transactional void itCanReadAndDownloadOtherDepartmentDriveWithoutWriting() {
        UUID folder=UUID.randomUUID(),department=(UUID)db.required("SELECT id FROM iam.department WHERE code='DHS/MEP'").get("id");
        db.update("INSERT INTO drive.folder(id,zone,department_id,folder_level,name,name_key) VALUES(?,'DEPARTMENT_INTERNAL',?,'TOP',?,?)",folder,department,folder.toString(),folder.toString());
        for(String action:List.of("VIEW","DOWNLOAD","UPLOAD","DELETE","MOVE","RESTORE","PURGE","FOLDER_MANAGE","COLLABORATE")) assertEquals(Set.of("VIEW","DOWNLOAD").contains(action)?1:0,db.count("SELECT CASE WHEN drive.can_folder(?,?,?) THEN 1 ELSE 0 END",admin.userId(),folder,action),action);
        UUID document=UUID.randomUUID();
        db.update("INSERT INTO drive.document(id,folder_id,publishing_department_id,uploaded_by,name,name_key,collaboration_enabled) VALUES(?,?,?,?,?,?,true)",
            document,folder,department,admin.userId(),document.toString(),document.toString());
        assertEquals(document,drive.requireDocument(admin,document,"VIEW").get("id"));
        assertEquals(document,drive.requireDocument(admin,document,"DOWNLOAD").get("id"));
        for(String action:List.of("EDIT","DELETE","MOVE","COLLABORATE"))
            assertEquals(403,assertThrows(ApiFault.class,()->drive.requireDocument(admin,document,action)).status,action);
        UUID employee=person();assertEquals(0,db.count("SELECT CASE WHEN drive.can_folder(?,?,'UPLOAD') THEN 1 ELSE 0 END",employee,folder));
        db.update("UPDATE drive.folder SET state='RECYCLED' WHERE id=?",folder);
        assertEquals(0,db.count("SELECT CASE WHEN drive.can_folder(?,?,'VIEW') THEN 1 ELSE 0 END",admin.userId(),folder));
    }
}
