<script setup lang="ts">
import { onMounted, ref, watch } from "vue";
import { api } from "./api";
import { vNumbered } from "./meetingNumbered";
import {
  materialLabels,
  reportMarkup,
  type MaterialKind,
  type MaterialRow,
  type MeetingMaterial,
  type MeetingOption,
  type MeetingPerson,
} from "./meeting";

const props = defineProps<{
  kind: MaterialKind;
  author: { name: string; department: string };
  existing?: MeetingMaterial;
  departments: MeetingOption[];
}>();
const editor = ref<HTMLElement>();
const weeklyPlan = ref(props.existing?.data.text ?? "");
let nextRowId = 0;
function makeRow(values: MaterialRow = { subject: "", expected: "" }) {
  return { departmentId: null, personId: null, ...values, rowId: nextRowId++ };
}
const rows = ref(props.existing?.data.rows?.map(makeRow) ?? [makeRow()]);
function addRow() {
  rows.value.push(makeRow());
}
const people = ref<MeetingPerson[]>([]);
const error = ref("");
onMounted(() => {
  if (editor.value)
    editor.value.innerHTML = reportMarkup(props.existing?.data.html ?? "");
});

/** 使用现有员工目录分页补齐下拉，配合人可按行内所选部门过滤。 */
watch(
  () => props.kind,
  async (kind, _, onCleanup) => {
    if (kind !== "COORDINATION") return;
    const controller = new AbortController();
    onCleanup(() => controller.abort());
    try {
      const result: MeetingPerson[] = [];
      for (let page = 1; ; page++) {
        const data = await api<{ items: MeetingPerson[]; hasNext: boolean }>(
          "/meetings/people?page=" + page,
          { signal: controller.signal },
        );
        result.push(...data.items);
        if (!data.hasNext) break;
      }
      if (!controller.signal.aborted) people.value = result;
    } catch (e) {
      if (!controller.signal.aborted) error.value = (e as Error).message;
    }
  },
  { immediate: true },
);

const tools = [
  ["bold", "加粗"],
  ["italic", "斜体"],
  ["underline", "下划线"],
  ["insertUnorderedList", "项目列表"],
  ["insertOrderedList", "编号列表"],
];
function format(command: string) {
  editor.value?.focus();
  document.execCommand(command);
}
function paste(event: ClipboardEvent) {
  document.execCommand(
    "insertText",
    false,
    event.clipboardData?.getData("text/plain") ?? "",
  );
}
/** 空材料保持待提交，已填写的行沿用字段校验。 */
function collect() {
  if (props.kind === "WEEKLY_PLAN") {
    if (!weeklyPlan.value.trim()) throw new Error("请先填写本周计划，再提交并参加");
    return { kind: props.kind, text: weeklyPlan.value };
  }
  if (props.kind === "REPORT") {
    const text = editor.value?.innerText.trim();
    return text
      ? {
          kind: props.kind,
          html: reportMarkup(editor.value?.innerHTML ?? ""),
          text,
        }
      : null;
  }
  const entries = rows.value.filter(
    (row) =>
      row.subject.trim() ||
      row.expected.trim() ||
      row.departmentId ||
      row.personId,
  );
  if (entries.some((row) => !row.subject.trim() || !row.expected.trim())) {
    throw new Error(
      materialLabels[props.kind] + "中已填写的行必须填写事项和期望结果",
    );
  }
  return entries.length
    ? {
        kind: props.kind,
        rows: entries.map(({ subject, expected, departmentId, personId }) => ({
          subject,
          expected,
          ...(props.kind === "COORDINATION" ? { departmentId, personId } : {}),
        })),
      }
    : null;
}
defineExpose({ collect });
</script>

<template>
  <section class="flow-dialog-section material-form">
    <h3>{{ materialLabels[kind] }}</h3>
    <p v-if="kind === 'REPORT'" class="material-author">
      发起人 {{ author.name }} · 发起部门 {{ author.department }}
    </p>
    <template v-if="kind === 'REPORT'">
      <div class="report-tools" aria-label="汇报格式">
        <button
          v-for="[command, label] in tools"
          :key="command"
          type="button"
          class="secondary"
          @mousedown.prevent
          @click="format(command)"
        >
          {{ label }}
        </button>
      </div>
      <div
        ref="editor"
        class="report-editor"
        contenteditable="true"
        v-numbered
        role="textbox"
        aria-label="工作汇报正文"
        aria-multiline="true"
        @paste.prevent="paste"
        @drop.prevent
      />
    </template>
    <label v-else-if="kind === 'WEEKLY_PLAN'">
      本周计划内容
      <textarea v-model="weeklyPlan" v-numbered rows="5" maxlength="8000" />
    </label>
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
            <col class="row-subject" />
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
              <th scope="col">{{ kind === "TOPICS" ? "议题" : "具体事宜" }}</th>
              <th scope="col">
                {{
                  kind === "TOPICS"
                    ? "需公司协调什么 / 期望结果"
                    : "需对方做什么 / 期望结果"
                }}
              </th>
              <th scope="col">发起部门</th>
              <th scope="col">发起人</th>
              <th v-if="kind === 'COORDINATION'" scope="col">
                配合部门 <small>选填</small>
              </th>
              <th v-if="kind === 'COORDINATION'" scope="col">
                配合人 <small>选填</small>
              </th>
              <th scope="col">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in rows" :key="row.rowId">
              <th scope="row">{{ index + 1 }}</th>
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
              <td>
                <textarea v-numbered
                  v-model="row.expected"
                  maxlength="1000"
                  rows="2"
                  :aria-label="
                    (kind === 'TOPICS'
                      ? '需公司协调什么 / 期望结果'
                      : '需对方做什么 / 期望结果') +
                    ' 第 ' +
                    (index + 1) +
                    ' 行'
                  "
                />
              </td>
              <td class="auto-cell">{{ author.department }}</td>
              <td class="auto-cell">{{ author.name }}</td>
              <td v-if="kind === 'COORDINATION'">
                <select
                  v-model="row.departmentId"
                  :aria-label="'配合部门 第 ' + (index + 1) + ' 行'"
                  @change="row.personId = null"
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
                <select
                  v-model="row.personId"
                  :aria-label="'配合人 第 ' + (index + 1) + ' 行'"
                >
                  <option :value="null">未选择</option>
                  <option
                    v-for="person in people.filter(
                      (person) =>
                        !row.departmentId ||
                        person.departmentId === row.departmentId,
                    )"
                    :key="person.id"
                    :value="person.id"
                  >
                    {{ person.name }} · {{ person.employeeNo }}
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
              <td :colspan="kind === 'COORDINATION' ? 8 : 6">
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
.report-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}
.report-tools button {
  min-height: 30px;
  font-size: 12px;
}
.report-editor {
  min-height: 135px;
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow-wrap: anywhere;
}
</style>
