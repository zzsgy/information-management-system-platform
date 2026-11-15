-- 递归复合列名与目录别名必须不同，避免PostgreSQL把递归项解释为上一层复合列。
CREATE OR REPLACE FUNCTION drive.active_path(target uuid) RETURNS SETOF drive.folder LANGUAGE sql STABLE AS $$
 WITH RECURSIVE p AS (
 SELECT leaf AS node,leaf.parent_id AS parent,ARRAY[leaf.id] AS visited,false AS cycle FROM drive.folder leaf WHERE leaf.id=target
 UNION ALL SELECT ancestor,ancestor.parent_id,p.visited||ancestor.id,ancestor.id=ANY(p.visited) FROM drive.folder ancestor JOIN p ON ancestor.id=p.parent WHERE NOT p.cycle AND cardinality(p.visited)<129
 ) SELECT (p.node).* FROM p WHERE NOT EXISTS(SELECT 1 FROM p q WHERE (q.node).state<>'ACTIVE' OR q.cycle OR cardinality(q.visited)>128);
$$;
-- 任一参与者撤权即改变编辑代次；旧回调仍可保存为待确认候选，不能继续发布。
CREATE FUNCTION drive.revoke_editor_on_identity_change() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.auth_version IS DISTINCT FROM OLD.auth_version OR NEW.status IS DISTINCT FROM OLD.status THEN
  PERFORM d.id FROM drive.document d WHERE d.id IN (SELECT s.document_id FROM drive.edit_session s JOIN drive.edit_participant p ON p.session_id=s.id WHERE p.user_id=NEW.id AND s.state IN ('OPEN','CLOSING','UNKNOWN')) ORDER BY d.id FOR UPDATE;
  UPDATE drive.document d SET edit_epoch=edit_epoch+1,row_version=row_version+1,updated_at=CURRENT_TIMESTAMP WHERE d.id IN (SELECT s.document_id FROM drive.edit_session s JOIN drive.edit_participant p ON p.session_id=s.id WHERE p.user_id=NEW.id AND s.state IN ('OPEN','CLOSING','UNKNOWN'));
  UPDATE drive.edit_session s SET state='REVOKED',row_version=row_version+1 WHERE s.state IN ('OPEN','CLOSING','UNKNOWN') AND EXISTS(SELECT 1 FROM drive.edit_participant p WHERE p.session_id=s.id AND p.user_id=NEW.id);
  UPDATE drive.edit_participant SET state='REVOKED' WHERE user_id=NEW.id AND state<>'REVOKED';
 END IF; RETURN NEW; END $$;
CREATE TRIGGER drive_editor_identity_guard AFTER UPDATE OF auth_version,status ON iam.user_account FOR EACH ROW EXECUTE FUNCTION drive.revoke_editor_on_identity_change();
COMMENT ON FUNCTION drive.revoke_editor_on_identity_change() IS '账号或授权变化使涉及的协作代次失效，保留旧候选供负责人处理';
