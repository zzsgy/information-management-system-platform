import { createApp } from "vue";
import "./preview.css";
import PlatformPreview from "./DesignPreview.vue";
import "./modules.css"; // 身份与业务模块的独立设计样式。

/** 共享基础样式先加载，各页面的响应式样式随后覆盖。 */
/** 只挂载独立设计稿，不注册业务路由，不导入正式登录和数据服务。 */
createApp(PlatformPreview).mount("#app");
