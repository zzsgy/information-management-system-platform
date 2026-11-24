import { createApp } from "vue";
import PrintView from "./PrintView.vue";
import "../design-tokens.css";
import { initializeAppearance } from "./appearance";
/** 独立电脑端打印入口，每次打开均向服务器恢复当前HttpOnly会话。 */
initializeAppearance();
createApp(PrintView).mount("#app");
