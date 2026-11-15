package com.company.platform.archive;

import com.company.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.util.*;

/** 受批副本Service API；HTTPS身份与CSRF由统一边界校验，动作固定在实际路由。 */
@RestController @RequestMapping("/api/v1/archive")
public class ArchiveExportController {
    /** 下载与打印共享技术处理器，但任何接口都不能互换批准动作。 */
    private final ArchiveExportService service;
    public ArchiveExportController(ArchiveExportService service) {this.service=service;}
    @PostMapping("/download-requests") @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> download(@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(service.create(actor(r),"DOWNLOAD",body,operation(r),ApiController.requestId(r)),r);}
    @PostMapping("/print-requests") @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> print(@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(service.create(actor(r),"PRINT",body,operation(r),ApiController.requestId(r)),r);}
    /** 元数据与正文均重新核验固定来源；知道任务号不授予内容权。 */
    @GetMapping("/download-requests/{id}") public Map<String,Object> downloadState(@PathVariable String id,HttpServletRequest r) {return ok(service.manifest(actor(r),Inputs.uuid(id),"DOWNLOAD"),r);}
    @GetMapping("/print-requests/{id}") public Map<String,Object> printState(@PathVariable String id,HttpServletRequest r) {return ok(service.manifest(actor(r),Inputs.uuid(id),"PRINT"),r);}
    @GetMapping("/export-requests") public Map<String,Object> history(HttpServletRequest r) {return ok(service.history(actor(r)),r);}
    /** 单份交付水印PDF，批量下载交付完整ZIP；不允许公共静态缓存。 */
    @GetMapping("/download-requests/{id}/content") public ResponseEntity<StreamingResponseBody> content(@PathVariable String id,HttpServletRequest r) {UUID usage=Inputs.uuid(id);var manifest=service.manifest(actor(r),usage,"DOWNLOAD");boolean batch=((List<?>)manifest.get("items")).size()>1;return binary(r,usage,"DOWNLOAD",batch?"application/zip":"application/pdf","档案使用副本"+(batch?".zip":".pdf"));}
    @GetMapping("/print-requests/{id}/content") public ResponseEntity<StreamingResponseBody> printContent(@PathVariable String id,HttpServletRequest r) {return binary(r,Inputs.uuid(id),"PRINT","application/pdf","打印使用副本.pdf");}
    /** 电脑端打印页含烘入像素的个人水印，页序已展开批准份数。 */
    @GetMapping(value="/print-requests/{id}/pages/{page}",produces="image/png") public ResponseEntity<byte[]> page(@PathVariable String id,@PathVariable String page,HttpServletRequest r) {return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(service.page(actor(r),Inputs.uuid(id),Inputs.integer(page,0,1,300),operation(r),ApiController.requestId(r)));}
    /** 仅记录浏览器声明，VERIFIED_PRINTED没有可信设备适配器时必定拒绝。 */
    @PostMapping("/print-requests/{id}/feedback") public Map<String,Object> feedback(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r) {return ok(service.feedback(actor(r),Inputs.uuid(id),body,operation(r)),r);}
    private ResponseEntity<StreamingResponseBody> binary(HttpServletRequest r,UUID usage,String action,String type,String filename) {Actor actor=actor(r);UUID operation=operation(r),request=ApiController.requestId(r);var ticket=service.prepare(actor,usage,action,operation,request);return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).contentLength(ticket.size()).cacheControl(CacheControl.noStore()).header("Content-Disposition",ContentDisposition.attachment().filename(filename,java.nio.charset.StandardCharsets.UTF_8).build().toString()).header("X-Content-SHA256",ticket.sha()).header("X-Content-Type-Options","nosniff").body(output->service.stream(ticket,output));}
    private Actor actor(HttpServletRequest r) {if(r.getAttribute(HttpBoundary.ACTOR) instanceof Actor actor) return actor;throw ApiFault.session();}
    private UUID operation(HttpServletRequest r) {return Inputs.uuid(r.getHeader("X-Operation-Id"));}
    private Map<String,Object> ok(Object value,HttpServletRequest r) {return ApiController.envelope("OK","操作成功",value,ApiController.requestId(r));}
}
