package com.company.platform.storage;

import com.company.platform.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实PostgreSQL验证授权边界与到期提醒，全部夹具在事务结束时回滚。 */
@EnabledIfEnvironmentVariable(named="IMS_SERVICE_IDENTITIES_INTEGRATION",matches="yes")
class ServiceIdentitiesIntegrationTest {
    private Sql db;private TransactionTemplate tx;private ServiceIdentities services;private UUID id,department;
    @BeforeEach void setup(){
        String url=System.getenv("IMS_PLATFORM_DB_URL");
        if(url==null||!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:55475/ims_platform_dev_[0-9]+"))throw new IllegalStateException("只允许55475隔离测试库");
        var source=new DriverManagerDataSource(url,System.getenv("IMS_PLATFORM_DB_USER"),System.getenv("IMS_PLATFORM_DB_PASSWORD"));
        db=new Sql(new JdbcTemplate(source));tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        id=UUID.randomUUID();department=UUID.randomUUID();
        services=new ServiceIdentities(db,new MockEnvironment().withProperty("platform.scanner.service-id",id.toString()));
    }
    private void fixture(Runnable checks){tx.executeWithoutResult(status->{
        db.update("INSERT INTO iam.service_identity(id,service_type,name,allowed_module,department_scope,allowed_actions,credential_ref,valid_until) VALUES(?,'FILE_CHECK','到期边界隔离测试','DRIVE',?::uuid[],'{FILE_CHECK}','test-only',CURRENT_TIMESTAMP+interval '1 year')",id,"{"+department+"}");
        try{checks.run();}finally{status.setRollbackOnly();}
    });}
    private String state(){return services.status().getFirst().get("state").toString();}
    private void require(){services.require(id,"FILE_CHECK","DRIVE",department,"FILE_CHECK");}
    @Test void healthyExpiringExpiredAndRenewed(){fixture(()->{
        assertEquals("READY",state());require();
        db.update("UPDATE iam.service_identity SET valid_until=CURRENT_TIMESTAMP+interval '29 days' WHERE id=?",id);assertEquals("EXPIRING",state());require();
        db.update("UPDATE iam.service_identity SET valid_until=CURRENT_TIMESTAMP WHERE id=?",id);assertEquals("EXPIRED",state());assertEquals("SERVICE_IDENTITY_EXPIRED",assertThrows(ApiFault.class,this::require).code);
        db.update("UPDATE iam.service_identity SET valid_until=CURRENT_TIMESTAMP+interval '1 year' WHERE id=?",id);assertEquals("READY",state());require();
    });}
    @Test void wrongDepartmentActionAndModuleStayBlocked(){fixture(()->{
        assertEquals("SERVICE_IDENTITY_SCOPE_MISMATCH",assertThrows(ApiFault.class,()->services.require(id,"FILE_CHECK","DRIVE",UUID.randomUUID(),"FILE_CHECK")).code);
        assertEquals("SERVICE_IDENTITY_SCOPE_MISMATCH",assertThrows(ApiFault.class,()->services.require(id,"FILE_CHECK","DRIVE",department,"EDIT")).code);
        assertEquals("SERVICE_IDENTITY_UNAVAILABLE",assertThrows(ApiFault.class,()->services.require(id,"FILE_CHECK","ARCHIVE",department,"FILE_CHECK")).code);
    });}
    @Test void disabledAndMissingAreNotHealthy(){fixture(()->{
        db.update("UPDATE iam.service_identity SET status='DISABLED' WHERE id=?",id);assertEquals("DISABLED",state());assertEquals("SERVICE_IDENTITY_UNAVAILABLE",assertThrows(ApiFault.class,this::require).code);
        db.update("DELETE FROM iam.service_identity WHERE id=?",id);assertEquals("NOT_CONFIGURED",state());assertEquals("SERVICE_IDENTITY_UNAVAILABLE",assertThrows(ApiFault.class,this::require).code);
    });}
}
