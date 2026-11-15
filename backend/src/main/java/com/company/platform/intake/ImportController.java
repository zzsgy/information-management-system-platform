package com.company.platform.intake;

import com.company.platform.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 导入 API：类型与部门范围分别核验，预检不能写业务表，确认必须携带版本和本人再验证。 */
@RestController @RequestMapping("/api/v1/imports")
public class ImportController {
    /** 台账领域服务。 */
    private final ImportService imports;
    public ImportController(ImportService imports){this.imports=imports;}
    /** 当前模板为标准 XLSX，下载不授予导入范围。 */
    @GetMapping("/templates/{type}") public ResponseEntity<byte[]> template(@PathVariable String type,@RequestParam(defaultValue="BOX_LEDGER") String format,HttpServletRequest r){return ResponseEntity.ok().header("Cache-Control","no-store").header("Content-Disposition","attachment; filename=\"import-"+ImportWorkbook.type(type)+(type.equals("ARCHIVE")&&format.equals("BOX_LEDGER")?"-boxes":"")+".xlsx\"").contentType(MediaType.parseMediaType(ImportWorkbook.MEDIA)).body(imports.template(actor(r),type,format));}
    /** 只返回相应类型的字段说明和部门选择范围。 */
    @GetMapping("/options/{type}") public Map<String,Object> options(@PathVariable String type,@RequestParam(defaultValue="BOX_LEDGER") String format,HttpServletRequest r){return ok(imports.options(actor(r),type,format),r);}
    /** 已完成真实检测的 XLSX 才可建立预检批次，结构错误以批次错误显示。 */
    @PostMapping("/batches") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> create(@RequestBody Map<String,Object> b,HttpServletRequest r){return ok(imports.create(context(r),b),r);}
    /** 分页仅列当前仍有权读取的本人批次。 */
    @GetMapping("/batches") public Map<String,Object> list(@RequestParam Map<String,String> q,HttpServletRequest r){return ok(imports.list(actor(r),q),r);}
    /** 当前批次汇总与预检摘要。 */
    @GetMapping("/batches/{id}") public Map<String,Object> detail(@PathVariable String id,HttpServletRequest r){return ok(imports.detail(actor(r),Inputs.uuid(id)),r);}
    /** 按原表行号分页返回字段错误和新增对象。 */
    @GetMapping("/batches/{id}/rows") public Map<String,Object> rows(@PathVariable String id,@RequestParam Map<String,String> q,HttpServletRequest r){return ok(imports.rows(actor(r),Inputs.uuid(id),q),r);}
    /** 重新鉴权及整批校验后才写业务；相同操作号返回原批次。 */
    @PostMapping("/batches/{id}/confirm") public Map<String,Object> confirm(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r){return ok(imports.confirm(context(r),Inputs.uuid(id),b),r);}
    /** 未发布且无外部关联的档案批次才可原子撤回。 */
    @PostMapping("/batches/{id}/withdraw") public Map<String,Object> withdraw(@PathVariable String id,@RequestBody Map<String,Object> b,HttpServletRequest r){return ok(imports.withdraw(context(r),Inputs.uuid(id),b),r);}
    /** 身份只取自会话过滤器。 */
    private Actor actor(HttpServletRequest r){var actor=(Actor)r.getAttribute(HttpBoundary.ACTOR);if(actor==null)throw ApiFault.session();return actor;}
    /** 固定命令绑定 HTTP 路由、操作号和版本。 */
    private Commands.Context context(HttpServletRequest r){String version=r.getHeader("X-Expected-Version");return new Commands.Context(actor(r),ApiController.requestId(r),Inputs.uuid(r.getHeader("X-Operation-Id")),r.getMethod()+" "+r.getRequestURI(),r.getHeader("X-Reauth-Token"),version==null?null:Inputs.integer(version,0,1,Integer.MAX_VALUE));}
    /** 统一响应不回传文件内部路径。 */
    private Map<String,Object> ok(Object value,HttpServletRequest r){return ApiController.envelope("OK","操作成功",value,ApiController.requestId(r));}
}
