-- 公盘授权使用同一数据库谓词；检索先按权限过滤，再匹配名称、计数和分页。
CREATE FUNCTION drive.active_path(target uuid) RETURNS SETOF drive.folder LANGUAGE sql STABLE AS $$
 WITH RECURSIVE p AS (
 SELECT f, f.parent_id AS parent, ARRAY[f.id] AS visited, false AS cycle FROM drive.folder f WHERE f.id=target
 UNION ALL SELECT f,f.parent_id,p.visited||f.id,f.id=ANY(p.visited) FROM drive.folder f JOIN p ON f.id=p.parent WHERE NOT p.cycle AND cardinality(p.visited)<129
 ) SELECT (p.f).* FROM p WHERE NOT EXISTS(SELECT 1 FROM p q WHERE (q.f).state<>'ACTIVE' OR q.cycle OR cardinality(q.visited)>128);
$$;
COMMENT ON FUNCTION drive.active_path(uuid) IS '只返回完整有效祖先链；停用、回收、循环和过深路径一律关闭';

CREATE FUNCTION drive.manager_scope(person uuid,target uuid,act text) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM drive.active_path(target) p JOIN drive.delegation d ON d.folder_id=p.id
 JOIN drive.folder leaf ON leaf.id=target AND leaf.department_id=d.department_id
 JOIN iam.user_role ur ON ur.role_id=d.role_id AND ur.department_id=d.department_id AND ur.user_id=person
 JOIN iam.role r ON r.id=ur.role_id JOIN iam.department_manager m ON m.user_id=person AND m.department_id=d.department_id
 JOIN iam.user_account u ON u.id=person AND u.status='ACTIVE'
 WHERE d.state='ACTIVE' AND act=ANY(d.allowed_actions) AND ur.module='DRIVE' AND ur.role_code='DEPARTMENT_MANAGER' AND ur.state='ACTIVE' AND r.status='ACTIVE'
 AND ur.valid_from<=CURRENT_TIMESTAMP AND (ur.valid_until IS NULL OR ur.valid_until>CURRENT_TIMESTAMP)
 AND m.duty_type='DEPARTMENT_MANAGER' AND m.valid_from<=CURRENT_TIMESTAMP AND (m.valid_until IS NULL OR m.valid_until>CURRENT_TIMESTAMP));
$$;
COMMENT ON FUNCTION drive.manager_scope(uuid,uuid,text) IS '当前唯一负责人职责、有效角色与IT委托动作的交集';

CREATE FUNCTION drive.can_folder(person uuid,target uuid,act text) RETURNS boolean LANGUAGE sql STABLE AS $$
 WITH path AS MATERIALIZED (SELECT * FROM drive.active_path(target)),
 rules AS MATERIALIZED (SELECT g.* FROM drive.resource_grant g JOIN path p ON p.id=g.resource_id WHERE g.subject_id=person AND g.resource_type='FOLDER' AND g.action=act AND g.state='ACTIVE'),
 roles AS MATERIALIZED (SELECT ur.role_code FROM iam.user_role ur JOIN iam.role r ON r.id=ur.role_id WHERE ur.user_id=person AND ur.module='DRIVE' AND ur.state='ACTIVE' AND r.status='ACTIVE' AND ur.valid_from<=CURRENT_TIMESTAMP AND (ur.valid_until IS NULL OR ur.valid_until>CURRENT_TIMESTAMP))
 SELECT EXISTS(SELECT 1 FROM path f JOIN iam.user_account u ON u.id=person AND u.status='ACTIVE' WHERE f.id=target
 AND NOT EXISTS(SELECT 1 FROM rules WHERE effect='DENY') AND (
 drive.manager_scope(person,target,act)
 OR (act IN ('VIEW','DOWNLOAD') AND (f.zone<>'DEPARTMENT_INTERNAL' OR u.department_id=f.department_id
 OR EXISTS(SELECT 1 FROM roles WHERE role_code IN ('IT_ADMIN','GENERAL_MANAGER'))
 OR (EXISTS(SELECT 1 FROM roles WHERE role_code='DEPARTMENT_MANAGER') AND EXISTS(
 WITH RECURSIVE parents AS (SELECT id,parent_id FROM iam.department WHERE id=f.department_id UNION SELECT d.id,d.parent_id FROM iam.department d JOIN parents p ON d.id=p.parent_id)
 SELECT 1 FROM parents p JOIN iam.department_manager m ON m.department_id=p.id AND m.user_id=person WHERE m.duty_type='DEPARTMENT_MANAGER' AND m.valid_from<=CURRENT_TIMESTAMP AND (m.valid_until IS NULL OR m.valid_until>CURRENT_TIMESTAMP)))))
 OR (act IN ('VIEW','DOWNLOAD','UPLOAD','DELETE','MOVE','RESTORE') AND EXISTS(SELECT 1 FROM rules g JOIN drive.delegation d ON d.id=g.delegation_id JOIN path p ON p.id=d.folder_id
 WHERE g.effect='ALLOW' AND d.state='ACTIVE' AND act=ANY(d.allowed_actions) AND d.department_id=f.department_id))));
$$;
COMMENT ON FUNCTION drive.can_folder(uuid,uuid,text) IS '独立目录动作授权；IT默认可读可下载但不推导内容写权';

CREATE FUNCTION drive.can_document(person uuid,target uuid,act text) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT EXISTS(SELECT 1 FROM drive.document doc JOIN iam.user_account u ON u.id=person AND u.status='ACTIVE'
 WHERE doc.id=target
 AND EXISTS(SELECT 1 FROM drive.active_path(doc.folder_id))
 AND NOT EXISTS(SELECT 1 FROM drive.resource_grant g WHERE g.subject_id=person AND g.action=act AND g.effect='DENY' AND g.state='ACTIVE' AND
 ((g.resource_type='DOCUMENT' AND g.resource_id=target) OR (g.resource_type='FOLDER' AND g.resource_id IN (SELECT id FROM drive.active_path(doc.folder_id)))))
 AND (drive.manager_scope(person,doc.folder_id,act) OR (act<>'PURGE' AND (act NOT IN ('DELETE','MOVE','RESTORE') OR doc.uploaded_by=person)
 AND (act<>'EDIT' OR doc.collaboration_enabled) AND (drive.can_folder(person,doc.folder_id,act) OR EXISTS(
 SELECT 1 FROM drive.resource_grant g JOIN drive.delegation d ON d.id=g.delegation_id JOIN drive.active_path(doc.folder_id) p ON p.id=d.folder_id
 WHERE g.subject_id=person AND g.resource_type='DOCUMENT' AND g.resource_id=target AND g.action=act AND g.effect='ALLOW' AND g.state='ACTIVE'
 AND d.state='ACTIVE' AND act=ANY(d.allowed_actions) AND d.department_id=doc.publishing_department_id)))));
$$;
COMMENT ON FUNCTION drive.can_document(uuid,uuid,text) IS '独立文档动作权限，调用入口另验状态；幂等重试仍核当前权限，员工删除移动恢复仅限本人上传';

CREATE INDEX ix_drive_recycle ON drive.document(folder_id,deleted_at,id) WHERE status='RECYCLED';
CREATE INDEX ix_drive_version_history ON drive.document_version(document_id,version_no DESC);
CREATE INDEX ix_drive_grant_resource ON drive.resource_grant(resource_type,resource_id,action,subject_id) WHERE state='ACTIVE';
-- 版本与引用永不覆写；恢复历史版本新增版本号，逻辑清理也保留原件引用以便独立核验。
CREATE FUNCTION drive.keep_version_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 RAISE EXCEPTION '公盘历史版本不可修改或删除'; END $$;
CREATE TRIGGER drive_version_immutable BEFORE UPDATE OR DELETE ON drive.document_version FOR EACH ROW EXECUTE FUNCTION drive.keep_version_immutable();
COMMENT ON FUNCTION drive.keep_version_immutable() IS '强制历史版本不可变，防止回滚编号或绕过服务改写文件';
