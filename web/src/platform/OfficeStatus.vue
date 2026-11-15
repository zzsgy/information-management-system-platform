<script setup lang="ts">
import { computed, inject, onMounted, onUnmounted, ref } from "vue";
import { api, mutate, query } from "./api";
interface Session {
  id: string;
  state: string;
  rowVersion: number;
  createdAt: string;
  createdByName: string;
  participants: { userId: string; name: string; joinedAt: string }[];
}
interface Receipt {
  id: string;
  state: string;
  saveSeq: number;
  objectId: string | null;
  resultCode: string | null;
  createdAt: string;
  publishedVersionId: string | null;
  requestedByName: string;
  resolvedByName: string | null;
  resolvedAt: string | null;
  versionNo: number | null;
  publishedAt: string | null;
  resolution: string | null;
}
const props = defineProps<{
    documentId: string;
    documentVersion: number;
    downloadAllowed: boolean;
  }>(),
  emit = defineEmits<{ changed: [] }>(),
  ask = inject<(title: string) => Promise<string | null>>("askPassword")!;
const sessions = ref<Session[]>([]),
  receipts = ref<Receipt[]>([]),
  manage = ref(false),
  error = ref(""),
  busy = ref(false),
  reason = ref(""),
  target = ref<{
    id: string;
    type: "session" | "receipt";
    version: number;
    object?: string | null;
  }>(),
  resolution = ref("DISCARD"),
  reviewed = ref(false);
const tab = ref<"sessions" | "receipts">("sessions"),
  sessionPage = ref(1),
  receiptPage = ref(1),
  sessionTotal = ref(0),
  receiptTotal = ref(0),
  jumpPage = ref(1),
  loading = ref(false),
  hasPending = ref(false);
const pageSize = 10;
const currentPage = computed(() =>
    tab.value === "sessions" ? sessionPage.value : receiptPage.value,
  ),
  total = computed(() =>
    tab.value === "sessions" ? sessionTotal.value : receiptTotal.value,
  ),
  pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize)));
let timer: ReturnType<typeof setInterval> | undefined,
  alive = true,
  loaded = false,
  publishedVersion: string | null | undefined;
const labels: Record<string, string> = {
  OPEN: "协作中",
  CLOSING: "等待最终保存",
  CLOSED: "已结束",
  REVOKED: "授权已失效",
  UNKNOWN: "待负责人确认",
  REQUESTED: "已申请保存",
  RECEIVING: "正在接收",
  SAVED: "已保存正式版本",
  NO_CHANGES: "没有新修改",
  RESOLVED: "已处理",
  REJECTED: "检测拒绝",
};
function dateTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    dateStyle: "medium",
    timeStyle: "medium",
  }).format(new Date(value));
}
async function load() {
  if (loading.value) return;
  loading.value = true;
  try {
    const result = await api<{
      canManage: boolean;
      sessions: Session[];
      receipts: Receipt[];
      sessionTotal: number;
      receiptTotal: number;
      currentVersionId: string;
      hasPending: boolean;
    }>(
      `/drive/documents/${props.documentId}/office-history?${query({ sessionPage: sessionPage.value, receiptPage: receiptPage.value, pageSize })}`,
      {},
      false,
    );
    if (!alive) return;
    error.value = "";
    sessions.value = result.sessions;
    receipts.value = result.receipts;
    manage.value = result.canManage;
    sessionTotal.value = result.sessionTotal;
    receiptTotal.value = result.receiptTotal;
    hasPending.value = result.hasPending;
    const nextVersion = result.currentVersionId;
    // 关闭编辑器后的最终回调可能稍后到达，正式版本确认后同步刷新详情。
    if (loaded && nextVersion !== publishedVersion) emit("changed");
    publishedVersion = nextVersion;
    loaded = true;
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    loading.value = false;
  }
}
function switchTab(value: "sessions" | "receipts") {
  tab.value = value;
  jumpPage.value = currentPage.value;
}
async function turn(page: number) {
  if (tab.value === "sessions") sessionPage.value = page;
  else receiptPage.value = page;
  jumpPage.value = page;
  await load();
}
function choose(
  id: string,
  type: "session" | "receipt",
  version: number,
  object?: string | null,
) {
  target.value = { id, type, version, object };
  reason.value = "";
  resolution.value = "DISCARD";
  reviewed.value = false;
}
async function apply() {
  if (!target.value) return;
  busy.value = true;
  try {
    const current = target.value,
      password = await ask("确认协作稿件处理结论");
    if (!password) return;
    const session = current.type === "session";
    await mutate(
      session
        ? `/drive/edit-sessions/${current.id}/terminate`
        : `/drive/save-receipts/${current.id}/resolve`,
      "POST",
      session
        ? { reason: reason.value }
        : {
            reason: reason.value,
            resolution: resolution.value,
            ...(resolution.value === "ACCEPT"
              ? { reviewedObjectId: current.object }
              : {}),
          },
      current.id,
      session ? "EDIT_SESSION" : "SAVE_RECEIPT",
      session ? "TERMINATE" : "RESOLVE",
      password,
      current.version,
    );
    target.value = undefined;
    await load();
    emit("changed");
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
onMounted(async () => {
  await load();
  if (alive)
    timer = setInterval(() => {
      if (hasPending.value) void load();
    }, 5000);
});
onUnmounted(() => {
  alive = false;
  if (timer) clearInterval(timer);
});
</script>
<template>
  <section v-if="sessionTotal || receiptTotal || error" class="office-status">
    <header>
      <h3>协作与保存记录</h3>
    </header>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div class="record-tabs" role="tablist" aria-label="协作记录分类">
      <button
        id="office-sessions-tab"
        role="tab"
        :aria-selected="tab === 'sessions'"
        aria-controls="office-records"
        @click="switchTab('sessions')"
      >
        编辑记录
      </button>
      <button
        id="office-receipts-tab"
        role="tab"
        :aria-selected="tab === 'receipts'"
        aria-controls="office-records"
        @click="switchTab('receipts')"
      >
        保存记录
      </button>
    </div>
    <p class="record-hint muted">
      参与时间表示进入编辑的时间；实际保存结果和版本见保存记录。
    </p>
    <div
      id="office-records"
      class="record-table"
      role="tabpanel"
      :aria-labelledby="
        tab === 'sessions' ? 'office-sessions-tab' : 'office-receipts-tab'
      "
      :aria-busy="loading"
    >
      <table v-if="tab === 'sessions'" aria-label="编辑记录">
        <thead>
          <tr>
            <th>编辑参与人</th>
            <th>发起时间</th>
            <th>状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="session in sessions" :key="session.id">
            <td>
              <details class="record-people">
                <summary>
                  {{
                    session.participants
                      .map((person) => person.name)
                      .join("、") || session.createdByName
                  }}
                </summary>
                <span class="muted">发起人：{{ session.createdByName }}</span>
                <span
                  v-for="person in session.participants"
                  :key="person.userId"
                  >{{ person.name
                  }}<small class="muted"
                    >{{ dateTime(person.joinedAt) }} 进入编辑</small
                  ></span
                >
              </details>
            </td>
            <td>{{ dateTime(session.createdAt) }}</td>
            <td>
              <span
                class="record-state"
                :class="{
                  pending: ['CLOSING', 'UNKNOWN'].includes(session.state),
                }"
                >{{ labels[session.state] || session.state }}</span
              >
            </td>
            <td>
              <button
                v-if="
                  manage &&
                  ['OPEN', 'CLOSING', 'UNKNOWN'].includes(session.state)
                "
                class="text-button"
                :disabled="busy"
                @click="choose(session.id, 'session', session.rowVersion)"
              >
                终止并处理待存稿件</button
              ><span v-else class="muted">—</span>
            </td>
          </tr>
          <tr v-if="!sessions.length">
            <td colspan="4" class="record-empty">暂无编辑记录</td>
          </tr>
        </tbody>
      </table>
      <table v-else aria-label="保存记录">
        <thead>
          <tr>
            <th>保存人</th>
            <th>保存时间</th>
            <th>保存结果</th>
            <th>正式版本</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="receipt in receipts" :key="receipt.id">
            <td>{{ receipt.requestedByName }}</td>
            <td>
              {{ dateTime(receipt.createdAt)
              }}<small v-if="receipt.publishedAt" class="record-sub muted"
                >{{ dateTime(receipt.publishedAt) }} 确认发布</small
              >
            </td>
            <td>
              <span
                class="record-state"
                :class="{
                  pending: ['REQUESTED', 'RECEIVING', 'UNKNOWN'].includes(
                    receipt.state,
                  ),
                }"
                >{{ labels[receipt.state] || receipt.state }}</span
              >
              <small
                v-if="receipt.resolvedByName && receipt.resolvedAt"
                class="record-sub muted"
                >{{ receipt.resolvedByName }} · {{ dateTime(receipt.resolvedAt)
                }}<br />{{
                  receipt.resolution === "ACCEPT" ? "接受候选" : "舍弃候选"
                }}</small
              >
            </td>
            <td>{{ receipt.versionNo ? `V${receipt.versionNo}` : "—" }}</td>
            <td>
              <template v-if="manage && receipt.state === 'UNKNOWN'"
                ><a
                  v-if="receipt.objectId && downloadAllowed"
                  class="text-button"
                  :href="`/api/v1/drive/save-receipts/${receipt.id}/candidate`"
                  >下载候选审阅</a
                ><button
                  class="text-button"
                  :disabled="busy"
                  @click="
                    choose(
                      receipt.id,
                      'receipt',
                      documentVersion,
                      receipt.objectId,
                    )
                  "
                >
                  处理
                </button></template
              ><span v-else class="muted">—</span>
            </td>
          </tr>
          <tr v-if="!receipts.length">
            <td colspan="5" class="record-empty">暂无保存记录</td>
          </tr>
        </tbody>
      </table>
    </div>
    <footer class="list-footer record-footer" aria-label="协作记录分页">
      <button
        type="button"
        class="secondary list-refresh"
        :disabled="loading || busy"
        @click="load()"
      >
        刷新列表
      </button>
      <div class="list-pagination">
        <div>
          <button
            class="secondary"
            :disabled="loading || currentPage <= 1"
            @click="turn(currentPage - 1)"
          >
            上一页</button
          ><span>第 {{ currentPage }} / {{ pageCount }} 页</span
          ><button
            class="secondary"
            :disabled="loading || currentPage >= pageCount"
            @click="turn(currentPage + 1)"
          >
            下一页
          </button>
        </div>
        <form v-if="pageCount > 1" @submit.prevent="turn(jumpPage)">
          <label class="list-filter-field"
            >跳至<input
              v-model.number="jumpPage"
              aria-label="协作记录跳转页码"
              type="number"
              min="1"
              :max="pageCount"
              step="1"
              required /></label
          ><button class="secondary" :disabled="loading">跳转</button>
        </form>
        <span class="list-total"
          >共 {{ total }} 条 · 每页 {{ pageSize }} 条</span
        >
      </div>
    </footer>
    <form v-if="target" class="office-resolve" @submit.prevent="apply">
      <p v-if="target.type === 'session'">
        终止会让旧协作代次失效。已经修改但尚未确认的内容将进入待处理状态。
      </p>
      <template v-else
        ><label
          >处理结论<select v-model="resolution">
            <option value="DISCARD">舍弃候选，保留当前正式版本</option>
            <option v-if="target.object && downloadAllowed" value="ACCEPT">
              接受候选，生成新的正式版本
            </option>
          </select></label
        ><label v-if="resolution === 'ACCEPT'"
          ><input
            v-model="reviewed"
            type="checkbox"
            required
          />已下载并核对候选内容，确认以其生成新版本</label
        ></template
      ><label
        >处理原因<textarea
          v-model="reason"
          maxlength="1000"
          rows="2"
          required
        /></label
      ><button
        class="primary"
        :disabled="busy || (resolution === 'ACCEPT' && !reviewed)"
      >
        确认处理</button
      ><button
        type="button"
        class="secondary"
        :disabled="busy"
        @click="target = undefined"
      >
        取消
      </button>
    </form>
  </section>
</template>
<style scoped>
.office-status {
  margin-top: 28px;
  border-top: 1px solid var(--line);
  padding-top: 12px;
}
.office-status header {
  display: flex;
  gap: 15px;
  align-items: center;
  justify-content: space-between;
}
.record-tabs {
  display: flex;
  gap: 20px;
  border-bottom: 1px solid var(--line);
}
.record-tabs button {
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--muted);
  padding: 10px 0;
  font: inherit;
  cursor: pointer;
}
.record-tabs button[aria-selected="true"] {
  border-color: var(--brand);
  color: var(--brand);
  font-weight: 600;
}
.record-hint {
  font-size: 12px;
  margin: 12px 0;
}
.record-table {
  max-height: 400px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 10px;
}
.record-table table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.record-table th,
.record-table td {
  text-align: left;
  padding: 13px 12px;
  vertical-align: top;
  border-bottom: 1px solid var(--line);
}
.record-table th {
  position: sticky;
  top: 0;
  background: #f6f8fb;
  color: #63798a;
  z-index: 1;
}
.record-people {
  max-width: 250px;
}
.record-people summary {
  cursor: pointer;
}
.record-people > span,
.record-sub {
  display: block;
  margin-top: 7px;
}
.record-people small {
  display: block;
  margin-top: 4px;
}
.record-state {
  white-space: nowrap;
}
.record-state.pending {
  color: #a05b10;
}
.record-table .record-empty {
  text-align: center;
  padding: 28px;
}
.record-footer {
  padding-inline: 0;
}
.record-footer .list-filter-field input {
  width: 58px;
  padding: 7px;
}
@media (max-width: 760px) {
  .record-table table {
    min-width: 650px;
  }
}
.office-resolve {
  background: var(--surface-muted);
  padding: 18px;
  margin-top: 14px;
  border-radius: 10px;
}
.office-resolve label {
  display: grid;
  gap: 8px;
  margin: 12px 0;
}
.office-resolve button {
  margin-right: 10px;
}
</style>
