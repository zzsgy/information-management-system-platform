<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import { api } from "./api";
import { hasReadership, type Announcement } from "./portal";
import AnnouncementLetter from "./AnnouncementLetter.vue";
import AnnouncementReadership from "./AnnouncementReadership.vue";

const props = defineProps<{ id: string }>();
const emit = defineEmits<{ close: []; read: [id: string] }>();
const dialog = ref<HTMLDialogElement | null>(null);
const item = ref<Announcement | null>(null);
const error = ref("");
const confirming = ref(false);
const readership = ref(false);
let refreshTimer: ReturnType<typeof setInterval>;

/** 定时和窗口回焦只重验可见性，公告撤下后清除正文和阅读名单。 */
async function load(activity: boolean) {
  try {
    item.value = await api<Announcement>(`/portal/announcements/${props.id}`, {}, activity);
    error.value = "";
  } catch (failure) {
    item.value = null;
    readership.value = false;
    error.value = (failure as Error).message;
  }
}
function refresh() {
  void load(false);
}
async function confirmRead() {
  if (confirming.value) return;
  confirming.value = true;
  error.value = "";
  try {
    await api(`/portal/announcements/${props.id}/confirm-read`, { method: "POST" });
    emit("read", props.id);
    dialog.value?.close();
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    confirming.value = false;
  }
}
onMounted(() => {
  dialog.value?.showModal();
  void load(true);
  refreshTimer = setInterval(refresh, 30000);
  window.addEventListener("focus", refresh);
});
onUnmounted(() => {
  clearInterval(refreshTimer);
  window.removeEventListener("focus", refresh);
});
</script>

<template>
  <dialog ref="dialog" class="form-dialog flow-dialog announcement-reader" aria-labelledby="announcement-reader-title"
    @close="emit('close')" @cancel="confirming && $event.preventDefault()">
    <div class="flow-dialog-shell">
      <header class="flow-dialog-header">
        <h2 id="announcement-reader-title">公告详情</h2>
        <button class="icon-button" type="button" aria-label="关闭公告详情" :disabled="confirming" autofocus @click="dialog?.close()">×</button>
      </header>
      <div class="flow-dialog-body">
        <AnnouncementLetter v-if="item" :item="item" />
        <p v-else-if="!error" role="status" class="muted">正在加载公告…</p>
      </div>
      <p v-if="error" role="alert" class="form-error">{{ error }}</p>
      <footer class="flow-dialog-footer">
        <button v-if="item?.isCreator && hasReadership(item)" type="button" class="secondary" @click="readership = true">阅读统计</button>
        <button v-if="item?.canConfirmRead" type="button" class="primary" :disabled="confirming" @click="confirmRead">我已阅读</button>
        <button v-else type="button" class="secondary" @click="dialog?.close()">关闭</button>
      </footer>
    </div>
    <AnnouncementReadership v-if="readership && item" :announcement="item" @close="readership = false" />
  </dialog>
</template>

<style scoped>
.announcement-reader {
  --flow-dialog-width: 960px;
}
.announcement-reader :deep(.portal-letterhead) {
  max-width: 100%;
  margin: 0 auto;
}
.announcement-reader > .flow-dialog-shell > .form-error {
  padding: 0 24px;
}
@media (max-width: 660px) {
  .announcement-reader .flow-dialog-body {
    padding: 12px;
  }
  .announcement-reader :deep(.portal-letter-content) {
    padding-inline: 20px;
  }
}
</style>
