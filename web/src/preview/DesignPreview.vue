<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import LoginPreview from "./LoginPreview.vue";
import HomePreview from "./RoleHomePreview.vue";
import ModulePreview from "./ModulePreview.vue";
import PreviewTopbar from "./PreviewTopbar.vue";
import Icon from "./PlatformIcon.vue";
import { hasManagement, profileOptions, profiles, type PreviewIdentity, type PreviewView } from "./design-data";
/** 状态仅用于界面设计，不读写真实账号与登录会话。 */
const identity = ref<PreviewIdentity>("user");
const view = ref<PreviewView>("login");
const profile = computed(() => profiles[identity.value]); // 当前模拟身份。
const pages = computed(() => [ // 工具条独立于正式产品操作区。
  { id: "login", label: "登录" }, { id: "home", label: "首页" }, { id: "drive", label: "公盘" }, { id: "archive", label: "档案管理" },
  ...(hasManagement(identity.value) ? [{ id: "admin", label: "管理后台" }] : []),
]);
/** 弹层只展示名称，不执行真实业务动作。 */
const dialog = ref<HTMLDialogElement | null>(null);
const dialogTitle = ref("");
const dialogItems = ref<string[]>([]);
/** 从原型地址恢复页面与身份，未知值回到登录页。 */
function syncHash() {
  const [path, query] = location.hash.replace(/^#\//, "").split("?"); // 页面与身份参数。
  const role = new URLSearchParams(query).get("role") ?? "user"; // 模拟身份编号。
  identity.value = Object.hasOwn(profiles, role) ? role as PreviewIdentity : "user";
  const validViews: PreviewView[] = ["login", "home", "drive", "archive", "admin"]; // 原型页面白名单。
  const nextView = validViews.includes(path as PreviewView) ? path as PreviewView : "login";
  view.value = nextView === "admin" && !hasManagement(identity.value) ? "home" : nextView;
}
/** 页面切换不调用认证接口。 */
function navigate(target: PreviewView) {
  view.value = target === "admin" && !hasManagement(identity.value) ? "home" : target;
  location.hash = `/${view.value}?role=${identity.value}`;
  window.scrollTo({ top: 0 });
}
/** 身份切换后不显示该身份不存在的后台入口。 */
function changeIdentity() { navigate(view.value); }
/** 弹层用于展示子模块名称，不提供保存或执行操作。 */
async function showModule(title: string, items: string[] = []) {
  dialogTitle.value = title;
  dialogItems.value = items;
  await nextTick();
  dialog.value?.showModal();
}
onMounted(() => { syncHash(); window.addEventListener("hashchange", syncHash); });
onBeforeUnmount(() => window.removeEventListener("hashchange", syncHash));
</script>
<template>
  <div :class="['p-preview-shell', { 'is-login': view === 'login' }]">
    <div class="p-preview-bar"><div class="p-preview-tools"><span class="p-preview-badge">UI DESIGN · V0.2</span><span class="p-preview-description">界面初稿 · 示例数据</span><label class="p-preview-identity"><span>预览身份</span><select v-model="identity" aria-label="切换预览身份" @change="changeIdentity"><option v-for="option in profileOptions" :key="option.id" :value="option.id">{{ option.label }}</option></select></label></div><nav class="p-preview-switch" aria-label="切换设计页面"><button v-for="page in pages" :key="page.id" :class="{ active: view === page.id }" :aria-pressed="view === page.id" @click="navigate(page.id as PreviewView)">{{ page.label }}</button></nav></div>
    <LoginPreview v-if="view === 'login'" @enter="navigate('home')" @recover="showModule('找回密码', ['公司邮箱验证码找回', '联系 IT 管理员重置'])" />
    <div v-else class="p-workspace"><PreviewTopbar :profile="profile" :view="view" @navigate="navigate" @module="showModule" /><HomePreview v-if="view === 'home'" :key="identity" :identity="identity" @navigate="navigate" @module="showModule" /><ModulePreview v-else :key="`${view}-${identity}`" :view="view" :identity="identity" @navigate="navigate" @module="showModule" /></div>
    <dialog ref="dialog" class="p-module-dialog" aria-labelledby="preview-module-title" @click="(event) => { if (event.target === dialog) dialog?.close(); }"><button class="p-icon-button p-dialog-close" aria-label="关闭模块预览" @click="dialog?.close()"><Icon name="close" /></button><span class="p-module-dialog-symbol"><Icon name="grid" :size="25" /></span><p class="p-module-dialog-kicker">功能模块</p><h2 id="preview-module-title">{{ dialogTitle }}</h2><div class="p-dialog-module-list"><span v-for="item in dialogItems" :key="item"><Icon name="file" :size="17" />{{ item }}</span></div><button class="p-dialog-dismiss" @click="dialog?.close()">返回查看<Icon name="arrow" :size="16" /></button></dialog>
  </div>
</template>
