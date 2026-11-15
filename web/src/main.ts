import { createApp } from "vue";
import ElementPlus from "element-plus";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import "element-plus/dist/index.css";
import "./style.css";
import App from "./App.vue";
import { router } from "./router";

/** 初始化中文界面及浏览器路由；身份恢复在根组件内完成。 */
createApp(App).use(router).use(ElementPlus, { locale: zhCn }).mount("#app");
