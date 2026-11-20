<script setup lang="ts">
import {
  computed,
  nextTick,
  onMounted,
  onUnmounted,
  reactive,
  ref,
  watch,
} from "vue";
import { api } from "./api";
import MarkdownImageEditor from "./MarkdownImageEditor.vue";
import MeetingDepartmentAssignee from "./MeetingDepartmentAssignee.vue";
import {
  markdownImageUploads,
  type MarkdownImageDraft,
} from "./markdownImages";
import { coordinationLabels, type MeetingOption } from "./meeting";
import { weeklyLabels, type WeeklyKind, type WeeklyRow } from "./weeklyReport";

const props = defineProps<{
  base: string;
  kind: WeeklyKind;
  row?: WeeklyRow;
  departments: MeetingOption[];
}>();
const emit = defineEmits<{ saved: []; closed: [] }>();
const dialog = ref<HTMLDialogElement>();
const rowId = props.row?.id ?? crypto.randomUUID();
const version = ref(props.row?.version ?? 0);
const images = ref<MarkdownImageDraft[]>([]);
const uploaded = ref(props.row?.data.imageKeys ?? []);
const preparing = ref(false);
const busy = ref(false);
const error = ref("");
const coordination = props.kind === "COORDINATION";
const existing = props.row?.data;
const fields = reactive({
  markdown: existing?.markdown ?? "",
  note: existing?.note ?? "",
  subject: existing?.subject ?? "",
  support: existing?.support ?? "",
  expected: existing?.expected ?? "",
  category: existing?.category ?? "DEPARTMENT",
  departmentId: existing?.departmentId ?? "",
  personId: existing?.personId ?? "",
});
const draft = computed(() =>
  coordination
    ? {
        kind: props.kind,
        subject: fields.subject,
        support: fields.support,
        expected: fields.expected,
        category: fields.category,
        departmentId: fields.departmentId || null,
        personId: fields.personId || null,
      }
    : { kind: props.kind, markdown: fields.markdown, note: fields.note },
);
const stored = ref(JSON.stringify(draft.value));
const dirty = computed(() => stored.value !== JSON.stringify(draft.value));
let timer: number | undefined;
let pending: Promise<void> | undefined;

/** 保存版本推进的同时保留请求期间继续输入的内容，由下一次保存发送。 */
function save(): Promise<void> {
  if (pending) return pending;
  if (!dirty.value || preparing.value) return Promise.resolve();
  const snapshot = draft.value;
  const attachments = coordination
    ? []
    : markdownImageUploads(fields.markdown, images.value, true);
  busy.value = true;
  error.value = "";
  pending = (async () => {
    try {
      const value = await api<WeeklyRow>(props.base + "/rows/" + rowId, {
        method: "PUT",
        body: JSON.stringify({
          ...snapshot,
          version: version.value,
          images: attachments,
        }),
      });
      version.value = value.version;
      uploaded.value = value.data.imageKeys ?? [];
      const savedImages = new Set(attachments.map(({ id }) => id));
      images.value = images.value.filter(({ id }) => !savedImages.has(id));
      stored.value = JSON.stringify(snapshot);
      emit("saved");
    } catch (failure) {
      error.value = (failure as Error).message;
    } finally {
      pending = undefined;
      busy.value = false;
      if (!error.value && dirty.value) queueSave();
    }
  })();
  return pending;
}

/** 打字、粘贴和下拉更改延时自动保存，输入时不会逐字符发请求。 */
function queueSave() {
  window.clearTimeout(timer);
  if (dirty.value && !preparing.value)
    timer = window.setTimeout(() => void save(), 650);
}
watch([draft, preparing], queueSave);

/** 退出前先保存最后一次输入，失败时留在编辑器。 */
async function finish() {
  if (preparing.value) return;
  window.clearTimeout(timer);
  await save();
  if (!error.value && dirty.value) await save();
  if (!dirty.value && !error.value) emit("closed");
}

onMounted(async () => {
  dialog.value?.showModal();
  await nextTick();
  dialog.value?.querySelector("textarea")?.focus();
});
onUnmounted(() => window.clearTimeout(timer));
</script>

<template>
  <dialog
    ref="dialog"
    class="weekly-row-editor"
    aria-labelledby="weekly-row-editor-title"
    @cancel.prevent="finish"
  >
    <header>
      <h3 id="weekly-row-editor-title">
        {{ row ? "编辑" : "新增" }}{{ weeklyLabels[kind] }}
      </h3>
      <button
        type="button"
        class="text-button"
        aria-label="关闭"
        :disabled="preparing"
        @click="finish"
      >
        关闭
      </button>
    </header>
    <div v-if="coordination" class="weekly-row-fields">
      <label
        >具体事宜<textarea v-model="fields.subject" rows="2" maxlength="500" />
      </label>
      <label
        >需协调或配合内容<textarea
          v-model="fields.support"
          rows="2"
          maxlength="1000"
        />
      </label>
      <label
        >期望结果 / 验收标准<textarea
          v-model="fields.expected"
          rows="2"
          maxlength="1000"
        />
      </label>
      <label>
        事项类别
        <select v-model="fields.category">
          <option
            v-for="(label, key) in coordinationLabels"
            :key="key"
            :value="key"
          >
            {{ label }}
          </option>
        </select>
      </label>
      <label>
        配合部门
        <select
          v-model="fields.departmentId"
          aria-label="配合部门"
          @change="fields.personId = ''"
        >
          <option value="">请选择</option>
          <option
            v-for="department in departments"
            :key="department.id"
            :value="department.id"
          >
            {{ department.name }}
          </option>
        </select>
      </label>
      <label>
        责任人（唯一）
        <MeetingDepartmentAssignee
          v-model="fields.personId"
          :department-id="fields.departmentId"
          :selected-name="row?.assigneeName"
          :append-to="dialog"
          label="责任人（唯一）"
        />
      </label>
    </div>
    <div v-else class="weekly-row-fields">
      <MarkdownImageEditor
        v-model="fields.markdown"
        v-model:images="images"
        v-model:preparing="preparing"
        label="事项说明"
        :rows="5"
        :limit="8000"
        :image-base="'/api/v1' + base + '/rows/' + rowId + '/images'"
        :image-keys="uploaded"
        numbered
      />
      <label
        >备注<textarea v-model="fields.note" rows="2" maxlength="2000" />
      </label>
    </div>
    <footer>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <p v-else role="status">
        {{
          busy
            ? "正在自动保存…"
            : dirty
              ? "待自动保存"
              : version
                ? "草稿已保存"
                : "输入后自动保存"
        }}
      </p>
      <button
        v-if="error && !busy"
        type="button"
        class="secondary"
        @click="emit('closed')"
      >
        关闭并放弃未保存改动
      </button>
      <button
        type="button"
        class="primary"
        :disabled="preparing"
        @click="finish"
      >
        {{ error ? "重试保存" : "完成" }}
      </button>
    </footer>
  </dialog>
</template>

<style scoped>
.weekly-row-editor {
  width: min(760px, calc(100vw - 30px));
  max-height: calc(100dvh - 42px);
  padding: 22px;
  color: var(--ink);
  border: 1px solid var(--line);
  border-radius: 14px;
}
.weekly-row-editor::backdrop {
  background: rgb(15 23 42 / 42%);
}
header,
footer {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
header {
  justify-content: space-between;
}
h3,
p {
  margin: 0;
}
.weekly-row-fields {
  display: grid;
  gap: 12px;
  margin-block: 18px;
}
.weekly-row-fields label {
  display: grid;
  gap: 6px;
}
textarea,
select {
  width: 100%;
}
footer p {
  margin-inline-end: auto;
}
</style>
