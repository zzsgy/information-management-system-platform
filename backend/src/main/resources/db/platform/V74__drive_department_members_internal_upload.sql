-- 部门成员可向本部门内部目录上传，目录明确拒绝优先。
CREATE OR REPLACE FUNCTION drive.can_folder(person uuid,target uuid,act text) RETURNS boolean LANGUAGE sql STABLE AS $$
 WITH path AS MATERIALIZED (SELECT * FROM drive.active_path(target)),
 rules AS MATERIALIZED (SELECT g.* FROM drive.resource_grant g JOIN path p ON p.id=g.resource_id WHERE g.subject_id=person AND g.resource_type='FOLDER' AND g.action=act AND g.state='ACTIVE'),
 roles AS MATERIALIZED (SELECT ur.role_code,ur.department_id FROM iam.user_role ur JOIN iam.role r ON r.id=ur.role_id WHERE ur.user_id=person AND ur.module='DRIVE' AND ur.state='ACTIVE' AND r.status='ACTIVE' AND ur.valid_from<=CURRENT_TIMESTAMP AND (ur.valid_until IS NULL OR ur.valid_until>CURRENT_TIMESTAMP))
 SELECT EXISTS(SELECT 1 FROM path f JOIN iam.user_account u ON u.id=person AND u.status='ACTIVE' WHERE f.id=target
 AND ((act IN ('VIEW','DOWNLOAD') AND iam.system_administrator(person)) OR (NOT EXISTS(SELECT 1 FROM rules WHERE effect='DENY') AND (
 drive.manager_scope(person,target,act)
 OR (act='UPLOAD' AND f.zone='DEPARTMENT_INTERNAL' AND u.department_id=f.department_id AND NOT u.it_shared_account)
 OR (act='UPLOAD' AND u.department_id=f.department_id AND EXISTS(
     SELECT 1 FROM roles r JOIN iam.department_manager m ON m.department_id=r.department_id
     WHERE r.role_code='DEPARTMENT_MANAGER' AND r.department_id=f.department_id
       AND m.user_id=person AND m.duty_type='DEPARTMENT_MANAGER'
       AND m.valid_from<=CURRENT_TIMESTAMP AND (m.valid_until IS NULL OR m.valid_until>CURRENT_TIMESTAMP)))
 OR (act IN ('VIEW','DOWNLOAD') AND (f.zone<>'DEPARTMENT_INTERNAL' OR u.department_id=f.department_id
 OR EXISTS(SELECT 1 FROM roles WHERE role_code IN ('IT_ADMIN','GENERAL_MANAGER'))
 OR (EXISTS(SELECT 1 FROM roles WHERE role_code='DEPARTMENT_MANAGER') AND EXISTS(
 WITH RECURSIVE parents AS (SELECT id,parent_id FROM iam.department WHERE id=f.department_id UNION SELECT d.id,d.parent_id FROM iam.department d JOIN parents p ON d.id=p.parent_id)
 SELECT 1 FROM parents p JOIN iam.department_manager m ON m.department_id=p.id AND m.user_id=person WHERE m.duty_type='DEPARTMENT_MANAGER' AND m.valid_from<=CURRENT_TIMESTAMP AND (m.valid_until IS NULL OR m.valid_until>CURRENT_TIMESTAMP)))))
 OR (act IN ('VIEW','DOWNLOAD','UPLOAD','DELETE','MOVE','RESTORE') AND EXISTS(SELECT 1 FROM rules g JOIN drive.delegation d ON d.id=g.delegation_id JOIN path p ON p.id=d.folder_id
 WHERE g.effect='ALLOW' AND d.state='ACTIVE' AND act=ANY(d.allowed_actions) AND d.department_id=f.department_id))))));
$$;
