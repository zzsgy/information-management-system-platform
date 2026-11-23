<script setup lang="ts">
import { ref } from "vue";
import { api, dateTime } from "./api";
import { coordinationLabels, materialLabels, meetingDateInput, type MaterialKind, type MaterialRow } from "./meeting";

const props = defineProps<{ meetingId: string }>();
const emit = defineEmits<{ imported: [] }>();
interface ImportedMaterial {
  kind: MaterialKind;
  sheet: string;
  name: string;
  department: string;
  content: string | MaterialRow[];
  conflict: boolean;
}
interface Preview {
  preview: string;
  errors: string[];
  materials: ImportedMaterial[];
  emptyDepartments: string[];
}

const open = ref(false);
const selected = ref<File>();
const date = ref(meetingDateInput(new Date().toISOString()).slice(0, 10));
const preview = ref<Preview>();
const busy = ref(false);
const error = ref("");
const message = ref("");

function selectFile(event: Event) {
  selected.value = (event.target as HTMLInputElement).files?.[0];
  resetPreview();
}

function resetPreview() {
  preview.value = undefined;
  error.value = "";
  message.value = "";
}

/** 预览和确认均按原 XLSX 传输，避免大文件在浏览器另外生成 Base64 副本。 */
async function submit(confirm: boolean) {
  if (!selected.value || !date.value) return;
  busy.value = true;
  error.value = "";
  message.value = "";
  try {
    if (!selected.value.name.toLowerCase().endsWith(".xlsx")) throw Error("请选择 .xlsx 文件");
    if (selected.value.size > 50 * 1024 * 1024) throw Error("周会汇总 Excel 文件须在 50 MB 以内");
    const params = new URLSearchParams({ date: date.value });
    if (confirm && preview.value) params.set("preview", preview.value.preview);
    const path = "/meetings/" + props.meetingId + "/workbooks/weekly-summary?" + params;
    const options = {
      method: "POST",
      headers: { "Content-Type": "application/octet-stream" },
      body: selected.value,
    };
    if (confirm) {
      const result = await api<{ imported: number; skipped: number }>(path, options);
      preview.value = undefined;
      message.value = `已导入 ${result.imported} 项材料，保留 ${result.skipped} 项已有材料。`;
      emit("imported");
    } else {
      preview.value = undefined;
      preview.value = await api<Preview>(path, options);
    }
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section class="weekly-import">
    <button type="button" class="secondary" :aria-expanded="open" @click="open = !open">
      导入周会汇总 Excel
    </button>
    <div v-if="open" class="weekly-upload">
      <div class="meeting-form-row">
        <label>Excel 中的周会日期
          <input v-model="date" type="date" :disabled="busy" @change="resetPreview" />
        </label>
        <label>周会汇总文件
          <input type="file" accept=".xlsx" :disabled="busy" @change="selectFile" />
        </label>
        <button type="button" class="secondary" :disabled="busy || !selected || !date" @click="submit(false)">
          {{ busy ? "处理中…" : "预览导入" }}
        </button>
      </div>
      <small>按所选日期读取部门页，协调事宜读取同名工作表中的事项。</small>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <p v-if="message" role="status">{{ message }}</p>
      <div v-if="preview" class="weekly-preview">
        <ul v-if="preview.errors.length" class="form-error" role="alert">
          <li v-for="problem in preview.errors" :key="problem">{{ problem }}</li>
        </ul>
        <small v-if="preview.emptyDepartments.length">
          所选日期没有周报内容的部门页 · {{ preview.emptyDepartments.join("、") }}
        </small>
        <details v-for="entry in preview.materials" :key="entry.sheet + entry.kind + entry.name">
          <summary>
            {{ entry.department }} · {{ entry.name }} · {{ materialLabels[entry.kind] }}
            <small v-if="entry.conflict">（已有材料，保留原内容）</small>
          </summary>
          <p v-if="typeof entry.content === 'string'" class="weekly-text">{{ entry.content }}</p>
          <ul v-else>
            <li v-for="(row, index) in entry.content" :key="index">
              <strong>{{ row.subject }}</strong> · {{ coordinationLabels[row.category ?? "DEPARTMENT"] }}
              <div v-if="row.support">需协调或配合内容 {{ row.support }}</div>
              <div>期望结果 / 验收标准 {{ row.expected }}</div>
              <div>
                配合部门 {{ row.department }} · 责任人 {{ row.assigneeName }}
                <template v-if="row.dueAt"> · 截止 {{ dateTime(row.dueAt) }}</template>
              </div>
            </li>
          </ul>
        </details>
        <button type="button" class="primary"
          :disabled="busy || !!preview.errors.length || !preview.materials.some((entry) => !entry.conflict)"
          @click="submit(true)">
          确认导入本场会议
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.weekly-import {
  border-top: 1px solid var(--line);
  margin-top: 12px;
  padding-top: 12px;
}
.weekly-upload,
.weekly-preview {
  display: grid;
  justify-items: start;
  gap: 10px;
  margin-top: 12px;
}
.weekly-upload .meeting-form-row {
  align-items: end;
}
.weekly-text {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
