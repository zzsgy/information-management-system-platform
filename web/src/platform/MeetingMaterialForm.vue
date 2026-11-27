<script setup lang="ts">
import { computed, ref } from "vue";
import MarkdownImageEditor from "./MarkdownImageEditor.vue";
import MeetingDepartmentAssignee from "./MeetingDepartmentAssignee.vue";
import { markdownImageKeys, markdownImageUploads, type MarkdownImageDraft } from "./markdownImages";
import { api } from "./api";
import { vNumbered } from "./meetingNumbered";
import {
  materialLabels,
  coordinationLabels,
  meetingMaterialImageBase,
  type MaterialKind,
  type MaterialRow,
  type MeetingMaterial,
  type MeetingOption,
} from "./meeting";

const props = defineProps<{
  meetingId: string;
  kind: MaterialKind;
  author: { name: string; department: string };
  existing?: MeetingMaterial;
  departments: MeetingOption[];
}>();
const emit = defineEmits<{ saved: [] }>();
const narrative = ref(props.existing?.data.markdown ?? props.existing?.data.text ?? "");
const images = ref<MarkdownImageDraft[]>([]);
const preparing = ref(false);
let nextRowId = 0;
function makeRow(values: MaterialRow = { subject: "", expected: "" }) {
  return { departmentId: null, category: "DEPARTMENT", support: "", ...values, assigneeId: values.assigneeId ?? "", rowId: nextRowId++ };
}
const rows = ref(props.existing?.data.rows?.map(makeRow) ?? (props.kind === "TOPICS" ? [makeRow()] : []));
function addRow() {
  rows.value.push(makeRow());
}
const error = ref("");
const busy = ref(false);
/** 四类材料共用提交快照，行编号仅用于渲染，不参与内容判断。 */
const contentSignature = computed(() => JSON.stringify(
  props.kind === "REPORT" || props.kind === "WEEKLY_PLAN"
    ? narrative.value
    : rows.value.map(({ subject, expected, departmentId, category, support, assigneeId }) => ({
        subject, expected,
        ...(props.kind === "COORDINATION" ? { departmentId, category, support, assigneeId } : {}),
      })),
));
const submittedSignature = ref(props.existing ? contentSignature.value : null);
const submittedUnchanged = computed(() =>
  submittedSignature.value !== null && contentSignature.value === submittedSignature.value,
);
const checkedRows = ref(false);
const subjectLabel = computed(() => props.kind === "TOPICS" ? "议题" : "具体事宜");
/** 先保留界面行号，再忽略空行，确保提示能对应到原表。 */
const filledRows = computed(() => rows.value
  .map((row, index) => ({ row, number: index + 1 }))
  .filter(({ row }) => row.subject.trim() || row.expected.trim() || row.departmentId || row.support?.trim()
    || row.assigneeId || (props.kind === "COORDINATION" && row.category === "COMPANY")));
const rowErrors = computed(() => filledRows.value.flatMap(({ row, number }) => {
  const missing = [];
  if (!row.subject.trim()) missing.push(subjectLabel.value);
  if (!row.expected.trim()) missing.push("期望结果 / 验收标准");
  if (props.kind === "COORDINATION") {
    if (!row.departmentId) missing.push("配合部门");
    if (!row.assigneeId) missing.push("责任人（唯一）");
  }
  return missing.length ? [`第 ${number} 行请填写「${missing.join("」和「")}」`] : [];
}));

function collect() {
  if (props.kind === "REPORT" || props.kind === "WEEKLY_PLAN") {
    const markdown = narrative.value.trim();
    if (!markdown) return null;
    const references = markdownImageKeys(markdown, true);
    const imageKeys = (props.existing?.data.imageKeys ?? []).filter((key) => references.has(key));
    return {
      kind: props.kind,
      markdown,
      imageKeys,
      images: markdownImageUploads(markdown, images.value.filter(({ id }) => !imageKeys.includes(id)), true),
    };
  }
  const entries = filledRows.value;
  return entries.length || props.kind === "COORDINATION"
    ? {
        kind: props.kind,
        rows: entries.map(({ row: { subject, expected, departmentId, category, support, assigneeId } }) => ({
          subject,
          expected,
          ...(props.kind === "COORDINATION" ? { departmentId, category, support, personId: assigneeId } : {}),
        })),
      }
    : null;
}
/** 正文和议题独立提交，参会回复只处理是否参加。 */
async function submit() {
  if (busy.value || preparing.value) return;
  busy.value = true;
  error.value = "";
  try {
    checkedRows.value = true;
    if (rowErrors.value.length) return;
    const material = collect();
    if (!material) throw new Error("请填写材料后提交");
    const submittedContent = contentSignature.value;
    await api("/meetings/" + props.meetingId + "/materials", {
      method: "PUT",
      body: JSON.stringify(material),
    });
    submittedSignature.value = props.kind === "COORDINATION" && !filledRows.value.length ? null : submittedContent;
    emit("saved");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section class="flow-dialog-section material-form">
    <h3>{{ materialLabels[kind] }}{{ kind === 'COORDINATION' ? '（选填）' : '' }}</h3>
    <p v-if="kind === 'REPORT'" class="material-author">
      发起人 {{ author.name }} · 发起部门 {{ author.department }}
    </p>
    <MarkdownImageEditor
      v-if="kind === 'REPORT' || kind === 'WEEKLY_PLAN'"
      v-model="narrative" v-model:images="images" v-model:preparing="preparing"
      :label="materialLabels[kind]" :rows="5" :limit="8000" :disabled="busy"
      :image-base="meetingMaterialImageBase(meetingId, existing)" :image-keys="existing?.data.imageKeys"
      numbered
    />
    <template v-else>
      <p v-if="kind === 'COORDINATION' && !rows.length" class="booking-count">
        {{ existing ? '已移除全部事项，保存后生效。' : '没有协调事宜可直接参加会议。' }}
      </p>
      <div
        v-if="kind !== 'COORDINATION' || rows.length"
        class="material-table-wrap"
        tabindex="0"
        role="region"
        :aria-label="materialLabels[kind] + '填报表格'"
      >
        <table
          class="material-table"
          :class="{ coordination: kind === 'COORDINATION' }"
          :aria-label="materialLabels[kind]"
        >
          <colgroup>
            <col class="row-number" />
            <col v-if="kind === 'COORDINATION'" class="row-category" />
            <col />
            <col v-if="kind === 'COORDINATION'" />
            <col class="row-expected" />
            <col class="row-author" />
            <col class="row-author" />
            <col v-if="kind === 'COORDINATION'" class="row-partner" />
            <col v-if="kind === 'COORDINATION'" class="row-partner" />
            <col class="row-actions" />
          </colgroup>
          <thead>
            <tr>
              <th scope="col">序号</th>
              <th v-if="kind === 'COORDINATION'" scope="col">事项类别</th>
              <th scope="col">{{ subjectLabel }} <small>必填</small></th>
              <th v-if="kind === 'COORDINATION'" scope="col">需协调或配合内容 <small>选填</small></th>
              <th scope="col">期望结果 / 验收标准 <small>必填</small></th>
              <th scope="col">发起部门</th>
              <th scope="col">发起人</th>
              <th v-if="kind === 'COORDINATION'" scope="col">
                配合部门 <small>必填</small>
              </th>
              <th v-if="kind === 'COORDINATION'" scope="col">责任人（唯一） <small>必填</small></th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in rows" :key="row.rowId">
              <th scope="row">{{ index + 1 }}</th>
              <td v-if="kind === 'COORDINATION'">
                <select v-model="row.category" :aria-label="'事项类别 第 ' + (index + 1) + ' 行'">
                  <option v-for="(label, key) in coordinationLabels" :key="key" :value="key">{{ label }}</option>
                </select>
              </td>
              <td>
                <textarea v-numbered
                  v-model="row.subject"
                  maxlength="500"
                  rows="2"
                  :aria-label="
                    (kind === 'TOPICS' ? '议题' : '具体事宜') +
                    ' 第 ' +
                    (index + 1) +
                    ' 行'
                  "
                />
              </td>
              <td v-if="kind === 'COORDINATION'">
                <textarea v-model="row.support" maxlength="1000" rows="2"
                  :aria-label="'需协调或配合内容 第 ' + (index + 1) + ' 行'" />
              </td>
              <td>
                <textarea v-numbered
                  v-model="row.expected"
                  maxlength="1000"
                  rows="2"
                  :aria-label="
                    '期望结果 第 ' + (index + 1) + ' 行'
                  "
                />
              </td>
              <td class="auto-cell">{{ author.department }}</td>
              <td class="auto-cell">{{ author.name }}</td>
              <td v-if="kind === 'COORDINATION'">
                <select
                  v-model="row.departmentId"
                  :aria-label="'配合部门 第 ' + (index + 1) + ' 行'"
                  @change="row.assigneeId = ''; row.assigneeName = null"
                >
                  <option :value="null">未选择</option>
                  <option
                    v-for="department in departments"
                    :key="department.id"
                    :value="department.id"
                  >
                    {{ department.name }}
                  </option>
                </select>
              </td>
              <td v-if="kind === 'COORDINATION'">
                <MeetingDepartmentAssignee
                  v-model="row.assigneeId"
                  :department-id="row.departmentId"
                  :selected-name="row.assigneeName"
                  :label="'责任人（唯一） 第 ' + (index + 1) + ' 行'"
                />
              </td>
              <td>
                <button
                  type="button"
                  class="text-button"
                  :aria-label="'删除第 ' + (index + 1) + ' 行'"
                  @click="rows.splice(index, 1)"
                >
                  删除
                </button>
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td :colspan="kind === 'COORDINATION' ? 10 : 6">
                点击「新增行」开始填写。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="meeting-actions">
        <button
          type="button"
          class="secondary"
          :disabled="rows.length >= 30"
          @click="addRow"
        >
          {{ kind === 'COORDINATION' && !rows.length ? '＋ 添加协调事宜' : '＋ 新增行' }}
        </button>
      </div>
    </template>
    <div v-if="kind !== 'COORDINATION' || rows.length || existing" class="meeting-actions">
      <button type="button" class="primary material-submit"
        :class="{ 'submitted-unchanged': submittedUnchanged }"
        :disabled="busy || preparing" @click="submit">
        {{ kind === 'COORDINATION' && existing && !filledRows.length ? '保存清空' : '提交' + materialLabels[kind] }}
      </button>
      <small v-if="submittedSignature !== null"
        class="material-submitted-status"
        :class="{ current: submittedUnchanged }"
        role="status">已提交</small>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div v-if="checkedRows && rowErrors.length" class="form-error" role="alert">
      <div v-for="message in rowErrors" :key="message">{{ message }}</div>
    </div>
  </section>
</template>

<style scoped>
.material-submit.submitted-unchanged {
  background: var(--brand-soft);
  border-color: var(--line);
  color: var(--text-secondary);
  box-shadow: none;
}
.material-submitted-status {
  color: var(--text-secondary);
  opacity: 0.55;
}
.material-submitted-status.current {
  color: var(--ink);
  font-weight: 600;
  opacity: 1;
}
.material-author {
  color: var(--text-secondary);
  font-size: 13px;
}
.material-form {
  min-width: 0;
}
.material-table-wrap {
  overflow-x: auto;
  border: 1px solid var(--line);
  border-radius: 8px;
}
.material-table {
  width: 100%;
  min-width: 610px;
  table-layout: fixed;
  border-collapse: collapse;
  font-size: 13px;
}
.material-table.coordination {
  /* 窄屏只在表格区域横向滚动，给三个正文列都预留输入宽度。 */
  min-width: 1320px;
}
.material-table :is(th, td) {
  padding: 9px 8px;
  text-align: left;
  vertical-align: middle;
  white-space: normal;
  overflow-wrap: anywhere;
}
.material-table :is(th, td) + :is(th, td) {
  border-left: 1px solid var(--line);
}
.material-table tbody tr {
  border-top: 1px solid var(--line);
}
.material-table thead,
.material-table tbody th {
  background: var(--surface-muted);
}
.material-table thead small,
.auto-cell {
  color: var(--text-secondary);
}
.material-table thead small {
  font-weight: 400;
  white-space: nowrap;
}
.material-table .row-number {
  width: 48px;
}
.material-table .row-author {
  width: 86px;
}
.material-table .row-category {
  width: 176px;
}
.material-table .row-partner {
  width: 156px;
}
.material-table .row-actions {
  width: 54px;
}
.material-table:not(.coordination) .row-expected {
  width: 30%;
}
.material-table :is(textarea, select), .material-table :deep(select) {
  display: block;
  width: 100%;
  min-width: 0;
  margin: 0;
}
.material-table textarea {
  min-height: 58px;
  resize: vertical;
}
.material-table .text-button {
  padding-inline: 0;
}
</style>
