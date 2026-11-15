<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from "vue";
import { api } from "./api";
import { portalTime } from "./portal";

/** 已到时且未确认的本人提醒，正文只保存在当前页面。 */
interface Reminder {
  id: string;
  title: string;
  description: string;
  remindAt: string;
}

const dialog = ref<HTMLDialogElement | null>(null);
const item = ref<Reminder | null>(null);
const error = ref("");
const confirming = ref(false);
let polling = false;
let timer: ReturnType<typeof setInterval>;

/** 前台页面短轮询不延长系统登录会话，桌面在线时服务端不发网页提醒。 */
async function poll() {
  if (polling || confirming.value || document.hidden) return;
  polling = true;
  try {
    const result = await api<{ item: Reminder | null }>("/portal/todo-reminders", {}, false);
    if (item.value?.id !== result.item?.id) error.value = "";
    item.value = result.item;
    await nextTick();
    if (item.value && dialog.value && !dialog.value.open) dialog.value.showModal();
    if (!item.value) dialog.value?.close();
  } catch {
    // 网络恢复后重试，已打开的提醒可继续查看。
  } finally {
    polling = false;
  }
}

/** 确认提醒与办理待办分开，网络失败时保留弹窗。 */
async function acknowledge(open: boolean) {
  const current = item.value;
  if (!current || confirming.value) return;
  confirming.value = true;
  error.value = "";
  try {
    await api("/portal/todo-reminders/ack", {
      method: "POST",
      body: JSON.stringify({ id: current.id, remindAt: current.remindAt }),
    });
    item.value = null;
    dialog.value?.close();
    if (open) location.hash = `#/todos?item=${current.id}`;
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    confirming.value = false;
  }
}

onMounted(() => {
  void poll();
  timer = setInterval(poll, 10_000);
  document.addEventListener("visibilitychange", poll);
  window.addEventListener("todo-reminder-mode-changed", poll);
});
onUnmounted(() => {
  clearInterval(timer);
  document.removeEventListener("visibilitychange", poll);
  window.removeEventListener("todo-reminder-mode-changed", poll);
});
</script>

<template>
  <dialog ref="dialog" class="form-dialog compact" aria-labelledby="todo-reminder-title" @cancel.prevent>
    <div v-if="item">
      <h2 id="todo-reminder-title">待办提醒</h2>
      <h3>{{ item.title }}</h3>
      <p class="muted">{{ portalTime(item.remindAt) }}</p>
      <p v-if="item.description" class="reminder-description">{{ item.description }}</p>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <div class="dialog-actions">
        <button type="button" class="secondary" :disabled="confirming" @click="acknowledge(false)">知道了</button>
        <button type="button" class="primary" :disabled="confirming" @click="acknowledge(true)">查看待办</button>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.reminder-description {
  max-height: 34vh;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
