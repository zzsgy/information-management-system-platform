package com.company.ims.operations;

import com.company.ims.identity.PermissionGuard;
import com.company.ims.shared.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** 兼容桌面端安全运维路由；所有操作使用独立security.manage权限。 */
@RestController
@RequestMapping("/api/security")
public class OperationsController {
    /** 数据库与附件一体化备份服务。 */
    private final BackupService backups;
    /** 注入运维服务，控制器不接受任意数据库或文件系统目标。 */
    public OperationsController(BackupService backups) { this.backups=backups; }

    /** 重算全部历史附件版本的长度及SHA256。 */
    @GetMapping("/integrity/check")
    public Object integrity() { return ApiResponse.ok(backups.integrity(PermissionGuard.require("security.manage"))); }
    /** 创建经校验的数据库及附件整体备份包。 */
    @PostMapping("/db-backup")
    public Object backup() { return ApiResponse.ok(backups.create(PermissionGuard.require("security.manage")),"数据库和附件整体备份已完成并校验"); }
    /** 查询完整备份列表，未完成临时文件不在列表中。 */
    @GetMapping("/db-backups")
    public Object list() { PermissionGuard.require("security.manage"); return ApiResponse.ok(backups.list()); }
    /** 校验包并返回409离线恢复提示，绝不把未执行恢复显示为成功。 */
    @PostMapping("/db-restore")
    public Object restore(@RequestBody Map<String,Object> body) { backups.requestRestore(body,PermissionGuard.require("security.manage")); throw new IllegalStateException("恢复请求必须返回明确离线提示"); }
    /** 删除确认的单个备份，不提供目录删除或任意路径接口。 */
    @DeleteMapping("/db-backups/{filename}")
    public Object delete(@PathVariable String filename,@RequestParam(defaultValue="false") boolean confirm) {
        backups.delete(filename,confirm,PermissionGuard.require("security.manage")); return ApiResponse.ok(null,"备份已删除");
    }
}
