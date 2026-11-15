package com.company.platform.storage;

import com.company.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;

/** V1.1 公共上传 API；所有对象范围、状态和业务允许均由服务端重新检查。 */
@RestController
@RequestMapping("/api/v1")
public class UploadController {
    private final UploadService uploads;
    public UploadController(UploadService uploads) { this.uploads=uploads; }
    /** 创建上传会话；需要本人会话、领域上传授权和实时写保护。 */
    @PostMapping("/storage/upload-sessions") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@RequestBody Map<String,Object> body,HttpServletRequest request) { return ok(uploads.create(actor(request),body,operation(request),id(request)),request); }
    /** 读取本人会话和已收分片序号；不能读取内部路径或原件。 */
    @GetMapping("/storage/upload-sessions/{uploadId}")
    public Map<String,Object> get(@PathVariable String uploadId,HttpServletRequest request) { return ok(uploads.get(actor(request),Inputs.uuid(uploadId)),request); }
    /** 接收一片 octet-stream；校验长度、范围和内容摘要，拒绝超过固定上限。 */
    @PutMapping(value="/storage/upload-sessions/{uploadId}/parts/{partNo}",consumes="application/octet-stream")
    public Map<String,Object> part(@PathVariable String uploadId,@PathVariable String partNo,HttpServletRequest request) throws IOException {
        return ok(uploads.part(actor(request),Inputs.uuid(uploadId),Inputs.integer(partNo,0,1,640),request.getHeader("Content-Range"),request.getContentLengthLong(),request.getHeader("X-Content-SHA256"),request.getInputStream(),id(request)),request);
    }
    /** 完成接收并受理异步处理；202 只表示任务已登记。 */
    @PostMapping("/storage/upload-sessions/{uploadId}/complete") @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> complete(@PathVariable String uploadId,@RequestBody Map<String,Object> body,HttpServletRequest request) { return ok(uploads.complete(actor(request),Inputs.uuid(uploadId),body,operation(request),id(request)),request); }
    /** 取消尚未提交的本人上传，不删除正式对象或历史。 */
    @DeleteMapping("/storage/upload-sessions/{uploadId}")
    public Map<String,Object> cancel(@PathVariable String uploadId,HttpServletRequest request) { return ok(uploads.cancel(actor(request),Inputs.uuid(uploadId),operation(request),id(request)),request); }
    /** 查询本人后台任务，重查当前业务授权，不暴露内部处理地址。 */
    @GetMapping("/jobs/{jobId}")
    public Map<String,Object> job(@PathVariable String jobId,HttpServletRequest request) { return ok(uploads.job(actor(request),Inputs.uuid(jobId)),request); }
    /** 仅本人、仍有原目录上传权且保护正常时重试检测技术失败，不放行可疑对象。 */
    @PostMapping("/jobs/{jobId}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> retry(@PathVariable String jobId,HttpServletRequest request) { operation(request);return ok(uploads.retry(actor(request),Inputs.uuid(jobId),id(request)),request); }
    private Actor actor(HttpServletRequest request) { Object value=request.getAttribute(HttpBoundary.ACTOR); if(!(value instanceof Actor actor)) throw ApiFault.session(); return actor; }
    private UUID id(HttpServletRequest request) { return ApiController.requestId(request); }
    private UUID operation(HttpServletRequest request) { return Inputs.uuid(request.getHeader("X-Operation-Id")); }
    private Map<String,Object> ok(Object value,HttpServletRequest request) { return ApiController.envelope("OK","操作成功",value,id(request)); }
}
