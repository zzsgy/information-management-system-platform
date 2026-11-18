<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import { api } from "./api";
import MeetingPeoplePicker from "./MeetingPeoplePicker.vue";
import {
  meetingDateInput,
  meetingInstant,
  type CoordinationResult,
  type MeetingOption,
} from "./meeting";

const props = defineProps<{
  meetingId: string;
  rowKey: string;
  subject: string;
  initial?: CoordinationResult;
  trackFollowUp: boolean;
  departments: MeetingOption[];
}>();
const emit = defineEmits<{ saved: [] }>();
const open = ref(false);
const dialog = ref<HTMLDialogElement>();
const busy = ref(false);
const error = ref("");
/** 编辑中的截止时间使用上海时间，提交时转回 ISO 时间戳。 */
const draft = reactive({
  conclusion: "",
  assigneeId: "",
  assigneeName: "",
  dueAt: "",
  followUp: false,
});
watch(dialog, (value) => value?.showModal(), { flush: "post" });

function edit() {
  const current = props.initial;
  draft.conclusion = current?.conclusion ?? "";
  draft.assigneeId = current?.assigneeId ?? "";
  draft.assigneeName = current?.assigneeName ?? "";
  draft.dueAt = current?.dueAt ? meetingDateInput(current.dueAt) : "";
  draft.followUp = current?.followUp ?? false;
  error.value = "";
  open.value = true;
}

/** 更新本条事项，保存成功后重新加载发起人详情。 */
async function save() {
  busy.value = true;
  error.value = "";
  try {
    await api("/meetings/" + props.meetingId + "/coordination", {
      method: "PUT",
      body: JSON.stringify({
        key: props.rowKey,
        conclusion: draft.conclusion,
        assigneeId: draft.assigneeId || null,
        dueAt: draft.dueAt ? meetingInstant(draft.dueAt) : null,
        followUp: draft.followUp,
      }),
    });
    open.value = false;
    emit("saved");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <button type="button" class="secondary" @click="edit">
    {{ initial ? "修改结论" : "记录结论" }}
  </button>
  <dialog
    v-if="open"
    ref="dialog"
    class="flow-dialog"
    aria-label="记录事项结论"
    @cancel.prevent="!busy && (open = false)"
  >
    <form @submit.prevent="save">
      <header class="flow-dialog-header"><h2>记录事项结论</h2></header>
      <div class="flow-dialog-body">
        <p>{{ subject }}</p>
        <label
          >会议结论
          <textarea
            v-model="draft.conclusion"
            required
            maxlength="3000"
            rows="4"
          />
        </label>
        <div class="meeting-actions">
          <span>唯一责任人 {{ draft.assigneeName || "未选择" }}</span>
          <MeetingPeoplePicker
            :departments="departments"
            label="选择责任人"
            @select="
              (person) => {
                draft.assigneeId = person.id;
                draft.assigneeName = person.name;
              }
            "
          />
          <button
            v-if="draft.assigneeId"
            type="button"
            class="secondary"
            @click="
              draft.assigneeId = '';
              draft.assigneeName = '';
            "
          >
            清除
          </button>
        </div>
        <label
          >截止时间（北京时间）
          <input
            v-model="draft.dueAt"
            type="datetime-local"
            step="1"
            :required="draft.followUp"
          />
        </label>
        <label v-if="trackFollowUp">
          <input
            v-model="draft.followUp"
            type="checkbox"
          />发布纪要时生成会后待办
        </label>
        <p v-if="error" role="alert" class="form-error">{{ error }}</p>
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
        <button type="submit" class="primary" :disabled="busy">保存结论</button>
      </footer>
    </form>
  </dialog>
</template>
