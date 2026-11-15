package com.company.platform.storage;

import com.company.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 电脑端公盘API；授权在服务内按实际对象检查，页面只接收PNG且禁止缓存。 */
@RestController
@RequestMapping("/api/v1/drive")
public class DriveController {
    /** 发布、领域检查、页面交付和可见目录查询分别由单一边界承担。 */
    private final DriveDocumentService documents;private final ControlledReadService reading;private final DriveFilePolicy policy;private final Sql db;
    public DriveController(DriveDocumentService documents,ControlledReadService reading,DriveFilePolicy policy,Sql db) { this.documents=documents;this.reading=reading;this.policy=policy;this.db=db; }
    /** 目录分页前过滤权限；每层有界加载，避免逐项授权查询和无权数量泄露。 */
    @GetMapping("/folders") public Map<String,Object> folders(@RequestParam Map<String,String> query,HttpServletRequest request) {
        Actor actor=actor(request);actor.require("DRIVE.ACCESS");Inputs.only(query,"parentId","page","pageSize","zone","departmentId","space");
        UUID parent=query.get("parentId")==null?null:Inputs.uuid(query.get("parentId"));if(parent!=null)policy.requireFolder(actor,parent,"VIEW");
        UUID department=query.get("departmentId")==null?null:Inputs.uuid(query.get("departmentId"));String zone=query.getOrDefault("zone","");if(!zone.isEmpty())Inputs.choice(zone,"COMPANY_SHARED","DEPARTMENT_SHARED","DEPARTMENT_INTERNAL");
        int page=Inputs.integer(query.get("page"),1,1,100000),size=Inputs.integer(query.get("pageSize"),100,1,200);
        DriveSpace space=DriveSpace.from(query);
        List<Object> parameters=new ArrayList<>();
        String level="f.parent_id IS NOT DISTINCT FROM ?::uuid";
        if(parent==null && space!=DriveSpace.ALL) {
            // 内部目录可以挂在部门共享目录下，直接从分区入口开始浏览。
            level="NOT EXISTS (SELECT 1 FROM drive.folder p WHERE p.id=f.parent_id AND p.zone=f.zone)";
        } else {
            parameters.add(parent);
        }
        Collections.addAll(parameters,actor.userId(),zone,zone,department,department);
        String where=" FROM drive.folder f WHERE "+level+" AND f.state='ACTIVE' AND drive.can_folder(?,f.id,'VIEW') AND (?='' OR f.zone=?) AND (?::uuid IS NULL OR f.department_id=?)"+space.condition(actor,parameters);
        long total=db.count("SELECT count(*)"+where,parameters.toArray());
        parameters.addAll(0,List.of(actor.userId(),actor.userId()));
        Collections.addAll(parameters,size,(page-1)*size);
        var rows=db.rows("SELECT f.*,(SELECT name FROM iam.department d WHERE d.id=f.department_id) AS department_name,drive.can_folder(?,f.id,'UPLOAD') AS can_upload,drive.manager_scope(?,f.id,'FOLDER_MANAGE') AS can_manage"+where+" ORDER BY f.name,f.id LIMIT ? OFFSET ?",parameters.toArray());
        return ok(Sql.map("items",rows.stream().map(r->Sql.view(r,"id,parent_id,name,zone,department_id,department_name,row_version,can_upload,can_manage")).toList(),"page",page,"pageSize",size,"total",total,"hasNext",(long)page*size<total),request);
    }
    /** 当前目录正式文档分页，文档级拒绝在计数前生效。 */
    @GetMapping("/documents") public Map<String,Object> list(@RequestParam Map<String,String> query,HttpServletRequest request) {return ok(documents.documents(actor(request),query),request);}
    /** 检测通过后由有权上传人正式发布，同一操作号防止重复发布。 */
    @PostMapping("/documents") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> publish(@RequestBody Map<String,Object> body,HttpServletRequest request) {return ok(documents.publish(actor(request),body,operation(request),ApiController.requestId(request)),request);}
    /** 详情只投影受权字段，不能取得内部对象键。 */
    @GetMapping("/documents/{id}") public Map<String,Object> detail(@PathVariable String id,HttpServletRequest request) {return ok(documents.detail(actor(request),Inputs.uuid(id)),request);}
    /** 当前已发布PDF版本的异步预览，候选版本不适用此接口。 */
    @PostMapping("/documents/{id}/previews") @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String,Object> create(@PathVariable String id,@RequestParam Map<String,String> query,@RequestBody Map<String,Object> body,HttpServletRequest request) {
        Inputs.only(query,"purpose","saveReceiptId");
        if(!"PUBLISHED".equals(query.getOrDefault("purpose","PUBLISHED"))||query.containsKey("saveReceiptId")) throw ApiFault.invalid("当前接口仅开放已发布版本阅读");
        return ok(reading.create(actor(request),Inputs.uuid(id),body,operation(request),ApiController.requestId(request)),request);
    }
    /** 只有本人固定会话可取得页数和准备结果。 */
    @GetMapping("/documents/{documentId}/previews/{id}") public Map<String,Object> manifest(@PathVariable String documentId,@PathVariable String id,HttpServletRequest request) {return ok(manifestFor(actor(request),Inputs.uuid(documentId),Inputs.uuid(id)),request);}
    /** 每页重新核权，返回受限PNG；无原PDF下载路由。 */
    @GetMapping(value="/documents/{documentId}/previews/{id}/pages/{number}",produces="image/png")
    public ResponseEntity<byte[]> page(@PathVariable String documentId,@PathVariable String id,@PathVariable String number,HttpServletRequest request) {
        manifestFor(actor(request),Inputs.uuid(documentId),Inputs.uuid(id));
        byte[] page=reading.page(actor(request),Inputs.uuid(id),Inputs.integer(number,0,1,10000),ApiController.requestId(request));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").header("Content-Disposition","inline; filename=page.png").body(page);
    }
    /** 路径中的文档也必须与预览固定来源一致，不能替换成无关文档标识。 */
    private Map<String,Object> manifestFor(Actor actor,UUID document,UUID preview) {var result=reading.manifest(actor,preview);if(!document.equals(result.get("documentId"))) throw ApiFault.hidden();return result;}
    /** 身份由公共HTTPS边界注入，不从请求体接受操作者。 */
    private Actor actor(HttpServletRequest request) { if(request.getAttribute(HttpBoundary.ACTOR) instanceof Actor actor) return actor;throw ApiFault.session(); }
    /** 操作标识只用于重试去重，不作为授权凭据。 */
    private UUID operation(HttpServletRequest request) {return Inputs.uuid(request.getHeader("X-Operation-Id"));}
    /** 统一可审计响应，不回传数据库内部字段。 */
    private Map<String,Object> ok(Object data,HttpServletRequest request) {return ApiController.envelope("OK","操作成功",data,ApiController.requestId(request));}
}
