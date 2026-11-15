package com.company.ims.catalog;

import com.company.ims.identity.AuthenticationService;
import com.company.ims.identity.CurrentUser;
import com.company.ims.shared.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 原桌面客户端的系统配置和档案分类兼容入口。 */
@RestController
@RequestMapping("/api")
public class CatalogController {
    /** 基础目录业务服务负责精确权限和输入校验。 */
    private final CatalogService catalogs;
    /** 当前统一身份到旧客户端响应结构的投影服务。 */
    private final AuthenticationService authentication;

    /** 注入目录与身份投影服务。 */
    public CatalogController(CatalogService catalogs, AuthenticationService authentication) {
        this.catalogs = catalogs;
        this.authentication = authentication;
    }

    /** 返回实时角色与权限，桌面菜单不能依赖登录时缓存的特权角色名称。 */
    @GetMapping("/system/info")
    public Object info() {
        // 在统一身份字段上增加服务展示信息，不暴露部署连接配置。
        Map<String, Object> data = authentication.userInfo(CurrentUser.require());
        data.put("app_name", "信息管理系统 · 档案管理");
        data.put("version", "0.1.0-java");
        return ApiResponse.ok(data);
    }

    /** 档案表单的密级和保管期限下拉数据。 */
    @GetMapping("/system/dicts")
    public Object dictionaries() { return ApiResponse.ok(catalogs.dictionaries(CurrentUser.require())); }

    /** 查询四种明确开放的系统目录，未知资源不进入动态SQL。 */
    @GetMapping("/system/{kind:dict-items|security-levels|retention-periods|numbering-rules}")
    public Object list(@PathVariable String kind, @RequestParam(name = "type_code", required = false) String typeCode,
                       @RequestParam(name = "include_disabled", defaultValue = "false") boolean includeDisabled) {
        return ApiResponse.ok(catalogs.list(CurrentUser.require(), kind, typeCode, includeDisabled));
    }

    /** 新建基础配置，写权限由每种目录的固定定义控制。 */
    @PostMapping("/system/{kind:dict-items|security-levels|retention-periods|numbering-rules}")
    public Object create(@PathVariable String kind, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(catalogs.save(CurrentUser.require(), kind, null, body), "配置创建成功");
    }

    /** 更新基础配置，部分字段更新仍校验合并后的完整规则。 */
    @PutMapping("/system/{kind:dict-items|security-levels|retention-periods|numbering-rules}/{id}")
    public Object update(@PathVariable String kind, @PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(catalogs.save(CurrentUser.require(), kind, id, body), "配置更新成功");
    }

    /** 仅删除未被档案、分类或编号历史引用的配置。 */
    @DeleteMapping("/system/{kind:dict-items|security-levels|retention-periods|numbering-rules}/{id}")
    public Object delete(@PathVariable String kind, @PathVariable long id) {
        catalogs.delete(CurrentUser.require(), kind, id);
        return ApiResponse.ok(null, "配置已删除");
    }

    /** 按分类与部门预览编号，不消耗流水号。 */
    @GetMapping("/system/numbering-rules/preview")
    public Object preview(@RequestParam(name = "dept_id") long departmentId, @RequestParam(name = "category_id") long categoryId,
                          @RequestParam(name = "retention_id", required = false) Long retentionId) {
        return ApiResponse.ok(catalogs.preview(CurrentUser.require(), departmentId, categoryId, retentionId));
    }

    /** 读取公开展示参数，未设置时返回空值。 */
    @GetMapping("/system/param/{key}")
    public Object parameter(@PathVariable String key) { return ApiResponse.ok(catalogs.parameter(CurrentUser.require(), key)); }

    /** 更新首页公开参数，拒绝任意配置键或敏感连接参数。 */
    @PutMapping("/system/param/{key}")
    public Object setParameter(@PathVariable String key, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(catalogs.setParameter(CurrentUser.require(), key, body), "参数已保存");
    }

    /** 读取auto/manual编号方式，保持旧客户端mode字段。 */
    @GetMapping("/system/archive-no-mode")
    public Object mode() {
        // 该参数在空库迁移中预置，缺少时使用与旧客户端一致的auto。
        Object value = catalogs.parameter(CurrentUser.require(), "archive_no_mode").get("value");
        return ApiResponse.ok(Map.of("mode", value == null ? "auto" : value));
    }

    /** 修改编号方式，业务层要求archive.admin精确授权。 */
    @PutMapping("/system/archive-no-mode")
    public Object setMode(@RequestBody Map<String, Object> body) {
        CatalogService.checkKeys(body, java.util.Set.of("mode"));
        // 必须先校验字符串，避免Map.of接收空值导致非业务异常。
        String value = CatalogService.text(body.get("mode"), "编号方式", true, 10);
        catalogs.setParameter(CurrentUser.require(), "archive_no_mode", Map.of("value", value));
        return ApiResponse.ok(Map.of("mode", value), "编号模式已更新");
    }

    /** 查询分类树节点，层级组装仍由现有客户端完成。 */
    @GetMapping("/categories")
    public Object categories() { return ApiResponse.ok(catalogs.list(CurrentUser.require(), "categories", null)); }

    /** 创建分类及可选默认密级、期限、编号规则。 */
    @PostMapping("/categories")
    public Object createCategory(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(catalogs.save(CurrentUser.require(), "categories", null, body), "分类创建成功");
    }

    /** 更新分类并防止形成循环层级。 */
    @PutMapping("/categories/{id}")
    public Object updateCategory(@PathVariable long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(catalogs.save(CurrentUser.require(), "categories", id, body), "分类更新成功");
    }

    /** 分类仍有下级或档案引用时拒绝删除。 */
    @DeleteMapping("/categories/{id}")
    public Object deleteCategory(@PathVariable long id) {
        catalogs.delete(CurrentUser.require(), "categories", id);
        return ApiResponse.ok(null, "分类已删除");
    }
}
