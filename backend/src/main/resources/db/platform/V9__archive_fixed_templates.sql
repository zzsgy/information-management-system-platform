-- 新能力按模块独立登记，平台或公盘管理角色不获得档案正文动作。
INSERT INTO iam.permission(id,module,code,name,resource_type,action,sensitive_flag,assignable_scope_type) VALUES
 ('20000000-0000-4000-8000-000000000001','ARCHIVE','ARCHIVE.CATALOG_MANAGE','档案目录维护','MODULE','CATALOG_MANAGE',true,'MODULE'),
 ('20000000-0000-4000-8000-000000000002','ARCHIVE','ARCHIVE.GRANTS_MANAGE','档案安全撤权','MODULE','GRANTS_MANAGE',true,'MODULE'),
 ('20000000-0000-4000-8000-000000000003','ARCHIVE','ARCHIVE.CONFIG_MANAGE','档案基础配置','MODULE','CONFIG_MANAGE',true,'MODULE') ON CONFLICT(module,code) DO NOTHING;
INSERT INTO iam.role_permission(role_id,permission_id,scope_policy,effect,changed_by)
 SELECT r.id,p.id,'COMPANY','ALLOW',a.user_id FROM iam.role r JOIN iam.permission p ON p.module='ARCHIVE'
 CROSS JOIN LATERAL(SELECT ur.user_id FROM iam.user_role ur WHERE ur.role_id=r.id AND ur.state='ACTIVE' LIMIT 1) a
 WHERE r.module='ARCHIVE' AND (r.code='SUPER_ADMIN' OR (r.code='DEPARTMENT_ARCHIVIST' AND p.code='ARCHIVE.CATALOG_MANAGE'))
 ON CONFLICT DO NOTHING;
-- 模板由代码发布，不提供前端任意指定审批人、跳过必要节点或自由编排的入口。
INSERT INTO workflow.template_version(id,template_key,version_no,business_type,route_kind,nodes_definition,checksum)
 SELECT id::uuid,template_key,1,business_type,'FIXED',definition::jsonb,encode(sha256(convert_to((definition::jsonb)::text,'UTF8')),'hex') FROM (VALUES
 ('30000000-0000-4000-8000-000000000001','ARCHIVE_REGISTRATION_V1','REGISTRATION','{"route":["OWNER_MANAGER","ARCHIVE_ADMIN"],"selfApproval":false}'),
 ('30000000-0000-4000-8000-000000000002','ARCHIVE_BORROW_V1','BORROW','{"generalCross":["APPLICANT_ARCHIVIST","APPLICANT_MANAGER","OWNER_MANAGER","OWNER_ARCHIVIST","ARCHIVE_ADMIN"],"generalLocal":["APPLICANT_ARCHIVIST","APPLICANT_MANAGER","ARCHIVE_ADMIN"],"important":"GENERAL_PLUS_GM","core":["APPLICANT_ARCHIVIST","OWNER_ARCHIVIST","OWNER_MANAGER","GENERAL_MANAGER","ARCHIVE_ADMIN"],"deduplicate":"SAME_DEPARTMENT_ARCHIVIST_ONLY","skip":"APPLICANT_OWN_ARCHIVIST_ONLY"}'),
 ('30000000-0000-4000-8000-000000000003','ARCHIVE_ACCESS_POLICY_V1','ACCESS_POLICY','{"route":["OWNER_MANAGER","ARCHIVE_ADMIN"],"selfApproval":false}')
 ) t(id,template_key,business_type,definition);
