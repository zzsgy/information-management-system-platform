package com.company.ims.identity;

import com.company.ims.shared.ApiResponse;
import com.company.ims.shared.BusinessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 提供与原Python接口路径和返回结构兼容的组织管理API。 */
@RestController
@RequestMapping("/api")
public class OrganizationController {
    /** 将平台用户管理请求交给本地组织适配器。 */
    private final OrganizationService organization;

    /** 注入组织业务服务。 */
    public OrganizationController(OrganizationService organization) { this.organization = organization; }

    /** 查询档案表单或用户管理需要的最小部门目录，不授予账号管理权限。 */
    @GetMapping("/departments")
    public Object departments() {
        // 档案只读用户也需要部门下拉框，不能借此放行用户或角色管理接口。
        Actor actor = CurrentUser.require();
        if (!actor.has("user.read") && !actor.has("archive.read")) throw new BusinessException(403, "没有查看部门目录的权限");
        return ApiResponse.ok(organization.departments(actor));
    }

    /** 新增部门，服务层继续检查跨部门组织管理授权。 */
    @PostMapping("/departments")
    public Object createDepartment(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(organization.createDepartment(PermissionGuard.require("user.write"), body), "部门创建成功");
    }

    /** 更新部门信息，不能构造组织层级循环。 */
    @PutMapping("/departments/{id}")
    public Object updateDepartment(@PathVariable long id, @RequestBody Map<String, Object> body) {
        organization.updateDepartment(PermissionGuard.require("user.write"), id, body);
        return ApiResponse.ok(null, "部门更新成功");
    }

    /** 分页列出可管理的账号，固定白名单排除密码等敏感字段。 */
    @GetMapping("/users")
    public Object users(@RequestParam(defaultValue = "1") int page, @RequestParam(name = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.ok(organization.users(PermissionGuard.require("user.read"), page, pageSize));
    }

    /** 查看部门范围内账号及当前角色编号，不返回密码或会话数据。 */
    @GetMapping("/users/{id}")
    public Object user(@PathVariable long id) {
        return ApiResponse.ok(organization.user(PermissionGuard.require("user.read"), id));
    }

    /** 新增账号并分配权限不高于操作者的角色。 */
    @PostMapping("/users")
    public Object createUser(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(organization.createUser(PermissionGuard.require("user.write"), body), "用户创建成功");
    }

    /** 更新用户资料和角色，成功后旧会话失效。 */
    @PutMapping("/users/{id}")
    public Object updateUser(@PathVariable long id, @RequestBody Map<String, Object> body) {
        organization.updateUser(PermissionGuard.require("user.write"), id, body);
        return ApiResponse.ok(null, "用户更新成功");
    }

    /** 禁用目标账号并即时阻断其已登录会话。 */
    @PostMapping("/users/{id}/disable")
    public Object disableUser(@PathVariable long id) {
        organization.disableUser(PermissionGuard.require("user.write"), id);
        return ApiResponse.ok(null, "用户已禁用");
    }

    /** 查询角色列表供分配界面使用。 */
    @GetMapping("/roles")
    public Object roles() {
        PermissionGuard.require("user.read");
        return ApiResponse.ok(organization.roles());
    }

    /** 创建角色并严格校验每个权限编码。 */
    @PostMapping("/roles")
    public Object createRole(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(organization.createRole(PermissionGuard.require("user.write"), body), "角色创建成功");
    }

    /** 查看带中文解释的权限字典。 */
    @GetMapping("/permissions")
    public Object permissions() {
        PermissionGuard.require("user.read");
        return ApiResponse.ok(organization.permissions());
    }
}
