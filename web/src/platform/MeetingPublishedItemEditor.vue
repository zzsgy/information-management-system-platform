<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { api } from "./api";
import MeetingPeoplePicker from "./MeetingPeoplePicker.vue";
import RichTextEditor from "./RichTextEditor.vue";
import {
  meetingDateInput,
  meetingInstant,
  type MeetingItemDraft,
  type MeetingOption,
} from "./meeting";

const props = defineProps<{
  item: MeetingItemDraft & { id: string };
  departments: MeetingOption[];
}>();
const emit = defineEmits<{ saved: [] }>();
const dialog = ref<HTMLDialogElement>();
const open = ref(false);
const busy = ref(false);
const error = ref("");
const draft = reactive({
  content: "",
  assigneeId: "",
  assigneeName: "",
  dueAt: "",
});
const requiresFeedback = computed(
  () =>
    draft.assigneeId !== props.item.assigneeId ||
    draft.content !== props.item.content,
);
watch(dialog, (value) => value?.showModal(), { flush: "post" });

function edit() {
  draft.content = props.item.content;
  draft.assigneeId = props.item.assigneeId;
  draft.assigneeName = props.item.assigneeName ?? "";
  draft.dueAt = meetingDateInput(props.item.dueAt);
  error.value = "";
  open.value = true;
}

/** 更新原待办，详情刷新后沿用同一个待办链接。 */
async function save() {
  busy.value = true;
  error.value = "";
  try {
    await api("/meetings/items/" + props.item.id, {
      method: "PUT",
      body: JSON.stringify({
        content: draft.content,
        assigneeId: draft.assigneeId,
        dueAt: meetingInstant(draft.dueAt),
      }),
    });
    open.value = false;
    emit("saved");
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <button type="button" class="secondary" @click="edit">编辑待办</button>
  <dialog
    v-if="open"
    ref="dialog"
    class="flow-dialog form-dialog flow-dialog-compact"
    aria-label="编辑会后待办"
    @cancel.prevent="!busy && (open = false)"
  >
    <form class="flow-dialog-shell" @submit.prevent="save">
      <header class="flow-dialog-header"><h2>编辑会后待办</h2></header>
      <div class="flow-dialog-body">
        <RichTextEditor
          v-model="draft.content"
          label="待办内容"
          :rows="4"
          :limit="4000"
          :disabled="busy"
          required
        />
        <div class="flow-dialog-section">
          <span>责任人（唯一） {{ draft.assigneeName }}</span>
          <MeetingPeoplePicker
            :departments="departments"
            label="更换责任人"
            @select="
              (person) => {
                draft.assigneeId = person.id;
                draft.assigneeName = person.name;
              }
            "
          />
        </div>
        <label
          >截止时间（北京时间）<input
            v-model="draft.dueAt"
            type="datetime-local"
            step="1"
            required
        /></label>
        <p v-if="requiresFeedback">
          保存后需重新反馈，原反馈保留在待办记录中。
        </p>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button
          type="button"
          class="secondary"
          :disabled="busy"
          @click="open = false"
        >
          取消
        </button>
        <button
          type="submit"
          class="primary"
          :disabled="busy || !draft.content.trim()"
        >
          保存待办
        </button>
      </footer>
    </form>
  </dialog>
</template>
