<script setup lang="ts">
import { ref } from "vue";
import { api } from "./api";
import {
  coordinationLabels,
  materialLabels,
  type CoordinationCategory,
  type MaterialKind,
} from "./meeting";

/** 每个预检组对应同一员工的一类材料，保留原 Excel 行号。 */
interface WorkbookPreview {
  preview: string;
  errors: string[];
  materials: {
    key: string;
    rows: number[];
    employeeNo: string;
    name: string;
    kind: MaterialKind;
    conflict: boolean;
    content: {
      text?: string;
      subject?: string;
      expected?: string;
      support?: string;
      department?: string;
      category?: CoordinationCategory;
    }[];
  }[];
}
const props = defineProps<{ meetingId: string; canImport: boolean }>();
const emit = defineEmits<{ imported: [] }>();
const file = ref<File>();
const preview = ref<WorkbookPreview>();
const overwrite = ref<string[]>([]);
const message = ref("");
const error = ref("");
const busy = ref(false);
const path = "/meetings/" + props.meetingId + "/workbooks/";
/** 同一文件预检后再确认，只有明确选择的冲突组会覆盖线上材料。 */
let encoded = "";

function choose(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0];
  preview.value = undefined;
  overwrite.value = [];
  error.value = "";
  message.value = "";
  encoded = "";
}

async function inspect() {
  if (!file.value) return;
  busy.value = true;
  preview.value = undefined;
  overwrite.value = [];
  error.value = "";
  message.value = "";
  try {
    if (file.value.size > 4 * 1024 * 1024)
      throw new Error("会议 Excel 文件须在 4 MB 以内");
    encoded = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader();
      reader.onerror = () => reject(new Error("无法读取所选文件"));
      reader.onload = () => resolve(String(reader.result).split(",")[1] ?? "");
      reader.readAsDataURL(file.value!);
    });
    preview.value = await api<WorkbookPreview>(path + "import", {
      method: "POST",
      body: JSON.stringify({ file: encoded }),
    });
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}

async function importMaterials() {
  if (!preview.value) return;
  busy.value = true;
  error.value = "";
  try {
    const result = await api<{ imported: number; skipped: number }>(
      path + "import",
      {
        method: "POST",
        body: JSON.stringify({
          file: encoded,
          preview: preview.value.preview,
          overwrite: overwrite.value,
        }),
      },
    );
    message.value =
      "已导入 " + result.imported + " 项，保留原填报 " + result.skipped + " 项";
    preview.value = undefined;
    emit("imported");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section class="flow-dialog-section">
    <h3>会议 Excel</h3>
    <div class="meeting-actions">
      <a
        v-if="canImport"
        class="secondary"
        :href="'/api/v1' + path + 'template'"
        download
        >下载填报模板</a
      >
      <a class="secondary" :href="'/api/v1' + path + 'summary'" download
        >导出会议汇总</a
      >
    </div>
    <div v-if="canImport" class="meeting-actions workbook-upload">
      <input
        aria-label="选择会议填报 Excel"
        type="file"
        accept=".xlsx"
        :disabled="busy"
        @change="choose"
      />
      <button
        class="secondary"
        type="button"
        :disabled="!file || busy"
        @click="inspect"
      >
        预览导入
      </button>
    </div>
    <template v-if="preview">
      <ul v-if="preview.errors.length" class="form-error" role="alert">
        <li v-for="problem in preview.errors" :key="problem">{{ problem }}</li>
      </ul>
      <ul class="preview-list">
        <li v-for="material in preview.materials" :key="material.key">
          <details>
            <summary>
              {{ material.name }} · {{ material.employeeNo }} ·
              {{ materialLabels[material.kind] }} （第
              {{ material.rows.join("、") }} 行）
            </summary>
            <p
              v-for="(line, index) in material.content"
              :key="index"
              class="meeting-text"
            >
              {{
                [
                  line.category && coordinationLabels[line.category],
                  line.text,
                  line.subject,
                  line.support,
                  line.expected,
                  line.department,
                ]
                  .filter(Boolean)
                  .join("\n")
              }}
            </p>
          </details>
          <label v-if="material.conflict">
            <input
              v-model="overwrite"
              type="checkbox"
              :value="material.key"
              :disabled="busy"
            />覆盖已有填报
          </label>
        </li>
      </ul>
      <button
        class="primary"
        type="button"
        :disabled="busy || !!preview.errors.length || !preview.materials.length"
        @click="importMaterials"
      >
        确认导入
      </button>
    </template>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
  </section>
</template>

<style scoped>
.workbook-upload input {
  flex: 1 1 12rem;
  min-width: 0;
  width: 100%;
  margin: 0;
}
.preview-list {
  max-height: 22rem;
  overflow: auto;
  padding-inline-start: 1.5rem;
}
.preview-list li {
  margin-bottom: 0.5rem;
}
</style>
