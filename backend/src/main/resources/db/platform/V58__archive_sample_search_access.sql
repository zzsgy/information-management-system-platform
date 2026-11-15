-- 经用户授权预置的测试档案，仅对指定账号开放固定版本的安全题名。
CREATE TABLE archive.sample_search_access (
 record_id uuid NOT NULL,
 revision_id uuid NOT NULL,
 user_id uuid NOT NULL REFERENCES iam.user_account(id),
 valid_until timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(record_id,user_id),
 FOREIGN KEY(record_id,revision_id) REFERENCES archive.record_revision(record_id,id)
);
COMMENT ON TABLE archive.sample_search_access IS '演示档案的限定账号检索，不生成正文查看、下载、打印或实物借出授权';

CREATE OR REPLACE FUNCTION archive.visibility(record_id uuid,person uuid,catalog_allowed boolean) RETURNS text LANGUAGE sql STABLE AS $$
 SELECT CASE WHEN catalog_allowed AND EXISTS(SELECT 1 FROM iam.user_role a JOIN iam.role role ON role.id=a.role_id JOIN iam.user_account u ON u.id=a.user_id WHERE a.user_id=person AND u.status='ACTIVE' AND a.module='ARCHIVE'
 AND (a.role_code='SUPER_ADMIN' OR (a.role_code='DEPARTMENT_ARCHIVIST' AND a.department_id=r.department_id AND a.department_id=u.department_id)) AND a.state='ACTIVE' AND role.status='ACTIVE' AND a.valid_from<=CURRENT_TIMESTAMP AND (a.valid_until IS NULL OR a.valid_until>CURRENT_TIMESTAMP)) THEN 'MANAGE'
 WHEN r.state<>'PUBLISHED' OR b.state<>'PUBLISHED' THEN 'HIDDEN'
 WHEN EXISTS(SELECT 1 FROM archive.access_grant g WHERE g.user_id=person AND g.record_id=r.id AND g.state='ACTIVE' AND archive.grant_valid(g.id,person))
 OR EXISTS(SELECT 1 FROM archive.access_policy p WHERE p.object_type='RECORD' AND p.object_id=r.id AND p.state='ACTIVE' AND archive.baseline_valid(p.id,person)) THEN 'METADATA'
 WHEN iam.it_shared_borrower(person) THEN 'TITLE_ONLY'
 WHEN r.safe_title IS NOT NULL AND EXISTS(SELECT 1 FROM archive.sample_search_access s
   WHERE s.record_id=r.id AND s.revision_id=r.current_revision_id AND s.user_id=person
   AND s.valid_until>CURRENT_TIMESTAMP AND iam.archive_access_enabled(person)) THEN 'TITLE_ONLY'
 WHEN r.safe_title IS NOT NULL AND EXISTS(SELECT 1 FROM archive.access_policy p JOIN iam.user_account u ON u.id=person WHERE p.object_type='RECORD' AND p.object_id=r.id AND archive.policy_approved(p.id)
 AND p.record_revision_id=r.current_revision_id AND p.pdf_version_id IS NOT DISTINCT FROM r.current_pdf_version_id AND p.title_visibility IN ('SAFE_TITLE','OWNER_DEPARTMENT') AND (p.title_visibility<>'OWNER_DEPARTMENT' OR u.department_id=r.department_id)
 AND u.department_id=ANY(p.department_scope) AND 'safeTitle'=ANY(p.safe_search_fields)) THEN 'TITLE_ONLY' ELSE 'HIDDEN' END
 FROM archive.record r JOIN archive.box b ON b.id=r.box_id WHERE r.id=record_id;
$$;

