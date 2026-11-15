-- 送审快照沿用本申请版本；原在途借阅继续使用第一版。
INSERT INTO workflow.template_version(id,template_key,business_type,version_no,route_kind,nodes_definition,checksum)
SELECT '30000000-0000-4000-8000-000000000061','ARCHIVE_BORROW_V1','BORROW',2,'FIXED',definition,
       encode(sha256(convert_to(definition::text,'UTF8')),'hex')
FROM (SELECT '{"local":["APPLICANT_MANAGER","APPLICANT_ARCHIVIST","ARCHIVE_ADMIN"],"cross":["APPLICANT_MANAGER","APPLICANT_ARCHIVIST","OWNER_MANAGER","OWNER_ARCHIVIST","ARCHIVE_ADMIN"],"generalManagerBeforeArchiveAdmin":["CORE","UNWATERMARKED"],"paperHandoffs":true}'::jsonb AS definition) route;
