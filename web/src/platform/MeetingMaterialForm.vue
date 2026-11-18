<script setup lang="ts">
import { ref } from "vue";
import RichTextEditor from "./RichTextEditor.vue";
import { formattedText } from "./formattedText";
import { api } from "./api";
import { vNumbered } from "./meetingNumbered";
import {
  materialLabels,
  coordinationLabels,
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
const editor = ref<InstanceType<typeof RichTextEditor>>();
const narrative = ref(formattedText(
  props.existing?.data.text ?? "", props.existing?.data.html ?? "",
));
let nextRowId = 0;
function makeRow(values: MaterialRow = { subject: "", expected: "" }) {
  return { departmentId: null, category: "DEPARTMENT", support: "", ...values, rowId: nextRowId++ };
}
const rows = ref(props.existing?.data.rows?.map(makeRow) ?? [makeRow()]);
function addRow() {
  rows.value.push(makeRow());
}
const error = ref("");
const busy = ref(false);
const saved = ref(false);

/** 空材料保持待提交，已填写的行沿用字段校验。 */
function collect() {
  if (props.kind === "REPORT" || props.kind === "WEEKLY_PLAN") {
    const content = editor.value?.read();
    if (!content?.text) return null;
    if (content.text.length > 8000 || content.html.length > 12000)
      throw new Error(materialLabels[props.kind] + "正文或格式超过长度限制");
    return { kind: props.kind, ...content };
  }
  const entries = rows.value.filter(
    (row) =>
      row.subject.trim() ||
      row.expected.trim() ||
      row.departmentId ||
      row.support?.trim(),
  );
  if (entries.some((row) => !row.subject.trim() || !row.expected.trim())) {
    throw new Error(
      materialLabels[props.kind] + "中已填写的行必须填写事项和期望结果",
    );
  }
  return entries.length
    ? {
        kind: props.kind,
        rows: entries.map(({ subject, expected, departmentId, category, support }) => ({
          subject,
          expected,
          ...(props.kind === "COORDINATION" ? { departmentId, category, support } : {}),
        })),
      }
    : null;
}
/** 正文和议题独立提交，参会回复只处理是否参加。 */
async function submit() {
  busy.value = true;
  error.value = "";
  saved.value = false;
  try {
    const material = collect();
    if (!material) throw new Error("请填写材料后提交");
    await api("/meetings/" + props.meetingId + "/materials", {
      method: "PUT",
      body: JSON.stringify(material),
    });
    saved.value = true;
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
    <h3>{{ materialLabels[kind] }}</h3>
    <p v-if="kind === 'REPORT'" class="material-author">
      发起人 {{ author.name }} · 发起部门 {{ author.department }}
    </p>
    <RichTextEditor
      v-if="kind === 'REPORT' || kind === 'WEEKLY_PLAN'"
      ref="editor" v-model="narrative"
      :label="materialLabels[kind]" :rows="5"
    />
    <template v-else>
      <div
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
            <col v-if="kind === 'COORDINATION'" class="row-partner" />
            <col class="row-subject" />
            <col v-if="kind === 'COORDINATION'" class="row-expected" />
            <col class="row-expected" />
            <col class="row-author" />
            <col class="row-author" />
            <col v-if="kind === 'COORDINATION'" class="row-partner" />
            <col class="row-actions" />
          </colgroup>
          <thead>
            <tr>
              <th scope="col">序号</th>
              <th v-if="kind === 'COORDINATION'" scope="col">事项类别</th>
              <th scope="col">{{ kind === "TOPICS" ? "议题" : "具体事宜" }}</th>
              <th v-if="kind === 'COORDINATION'" scope="col">需协调或配合内容</th>
              <th scope="col">期望结果 / 验收标准</th>
              <th scope="col">发起部门</th>
              <th scope="col">发起人</th>
              <th v-if="kind === 'COORDINATION'" scope="col">
                配合部门 <small>选填</small>
              </th>
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
              <td :colspan="kind === 'COORDINATION' ? 9 : 6">
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
          ＋ 新增行
        </button>
      </div>
    </template>
    <div class="meeting-actions">
      <button type="button" class="primary" :disabled="busy" @click="submit">
        提交{{ materialLabels[kind] }}
      </button>
      <small v-if="saved" role="status">已提交</small>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
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
  min-width: 935px;
}
.material-table :is(th, td) {
  padding: 9px 8px;
  text-align: left;
  vertical-align: middle;
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
}
.material-table .row-number {
  width: 48px;
}
.material-table .row-author {
  width: 86px;
}
.material-table .row-partner {
  width: 140px;
}
.material-table .row-actions {
  width: 54px;
}
.material-table .row-expected {
  width: 30%;
}
.material-table.coordination .row-expected {
  width: 24%;
}
.material-table :is(textarea, select) {
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
