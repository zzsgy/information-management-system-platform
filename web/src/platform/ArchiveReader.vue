<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import { api } from "./api";
import DocumentReader from "./DocumentReader.vue";
import { waitArchiveJob, type ArchiveUsage } from "./archiveFiles";

/** 父页面接收安全中文错误；组件不回传图像、原件或登录凭据。 */
const emit = defineEmits<{ failure: [message: string]; closed: [] }>();
/** 图像只保存在内存，任何失权、换页、关闭或销毁都释放旧Blob。 */
const visible = ref(false),
  title = ref(""),
  usage = ref<ArchiveUsage | null>(null),
  busy = ref(false);
/** 请求代次阻止旧页面或旧上下文覆盖新窗口。 */
let generation = 0,
  alive = true,
  timer: ReturnType<typeof setInterval> | undefined;
function close() {
  generation++;
  visible.value = false;
  usage.value = null;
  busy.value = false;
}
/** 普通版本与候选上下文由服务器验证；前端不能据按钮可见性宣布获权。 */
async function open(body: object, label: string) {
  close();
  visible.value = true;
  title.value = label;
  busy.value = true;
  const current = generation;
  try {
    const receipt = await api<ArchiveUsage>("/archive/view-sessions", {
      method: "POST",
      headers: { "X-Operation-Id": crypto.randomUUID() },
      body: JSON.stringify(body),
    });
    await waitArchiveJob<ArchiveUsage>(
      receipt.jobId,
      () => alive && current === generation,
    );
    if (current !== generation || !alive) return;
    const ready = await api<ArchiveUsage>(
      `/archive/view-sessions/${receipt.usageId}`,
      {},
      false,
    );
    if (current !== generation || !alive) return;
    usage.value = ready;
  } catch (failure) {
    if (current === generation) {
      close();
      emit("failure", (failure as Error).message);
    }
  } finally {
    if (current === generation) busy.value = false;
  }
}
/** 轮询只负责及时清屏；服务端对实际交付的逐次授权才是权限边界。 */
onMounted(() => {
  timer = setInterval(async () => {
    if (!usage.value) return;
    const watched = usage.value.usageId;
    try {
      await api(`/archive/view-sessions/${watched}`, {}, false);
    } catch (failure) {
      if (usage.value?.usageId === watched) {
        close();
        emit("failure", (failure as Error).message);
      }
    }
  }, 4000);
});
onUnmounted(() => {
  alive = false;
  close();
  if (timer) clearInterval(timer);
});
defineExpose({ open, close });
</script>
<template>
  <DocumentReader
    v-if="visible"
    :key="generation"
    :title="title"
    :version="usage?.versionNo || 1"
    :page-count="usage?.pageCount || 0"
    :page-path="usage ? `/archive/view-sessions/${usage.usageId}/pages` : ''"
    :loading="busy"
    @close="
      close();
      emit('closed');
    "
    @error="
      close();
      emit('failure', $event);
    "
  />
</template>
