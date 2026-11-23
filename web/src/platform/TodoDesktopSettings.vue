<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref } from "vue";
import { api } from "./api";

/** 配对码只在当前弹窗显示，关闭后清除。 */
const dialog = ref<HTMLDialogElement | null>(null);
const paired = ref(false);
const connected = ref(false);
const enabled = ref(false);
const online = ref(false);
const installedVersion = ref<string | null>(null);
const availableVersion = ref("");
const helperAvailable = ref<boolean | null>(null);
const code = ref("");
const error = ref("");
const message = ref("");
const busy = ref(false);
const endpoint = "/portal/todo-reminders/desktop";
const helperDownload = "/downloads/ims-todo-helper.exe";
type DesktopStatus = { paired: boolean; connected: boolean; enabled: boolean; online: boolean; clientVersion: string | null };
let pendingCheck: ReturnType<typeof setInterval> | undefined;
const updateAvailable = computed(() => connected.value && !!availableVersion.value &&
  (!installedVersion.value || availableVersion.value.localeCompare(installedVersion.value, "en", { numeric: true }) > 0));

/** 同步账号开关与实时连接状态，离线不会清除已保存的偏好。 */
function showStatus(status: DesktopStatus) {
  paired.value = status.paired;
  connected.value = status.connected;
  enabled.value = status.enabled;
  online.value = status.online;
  installedVersion.value = status.clientVersion;
}

async function refresh() {
  if (busy.value) return;
  try {
    const status = await api<DesktopStatus>(endpoint, {}, false);
    if (!busy.value) showStatus(status);
  } catch (failure) {
    if (dialog.value?.open) error.value = (failure as Error).message;
  }
}

function open() {
  error.value = "";
  dialog.value?.showModal();
  void refresh();
  void refreshHelperAvailability();
  void refreshAvailableVersion();
}

/** 缺少助手的部署会返回 JSON 错误，不能交给浏览器作为文件下载。 */
async function refreshHelperAvailability() {
  helperAvailable.value = null;
  try {
    const response = await fetch(helperDownload, { method: "HEAD", cache: "no-store" });
    const media = response.headers.get("Content-Type") ?? "";
    helperAvailable.value = response.ok && !/text\/html|application\/(?:problem\+)?json/i.test(media);
  } catch {
    helperAvailable.value = false;
  }
}

/** 下载清单由部署产物生成，版本检查不影响现有提醒设置。 */
async function refreshAvailableVersion() {
  availableVersion.value = "";
  try {
    const response = await fetch("/downloads/ims-todo-helper-version.json", { cache: "no-store" });
    if (!response.ok) return;
    const { version } = await response.json() as { version: string };
    if (/^\d+\.\d+\.\d+$/.test(version)) availableVersion.value = version;
  } catch {
    // 无法读取清单时仍保留基础下载入口。
  }
}

async function toggle() {
  busy.value = true;
  error.value = "";
  try {
    showStatus(await api<DesktopStatus>(endpoint, {
      method: "PUT",
      body: JSON.stringify({ enabled: !enabled.value }),
    }));
    window.dispatchEvent(new Event("todo-reminder-mode-changed"));
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}

/** 重新生成时旧助手失效，允许员工主动换电脑。 */
async function pair() {
  busy.value = true;
  error.value = message.value = "";
  try {
    if (location.protocol !== "https:") throw new Error("请通过 HTTPS 地址打开系统后再配对");
    const result = await api<{ token: string }>(endpoint, { method: "POST" });
    code.value = JSON.stringify({ origin: location.origin, token: result.token });
    paired.value = true;
    online.value = connected.value = enabled.value = false;
    installedVersion.value = null;
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}

async function unpair() {
  busy.value = true;
  error.value = message.value = "";
  try {
    await api(endpoint, { method: "DELETE" });
    paired.value = online.value = connected.value = enabled.value = false;
    installedVersion.value = null;
    code.value = "";
    window.dispatchEvent(new Event("todo-reminder-mode-changed"));
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}

async function copy() {
  try {
    await navigator.clipboard.writeText(code.value);
    message.value = "已复制，请粘贴到 Windows 托盘助手。";
  } catch {
    error.value = "无法自动复制，请选中配对码手动复制。";
  }
}

/** 工作台暂时离开时暂停轮询，返回后按原设置重新读取状态。 */
function startStatusChecks() {
  if (pendingCheck !== undefined) return;
  void refresh();
  window.addEventListener("focus", refresh);
  pendingCheck = setInterval(() => {
    if (dialog.value?.open && paired.value && !connected.value) void refresh();
  }, 5000);
}
function stopStatusChecks() {
  window.removeEventListener("focus", refresh);
  clearInterval(pendingCheck);
  pendingCheck = undefined;
}
onMounted(startStatusChecks);
onActivated(startStatusChecks);
onDeactivated(stopStatusChecks);
onUnmounted(stopStatusChecks);
</script>

<template>
  <button type="button" class="secondary desktop-reminder-button" :class="{ 'is-enabled': enabled }" @click="open">
    桌面提醒 · {{ enabled ? "开启" : paired && !connected ? "待连接" : "关闭" }}
  </button>
  <dialog
    ref="dialog"
    class="form-dialog compact"
    aria-labelledby="todo-desktop-title"
    @close="code = message = ''"
    @cancel.prevent="!busy && dialog?.close()"
  >
    <h2 id="todo-desktop-title">Windows 桌面提醒</h2>
    <p>下载并打开助手，粘贴本人的配对码。助手运行时，即使关闭浏览器也会提醒。</p>
    <p class="muted">
      {{ enabled ? online ? "桌面提醒已开启，助手在线" : "桌面提醒已开启，助手离线时使用网页提醒"
        : paired && !connected ? "等待助手连接，当前使用网页提醒" : "桌面提醒已关闭，当前使用网页提醒" }}
    </p>
    <button v-if="connected" type="button" class="secondary" :disabled="busy" @click="toggle">
      {{ enabled ? "关闭桌面提醒" : "开启桌面提醒" }}
    </button>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="message" class="muted" role="status">{{ message }}</p>
    <label v-if="code">
      配对码（仅供本人使用）
      <textarea :value="code" rows="3" readonly />
    </label>
    <div class="desktop-release">
      <p v-if="helperAvailable && availableVersion" class="muted">
        本机版本 {{ connected ? installedVersion || "旧版" : "未连接" }} · 可用版本 {{ availableVersion }}
      </p>
      <p v-if="helperAvailable && updateAvailable">
        {{ installedVersion ? "有新版本。右键 Windows 托盘图标，选择「检查更新」。"
          : "旧版没有更新入口。请退出旧助手，下载新版覆盖后重新打开。" }}
      </p>
      <a v-if="helperAvailable" class="text-button" :href="helperDownload + (availableVersion ? '?v=' + availableVersion : '')" download="ims-todo-helper.exe">
        {{ updateAvailable ? "下载新版 Windows 托盘助手" : "下载 Windows 托盘助手" }}
      </a>
      <p v-else class="muted" role="status">
        {{ helperAvailable === null ? "正在检查托盘助手下载…" : "当前托盘助手下载不可用，请联系管理员。" }}
      </p>
    </div>
    <div class="dialog-actions">
      <button type="button" class="secondary" :disabled="busy" @click="dialog?.close()">关闭</button>
      <button v-if="paired" type="button" class="secondary" :disabled="busy" @click="unpair">解除配对</button>
      <button v-if="code" type="button" class="primary" @click="copy">复制配对码</button>
      <button v-else type="button" class="primary" :disabled="busy" @click="pair">
        {{ paired ? "生成新配对码" : "生成配对码" }}
      </button>
    </div>
  </dialog>
</template>

<style scoped>
.desktop-reminder-button {
  white-space: nowrap;
}
.desktop-reminder-button.is-enabled {
  color: var(--brand-hover);
  border-color: #d3e8e8;
  background: var(--brand-soft);
}
.desktop-release {
  margin-top: 14px;
}
.desktop-release p {
  margin: 0 0 6px;
}
</style>
