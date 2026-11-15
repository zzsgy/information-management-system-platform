<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import LoginPreview from "./LoginPreview.vue";
import HomePreview from "./HomePreview.vue";
import Icon from "./PlatformIcon.vue";
import type { PreviewIdentity } from "./preview-data";

/** 独立设计稿只允许登录页和首页两个状态，不复用业务应用路由。 */
const view = ref(location.hash.split("?")[0] === "#/home" ? "home" : "login");
/** 通过独立预览地址选择模拟身份；不读取、更改任何正式会话或权限。 */
const previewIdentity = ref<PreviewIdentity>(readPreviewIdentity());
/** 只接受明确的管理员预览参数，缺省或未知值都使用普通用户视角。 */
function readPreviewIdentity(): PreviewIdentity {
  return new URLSearchParams(location.hash.split("?")[1] ?? "").get("role") === "it-admin" ? "it-admin" : "user";
}
/** 原生对话框负责焦点约束、Esc 关闭和可访问性。 */
const dialog = ref<HTMLDialogElement | null>(null);
const dialogTitle = ref("");
const dialogDetail = ref("");
/** 切换页面时只更新当前预览地址，不建立登录会话。 */
function switchView(target: "login" | "home") {
  view.value = target;
  if (target === "login") previewIdentity.value = "user"; // 返回登录设计时恢复普通用户预览。
  location.hash = `/${target}${target === "home" && previewIdentity.value === "it-admin" ? "?role=it-admin" : ""}`;
  window.scrollTo({ top: 0 });
}
/** 同步浏览器前进、后退操作，未识别的地址返回登录设计。 */
function syncHash() {
  view.value = location.hash.split("?")[0] === "#/home" ? "home" : "login";
  previewIdentity.value = view.value === "home" ? readPreviewIdentity() : "user";
}
/** 展示业务入口的预览边界，说明信息不包含任何真实业务记录。 */
async function explain(title: string, detail: string) {
  dialogTitle.value = title;
  dialogDetail.value = detail;
  await nextTick();
  dialog.value?.showModal();
}
onMounted(() => window.addEventListener("hashchange", syncHash));
onBeforeUnmount(() => window.removeEventListener("hashchange", syncHash));
</script>

<template>
  <div :class="['p-preview-shell', { 'is-login': view === 'login' }]">
    <div v-if="view === 'home'" class="p-preview-bar">
      <div><span class="p-preview-badge">DESIGN PREVIEW</span><span class="p-preview-description">界面设计预览<span class="p-preview-detail"> · 示例内容，未连接业务数据</span></span><label class="p-preview-identity"><span>预览身份</span><select v-model="previewIdentity" aria-label="切换预览身份" @change="switchView('home')"><option value="user">普通用户</option><option value="it-admin">IT 管理员</option></select></label></div>
      <div class="p-preview-switch" aria-label="切换设计页面"><button @click="switchView('login')">登录页</button><button class="active" aria-pressed="true" @click="switchView('home')">平台首页<Icon name="arrow" :size="13" /></button></div>
    </div>
    <LoginPreview v-if="view === 'login'" @enter="switchView('home')" />
    <HomePreview v-else :key="previewIdentity" :identity="previewIdentity" @explain="explain" />
    <dialog ref="dialog" class="p-preview-dialog" aria-labelledby="preview-dialog-title" aria-describedby="preview-dialog-detail" @click="(event) => { if (event.target === dialog) dialog?.close(); }"><button class="p-icon-button p-dialog-close" aria-label="关闭预览说明" @click="dialog?.close()"><Icon name="close" /></button><span class="p-dialog-icon"><Icon name="grid" :size="28" /></span><p class="p-dialog-kicker">入口设计 · 功能待接入</p><h2 id="preview-dialog-title">{{ dialogTitle }}</h2><p id="preview-dialog-detail">{{ dialogDetail }}</p><button class="p-dialog-back" @click="dialog?.close()">继续查看设计<Icon name="arrow" :size="17" /></button></dialog>
  </div>
</template>
