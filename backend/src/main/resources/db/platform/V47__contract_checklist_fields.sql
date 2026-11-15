-- 追加合同电子清单的字段版本，旧模板定义及已有目录继续保留原版本。
WITH extra_fields AS (
 SELECT jsonb_agg(jsonb_build_object('code',code,'name',name,'type','TEXT','order',sort_order,
  'required',false,'critical',true,'searchable',false,'visible',true,'enabled',true,'options','[]'::jsonb) ORDER BY sort_order) AS fields
 FROM (VALUES
  ('signingEntity','签订主体',11),('contractNumber','合同编号',12),('documentYear','文件年份（原日期仅年份时）',13)
 ) AS f(code,name,sort_order)
), previous AS (
 SELECT * FROM archive.directory_template WHERE template_key='COMMON_RECORD_LEDGER' AND state='ACTIVE' ORDER BY version_no DESC LIMIT 1
)
INSERT INTO archive.directory_template(id,template_key,version_no,category_code,object_type,name,fields,created_by)
SELECT 'cc470000-0000-4000-8000-000000000002'::uuid,previous.template_key,previous.version_no+1,
 previous.category_code,previous.object_type,previous.name,previous.fields||extra_fields.fields,previous.created_by
FROM previous CROSS JOIN extra_fields;
