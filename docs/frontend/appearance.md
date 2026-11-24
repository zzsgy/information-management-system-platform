# 主题与界面字体

入口为登录后右上角“外观”，主题与字体分为两个页签，即选即生效；“完成”关闭弹窗，“恢复默认”恢复标准蓝和系统字体。

原有五个主题保留，新增“青岚水彩、暖阳流金、星河极光、浅纸草木”。四张图片由用户提供，原文件保存在 `web/src/platform/assets/themes/`，不修改图中的标记。图片作为界面背景，卡片、表格、输入框与弹窗使用相应色调的可读表面；深色极光使用独立的文字与状态色。第五张截图仅用于定位入口，不作为主题。

主题仍使用原键 `ims-appearance-theme`，字体新增独立键 `ims-appearance-font`。设置仅保存于本浏览器；刷新恢复，其他同源标签页同步，存储不可用时仍即时应用。非法值回退到默认项，不将存储内容作为 CSS 或资源地址。没有新增后端接口、数据库字段或账号同步行为。

字体只应用于平台界面，不改写原文件内容；独立打印入口不加载外观样式，保留原字体和排版。字体通过同源 `@font-face` 按实际选择加载，`font-display: swap` 避免等待期间文字不可见；字体页显示加载状态，加载失败时保留系统回退字体。无需访问外部字体服务。

## 随包字体及来源

| 显示名称 | 文件及来源 | 授权 |
| --- | --- | --- |
| 思源黑体（Noto Sans SC） | `web/public/fonts/noto-sans-sc/NotoSansSC.ttf`，[Google Fonts](https://github.com/google/fonts/tree/main/ofl/notosanssc) | SIL OFL 1.1 |
| 思源宋体（Noto Serif SC） | `web/public/fonts/noto-serif-sc/NotoSerifSC.ttf`，[Google Fonts](https://github.com/google/fonts/tree/main/ofl/notoserifsc) | SIL OFL 1.1 |
| 霞鹜文楷（LXGW WenKai） | `web/public/fonts/lxgw-wenkai/LXGWWenKai-Regular.ttf`，[官方 v1.530](https://github.com/lxgw/LxgwWenKai/releases/tag/v1.530) | SIL OFL 1.1 |

使用官方原始字体文件，不修改、子集化或重新编码。相邻 `OFL.txt` 保存原版权声明、保留字体名说明与许可证全文，并随构建一起分发；外观弹窗提供同源授权文件链接。校验摘要见 `web/public/fonts/checksums.json`。

OFL 允许免费使用及随软件嵌入分发，包括商用，但须保留原版权和许可，不能单独出售字体，也不能擅自以保留字体名发布修改版。具体以各字体随包的完整 OFL 原文为准。系统默认项仅使用设备已有字体，不复制或分发设备字体文件。
