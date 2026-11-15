-- 千文件目录实测逐文档递归授权超时；同目录读取规则先算一次，仅对文档例外补查。
CREATE FUNCTION drive.visible_documents(person uuid,target_folder uuid) RETURNS SETOF drive.document LANGUAGE sql STABLE AS $$
 WITH folders AS MATERIALIZED (
  SELECT f.id,drive.can_folder(person,f.id,'VIEW') AS allowed FROM drive.folder f
  WHERE f.state='ACTIVE' AND (target_folder IS NULL OR f.id=target_folder)
 )
 SELECT d.* FROM drive.document d JOIN folders f ON f.id=d.folder_id WHERE d.status='ACTIVE'
 AND NOT EXISTS(SELECT 1 FROM drive.resource_grant g WHERE g.subject_id=person AND g.resource_type='DOCUMENT' AND g.resource_id=d.id AND g.action='VIEW' AND g.effect='DENY' AND g.state='ACTIVE')
 AND CASE WHEN f.allowed THEN true
 WHEN EXISTS(SELECT 1 FROM drive.resource_grant g WHERE g.subject_id=person AND g.resource_type='DOCUMENT' AND g.resource_id=d.id AND g.action='VIEW' AND g.effect='ALLOW' AND g.state='ACTIVE')
 THEN drive.can_document(person,d.id,'VIEW') ELSE false END;
$$;
COMMENT ON FUNCTION drive.visible_documents(uuid,uuid) IS '批量文件可见性：复用目录规则，文档拒绝优先，跨部门具体文档允许不暴露父目录';
