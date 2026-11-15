-- 原有快捷入口沿用配置，办公工具保留原工作台分组和顺序。
ALTER TABLE portal.site ADD COLUMN category text NOT NULL DEFAULT 'QUICK_LINK'
    CHECK (category IN ('QUICK_LINK', 'ONLINE_TOOL', 'SOFTWARE_DOWNLOAD'));

INSERT INTO portal.site (id, name, url, sort_order, enabled, category) VALUES
    (gen_random_uuid(), '腾讯在线文档', 'https://docs.qq.com', 0, true, 'ONLINE_TOOL'),
    (gen_random_uuid(), '金山在线文档', 'https://kdocs.cn', 1, true, 'ONLINE_TOOL'),
    (gen_random_uuid(), '飞书在线文档', 'https://www.feishu.cn', 2, true, 'ONLINE_TOOL'),
    (gen_random_uuid(), '石墨在线文档', 'https://shimo.im', 3, true, 'ONLINE_TOOL'),
    (gen_random_uuid(), '问卷星', 'https://www.wjx.cn', 4, true, 'ONLINE_TOOL'),
    (gen_random_uuid(), '腾讯问卷', 'https://wj.qq.com', 5, true, 'ONLINE_TOOL'),
    (gen_random_uuid(), 'DeepL 翻译', 'https://www.deepl.com/translator', 6, true, 'ONLINE_TOOL'),
    (gen_random_uuid(), '百度翻译', 'https://fanyi.baidu.com', 7, true, 'ONLINE_TOOL'),
    (gen_random_uuid(), '草料二维码', 'https://cli.im', 8, true, 'ONLINE_TOOL'),
    (gen_random_uuid(), 'WPS Office', 'https://www.wps.cn', 0, true, 'SOFTWARE_DOWNLOAD'),
    (gen_random_uuid(), 'Microsoft 365', 'https://www.microsoft.com/zh-cn/microsoft-365', 1, true, 'SOFTWARE_DOWNLOAD'),
    (gen_random_uuid(), '永中 Office', 'https://www.yozosoft.com', 2, true, 'SOFTWARE_DOWNLOAD'),
    (gen_random_uuid(), 'Adobe Acrobat Reader', 'https://get.adobe.com/cn/reader', 3, true, 'SOFTWARE_DOWNLOAD'),
    (gen_random_uuid(), '格式工厂', 'https://www.pcgeshi.com/', 4, true, 'SOFTWARE_DOWNLOAD'),
    (gen_random_uuid(), '剪映专业版', 'https://www.capcut.cn', 5, true, 'SOFTWARE_DOWNLOAD'),
    (gen_random_uuid(), '印象笔记', 'https://www.yinxiang.com', 6, true, 'SOFTWARE_DOWNLOAD'),
    (gen_random_uuid(), 'OBS Studio', 'https://obsproject.com', 7, true, 'SOFTWARE_DOWNLOAD'),
    (gen_random_uuid(), '百度网盘', 'https://pan.baidu.com/download', 8, true, 'SOFTWARE_DOWNLOAD'),
    (gen_random_uuid(), '迅雷', 'https://www.xunlei.com', 9, true, 'SOFTWARE_DOWNLOAD'),
    (gen_random_uuid(), '腾讯会议', 'https://meeting.tencent.com/download', 10, true, 'SOFTWARE_DOWNLOAD'),
    (gen_random_uuid(), 'Edge 浏览器', 'https://www.microsoft.com/zh-cn/edge', 11, true, 'SOFTWARE_DOWNLOAD'),
    (gen_random_uuid(), '谷歌 Chrome', 'https://www.google.cn/chrome', 12, true, 'SOFTWARE_DOWNLOAD'),
    (gen_random_uuid(), '火狐 Firefox', 'https://www.mozilla.org/zh-CN/firefox/new/', 13, true, 'SOFTWARE_DOWNLOAD'),
    (gen_random_uuid(), 'WinRAR', 'https://www.winrar.com.cn', 14, true, 'SOFTWARE_DOWNLOAD'),
    (gen_random_uuid(), '搜狗输入法', 'https://shurufa.sogou.com', 15, true, 'SOFTWARE_DOWNLOAD'),
    (gen_random_uuid(), '火绒安全', 'https://www.huorong.cn', 16, true, 'SOFTWARE_DOWNLOAD'),
    (gen_random_uuid(), '微软电脑管家', 'https://pcmanager.microsoft.com', 17, true, 'SOFTWARE_DOWNLOAD');
