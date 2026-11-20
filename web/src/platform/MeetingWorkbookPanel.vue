<script setup lang="ts">
import { ref } from "vue";
import MeetingIncompleteExport from "./MeetingIncompleteExport.vue";
import { api, ApiError, responseRequestId, profile, requestFetch, responseError } from "./api";
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
  newInvitee: boolean;
  employeeNo: string;
  name: string;
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
      assigneeName?: string;
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

/** 模板与汇总下载先检查错误响应，避免把失败 JSON 当成 Excel 保存。 */
async function downloadWorkbook(kind: "template" | "summary") {
  busy.value = true;
  error.value = "";
  try {
    const response = await requestFetch("/api/v1" + path + kind, {
      credentials: "same-origin", cache: "no-store",
      headers: { "X-Request-Id": crypto.randomUUID(), "X-CSRF-Token": profile.value?.csrfToken || "" },
    });
    if (!response.ok) throw await responseError(response, "会议 Excel 下载失败");
    if (response.headers.get("Content-Type")?.split(";")[0] !== "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
      throw new ApiError("WORKBOOK_RESPONSE_INVALID", "会议 Excel 响应格式异常，请联系 IT 管理员", responseRequestId(response));
    const url = URL.createObjectURL(await response.blob());
    const link = document.createElement("a");
    link.href = url;
    link.download = kind === "template" ? "会议填报模板.xlsx" : "会议汇总.xlsx";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (failure) { error.value = (failure as Error).message; }
  finally { busy.value = false; }
}

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
    const result = await api<{ imported: number; skipped: number; inviteeAdded: boolean }>(
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
      (result.inviteeAdded ? "已新增受邀员工。 " : "") +
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
    <header class="workbook-toolbar">
      <h3>会议 Excel</h3>
      <div class="meeting-actions">
        <button
          v-if="canImport"
          class="secondary"
          :disabled="busy"
          @click="downloadWorkbook('template')"
          >下载填报模板</button
        >
        <button class="secondary" :disabled="busy" @click="downloadWorkbook('summary')"
          >导出会议汇总</button
        >
        <MeetingIncompleteExport :meeting-id="meetingId" />
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
    </header>
    <p v-if="canImport" class="booking-count">每份 Excel 填报一人。请在「协调事宜」F2 填写姓名和工号，即使没有协调事宜也要填写。</p>
    <template v-if="preview">
      <p v-if="preview.newInvitee">
        确认导入后将新增受邀员工 {{ preview.name }} · {{ preview.employeeNo }}
      </p>
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
                  line.assigneeName && "责任人 " + line.assigneeName,
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
        class="primary workbook-confirm"
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
.workbook-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 16px;
}
.workbook-toolbar h3 {
  font-size: 15px;
}
.workbook-upload {
  flex: 1 1 21rem;
  min-width: 0;
  max-width: 32rem;
  margin-inline-start: auto;
}
.workbook-upload input {
  flex: 1 1 12rem;
  min-width: 0;
  width: 100%;
  margin: 0;
}
.workbook-confirm {
  justify-self: start;
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
