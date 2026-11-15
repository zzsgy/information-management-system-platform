package com.company.platform.workflow;

import com.company.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 凭据 Service API：申请人提交、专门获权 IT 核验；二进制每次重新授权，无公开地址。 */
@RestController @RequestMapping("/api/v1")
public class EvidenceController {
    /** 不把通用档案管理权限作为 IT 核验权限。 */
    private final WorkflowEvidence evidence;
    public EvidenceController(WorkflowEvidence evidence){this.evidence=evidence;}
    /** 最多十份已检测原件；版本或节点失效返回冲突，提交不自动同意。 */
    @PostMapping("/workflow/steps/{id}/evidence") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> submit(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(evidence.submit(context(r),Inputs.uuid(id),body),r);}
    /** 本人或原合法处理人查看本节点提交记录。 */
    @GetMapping("/workflow/steps/{id}/evidence") public Map<String,Object> step(@PathVariable String id,HttpServletRequest r){return ok(evidence.forStep(actor(r),Inputs.uuid(id)),r);}
    /** 分页核验队列仅向 PLATFORM.EVIDENCE_RECORD 开放。 */
    @GetMapping("/admin/workflow/evidence") public Map<String,Object> queue(@RequestParam Map<String,String> query,HttpServletRequest r){return ok(evidence.queue(actor(r),query),r);}
    /** 已提交快照是本次明确核验范围，不开放其他档案全文。 */
    @GetMapping("/workflow/evidence-submissions/{id}") public Map<String,Object> detail(@PathVariable String id,HttpServletRequest r){return ok(evidence.detail(actor(r),Inputs.uuid(id)),r);}
    /** 绑定 WORKFLOW_STEP / EVIDENCE_RECORD 再验证；当前节点批准后仍执行下一必要节点。 */
    @PostMapping("/workflow/steps/{id}/evidence-decisions") public Map<String,Object> decide(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(evidence.decide(context(r),Inputs.uuid(id),body),r);}
    /** 核验退回不代替业务审批驳回，申请人可重新准备材料。 */
    @PostMapping("/workflow/evidence-submissions/{id}/reject") public Map<String,Object> reject(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(evidence.close(context(r),Inputs.uuid(id),body,true),r);}
    /** 申请人仅能撤回自己的待核验凭据，历史决定保持不可变。 */
    @PostMapping("/workflow/evidence-submissions/{id}/cancel") public Map<String,Object> cancel(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r){return ok(evidence.close(context(r),Inputs.uuid(id),body,false),r);}
    /** 已检测原件支持单字节区间；多区间和越界返回 416，禁止共享缓存及内容嗅探。 */
    @GetMapping("/workflow/evidence/{id}/content") public ResponseEntity<byte[]> content(@PathVariable String id,HttpServletRequest r){
        var value=evidence.content(actor(r),Inputs.uuid(id),ApiController.requestId(r));byte[] bytes=value.bytes();int[] range=range(r.getHeader("Range"),bytes.length);
        var response=ResponseEntity.status(range==null?200:206).cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(value.media())).header("X-Content-Type-Options","nosniff").header("Accept-Ranges","bytes").header("Content-Disposition",ContentDisposition.attachment().filename(value.name(),StandardCharsets.UTF_8).build().toString());
        if(range!=null)response.header("Content-Range","bytes "+range[0]+"-"+range[1]+"/"+bytes.length);
        return response.body(range==null?bytes:Arrays.copyOfRange(bytes,range[0],range[1]+1));
    }
    /** 区间使用长整型解析后核对长度，避免溢出或负数绕过。 */
    static int[] range(String header,int length){if(header==null)return null;try{if(!header.matches("bytes=(\\d+-\\d*|-\\d+)"))throw new IllegalArgumentException();String[] parts=header.substring(6).split("-",-1);long start=parts[0].isEmpty()?Math.max(0,(long)length-Long.parseLong(parts[1])):Long.parseLong(parts[0]);long end=parts[0].isEmpty()||parts[1].isEmpty()?length-1L:Math.min(Long.parseLong(parts[1]),length-1L);if(start<0||start>=length||end<start)throw new IllegalArgumentException();return new int[]{(int)start,(int)end};}catch(Exception e){throw new ApiFault(416,"RANGE_NOT_SATISFIABLE","文件区间无效");}}
    /** 仅接受公共 HTTPS 边界验证过的主体。 */
    private Actor actor(HttpServletRequest r){if(r.getAttribute(HttpBoundary.ACTOR) instanceof Actor a)return a;throw ApiFault.session();}
    /** 操作号、再验证与版本均绑定实际路由，不能由正文指定操作人。 */
    private Commands.Context context(HttpServletRequest r){String version=r.getHeader("X-Expected-Version");return new Commands.Context(actor(r),ApiController.requestId(r),Inputs.uuid(r.getHeader("X-Operation-Id")),r.getMethod()+" "+r.getRequestURI(),r.getHeader("X-Reauth-Token"),version==null?null:Inputs.integer(version,0,1,Integer.MAX_VALUE));}
    /** 通用接口包装只输出安全业务结果。 */
    private Map<String,Object> ok(Object value,HttpServletRequest r){return ApiController.envelope("OK","操作成功",value,ApiController.requestId(r));}
}
