package com.company.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 纯隔离单测：核验库外收紧水位、损坏拒绝与请求规范化，不连接任何数据库。 */
class PlatformSafetyTest {
    /** 每个测试由 JUnit 提供独立临时目录。 */
    @TempDir Path root;
    /** 重启后保留收紧水位，数据库旧代次不能重新获得访问。 */
    @Test void ledgerSurvivesRestartAndRejectsDatabaseRollback() {
        UUID instance=UUID.randomUUID(), user=UUID.randomUUID();
        SecurityLedger ledger=new SecurityLedger(root.toString()); ledger.bind(instance,true);
        ledger.restrict(user,3,"ROLE_CHANGE",UUID.randomUUID()); ledger.check(user,3);
        SecurityLedger restarted=new SecurityLedger(root.toString()); restarted.bind(instance,false);
        assertEquals("PROTECTION_NOT_READY",assertThrows(ApiFault.class,()->restarted.check(user,2)).code);
        assertDoesNotThrow(()->restarted.check(user,3));
    }
    /** 已有数据库缺少库外事实时拒绝自动重建，防止把未知状态当作安全。 */
    @Test void missingLedgerCannotBeRecreatedForExistingDatabase() {
        assertThrows(ApiFault.class,()->new SecurityLedger(root.toString()).bind(UUID.randomUUID(),false));
    }
    /** 不同实例必须使用不同账本，不能混用保护水位。 */
    @Test void ledgerCannotBeSharedByAnotherDatabase() {
        new SecurityLedger(root.toString()).bind(UUID.randomUUID(),true);
        assertThrows(ApiFault.class,()->new SecurityLedger(root.toString()).bind(UUID.randomUUID(),false));
    }
    /** 运行期间账本缩短立即拒绝访问。 */
    @Test void truncatedLedgerClosesAccess() throws Exception {
        UUID user=UUID.randomUUID(); SecurityLedger ledger=new SecurityLedger(root.toString()); ledger.bind(UUID.randomUUID(),true);
        ledger.restrict(user,2,"DISABLE",UUID.randomUUID()); Files.writeString(root.resolve("events.jsonl"),"");
        assertThrows(ApiFault.class,()->ledger.check(user,2));
    }
    /** 不完整记录不能按有效历史继续提供授权。 */
    @Test void malformedLedgerClosesAccess() throws Exception {
        SecurityLedger ledger=new SecurityLedger(root.toString()); ledger.bind(UUID.randomUUID(),true);
        Files.writeString(root.resolve("events.jsonl"),"{incomplete");
        assertThrows(ApiFault.class,()->ledger.check(UUID.randomUUID(),1));
    }
    /** 独立持久链头使重启后的完整行截断也无法逃过检查。 */
    @Test void truncationIsRejectedAfterRestart() throws Exception {
        UUID instance=UUID.randomUUID(); SecurityLedger ledger=new SecurityLedger(root.toString()); ledger.bind(instance,true);
        ledger.restrict(UUID.randomUUID(),2,"DISABLE",UUID.randomUUID()); Files.writeString(root.resolve("events.jsonl"),"");
        assertThrows(ApiFault.class,()->new SecurityLedger(root.toString()).bind(instance,false));
    }
    /** 核验字段白名单、整数版本、公司邮箱和非空个人密码契约。 */
    @Test void requestWhitelistRejectsUnknownFieldsAndFractionalVersions() {
        assertThrows(ApiFault.class,()->Inputs.only(Map.of("name","张三","admin",true),"name"));
        assertThrows(ApiFault.class,()->Inputs.integer(1.5,1,1,100));
        assertThrows(ApiFault.class,()->Inputs.email(Map.of("companyEmail","person@example.com")));
        assertEquals("x",Inputs.password(Map.of("password","x"),"password"));
    }
    /** JSON 对象换序可以重放，同一操作号变更业务内容则不能误判相同。 */
    @Test void canonicalRequestDigestIgnoresObjectKeyOrderButNotContent() {
        assertEquals(Crypto.json(Sql.map("b",2,"a",1)),Crypto.json(Sql.map("a",1,"b",2)));
        assertNotEquals(Crypto.json(Map.of("a",1)),Crypto.json(Map.of("a",2)));
    }
}
