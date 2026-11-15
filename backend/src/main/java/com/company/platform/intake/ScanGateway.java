package com.company.platform.intake;

import com.company.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** 扫描代理入口仅接收经过签名的固定批次，不能查询员工、目录或文件正文。 */
@RestController @RequestMapping("/internal/archive")
public class ScanGateway {
    /** 服务接收与业务状态分别处理。 */
    private final ScanUpload uploads;private final ScanIntake intake;
    public ScanGateway(ScanUpload uploads,ScanIntake intake){this.uploads=uploads;this.intake=intake;}
    /** 签名正文含稳定操作号；响应仅给分片会话。 */
    @PostMapping("/scan-upload-sessions") public Map<String,Object> create(HttpServletRequest r)throws IOException{var body=body(r);UUID operation=Inputs.uuid(body.remove("operationId"));return ok(uploads.create(service(r),operation,body),r);}
    /** 断线恢复先核对当前轮次及已接收分片。 */
    @GetMapping("/scan-upload-sessions/{id}") public Map<String,Object> status(@PathVariable String id,HttpServletRequest r){return ok(uploads.status(service(r),Inputs.uuid(id)),r);}
    /** 实测字节摘要必须等于签名固定的摘要；范围与固定分片大小一致。 */
    @PutMapping("/scan-upload-sessions/{id}/parts/{number}") public Map<String,Object> part(@PathVariable String id,@PathVariable int number,HttpServletRequest r)throws IOException{return ok(uploads.part(service(r),Inputs.uuid(id),number,r.getHeader("Content-Range"),r.getContentLengthLong(),r.getHeader("X-Content-Sha256"),r.getInputStream()),r);}
    /** 后台检测的重复提交重用同一任务。 */
    @PostMapping("/scan-upload-sessions/{id}/complete") public Map<String,Object> complete(@PathVariable String id,HttpServletRequest r)throws IOException{return ok(uploads.complete(service(r),Inputs.uuid(id),body(r)),r);}
    /** 检测完成后绑定候选并返回业务回执，人工质检与发布分别办理。 */
    @PostMapping("/scan-receipts") public Map<String,Object> receive(HttpServletRequest r)throws IOException{return ok(uploads.receive(service(r),body(r)),r);}
    /** 设备读取最小回执，不能获取题名或正文。 */
    @GetMapping("/scan-batches/{id}") public Map<String,Object> receipt(@PathVariable String id,HttpServletRequest r){return ok(intake.receipt(service(r),Inputs.uuid(id)),r);}
    /** JSON 原始字节与签名一致后才解析，避免签名与使用内容不一致。 */
    @SuppressWarnings("unchecked") private Map<String,Object> body(HttpServletRequest r)throws IOException{byte[] bytes=r.getInputStream().readNBytes(65537);if(bytes.length>65536)throw ApiFault.invalid("正文过大");try{String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));if(!Crypto.equal(hash,r.getHeader("X-Content-Sha256")))throw ApiFault.denied();return Crypto.JSON.readValue(bytes,Map.class);}catch(ApiFault failure){throw failure;}catch(Exception failure){throw ApiFault.invalid("扫描 JSON 格式不正确");}}
    /** 只能使用本次签名通过的服务身份。 */
    private UUID service(HttpServletRequest r){var id=r.getAttribute(ScanHttpBoundary.SERVICE);if(!(id instanceof UUID value))throw ApiFault.denied();return value;}
    /** 接收结果不得带内部文件键或签名密钥。 */
    private Map<String,Object> ok(Object data,HttpServletRequest r){return ApiController.envelope("OK","接收操作完成",data,ApiController.requestId(r));}
}
