-- 更正后通知仍须以对象当前部门为准；原部门历史身份不形成永久消息权。
ALTER FUNCTION ops.notification_current(uuid,uuid) RENAME TO notification_current_v22;
CREATE FUNCTION ops.notification_current(notice uuid,person uuid) RETURNS boolean LANGUAGE sql STABLE AS $$
 SELECT CASE WHEN n.source_kind='CATALOG_CHANGE' THEN n.user_id=person AND n.state<>'CANCELLED' AND EXISTS(
  SELECT 1 FROM archive.catalog_change c JOIN (SELECT 'BOX' AS object_type,id,department_id FROM archive.box UNION ALL SELECT 'RECORD',id,department_id FROM archive.record) o ON o.object_type=c.object_type AND o.id=c.object_id
  WHERE c.id=n.source_id AND c.state='APPLIED' AND archive.catalog_manager(person,o.department_id))
 ELSE ops.notification_current_v22(notice,person) END FROM ops.business_notification n WHERE n.id=notice;
$$;
