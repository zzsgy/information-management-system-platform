import { createApp } from "vue";
import App from "./PlatformApp.vue";
import "./platform.css";
// 门户布局在基础样式之后加载，确保桌面密度与自适应规则生效。
import "./portal.css";
// 全平台共用品牌、控件密度和桌面布局；业务组件仅保留专有内容样式。
import "./theme.css";
import "./flowDialog.css";

// 正式服务的根地址提供同一入口，旧链接仍携带原查询参数和模块路由。
if (import.meta.env.PROD && location.pathname === "/platform.html") {
  history.replaceState(history.state, "", "/" + location.search + location.hash);
}

/** 新平台从真实会话恢复；不加载旧 Bearer 登录和静态预览数据。 */
createApp(App).mount("#app");
