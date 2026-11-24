import { createApp, watch } from "vue";
import ElementPlus from "element-plus";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import "element-plus/dist/index.css";
import "element-plus/theme-chalk/dark/css-vars.css";
import "./design-tokens.css";
import "./style.css";
import { appearanceTheme, appearanceThemes, initializeAppearance } from "./platform/appearance";
import App from "./App.vue";
import { router } from "./router";

/** 初始化中文界面及浏览器路由；身份恢复在根组件内完成。 */
initializeAppearance();
// 复用组件库原有的深色状态色，筛选下拉、弹窗等浮层也跟随跨标签页的主题切换。
watch(appearanceTheme, (value) => {
  const selected = appearanceThemes.find((theme) => theme.id === value);
  document.documentElement.classList.toggle("dark", selected?.dark === true);
}, { immediate: true, flush: "sync" });
createApp(App).use(router).use(ElementPlus, { locale: zhCn }).mount("#app");
