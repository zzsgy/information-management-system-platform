<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import { api, ApiError } from "./api";
import { loadEditorScript } from "./officeClient";

const props = defineProps<{ route: string }>();
const query = new URLSearchParams(props.route.split("?")[1]);
const meeting = query.get("id") ?? "";
const department = query.get("department") ?? "";
const version = query.get("version");
const back =
  "#/meetings?id=" + encodeURIComponent(meeting) + "&action=weekly-report";
const endpoint =
  "/meetings/" +
  encodeURIComponent(meeting) +
  "/weekly-report/departments/" +
  encodeURIComponent(department) +
  "/excel";
const title = ref("在线编辑 Excel");
const message = ref("正在连接 Office 文档服务…");
const error = ref("");
const editable = ref(false);
const closing = ref(false);
const ended = ref(false);
const canvas = "weekly-excel-" + crypto.randomUUID();
let editor: { destroyEditor: () => void } | undefined;
let timer: number | undefined;
let session = "";
let alive = true;
let activityAt = 0;

/** 关闭原生编辑连接，正式保存结果以服务端回调为准。 */
function finish() {
  if (closing.value || ended.value) return;
  closing.value = true;
  message.value = "已退出编辑，正在等待文档服务确认保存…";
  editor?.destroyEditor();
  editor = undefined;
  void refresh();
}

/** 留在标签页即可看到最终保存、失败或权限撤回状态。 */
async function refresh(activity = false) {
  if (!session || ended.value) return;
  try {
    const data = await api<{ state: string; message?: string }>(
      "/meetings/weekly-excel-sessions/" + session,
      {},
      activity,
    );
    if (!alive) return;
    if (data.state === "CLOSED" || data.state === "FAILED") {
      editable.value = false;
      ended.value = true;
      window.clearInterval(timer);
      editor?.destroyEditor();
      editor = undefined;
      if (data.state === "CLOSED") {
        error.value = "";
        message.value = "工作簿已保存，可以返回会议详情。";
      } else error.value = data.message || "工作簿保存失败，请联系管理员。";
    }
  } catch (failure) {
    if (!alive) return;
    error.value = (failure as Error).message;
    if (
      failure instanceof ApiError &&
      [401, 403, 404].includes(failure.status)
    ) {
      window.clearInterval(timer);
      editor?.destroyEditor();
      editor = undefined;
      ended.value = true;
    }
  }
}

onMounted(async () => {
  try {
    if (!meeting || !department) throw Error("请选择会议中的一个部门工作簿。");
    const config = await api<{ editorScriptUrl: string | null }>(endpoint);
    if (!config.editorScriptUrl)
      throw Error("Office 文档服务尚未配置，请联系管理员。");
    await loadEditorScript(config.editorScriptUrl);
    if (!alive) return;
    const result = await api<{
      sessionId: string;
      editorConfig: Record<string, unknown>;
      editable: boolean;
      title: string;
    }>(endpoint, {
      method: "POST",
      body: JSON.stringify(version ? { version } : {}),
    });
    if (!alive || !window.DocsAPI) return;
    session = result.sessionId;
    editable.value = result.editable;
    title.value = result.title;
    document.title = result.title;
    editor = new window.DocsAPI.DocEditor(canvas, {
      ...result.editorConfig,
      events: {
        onDocumentReady: () => {
          message.value = editable.value
            ? "填写人请使用姓名+工号。全部协作者退出后，系统确认保存。"
            : "正在查看工作簿";
        },
        onDocumentStateChange: (event: { data: boolean }) => {
          if (event.data && Date.now() - activityAt >= 30000) {
            activityAt = Date.now();
            void refresh(true);
          }
        },
        onRequestClose: () => (editable.value ? finish() : window.close()),
        onError: (event: { data: { errorCode: number } }) => {
          error.value = "Excel 编辑器错误（" + event.data.errorCode + "）";
        },
      },
    });
    timer = window.setInterval(() => void refresh(), 4000);
  } catch (failure) {
    if (alive) error.value = (failure as Error).message;
  }
});
onUnmounted(() => {
  alive = false;
  window.clearInterval(timer);
  editor?.destroyEditor();
});
</script>

<template>
  <main class="weekly-excel-page">
    <header>
      <div>
        <strong>{{ title }}</strong>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <p v-else role="status">{{ message }}</p>
      </div>
      <button
        v-if="editable && !ended"
        type="button"
        class="primary"
        :disabled="closing"
        @click="finish"
      >
        结束编辑并保存
      </button>
      <a class="secondary" :href="back">返回会议详情</a>
    </header>
    <div class="weekly-excel-canvas" :hidden="closing || ended">
      <div :id="canvas"></div>
    </div>
  </main>
</template>

<style scoped>
.weekly-excel-page {
  height: 100dvh;
  display: flex;
  flex-direction: column;
  background: var(--surface);
}
header {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  border-bottom: 1px solid var(--line);
  padding: 10px 16px;
}
header div {
  margin-inline-end: auto;
}
header p {
  margin: 4px 0 0;
}
.weekly-excel-canvas {
  flex: 1;
  min-height: 0;
}
.weekly-excel-canvas > div {
  width: 100%;
  height: 100%;
}
</style>
