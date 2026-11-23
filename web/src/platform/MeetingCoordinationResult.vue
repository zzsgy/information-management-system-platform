<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { ElOption, ElSelect } from "element-plus";
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
  sourceDepartments?: MeetingOption[];
  assigneeId?: string | null;
  assigneeName?: string | null;
  sourceAssignees?: MeetingOption[];
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
const assigneeSearch = ref("");
/** 协调截止时间保留文本，待办时间使用北京时间。 */
const draft = reactive({
  conclusion: "",
  departmentIds: [] as string[],
  assignees: [] as MeetingOption[],
  deadlineText: "",
  dueAt: "",
  followUp: false,
});
/** 旧记录可能没有配合部门，保留原来的全员选择。 */
const savedDepartments = computed(() => {
  const id = props.initial?.departmentId ?? props.departmentId;
  return props.initial?.departments ?? (!props.initial ? props.sourceDepartments : undefined)
    ?? (id ? [{ id, name: props.initial?.department ?? props.departmentName ?? "原配合部门" }] : []);
});
const availableDepartments = computed(() => [
  ...props.departments,
  ...savedDepartments.value.filter((unit) => !props.departments.some(({ id }) => id === unit.id)),
]);
const departmentChanged = computed(
  () => draft.departmentIds.join() !== savedDepartments.value.map(({ id }) => id).join(),
);
const needsAssignee = computed(
  () => departmentChanged.value || !!props.assigneeId,
);
watch(dialog, (value) => value?.showModal(), { flush: "post" });

function edit() {
  const current = props.initial;
  draft.conclusion = current?.conclusion ?? "";
  draft.departmentIds = savedDepartments.value.map(({ id }) => id);
  const id = current ? current.assigneeId : props.assigneeId;
  const name = current ? current.assigneeName : props.assigneeName;
  draft.assignees = [...((current ? current.assignees : props.sourceAssignees)
    ?? (id ? [{ id, name: name ?? "原责任人" }] : []))];
  assigneeSearch.value = "";
  draft.deadlineText = props.deadlineText ?? "";
  const due = current ? current.dueAt : props.sourceDueAt;
  draft.dueAt = due ? meetingDateInput(due) : "";
  draft.followUp = current?.followUp ?? false;
  error.value = "";
  open.value = true;
}

function addAssignee(person: MeetingOption) {
  draft.assignees.push({ id: person.id, name: person.name });
  assigneeSearch.value = "";
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
          ? { departmentIds: draft.departmentIds }
          : {}),
        assigneeIds: draft.assignees.map(({ id }) => id),
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
            <ElSelect
              v-model="draft.departmentIds"
              :append-to="dialog"
              aria-label="配合部门"
              placeholder="选择配合部门"
              multiple
              filterable
              @change="draft.assignees = []"
            >
              <ElOption
                v-for="department in availableDepartments"
                :key="department.id"
                :value="department.id"
                :label="department.name"
                :disabled="!departments.some(({ id }) => id === department.id)"
              />
            </ElSelect>
          </label>
          <div>
            <span>责任人</span>
            <ul v-if="draft.assignees.length" aria-label="已选责任人">
              <li v-for="person in draft.assignees" :key="person.id">
                {{ person.name }}
                <button type="button" class="icon-button" :aria-label="'移除' + person.name"
                  @click="draft.assignees = draft.assignees.filter(({ id }) => id !== person.id)">×</button>
              </li>
            </ul>
            <MeetingDepartmentAssignee
              v-if="draft.departmentIds.length"
              v-model="assigneeSearch"
              :department-ids="draft.departmentIds"
              :excluded="draft.assignees.map(({ id }) => id)"
              :append-to="dialog"
              label="添加责任人"
              @select="addAssignee"
            />
            <MeetingPeoplePicker
              v-else
              :departments="departments"
              :excluded="draft.assignees.map(({ id }) => id)"
              multiple
              label="添加责任人"
              @confirm="(people) => people.forEach(addAssignee)"
            />
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
          :disabled="busy || (departmentChanged && !draft.departmentIds.length) || (needsAssignee && !draft.assignees.length)"
        >
          保存
        </button>
      </footer>
    </form>
  </dialog>
</template>
