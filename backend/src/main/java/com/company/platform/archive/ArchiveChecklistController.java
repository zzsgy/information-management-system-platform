package com.company.platform.archive;

import com.company.platform.*;
import com.company.platform.storage.OfficeGateway;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;

/** 电子清单与文档对应的盒内入口；上传和正式归档继续走既有授权、检测和再验证。 */
@RestController
public class ArchiveChecklistController {
    private final ArchiveChecklists checklists;private final OfficeGateway office;
    public ArchiveChecklistController(ArchiveChecklists checklists,OfficeGateway office){this.checklists=checklists;this.office=office;}
    @GetMapping("/api/v1/archive/boxes/{box}/checklist-items") public Map<String,Object> items(@PathVariable UUID box,HttpServletRequest request){return ok(checklists.items(actor(request),box),request);}
    /** 在获权部门读取本人检测通过的登记清单，仅预览、不创建档案盒。 */
    @PostMapping("/api/v1/archive/registration-checklist-preview")
    public Map<String,Object> registrationPreview(@RequestBody Map<String,Object> body,HttpServletRequest request){
        Inputs.only(body,"departmentId","uploadSessionId");
        return ok(checklists.registrationPreview(actor(request),Inputs.uuid(body.get("departmentId")),Inputs.uuid(body.get("uploadSessionId"))),request);
    }
    /** 草稿盒的单条补录需要再验证与版本一致，序号由服务端分配。 */
    @PostMapping("/api/v1/archive/boxes/{box}/checklist-items")
    public Map<String,Object> appendItem(@PathVariable UUID box,@RequestBody Map<String,Object> body,HttpServletRequest request){
        return ok(checklists.appendItem(context(request),box,body),request);
    }
    @PostMapping("/api/v1/archive/boxes/{box}/checklist-preview") public Map<String,Object> preview(@PathVariable UUID box,@RequestBody Map<String,Object> body,HttpServletRequest request){Inputs.only(body,"uploadSessionId");return ok(checklists.preview(actor(request),box,Inputs.uuid(body.get("uploadSessionId"))),request);}
    @PostMapping("/api/v1/archive/boxes/{box}/checklists") public Map<String,Object> create(@PathVariable UUID box,@RequestBody Map<String,Object> body,HttpServletRequest request){return ok(checklists.importChecklist(context(request),box,body),request);}
    @PostMapping("/api/v1/archive/boxes/{box}/electronic-files") public Map<String,Object> bind(@PathVariable UUID box,@RequestBody Map<String,Object> body,HttpServletRequest request){return ok(checklists.bind(context(request),box,body),request);}
    @PostMapping("/api/v1/archive/records/{record}/prepare-electronic") public Map<String,Object> prepare(@PathVariable UUID record,@RequestBody Map<String,Object> body,HttpServletRequest request){Inputs.only(body);return ok(checklists.prepare(actor(request),record),request);}
    /** 独立签名源地址只用于当前档案转换，浏览器会话不能代替文档服务签名。 */
    @GetMapping("/integrations/onlyoffice/archive-source/{id}") public void source(@PathVariable UUID id,HttpServletRequest request,HttpServletResponse response)throws IOException{String token=request.getHeader("Authorization");if(token==null||!token.startsWith("Bearer "))throw ApiFault.denied();var payload=office.nativePayload(token.substring(7),true);var object=checklists.conversionSource(id,payload);response.setHeader("Cache-Control","no-store");response.setContentType("application/octet-stream");response.setContentLengthLong(((Number)object.get("byte_size")).longValue());checklists.streamSource(id,payload,response.getOutputStream());}
    private Actor actor(HttpServletRequest request){var actor=(Actor)request.getAttribute(HttpBoundary.ACTOR);if(actor==null)throw ApiFault.session();return actor;}
    private Commands.Context context(HttpServletRequest request){String version=request.getHeader("X-Expected-Version");return new Commands.Context(actor(request),ApiController.requestId(request),Inputs.uuid(request.getHeader("X-Operation-Id")),request.getMethod()+" "+request.getRequestURI(),request.getHeader("X-Reauth-Token"),version==null?null:Inputs.integer(version,0,1,Integer.MAX_VALUE));}
    private Map<String,Object> ok(Object value,HttpServletRequest request){return ApiController.envelope("OK","操作成功",value,ApiController.requestId(request));}
}
