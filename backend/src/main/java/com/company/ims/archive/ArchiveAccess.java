package com.company.ims.archive;

import com.company.ims.identity.*;
import com.company.ims.shared.*;
import org.springframework.stereotype.Service;
import java.util.Map;

/** 档案数据授权唯一入口：功能权限和对象范围必须同时满足。 */
@Service
public class ArchiveAccess {
    /** 读取部门归属、档案状态与显式共享授权。 */
    private final Db db;
    /** 注入用于验证部门归属、活动销毁与共享记录的数据库访问器；db须参与业务事务。 */
    public ArchiveAccess(Db db) { this.db = db; }
    /**
     * 返回当前用户可查阅的档案，禁止泄露其他部门对象是否存在。
     * @param id 待读取的档案主键
     * @return 通过查询动作与对象范围校验的档案行
     */
    public Map<String,Object> requireRead(long id) { return require(id, "archive.read"); }
    /**
     * 返回当前用户可编辑的档案；编辑能力不由查询共享自动继承。
     * @param id 待编辑的档案主键
     * @return 通过编辑动作与对象范围校验的档案行，写入前仍须锁定并校验状态
     */
    public Map<String,Object> requireWrite(long id) { return require(id, "archive.write"); }
    /**
     * 对指定动作执行功能权限和档案范围双重检查。
     * @param id 待访问的档案主键
     * @param permission 服务端选择的精确动作权限码
     * @return 未软删除且位于当前身份授权范围的档案行
     */
    public Map<String,Object> require(long id, String permission) {
        // 认证主体从请求上下文取得，客户端不能传入替代身份。
        Actor actor = PermissionGuard.require(permission);
        // 不存在与不可访问统一返回 404，避免编号枚举泄露。
        return db.required("select a.* from archive a where a.id=? and a.deleted_at is null and (" + predicate(actor, "a", permission) + ")", id);
    }
    /**
     * 返回可复用的查询范围表达式，适用于列表、详情、统计和文件查询。
     * @param actor 已认证主体，部门和账号标识不能从请求自由指定
     * @param alias 服务端SQL中的档案表别名
     * @return 基于档案查询权限的对象范围表达式，调用方仍需验证功能权限
     */
    public String predicate(Actor actor, String alias) { return predicate(actor, alias, "archive.read"); }
    /**
     * 根据动作构造范围表达式；只有经过认证的数字标识会进入表达式。
     * @param actor 已认证主体，其部门、用户和精确权限决定数据范围
     * @param alias 服务端SQL中的档案表别名，限制为安全标识符
     * @param permission 服务端选择的精确共享动作权限码
     * @return 本部门、全范围权限或未过期显式授权对应的SQL范围表达式
     */
    public String predicate(Actor actor, String alias, String permission) {
        if (!alias.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("非法查询别名");
        if (!permission.matches("[a-z.]+")) throw new IllegalArgumentException("非法权限编码");
        if (actor.has("*") || actor.has("archive.manage.all")) return "1=1";
        return "(" + alias + ".dept_id=" + actor.deptId() + " or exists(select 1 from archive_grant g where g.archive_id=" + alias + ".id and g.permission_code='" + permission + "' and (g.expires_at is null or g.expires_at>current_timestamp) and ((g.grantee_type='user' and g.grantee_id=" + actor.id() + ") or (g.grantee_type='dept' and g.grantee_id=" + actor.deptId() + "))))";
    }
    /**
     * 校验登记或迁移归属的部门；跨部门登记必须具备显式全范围权限。
     * @param departmentId 拟归属的启用且未删除部门主键
     */
    public void requireDepartment(long departmentId) {
        // 登记时沿用当前主体，不允许仅凭请求指定其他部门。
        Actor actor = CurrentUser.require();
        if (departmentId != actor.deptId() && !actor.has("archive.manage.all")) throw new BusinessException(403, "不能在未授权部门登记或移动档案");
        db.required("select id from ims_department where id=? and status=1 and deleted_at is null", departmentId);
    }
    /**
     * 保证待审批或已批准销毁的档案内容保持申请时状态；拒绝申请后自动解除冻结。
     * 调用方必须已在当前事务中锁定档案行，与创建销毁申请采用同一串行化边界。
     *
     * @param archiveId 已通过动作授权且已取得行锁的档案主键
     * @throws BusinessException 有活动销毁申请时返回409，调用方不得继续修改元数据或附件
     */
    public void requireNoActiveDestruction(long archiveId) {
        if (db.scalarLong("select count(*) from destroy_request_detail d join destroy_request r on r.id=d.request_id where d.archive_id=? and r.status in ('pending','approved')", archiveId) > 0)
            throw new BusinessException(409, "档案已进入销毁审批或待执行阶段，不能修改内容或上传附件");
    }
}
