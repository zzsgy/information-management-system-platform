package com.company.platform;

import java.util.*;

/** 当前请求的身份快照；写事务还要再次核对数据库代次，不能长期缓存。 */
public record Actor(
    /** 账号 UUID。 */ UUID userId,
    /** 当前会话 UUID。 */ UUID sessionId,
    /** 请求检查时的授权代次。 */ int authVersion,
    /** 首次设密会话不得访问业务。 */ boolean setupRequired,
    /** 已经通过字段白名单投影的本人资料。 */ Map<String,Object> user,
    /** 本人有效的分模块角色。 */ List<Map<String,Object>> roles,
    /** 当前获准的精确动作代码；没有星号权限。 */ Set<String> actions,
    /** 会话绑定的 CSRF 值，可返回本人但不是登录凭据。 */ String csrfToken) {
    /** 检查明确能力，不能通过模块名字推断权限。 */
    public void require(String action) { if (!actions.contains(action) || setupRequired) throw ApiFault.denied(); }
    /** IT 系统管理资格来自当前有效任职；普通模块管理员不继承此资格。 */
    public boolean systemAdministrator() { return !setupRequired && roles.stream().anyMatch(r -> "PLATFORM".equals(r.get("module")) && "IT_ADMIN".equals(r.get("code"))); }
    /** 通用账号借阅例外由服务端的账号标记和当前 IT 资格决定。 */
    public boolean sharedItBorrower() { return actions.contains("ARCHIVE.IT_SHARED_BORROW"); }
    /** 输出 V1.1 Profile，只包含允许展示的个人资料。 */
    public Map<String,Object> profile() { return Sql.map("user",user,"roles",roles,"actions",actions.stream().sorted().toList(),"csrfToken",csrfToken); }
}
