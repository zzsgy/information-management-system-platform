-- 档案盒和文件共用跨部门台账字段，历史六类模板及既有目录不改写。
ALTER TABLE archive.box DROP CONSTRAINT box_category_code_check;
ALTER TABLE archive.box ADD CONSTRAINT box_category_code_check CHECK(category_code IN ('COMMON','ADMIN','SALES_CONTRACT','PURCHASE_CONTRACT','PROJECT_CONTRACT','QUALITY','WORK_RECORD'));
ALTER TABLE archive.record DROP CONSTRAINT record_category_code_check;
ALTER TABLE archive.record ADD CONSTRAINT record_category_code_check CHECK(category_code IN ('COMMON','ADMIN','SALES_CONTRACT','PURCHASE_CONTRACT','PROJECT_CONTRACT','QUALITY','WORK_RECORD'));

WITH definitions AS (
 SELECT jsonb_agg(jsonb_build_object('code',code,'name',name,'type',type,'order',sort_order,
  'required',false,'critical',true,'searchable',false,'visible',true,'enabled',true,'options','[]'::jsonb) ORDER BY sort_order) AS fields
 FROM (VALUES
  ('originalNumber','原档案编号','TEXT',0),('fileType','文件类型','TEXT',1),
  ('cabinetNo','存放柜号','TEXT',2),('columnNo','存放列号','TEXT',3),('layerNo','存放层号','TEXT',4),
  ('inventory','清单','TEXT',5),('preservationType','保存类型','TEXT',6),('copyCount','保存份数','NUMBER',7),
  ('sourceExpiryDate','原台账到期日期','DATE',8),('sourceStatus','原台账档案状态','TEXT',9),('sourceUpdatedAt','原台账最后更新时间','TEXT',10)
 ) AS f(code,name,type,sort_order)
), creator AS (SELECT id FROM iam.user_account ORDER BY created_at,id LIMIT 1)
INSERT INTO archive.directory_template(id,template_key,version_no,category_code,object_type,name,fields,created_by)
SELECT t.id::uuid,template_key,1,'COMMON',object_type,name,fields,creator.id
FROM (VALUES
 ('cc450000-0000-4000-8000-000000000001','COMMON_BOX_LEDGER','BOX','档案盒通用台账模板'),
 ('cc450000-0000-4000-8000-000000000002','COMMON_RECORD_LEDGER','RECORD','文件通用台账模板')
) AS t(id,template_key,object_type,name) CROSS JOIN definitions CROSS JOIN creator;
