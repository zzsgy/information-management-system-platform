package com.company.ims.identity;

import java.util.Set;

/**
 * 档案服务使用的平台统一身份；操作权限与数据范围分别判断。
 * @param id 用户稳定标识
 * @param deptId 所属部门，用于默认数据隔离
 * @param username 登录账号
 * @param realName 办理人展示姓名
 * @param roles 本次请求实时启用的角色编码
 * @param permissions 本次请求实时启用的精确权限编码
 */
public record Actor(long id, long deptId, String username, String realName,
                    Set<String> roles, Set<String> permissions) {
    /** 复制授权集合，防止下游业务意外修改本次请求身份。 */
    public Actor {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }

    /** 精确判断操作权限，仅星号具有通配含义，archive.admin不扩大授权。 */
    public boolean has(String permission) {
        return permissions.contains("*") || permissions.contains(permission);
    }
}
