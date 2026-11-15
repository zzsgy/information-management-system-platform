<script setup lang="ts">
import Icon from "../preview/PlatformIcon.vue";
import { inject, onUnmounted, ref } from "vue";
import { api } from "./api";
import type { ArchiveRecord } from "./archiveFiles";
import { usageBlob, type ExportUsage } from "./exports";

/** 技术状态只翻译名称，批准事实仍来自服务端。 */
const exportStates: Record<string, string> = {
  PENDING: "准备中",
  RUNNING: "生成中",
  READY: "已生成",
  FAILED: "未完成",
};
/** 对话框只保存本人本次操作；密码与一次性凭据不写浏览器持久存储。 */
const askPassword =
  inject<(title: string) => Promise<string | null>>("askPassword")!;
const emit = defineEmits<{ changed: [] }>();
const dialog = ref<HTMLDialogElement | null>(null),
  records = ref<ArchiveRecord[]>([]),
  action = ref<"DOWNLOAD" | "PRINT">("DOWNLOAD"),
  /** 本次使用的授权副本是否为无水印版本。 */
  unwatermarked = ref(false);
/** 受理成功后重试只交付既有任务；网络结果不确定时保留原操作号。 */
const operation = ref(""),
  usage = ref<ExportUsage | null>(null),
  busy = ref(false),
  error = ref(""),
  pages = ref(""),
  copies = ref(1),
  history = ref<
    {
      id: string;
      action: "DOWNLOAD" | "PRINT";
      state: string;
      createdAt: string;
      fileNos: string[];
      canOpen: boolean;
      deliveryCount: number;
      feedback: string | null;
    }[]
  >([]);
let alive = true,
  generation = 0;
/** 显式选择一次新的业务动作才重新分配操作号。 */
function open(values: ArchiveRecord[], selected: "DOWNLOAD" | "PRINT", plain = false) {
  if (busy.value) return;
  generation++;
  records.value = values;
  action.value = selected;
  unwatermarked.value = plain;
  operation.value = crypto.randomUUID();
  usage.value = null;
  pages.value = "";
  copies.value = 1;
  error.value = "";
  dialog.value?.showModal();
}
/** 最近记录只显示本人过程状态；记录存在并不表示当前仍有内容权。 */
async function loadHistory() {
  try {
    history.value = (
      await api<{ items: typeof history.value }>("/archive/export-requests")
    ).items;
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
/** 重新打开既有任务只重新鉴权和取件，不生成新任务、不再次占用打印额度。 */
async function reopen(row: (typeof history.value)[number]) {
  if (busy.value) return;
  error.value = "";
  try {
    const result = await api<ExportUsage>(
      `/archive/${row.action === "PRINT" ? "print" : "download"}-requests/${row.id}`,
    );
    open([], row.action, result.unwatermarked);
    usage.value = result;
  } catch (failure) {
    error.value = (failure as Error).message;
    await loadHistory();
  }
}
/** 敏感或批量操作的密码凭据绑定完整修订清单及独立动作。 */
async function generate() {
  busy.value = true;
  error.value = "";
  const current = generation;
  try {
    if (!usage.value) {
      let verificationToken: string | undefined;
      if (
        records.value.length > 1 ||
        records.value.some((record) => record.classification !== "GENERAL")
      ) {
        const password = await askPassword("验证本人密码，生成授权使用副本");
        if (password === null) return;
        const ids = records.value.map((record) => record.currentRevisionId!);
        verificationToken = (
          await api<{ verificationToken: string }>("/auth/reauthentications", {
            method: "POST",
            body: JSON.stringify({
              password,
              purpose: "MANAGE",
              objectIds: ids,
              scopeItems: ids.map((id) => ({
                objectType: "ARCHIVE_REVISION",
                objectId: id,
                actions: [action.value],
              })),
            }),
          })
        ).verificationToken;
      }
      usage.value = await api<ExportUsage>(
        `/archive/${action.value === "PRINT" ? "print" : "download"}-requests`,
        {
          method: "POST",
          headers: { "X-Operation-Id": operation.value },
          body: JSON.stringify({
            items: records.value.map((record) => ({
              recordRevisionId: record.currentRevisionId,
              pdfVersionId: record.currentPdfVersionId,
              ...(action.value === "PRINT"
                ? { pageRange: pages.value || undefined, copies: copies.value }
                : {}),
            })),
            verificationToken,
            ...(unwatermarked.value ? { unwatermarked: true } : {}),
          }),
        },
      );
    }
    for (
      let attempt = 0;
      attempt < 180 && alive && current === generation;
      attempt++
    ) {
      const result: ExportUsage = await api(
        `/archive/${action.value === "PRINT" ? "print" : "download"}-requests/${usage.value!.usageId}`,
        {},
        false,
      );
      usage.value = result;
      if (result.state === "READY") {
        emit("changed");
        await loadHistory();
        return;
      }
      if (result.state === "FAILED")
        throw new Error(
          "副本生成失败，请查看交付记录；已受理打印额度不会自动退回。",
        );
      await new Promise((resolve) => setTimeout(resolve, 1000));
    }
    throw new Error("副本仍在处理，请稍后查询原任务。");
  } catch (failure) {
    if (current === generation) error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 下载保存的是当前本人水印副本；失效响应不会生成本地文件。 */
async function download() {
  if (!usage.value) return;
  busy.value = true;
  error.value = "";
  try {
    const blob = await usageBlob(
        `/archive/download-requests/${usage.value.usageId}/content`,
        operation.value,
        true,
      ),
      url = URL.createObjectURL(blob),
      link = document.createElement("a");
    link.href = url;
    link.download =
      usage.value.items.length > 1
        ? "档案使用副本.zip"
        : `${usage.value.items[0]!.fileNo}-使用副本.pdf`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
    await loadHistory();
  } catch (failure) {
    error.value = (failure as Error).message;
    usage.value = null;
    emit("changed");
  } finally {
    busy.value = false;
  }
}
/** 单独打印页重新鉴权并加载烘入水印的图像，不在主工作台展开大量页面。 */
function print() {
  if (usage.value)
    window.open(
      `/platform-print.html?usage=${encodeURIComponent(usage.value.usageId)}`,
      "_blank",
      "noopener",
    );
}
function close() {
  if (busy.value) return;
  generation++;
  dialog.value?.close();
}
onUnmounted(() => {
  alive = false;
  generation++;
});
defineExpose({ open, loadHistory });
</script>

<template>
  <section class="export-history" v-if="history.length">
    <div class="export-history-title">
      <strong>最近交付记录</strong>
    </div>
    <div
      v-for="row in history.slice(0, 5)"
      :key="row.id"
      class="export-history-row"
      :data-usage-id="row.id"
    >
      <span
        >{{ row.action === "PRINT" ? "打印" : "下载" }} ·
        {{ new Date(row.createdAt).toLocaleString("zh-CN") }} ·
        {{ row.fileNos.join("、") }}</span
      ><span
        >{{ exportStates[row.state] }} · {{ row.deliveryCount }}次交付许可{{
          row.feedback ? " · 客户端已反馈" : ""
        }}
        <button v-if="row.canOpen" class="text-button" @click="reopen(row)">
          查看任务</button
        ><small v-else> · 当前不可交付</small></span
      >
    </div>
    <footer class="list-footer">
      <button
        type="button"
        class="secondary list-refresh"
        :disabled="busy"
        @click="loadHistory()"
      >
        刷新列表
      </button>
      <div class="list-pagination">
        <span class="list-total"
          >显示最近 {{ Math.min(history.length, 5) }} 条</span
        >
      </div>
    </footer>
  </section>
  <dialog
    ref="dialog"
    class="form-dialog flow-dialog export-dialog"
    aria-labelledby="archive-export-title"
    @cancel.prevent="close"
  >
    <form class="flow-dialog-shell" @submit.prevent="generate">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"
          ><Icon name="download" :size="22"
        /></span>
        <div class="flow-dialog-title">
          <span class="export-eyebrow">个人授权使用副本</span>
          <h2 id="archive-export-title">
            {{ action === "PRINT" ? "受批打印" : "受批下载" }}
          </h2>
        </div>
        <button
          class="icon-button"
          type="button"
          :disabled="busy"
          aria-label="关闭使用副本"
          @click="close"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section class="flow-dialog-overview">
          <h3>使用范围</h3>
          <p class="muted">
            已选择
            {{ usage?.items.length || records.length }}
            份文件，使用当前批准的{{ unwatermarked ? "无水印副本" : "本人水印副本" }}。
          </p>
        </section>
        <section class="flow-dialog-section">
          <h3>文件清单</h3>
          <ul class="export-files">
            <template v-if="records.length"
              ><li v-for="record in records" :key="record.id">
                <strong>{{ record.title }}</strong
                ><small>{{ record.fileNo }}</small>
              </li></template
            ><template v-else
              ><li v-for="item in usage?.items" :key="item.fileNo">
                <strong>{{ item.fileNo }}</strong
                ><small>{{
                  usage?.action === "PRINT"
                    ? `固定原第${item.pages.join(",")}页 · ${item.copies}份`
                    : unwatermarked ? "原任务无水印副本" : "原任务水印副本"
                }}</small>
              </li></template
            >
          </ul>
        </section>
        <section v-if="action === 'PRINT'" class="flow-dialog-section">
          <h3>打印设置</h3>
          <fieldset class="flow-dialog-fields" :disabled="busy || !!usage">
            <label
              >打印页码<input
                v-model="pages"
                placeholder="留空使用批准范围，如1-3,5"
                maxlength="2000" /></label
            ><label
              >本次份数<input
                v-model.number="copies"
                type="number"
                min="1"
                max="100"
                required
            /></label>
            <p class="muted">
              累计份数受批准额度约束，单次最多50个输出页。重复查询不重复占用额度；结果未知时不自动退回。
            </p>
          </fieldset>
        </section>
        <p v-if="usage" class="export-status">
          {{ usage.state === "READY" ? "副本已就绪" : "正在生成副本" }}
          · 有效至 {{ new Date(usage.expiresAt).toLocaleString("zh-CN") }}
        </p>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button class="secondary" type="button" :disabled="busy" @click="close">
          关闭</button
        ><button
          v-if="usage?.state === 'READY'"
          class="primary"
          type="button"
          :disabled="busy"
          @click="action === 'PRINT' ? print() : download()"
        >
          {{ action === "PRINT" ? "打开打印页面" : unwatermarked ? "下载无水印副本" : "下载水印副本" }}</button
        ><button
          v-else
          class="primary"
          :disabled="busy || usage?.state === 'FAILED'"
        >
          {{ busy ? "正在准备…" : usage ? "查询原任务" : "生成使用副本" }}
        </button>
      </footer>
    </form>
  </dialog>
</template>

<style scoped>
.export-dialog {
  --flow-dialog-width: 620px;
}
.export-eyebrow {
  font-size: 12px;
  letter-spacing: 2px;
  color: var(--ink);
}
.export-files {
  max-height: 190px;
  overflow: auto;
  padding: 0;
  margin: 0;
  list-style: none;
  border-block: 1px solid var(--line);
}
.export-files li {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 13px 0;
}
.export-files small {
  color: var(--text-secondary);
}
.export-dialog fieldset {
  border: 0;
  padding: 0;
  margin: 0;
  min-width: 0;
}
.export-dialog fieldset p {
  grid-column: 1/-1;
  margin-top: 0;
}
.export-status {
  padding: 13px;
  background: var(--surface-muted);
  color: var(--ink);
  border-radius: 8px;
}
.export-history {
  margin: 20px 0 0;
  padding: 20px 24px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 12px;
}
.export-history-title,
.export-history-row {
  display: flex;
  justify-content: space-between;
  gap: 20px;
}
.export-history-row {
  font-size: 12px;
  color: var(--text-secondary);
  padding-top: 12px;
}
</style>
