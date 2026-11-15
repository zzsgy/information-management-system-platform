<script setup lang="ts">
import { onBeforeUnmount, ref } from "vue";
import { api } from "./api";

const props = defineProps<{
  meetingId: string;
  initial: string | null;
  disabled: boolean;
}>();
const notes = ref(props.initial ?? "");
let saved = notes.value;
let pending: Promise<void> | undefined;
let timer: ReturnType<typeof setTimeout> | undefined;
const status = ref("");
const error = ref("");

function scheduleSave() {
  clearTimeout(timer);
  status.value = "待保存";
  error.value = "";
  timer = setTimeout(() => void flush(), 650);
}

/** 关闭详情或结束会议时先等已发出的请求，再保存最新输入。 */
async function flush() {
  clearTimeout(timer);
  try {
    while (pending || notes.value !== saved) {
      if (pending) {
        await pending;
        continue;
      }
      const snapshot = notes.value;
      error.value = "";
      status.value = "保存中";
      pending = api("/meetings/" + props.meetingId + "/live-notes", {
        method: "PUT",
        body: JSON.stringify({ notes: snapshot }),
      })
        .then(() => {
          saved = snapshot;
          status.value = "已保存";
        })
        .finally(() => {
          pending = undefined;
        });
      await pending;
    }
    return true;
  } catch (e) {
    status.value = "";
    error.value = "保存失败：" + (e as Error).message;
    return false;
  }
}

onBeforeUnmount(() => clearTimeout(timer));
defineExpose({ flush });
</script>

<template>
  <section class="flow-dialog-section">
    <label>
      会议记录
      <textarea
        v-model="notes"
        rows="7"
        maxlength="12000"
        :disabled="disabled"
        @input="scheduleSave"
      />
    </label>
    <p v-if="error" role="alert" class="form-error">
      {{ error }}
      <button
        type="button"
        class="secondary"
        :disabled="disabled"
        @click="flush()"
      >
        重试
      </button>
    </p>
    <small v-else role="status">{{ status }}</small>
  </section>
</template>
