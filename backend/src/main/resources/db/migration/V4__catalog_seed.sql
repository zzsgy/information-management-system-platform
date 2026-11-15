-- 档案表单默认选项；仅初始化通用字典，不写入真实业务或个人信息。
INSERT INTO dict_item(type_code,item_code,item_name,sort_order) VALUES
    ('storage_location','unspecified','未指定位置',0), -- 没有原件位置时显式标记未填写
    ('file_type','doc','文书',10),
    ('file_type','contract','合同',20),
    ('file_type','finance','财务',30),
    ('file_type','image','图片',40),
    ('file_type','video','音视频',50),
    ('file_type','tech','技术资料',60),
    ('file_type','other','其他',90),
    ('storage_type','paper','纸质',10),
    ('storage_type','electronic','电子',20),
    ('storage_type','mixed','混合',30);
-- 公开首页参数只保存展示设置，不能存储口令、令牌或数据库连接串。
INSERT INTO system_parameter(param_key,param_value,description) VALUES
    ('enterprise_logo',NULL,'首页LOGO路径，当前客户端在本机读取，后续平台统一管理媒体'),
    ('dashboard_cards',NULL,'首页概览卡片顺序，JSON字符串数组');
