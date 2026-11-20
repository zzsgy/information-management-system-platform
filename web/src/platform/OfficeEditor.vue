<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { api, ApiError } from "./api";
import { loadEditorScript } from "./officeClient";
interface Editor {
  destroyEditor: () => void;
}
interface Participant {
  userId: string;
  name: string;
  state: string;
}
declare global {
  interface Window {
    DocsAPI?: {
      DocEditor: new (id: string, config: Record<string, unknown>) => Editor;
    };
  }
}
const props = defineProps<{
    document: { id: string; name: string; currentVersion: { id: string } };
  }>(),
  emit = defineEmits<{ close: [] }>();
const session = ref(""),
  state = ref("PREPARING"),
  message = ref("正在连接文档服务…"),
  error = ref(""),
  busy = ref(false),
  ready = ref(false),
  checkpointEnabled = ref(false),
  departed = ref(false),
  synchronized = ref(true),
  // 网络失联时保留原生稿件，但隐藏内容并暂停操作，须实时鉴权成功后恢复。
  disconnected = ref(false),
  participants = ref<Participant[]>([]),
  canvas = "office-" + crypto.randomUUID();
const activeParticipants = computed(() =>
  participants.value.filter((person) => person.state === "ACTIVE"),
);
let editor: Editor | undefined,
  timer: ReturnType<typeof setInterval> | undefined,
  alive = true,
  leaving = false,
  lastActivity = 0,
  operation: string | undefined;
async function refresh(activity = false) {
  if (!session.value) return;
  try {
    const data = await api<{
      state: string;
      participants: Participant[];
      saveReceipts: { state: string; saveSeq: number }[];
    }>(`/drive/edit-sessions/${session.value}`, {}, activity);
    if (!alive) return;
    if (disconnected.value) error.value = "";
    disconnected.value = false;
    state.value = data.state;
    participants.value = data.participants;
    const latest = data.saveReceipts[0];
    message.value =
      data.state === "CLOSED"
        ? "协作已结束，平台已确认最终保存结果。"
        : data.state === "CLOSING"
          ? "已退出编辑，正在等待文档服务完成最终保存。"
          : latest?.state === "SAVED"
            ? `平台已保存第${latest.saveSeq}次版本。`
            : latest?.state === "NO_CHANGES"
              ? "未发现需要保存的新修改。"
              : latest?.state === "UNKNOWN"
                ? "保存结果待确认，请联系负责人处理。"
                : checkpointEnabled.value
                  ? "编辑内容由文档服务同步，点击“保存到平台”生成正式版本。"
                  : "所有参与人关闭编辑后生成正式版本，请等待最终保存结果。";
    if (["SAVED", "NO_CHANGES", "UNKNOWN"].includes(latest?.state || ""))
      operation = undefined;
    if (["CLOSED", "UNKNOWN", "REVOKED"].includes(data.state)) {
      editor?.destroyEditor();
      editor = undefined;
      if (timer) clearInterval(timer);
    }
  } catch (failure) {
    if (!alive) return;
    if (failure instanceof TypeError) {
      disconnected.value = true;
      message.value = "与平台的连接暂时中断，正在重新连接。";
      error.value =
        "编辑区已暂停并保留当前稿件，请保留此页面；连接恢复并核对权限后继续。";
      return;
    }
    error.value = (failure as Error).message;
    editor?.destroyEditor();
    editor = undefined;
    state.value = "REVOKED";
  }
}
async function save() {
  if (busy.value) return;
  busy.value = true;
  try {
    operation ??= crypto.randomUUID();
    await api(`/drive/edit-sessions/${session.value}/checkpoints`, {
      method: "POST",
      headers: { "X-Operation-Id": operation },
    });
    await refresh();
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
async function close() {
  if (busy.value) return;
  if (
    departed.value ||
    ["CLOSED", "UNKNOWN", "REVOKED", "FAILED"].includes(state.value)
  ) {
    emit("close");
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    if (session.value) {
      leaving = true;
      const result = await api<{ state: string }>(
        `/drive/edit-sessions/${session.value}/participants/me`,
        {
          method: "DELETE",
        },
      );
      departed.value = true;
      state.value = result.state;
    }
    editor?.destroyEditor();
    editor = undefined;
    if (state.value === "CLOSING") await refresh();
    else emit("close");
  } catch (failure) {
    leaving = false;
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 卸载无法等待响应；仍携带原登录与CSRF，最终保存由服务器回调确认。 */
function release() {
  if (
    session.value &&
    !leaving &&
    !departed.value &&
    ["OPEN", "PREPARING"].includes(state.value)
  ) {
    leaving = true;
    void api(
      `/drive/edit-sessions/${session.value}/participants/me`,
      {
        method: "DELETE",
        keepalive: true,
      },
      false,
    ).catch(() => {});
  }
}
async function abandonFailedEditor() {
  if (session.value && !leaving) {
    leaving = true;
    try {
      await api(`/drive/edit-sessions/${session.value}/participants/me`, {
        method: "DELETE",
      });
      departed.value = true;
    } catch {
      leaving = false;
    }
  }
}
onMounted(async () => {
  try {
    const status = await api<{ enabled: boolean; editorScriptUrl: string }>(
      "/drive/office/status",
    );
    if (!status.enabled) throw Error("Office文档服务尚未配置。");
    await loadEditorScript(status.editorScriptUrl);
    if (!alive) return;
    const result = await joinEditor();
    if (!result) return;
    session.value = result.sessionId;
    checkpointEnabled.value = result.checkpointEnabled;
    if (!alive) {
      release();
      return;
    }
    if (!alive || !window.DocsAPI) return;
    editor = new window.DocsAPI.DocEditor(canvas, {
      ...result.editorConfig,
      events: {
        onDocumentReady: () => {
          ready.value = true;
        },
        // 文档服务同步并不代表平台已有正式版本，两者分别提示。
        onDocumentStateChange: (event: { data: boolean }) => {
          synchronized.value = !event.data;
          // 真正的编辑动作可续期，静默状态轮询不能让无人操作的登录永久有效。
          if (event.data && Date.now() - lastActivity >= 30000) {
            lastActivity = Date.now();
            void refresh(true);
          }
        },
        onError: (event: { data: { errorCode: number } }) => {
          error.value = `编辑器报告错误（${event.data.errorCode}），请保留页面并检查保存记录。`;
        },
        onRequestClose: () => void close(),
      },
    });
    state.value = "OPEN";
    await refresh();
    if (["OPEN", "CLOSING"].includes(state.value))
      timer = setInterval(() => void refresh(), 5000);
  } catch (failure) {
    error.value = (failure as Error).message;
    message.value = "编辑器未能启动，请检查提示后重试。";
    await abandonFailedEditor();
    state.value = "FAILED";
  }
});
/** 最终保存回调需要时间；短暂等待期间保留页面，确认结束后按最新版本加入。 */
async function joinEditor() {
  for (let attempt = 0; attempt < 30 && alive; attempt++) {
    const current = await api<{ currentVersion: { id: string } }>(
      `/drive/documents/${props.document.id}`,
      {},
      false,
    );
    try {
      return await api<{
        sessionId: string;
        editorConfig: Record<string, unknown>;
        checkpointEnabled: boolean;
      }>(
        `/drive/documents/${props.document.id}/edit-sessions`,
        {
          method: "POST",
          body: JSON.stringify({ baseVersionId: current.currentVersion.id }),
        },
        attempt === 0,
      );
    } catch (failure) {
      if (
        !(failure instanceof ApiError) ||
        failure.code !== "OFFICE_FINAL_SAVE_PENDING"
      )
        throw failure;
      message.value = "正在确认上次编辑的最终保存，完成后自动进入…";
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }
  }
  if (alive)
    throw Error(
      "上次编辑的最终保存仍在处理中，请稍后重试或在文件详情查看协作与保存记录。",
    );
}
window.addEventListener("pagehide", release);
onUnmounted(() => {
  alive = false;
  release();
  window.removeEventListener("pagehide", release);
  if (timer) clearInterval(timer);
  editor?.destroyEditor();
});
</script>
<template>
  <section
    class="office-workspace"
    role="dialog"
    aria-modal="true"
    :aria-label="document.name"
  >
    <header>
      <div>
        <strong>{{ document.name }}</strong>
        <p role="status">{{ message }}</p>
        <p
          v-if="!departed && !disconnected && state === 'OPEN'"
          class="office-participants"
        >
          <span>参与人员 {{ activeParticipants.length }}：</span>
          <span v-for="person in activeParticipants" :key="person.userId">{{
            person.name
          }}</span>
          <span
            >·
            {{
              ready
                ? synchronized
                  ? "已同步到文档服务"
                  : "正在同步修改"
                : "正在加载文档"
            }}</span
          >
        </p>
      </div>
      <div>
        <button
          v-if="checkpointEnabled"
          class="primary"
          :disabled="
            busy ||
            disconnected ||
            !ready ||
            !synchronized ||
            departed ||
            state !== 'OPEN'
          "
          @click="save"
        >
          保存到平台</button
        ><button
          class="secondary"
          :disabled="busy || disconnected"
          @click="close"
        >
          {{ departed ? "返回文件详情" : "关闭编辑" }}
        </button>
      </div>
    </header>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div v-if="departed" class="office-finished">
      <strong>{{
        state === "CLOSING" ? "正在确认最终保存" : "本次编辑已结束"
      }}</strong>
      <p>可返回文件详情查看协作与保存记录；只有平台确认的版本才是正式版本。</p>
    </div>
    <div
      v-show="!departed"
      class="office-canvas"
      :style="{ visibility: disconnected ? 'hidden' : undefined }"
      :aria-hidden="disconnected"
    >
      <div :id="canvas" />
    </div>
  </section>
</template>
<style scoped>
.office-canvas > div {
  height: 100%;
}
.office-workspace {
  position: fixed;
  inset: 16px;
  background: #fff;
  z-index: 90;
  box-shadow: 0 20px 70px #172b2480;
  border-radius: 14px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.office-workspace header {
  padding: 14px 20px;
  display: flex;
  justify-content: space-between;
  gap: 24px;
  border-bottom: 1px solid var(--line);
}
.office-workspace header p {
  font-size: 13px;
  color: var(--ink);
  margin: 7px 0 0;
}
.office-workspace header button {
  margin-left: 12px;
}
.office-canvas {
  flex: 1;
  min-height: 0;
}
.office-participants {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.office-finished {
  margin: 60px auto;
  text-align: center;
  color: var(--ink);
}
.form-error {
  margin: 16px 24px;
}
</style>
