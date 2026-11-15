-- 无水印只从本人仍有效、含总经理批准的固定电子借阅来源取得。
CREATE FUNCTION archive.unwatermarked_grant(grant_key uuid, person uuid) RETURNS boolean
LANGUAGE sql STABLE AS $$
SELECT EXISTS (
  SELECT 1 FROM archive.access_grant g JOIN archive.borrow_request q ON q.id=g.source_request_id
  JOIN workflow.instance w ON w.id=q.workflow_instance_id
  WHERE g.id=grant_key AND g.user_id=person AND g.source_type='BORROW'
    AND archive.grant_valid(g.id,person) AND w.business_snapshot->>'unwatermarked'='true'
    AND EXISTS(SELECT 1 FROM workflow.step s WHERE s.instance_id=w.id
      AND s.duty_code='GENERAL_MANAGER' AND s.state='APPROVED'
      AND EXISTS(SELECT 1 FROM workflow.decision d WHERE d.step_id=s.id AND workflow.decision_valid(d.id,w.id,s.id)))
);
$$;
ALTER TABLE archive.usage_request ADD COLUMN unwatermarked boolean NOT NULL DEFAULT false;
ALTER TABLE archive.export_request ADD COLUMN unwatermarked boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN archive.usage_request.unwatermarked IS '本次阅读明确使用带总经理批准的无水印电子授权';
COMMENT ON COLUMN archive.export_request.unwatermarked IS '本次下载或打印明确使用带总经理批准的无水印电子授权';
