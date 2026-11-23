<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { api } from "./api";
import MeetingPeoplePicker from "./MeetingPeoplePicker.vue";
import MeetingDepartmentAssignee from "./MeetingDepartmentAssignee.vue";
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
  departmentId?: string | null;
  departmentName?: string | null;
  assigneeId?: string | null;
  assigneeName?: string | null;
  deadlineText?: string;
  sourceDueAt?: string;
  trackFollowUp: boolean;
  departments: MeetingOption[];
  published?: boolean;
  triggerId?: string;
}>();
const emit = defineEmits<{ saved: [] }>();
const open = ref(false);
const dialog = ref<HTMLDialogElement>();
const busy = ref(false);
const error = ref("");
/** 协调截止时间保留文本，待办时间使用北京时间。 */
const draft = reactive({
  conclusion: "",
  departmentId: "",
  assigneeId: "",
  assigneeName: "",
  deadlineText: "",
  dueAt: "",
  followUp: false,
});
/** 旧记录可能没有配合部门，保留原来的全员选择。 */
const savedDepartment = computed(
  () => props.initial?.departmentId ?? props.departmentId ?? "",
);
const departmentChanged = computed(
  () => draft.departmentId !== savedDepartment.value,
);
const needsAssignee = computed(
  () => departmentChanged.value || !!props.assigneeId,
);
watch(dialog, (value) => value?.showModal(), { flush: "post" });

function edit() {
  const current = props.initial;
  draft.conclusion = current?.conclusion ?? "";
  draft.departmentId = savedDepartment.value;
  draft.assigneeId = current?.assigneeId ?? props.assigneeId ?? "";
  draft.assigneeName = current?.assigneeName ?? props.assigneeName ?? "";
  draft.deadlineText = props.deadlineText ?? "";
  const due = current ? current.dueAt : props.sourceDueAt;
  draft.dueAt = due ? meetingDateInput(due) : "";
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
        ...(departmentChanged.value
          ? { departmentId: draft.departmentId }
          : {}),
        assigneeId: draft.assigneeId || null,
        deadlineText: draft.deadlineText,
        dueAt: draft.followUp && draft.dueAt ? meetingInstant(draft.dueAt) : null,
        ...(props.published ? {} : { followUp: draft.followUp }),
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
  <button :id="triggerId" type="button" class="secondary" @click="edit">编辑协调结果</button>
  <dialog
    v-if="open"
    ref="dialog"
    class="flow-dialog form-dialog flow-dialog-compact"
    aria-label="编辑协调结果"
    @cancel.prevent="!busy && (open = false)"
  >
    <form class="flow-dialog-shell" @submit.prevent="save">
      <header class="flow-dialog-header"><h2>编辑协调结果</h2></header>
      <div class="flow-dialog-body">
        <p>{{ subject }}</p>
        <label
          >会议结论
          <textarea
            v-model="draft.conclusion"
            :required="draft.followUp"
            maxlength="3000"
            rows="4"
          />
        </label>
        <div class="flow-dialog-fields">
          <label>
            配合部门
            <select
              v-model="draft.departmentId"
              @change="
                draft.assigneeId = '';
                draft.assigneeName = '';
              "
            >
              <option value="" disabled>未指定</option>
              <option
                v-if="
                  savedDepartment &&
                  !departments.some(({ id }) => id === savedDepartment)
                "
                :value="savedDepartment"
                disabled
              >
                {{ initial?.department ?? departmentName ?? "原配合部门" }}
              </option>
              <option
                v-for="department in departments"
                :key="department.id"
                :value="department.id"
              >
                {{ department.name }}
              </option>
            </select>
          </label>
          <label v-if="draft.departmentId">
            责任人（唯一）
            <MeetingDepartmentAssignee
              v-model="draft.assigneeId"
              :department-id="draft.departmentId"
              :selected-name="draft.assigneeName"
              :append-to="dialog"
              label="责任人（唯一）"
            />
          </label>
          <div v-else class="meeting-actions">
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
        </div>
        <label>
          截止时间
          <input v-model="draft.deadlineText" type="text" maxlength="3000" placeholder="例如 两周或 12 月底" />
        </label>
        <label v-if="trackFollowUp && !published">
          <input
            v-model="draft.followUp"
            type="checkbox"
          />发布纪要时生成会后待办
        </label>
        <label v-if="draft.followUp">
          待办截止时间（北京时间）
          <input v-model="draft.dueAt" type="datetime-local" step="1" required />
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
        <button
          type="submit"
          class="primary"
          :disabled="busy || (needsAssignee && !draft.assigneeId)"
        >
          保存
        </button>
      </footer>
    </form>
  </dialog>
</template>
