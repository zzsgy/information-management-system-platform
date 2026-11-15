package com.company.platform.storage;

import com.company.platform.Crypto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

/** 独立临时目录测试：仅生成无害文本和测试用自定义摘要签名。 */
class ClamFileScannerTest {
    @TempDir Path root;
    /** 不存在的检测器和签名库必须返回不可判定。 */
    @Test void missingScannerIsNeverClean() {
        var scanner=new ClamFileScanner(root.resolve("missing.exe"),root.resolve("db"),root.resolve("temp"),"0".repeat(64),Duration.ofSeconds(1),true);
        assertEquals(ClamFileScanner.Verdict.UNAVAILABLE,scanner.scan(root.resolve("sample")));
    }
    /** 显式指定独立测试引擎时，真实 clamscan 应区分无害命中和普通内容。 */
    @Test @EnabledIfEnvironmentVariable(named="IMS_CLAM_INTEGRATION",matches="yes")
    void actualEngineDetectsTestSignatureAndRejectsUnofficialAsProduction() throws Exception {
        Path exe=Path.of(System.getenv("IMS_CLAM_TEST_EXE")),db=Files.createDirectory(root.resolve("db")),temp=Files.createDirectory(root.resolve("temp"));
        byte[] marker="IMS-INTEGRATION-SIGNATURE-ONLY".getBytes(StandardCharsets.UTF_8);
        String md5=HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(marker));
        Files.writeString(db.resolve("sample.hdb"),md5+":"+marker.length+":IMS.Integration.Test\n",StandardCharsets.UTF_8);
        Path suspicious=root.resolve("signature.txt"),clean=root.resolve("clean.txt"); Files.write(suspicious,marker); Files.writeString(clean,"innocent sample");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(exe)));
        var testScanner=new ClamFileScanner(exe,db,temp,hash,Duration.ofSeconds(30),false);
        assertEquals(ClamFileScanner.Verdict.SUSPICIOUS,testScanner.scan(suspicious));
        assertEquals(ClamFileScanner.Verdict.CLEAN,testScanner.scan(clean));
        assertEquals(ClamFileScanner.Verdict.UNAVAILABLE,new ClamFileScanner(exe,db,temp,hash,Duration.ofSeconds(30),true).scan(clean));
        assertEquals(ClamFileScanner.Verdict.UNAVAILABLE,new ClamFileScanner(exe,db,temp,"0".repeat(64),Duration.ofSeconds(30),false).scan(clean));
    }
}
