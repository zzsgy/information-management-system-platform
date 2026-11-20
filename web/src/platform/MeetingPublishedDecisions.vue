<script setup lang="ts">
import { ref } from "vue";
import { api } from "./api";
import RichTextEditor from "./RichTextEditor.vue";
import RichTextView from "./RichTextView.vue";

const props = defineProps<{
  meetingId: string;
  version: number;
  value: string | null;
}>();
const emit = defineEmits<{ saved: [] }>();
const editing = ref(false);
const draft = ref("");
const busy = ref(false);
const error = ref("");

function edit() {
  draft.value = props.value ?? "";
  error.value = "";
  editing.value = true;
}

/** 更正只提交决议，沿用纪要标识和当前版本。 */
async function save() {
  busy.value = true;
  error.value = "";
  try {
    await api("/meetings/" + props.meetingId + "/minutes/decisions", {
      method: "PUT",
      headers: { "X-Expected-Version": String(props.version) },
      body: JSON.stringify({ decisions: draft.value }),
    });
    editing.value = false;
    emit("saved");
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section class="flow-dialog-section">
    <div class="decision-heading">
      <h3>会议决议</h3>
      <button v-if="!editing" type="button" class="secondary" @click="edit">
        编辑会议决议
      </button>
    </div>
    <form v-if="editing" class="flow-dialog-section" @submit.prevent="save">
      <RichTextEditor
        v-model="draft"
        label="会议决议"
        :rows="4"
        :limit="12000"
        :disabled="busy"
      />
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <div class="decision-actions">
        <button
          type="button"
          class="secondary"
          :disabled="busy"
          @click="editing = false"
        >
          取消
        </button>
        <button type="submit" class="primary" :disabled="busy">保存决议</button>
      </div>
    </form>
    <RichTextView v-else-if="value" :value="value" />
    <p v-else>暂无会议决议。</p>
  </section>
</template>

<style scoped>
.decision-heading,
.decision-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}
.decision-heading {
  justify-content: space-between;
}
.decision-actions {
  justify-content: flex-end;
}
</style>
