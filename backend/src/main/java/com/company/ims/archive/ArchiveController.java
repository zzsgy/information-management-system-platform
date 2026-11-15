package com.company.ims.archive;

import com.company.ims.shared.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 档案 HTTP 兼容层；接口地址保留旧客户端约定，业务校验全部委托应用服务。 */
@RestController
@RequestMapping("/api/archives")
public class ArchiveController {
    /** 档案应用服务，集中处理权限、事务和状态。 */
    private final ArchiveService service;
    /** 构造控制器并注入应用服务。 */
    public ArchiveController(ArchiveService service) { this.service=service; }
    /** 按查询参数分页读取当前用户可见档案。 */
    @GetMapping public ApiResponse list(@RequestParam Map<String,String> filters) { return ApiResponse.ok(service.list(filters)); }
    /** 新建草稿档案，body 不得指定内部生命周期状态。 */
    @PostMapping public ApiResponse create(@RequestBody Map<String,Object> body) { return ApiResponse.ok(service.create(body),"档案创建成功"); }
    /** 读取当前授权范围内的统计，不会汇总其他部门数据。 */
    @GetMapping("/stats") public ApiResponse stats() { return ApiResponse.ok(service.stats()); }
    /** 共享管理使用的最小身份候选，不返回电话号码或其他账号管理数据。 */
    @GetMapping("/share-subjects") public ApiResponse subjects(@RequestParam String type) { return ApiResponse.ok(service.shareSubjects(type)); }
    /** 读取指定档案详情。 */
    @GetMapping("/{id}") public ApiResponse detail(@PathVariable long id) { return ApiResponse.ok(service.detail(id)); }
    /** 更新允许编辑的元数据，并记录前后快照。 */
    @PutMapping("/{id}") public ApiResponse update(@PathVariable long id,@RequestBody Map<String,Object> body) { return ApiResponse.ok(service.update(id,body),"档案更新成功"); }
    /** 执行草稿到已归档的唯一合法转换。 */
    @PostMapping("/{id}/archive") public ApiResponse archive(@PathVariable long id) { service.archive(id);return ApiResponse.ok(null,"档案已归档"); }
    /** 返回可追溯的元数据变更记录。 */
    @GetMapping("/{id}/update-logs") public ApiResponse logs(@PathVariable long id) { return ApiResponse.ok(service.changes(id)); }
    /** 读取当前档案的共享授权清单。 */
    @GetMapping("/{id}/grants") public ApiResponse grants(@PathVariable long id) { return ApiResponse.ok(service.grants(id)); }
    /** 向指定人员或部门授予一个明确的档案操作权限。 */
    @PostMapping("/{id}/grants") public ApiResponse grant(@PathVariable long id,@RequestBody Map<String,Object> body) { return ApiResponse.ok(service.grant(id,body),"共享授权已生效"); }
    /** 撤销授权记录，后续请求立即重新校验范围。 */
    @DeleteMapping("/{id}/grants/{grantId}") public ApiResponse revoke(@PathVariable long id,@PathVariable long grantId) { service.revoke(id,grantId);return ApiResponse.ok(null,"共享授权已撤销"); }
}
