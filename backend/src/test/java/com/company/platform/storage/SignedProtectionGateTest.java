package com.company.platform.storage;

import com.company.platform.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 保护门槛使用真实签名和磁盘高水位；仅实例查询使用固定测试替身。 */
class SignedProtectionGateTest {
    /** 每项测试使用新的私有路径和独立数据库实例标识。 */
    @TempDir Path root;
    private final UUID instance=UUID.randomUUID();private final String secret=Crypto.token();
    /** 替身不开放业务SQL，只响应实例绑定查询。 */
    private final Sql db=new Sql(new JdbcTemplate()) {@Override public Map<String,Object> required(String sql,Object... args) {assertEquals("SELECT id FROM platform_meta.instance",sql);return Map.of("id",instance);}};
    private Path key,evidence;
    /** 秘钥和证据只能属于该测试目录，不读取部署环境。 */
    @BeforeEach void setup() throws Exception {key=root.resolve("key");evidence=root.resolve("state.json");Files.writeString(key,secret);PrivateFiles.restrict(key);}
    private SignedProtectionGate gate() {return new SignedProtectionGate(db,evidence.toString(),key.toString(),"test-node");}
    /** 证据所有字段参加规范化签名；调用者可明确模拟现场异常。 */
    private Map<String,Object> state(int epoch) {Instant now=Instant.now();return Sql.map("instanceId",instance.toString(),"nodeId","test-node","epoch",epoch,"issuedAt",now.toString(),"expiresAt",now.plusSeconds(60).toString(),"mode","NORMAL","oldPrimaryFenced",true,"replicaVerifiedAt",now.toString(),"backupId",UUID.randomUUID().toString(),"backupProtectedAt",now.toString(),"writableModules",List.of("DRIVE"));}
    private void write(Map<String,Object> data) throws Exception {data.remove("signature");data.put("signature",Crypto.hmac(secret,Crypto.json(data)));Files.writeString(evidence,Crypto.json(data));}
    /** 正常签名可用，但篡改任一字段及未获准模块立即拒绝。 */
    @Test void signedCurrentEvidenceCannotBeTamperedOrUsedForAnotherModule() throws Exception {
        var current=state(1);write(current);var gate=gate();gate.requireWritable("DRIVE","UPLOAD");gate.requireReadable("DRIVE");
        assertEquals(503,assertThrows(ApiFault.class,()->gate.requireWritable("ARCHIVE","UPLOAD")).status);
        current.put("oldPrimaryFenced",false);Files.writeString(evidence,Crypto.json(current));assertEquals(503,assertThrows(ApiFault.class,()->gate.requireReadable("DRIVE")).status);
    }
    /** 主备和恢复点新鲜度分别核验，只读状态不等于写保护已恢复。 */
    @Test void staleReplicaBackupAndUnfencedPrimaryBlockWrites() throws Exception {
        var current=state(1);current.put("replicaVerifiedAt",Instant.now().minusSeconds(31).toString());write(current);assertThrows(ApiFault.class,()->gate().requireWritable("DRIVE","UPLOAD"));
        current=state(1);current.put("backupProtectedAt",Instant.now().minusSeconds(1801).toString());write(current);assertThrows(ApiFault.class,()->gate().requireWritable("DRIVE","UPLOAD"));
        current=state(1);current.put("oldPrimaryFenced",false);write(current);assertThrows(ApiFault.class,()->gate().requireReadable("DRIVE"));
        current=state(1);current.put("mode","ALL_READ_ONLY");write(current);gate().requireReadable("DRIVE");assertThrows(ApiFault.class,()->gate().requireWritable("DRIVE","UPLOAD"));
    }
    /** 服务重启后仍读取库外最高接管代次，旧主证据和高水位篡改均拒绝。 */
    @Test void durableEpochRejectsRollbackAcrossProcessRestart() throws Exception {
        write(state(2));gate().requireReadable("DRIVE");write(state(1));assertThrows(ApiFault.class,()->gate().requireReadable("DRIVE"));
        Files.writeString(root.resolve("key.epoch"),"{}");write(state(3));assertThrows(ApiFault.class,()->gate().requireReadable("DRIVE"));
    }
}
