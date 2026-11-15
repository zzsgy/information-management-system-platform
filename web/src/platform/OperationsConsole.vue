<script setup lang="ts">
import { computed, inject, onMounted, onUnmounted, ref } from "vue";
import { api, can, COLLECTION, dateTime, mutate, query } from "./api";
import ListFilters, { type ListFilterField } from "./ListFilters.vue";
import { recentWeekFilters } from "./listDates";
import ServiceHealthNotice from "./ServiceHealthNotice.vue";
/** 页面只接收技术状态与受控编号，不接收目录路径、存储键和档案名称。 */
interface Run {
  id: string;
  kind: string;
  state: string;
  reason: string;
  createdAt: string;
  finishedAt: string | null;
  checkedCount: number;
  failedCount: number;
  totalCount: number;
  errorCode: string | null;
  rowVersion: number;
  sourceBackupId: string | null;
  summary?: Record<string, unknown>;
}
interface Backup {
  id: string;
  verifiedAt: string;
  retainUntil: string;
  tableCount: number;
  objectCount: number;
  fileCount: number;
}
interface Plan {
  enabled: boolean;
  hourShanghai: number;
  retentionDays: number;
  rowVersion: number;
}
interface Overview {
  enabled: boolean;
  protection: { ready: boolean; mode: string };
  office: { enabled: boolean; state: string };
  storage: { objects: number; bytes: number };
  latestCheck: { summary: string; finished_at: string } | null;
  latestBackup: { verified_at: string } | null;
  backupPlan: Plan;
}
const summary = ref<Overview | null>(null),
  section = ref("runs"),
  rows = ref<Record<string, unknown>[]>([]),
  page = ref(1),
  total = ref(0),
  listFilters = ref(recentWeekFilters("created")),
  busy = ref(false),
  error = ref(""),
  kind = ref("PROTECTION_CHECK"),
  reason = ref(""),
  sourceBackupId = ref(""),
  backups = ref<Backup[]>([]),
  detail = ref<Run | null>(null),
  plan = ref<Plan>({
    enabled: false,
    hourShanghai: 2,
    retentionDays: 30,
    rowVersion: 1,
  }),
  planReason = ref("");
const askPassword =
  inject<(title: string) => Promise<string | null>>("askPassword")!;
const labels: Record<string, string> = {
  PROTECTION_CHECK: "保护检查",
  REPLICA_CHECK: "双副本核验",
  BACKUP: "独立备份",
  RESTORE_VERIFY: "隔离恢复",
  QUEUED: "已排队",
  WAITING_WINDOW: "等待停机窗口",
  RUNNING: "正在执行",
  SUCCEEDED: "任务完成",
  FAILED: "执行失败",
  MANUAL: "待人工核对",
  CANCELLED: "已取消",
  PASSED: "核验一致",
  PENDING: "待处理",
  UNKNOWN: "结果待核对",
};
const filterFields: Record<string, ListFilterField[]> = {
  runs: [
    { key: "number", label: "任务编号" },
    {
      key: "kind",
      label: "任务类型",
      options: {
        PROTECTION_CHECK: labels.PROTECTION_CHECK!,
        REPLICA_CHECK: labels.REPLICA_CHECK!,
        BACKUP: labels.BACKUP!,
        RESTORE_VERIFY: labels.RESTORE_VERIFY!,
      },
    },
    {
      key: "state",
      label: "任务状态",
      options: {
        QUEUED: labels.QUEUED!,
        WAITING_WINDOW: labels.WAITING_WINDOW!,
        RUNNING: labels.RUNNING!,
        SUCCEEDED: labels.SUCCEEDED!,
        FAILED: labels.FAILED!,
        MANUAL: labels.MANUAL!,
        CANCELLED: labels.CANCELLED!,
      },
    },
    { key: "created", label: "申请日期", type: "date" },
  ],
  backups: [
    { key: "number", label: "备份编号" },
    { key: "verified", label: "验证日期", type: "date" },
    { key: "retained", label: "保留截止日期", type: "date" },
  ],
  replicas: [
    { key: "number", label: "原件编号" },
    {
      key: "state",
      label: "副本核验",
      options: { PASSED: labels.PASSED!, FAILED: labels.FAILED! },
    },
    { key: "error", label: "异常码" },
    { key: "checked", label: "检查日期", type: "date" },
  ],
  jobs: [
    { key: "number", label: "任务编号" },
    {
      key: "module",
      label: "所属模块",
      options: { ARCHIVE: "档案", DRIVE: "公盘" },
    },
    {
      key: "kind",
      label: "处理类型",
      options: {
        ARCHIVE_EXPORT: "档案交付",
        ARCHIVE_PREVIEW: "档案预览",
        SCAN_FILE_CHECK: "扫描文件检测",
        PREVIEW_METADATA: "公盘预览",
        FILE_ASSEMBLY_CHECK: "文件组装与检测",
      },
    },
    {
      key: "state",
      label: "任务状态",
      options: {
        PENDING: labels.PENDING!,
        RUNNING: labels.RUNNING!,
        SUCCEEDED: labels.SUCCEEDED!,
        FAILED: labels.FAILED!,
        UNKNOWN: labels.UNKNOWN!,
        CANCELLED: labels.CANCELLED!,
      },
    },
    { key: "error", label: "异常码" },
  ],
};
const report = computed(() =>
  summary.value?.latestCheck
    ? (JSON.parse(summary.value.latestCheck.summary) as Record<string, unknown>)
    : null,
);
const modes: Record<string, string> = {
  NOT_CONFIGURED: "未配置保护代理",
  PROTECTION_NOT_READY: "保护未就绪",
  NORMAL: "正常保护",
  ARCHIVE_READ_ONLY: "档案只读",
  ALL_READ_ONLY: "全局只读",
  RECOVERY_LOCKED: "恢复锁定",
};
let generation = 0,
  alive = true,
  timer: ReturnType<typeof setInterval> | undefined;
/** 自动刷新只读状态，页面离开后停止；旧请求不能覆盖新的筛选结果。 */
async function load(background = false) {
  const current = ++generation;
  if (!background) busy.value = true;
  try {
    const detailId = detail.value?.id;
    const [overview, list, points, currentDetail] = await Promise.all([
      api<Overview>("/admin/operations", {}, !background),
      api<{ items: Record<string, unknown>[]; total: number }>(
        `/admin/operations/lists/${section.value}?${query({ page: page.value, ...listFilters.value })}`,
        {},
        !background,
      ),
      api<{ items: Backup[] }>("/admin/operations/lists/backups", {}, false),
      detailId
        ? api<Run>(`/admin/operations/runs/${detailId}`, {}, false)
        : Promise.resolve(null),
    ]);
    if (!alive || current !== generation) return;
    summary.value = overview;
    rows.value = list.items;
    total.value = list.total;
    backups.value = points.items;
    if (detail.value?.id === detailId && currentDetail)
      detail.value = currentDetail;
    if (!background) plan.value = { ...overview.backupPlan };
    error.value = "";
  } catch (e) {
    if (alive && current === generation) error.value = (e as Error).message;
  } finally {
    if (current === generation) busy.value = false;
  }
}
function tab(value: string) {
  section.value = value;
  listFilters.value = recentWeekFilters(filterFields[value]?.find((field) => field.type === "date")?.key);
  page.value = 1;
  detail.value = null;
  void load();
}
/** 维护请求须再验证，提交后显示真实排队状态，不将后台未执行的请求标成成功。 */
async function create() {
  const password = await askPassword(
    "确认申请" + (labels[kind.value] || kind.value),
  );
  if (!password) return;
  busy.value = true;
  error.value = "";
  try {
    const body: Record<string, unknown> = {
      kind: kind.value,
      reason: reason.value,
    };
    if (kind.value === "RESTORE_VERIFY")
      body.sourceBackupId = sourceBackupId.value;
    detail.value = await mutate<Run>(
      "/admin/operations/runs",
      "POST",
      body,
      COLLECTION,
      "MAINTENANCE_RUN",
      "CREATE_MAINTENANCE",
      password,
    );
    reason.value = "";
    section.value = "runs";
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function inspect(id: string) {
  try {
    detail.value = await api<Run>(`/admin/operations/runs/${id}`);
  } catch (e) {
    error.value = (e as Error).message;
  }
}
async function control(action: "retry" | "cancel") {
  if (!detail.value || !reason.value.trim()) return;
  const password = await askPassword(
    action === "retry" ? "重新申请维护任务" : "取消未执行任务",
  );
  if (!password) return;
  busy.value = true;
  try {
    const row = detail.value;
    detail.value = await mutate<Run>(
      `/admin/operations/runs/${row.id}/${action}`,
      "POST",
      { reason: reason.value },
      row.id,
      "MAINTENANCE_RUN",
      action === "retry" ? "RETRY_MAINTENANCE" : "CANCEL_MAINTENANCE",
      password,
      row.rowVersion,
    );
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function savePlan() {
  const password = await askPassword("保存独立备份计划");
  if (!password) return;
  busy.value = true;
  try {
    await mutate(
      "/admin/operations/backup-plan",
      "PUT",
      {
        enabled: plan.value.enabled,
        hourShanghai: plan.value.hourShanghai,
        retentionDays: plan.value.retentionDays,
        reason: planReason.value,
      },
      COLLECTION,
      "BACKUP_PLAN",
      "SAVE_BACKUP_PLAN",
      password,
      plan.value.rowVersion,
    );
    planReason.value = "";
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
function stateText(value: unknown) {
  return labels[String(value)] || String(value ?? "—");
}
onMounted(() => {
  void load();
  timer = setInterval(() => {
    if (!busy.value) void load(true);
  }, 15000);
});
onUnmounted(() => {
  alive = false;
  clearInterval(timer);
});
</script>
<template>
  <ServiceHealthNotice show-healthy />
  <section class="page-heading">
    <div>
      <span class="eyebrow">运维与数据保护</span>
      <h1>运行状态与维护中心</h1>
      <p>检查保护条件，核验双副本，追踪备份、恢复和后台任务。</p>
    </div>
  </section>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  <div v-if="summary" class="ops-cards">
    <article class="panel">
      <span>当前保护</span
      ><strong>{{
        modes[summary.protection.mode] || summary.protection.mode
      }}</strong
      ><small>写入权限仍以实时保护检查为准</small>
    </article>
    <article class="panel">
      <span>原件与历史源</span><strong>{{ summary.storage.objects }} 份</strong
      ><small
        >{{ (summary.storage.bytes / 1048576).toFixed(1) }} MiB ·
        两份独立摘要核验</small
      >
    </article>
    <article class="panel">
      <span>最近独立备份</span
      ><strong>{{
        summary.latestBackup
          ? dateTime(summary.latestBackup.verified_at)
          : "尚无验证记录"
      }}</strong
      ><small>副本同步不能代替独立备份</small>
    </article>
  </div>
  <section class="panel ops-panel">
    <div class="ops-section-heading">
      <h2>检查与维护申请</h2>
      <span v-if="!summary?.enabled" class="chip">当前执行器未启用</span>
    </div>
    <form class="toolbar" @submit.prevent="create">
      <label
        >任务类型<select v-model="kind">
          <option value="PROTECTION_CHECK">保护检查</option>
          <option value="REPLICA_CHECK">双副本核验</option>
          <option v-if="can('PLATFORM.BACKUP_MANAGE')" value="BACKUP">
            独立备份
          </option>
          <option v-if="can('PLATFORM.RESTORE_VERIFY')" value="RESTORE_VERIFY">
            隔离恢复演练
          </option>
        </select></label
      ><label v-if="kind === 'RESTORE_VERIFY'"
        >已验证备份<select v-model="sourceBackupId" required>
          <option value="">请选择</option>
          <option v-for="backup in backups" :key="backup.id" :value="backup.id">
            {{ dateTime(backup.verifiedAt) }} · {{ backup.id.slice(0, 8) }}
          </option>
        </select></label
      ><label class="ops-reason"
        >申请依据<input
          v-model.trim="reason"
          maxlength="1000"
          required
          placeholder="填写维护事项或恢复授权依据" /></label
      ><button class="primary" :disabled="busy || !summary?.enabled">
        提交申请
      </button>
    </form>
    <p class="ops-note">
      备份和恢复等待停机维护窗口，由独立执行器运行；恢复仅写入全新隔离库和目录，技术核验通过后仍需业务复核。
    </p>
    <div v-if="report" class="ops-checks">
      <span
        >最近检查：{{
          report.health === "READY" ? "检查通过" : "发现未就绪项"
        }}</span
      ><span
        >存储：{{
          report.storage === "ROOTS_VERIFIED" ? "目录配对正常" : "未就绪"
        }}</span
      ><span
        >Office：{{
          report.office === "VERIFIED" ? "版本核对通过" : "未就绪 / 未配置"
        }}</span
      ><span
        >扫描：{{
          report.scanner === "CLEAN" ? "探针通过" : "未就绪 / 未配置"
        }}</span
      ><span
        >PDF：{{
          report.pdf === "VERIFIED" ? "探针通过" : "未就绪 / 未配置"
        }}</span
      >
    </div>
  </section>
  <section class="panel management-panel">
    <nav class="module-tabs panel-tabs" aria-label="运维分类">
      <button
        v-for="[key, name] in [
          ['runs', '维护任务'],
          ['backups', '独立备份'],
          ['replicas', '副本状态'],
          ['jobs', '业务处理任务'],
        ]"
        :key="key"
        :class="{ selected: section === key }"
        @click="tab(key!)"
      >
        {{ name }}
      </button>
    </nav>
    <ListFilters
      :key="section"
      :fields="filterFields[section]!"
      :values="listFilters"
      :busy="busy"
      @apply="
        listFilters = $event;
        page = 1;
        load();
      "
    />
    <div class="table-scroll">
      <table v-if="section === 'runs'">
        <thead>
          <tr>
            <th>任务编号</th>
            <th>类型</th>
            <th>状态</th>
            <th>已核验 / 总数</th>
            <th>异常数</th>
            <th>申请时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="String(row.id)">
            <td :title="String(row.id)">{{ String(row.id).slice(0, 8) }}</td>
            <td>{{ stateText(row.kind) }}</td>
            <td>
              <span
                class="status-badge"
                :class="{
                  rejected: row.state === 'FAILED' || row.state === 'MANUAL',
                }"
                >{{ stateText(row.state) }}</span
              >
            </td>
            <td>{{ row.checkedCount }} / {{ row.totalCount }}</td>
            <td>{{ row.failedCount }}</td>
            <td>{{ dateTime(String(row.createdAt)) }}</td>
            <td>
              <button class="secondary" @click="inspect(String(row.id))">
                查看详情
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <table v-else-if="section === 'backups'">
        <thead>
          <tr>
            <th>备份编号</th>
            <th>验证时间</th>
            <th>表数量</th>
            <th>原件数</th>
            <th>文件数</th>
            <th>至少保留至</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="String(row.id)">
            <td :title="String(row.id)">{{ String(row.id).slice(0, 8) }}</td>
            <td>{{ dateTime(String(row.verifiedAt)) }}</td>
            <td>{{ row.tableCount }}</td>
            <td>{{ row.objectCount }}</td>
            <td>{{ row.fileCount }}</td>
            <td>{{ dateTime(String(row.retainUntil)) }}</td>
          </tr>
        </tbody>
      </table>
      <table v-else-if="section === 'replicas'">
        <thead>
          <tr>
            <th>原件编号</th>
            <th>副本核验</th>
            <th>最近检查</th>
            <th>字节数</th>
            <th>异常码</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="String(row.objectId)">
            <td :title="String(row.objectId)">
              {{ String(row.objectId).slice(0, 8) }}
            </td>
            <td>{{ stateText(row.state) }}</td>
            <td>{{ dateTime(String(row.checkedAt)) }}</td>
            <td>{{ row.byteSize }}</td>
            <td>{{ row.errorCode || "—" }}</td>
          </tr>
        </tbody>
      </table>
      <table v-else>
        <thead>
          <tr>
            <th>任务编号</th>
            <th>所属模块</th>
            <th>处理类型</th>
            <th>状态</th>
            <th>尝试次数</th>
            <th>异常码</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="String(row.id)">
            <td :title="String(row.id)">{{ String(row.id).slice(0, 8) }}</td>
            <td>{{ row.module }}</td>
            <td>{{ row.jobType }}</td>
            <td>{{ stateText(row.state) }}</td>
            <td>{{ row.attemptCount }}</td>
            <td>{{ row.errorCode || "—" }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="!rows.length" class="empty-state">
      <h3>当前没有匹配记录</h3>
      <p>检查执行后显示真实结果，未检测的副本不会显示为通过。</p>
    </div>
    <footer class="list-footer">
      <button
        type="button"
        class="secondary list-refresh"
        :disabled="busy"
        @click="load()"
      >
        刷新列表
      </button>
      <div class="list-pagination">
        <span>第 {{ page }} 页</span>
        <div>
          <button
            class="secondary"
            :disabled="busy || page <= 1"
            @click="
              page--;
              load();
            "
          >
            上一页</button
          ><button
            class="secondary"
            :disabled="busy || page * 20 >= total"
            @click="
              page++;
              load();
            "
          >
            下一页
          </button>
        </div>
        <span class="list-total">共 {{ total }} 条</span>
      </div>
    </footer>
    <p v-if="section === 'jobs'" class="ops-note inset">
      文件检测、预览和导出任务继续由原业务入口核对权限后重试，运维页面不能代替业务授权。
    </p>
  </section>
  <section v-if="detail" class="panel ops-panel">
    <h2>{{ labels[detail.kind] }} · {{ labels[detail.state] }}</h2>
    <p>任务编号：{{ detail.id }}</p>
    <p>申请依据：{{ detail.reason }}</p>
    <p v-if="detail.errorCode" class="form-error">{{ detail.errorCode }}</p>
    <p v-if="detail.kind === 'RESTORE_VERIFY' && detail.state === 'SUCCEEDED'">
      技术恢复核验通过，原入口未切换；请继续核对业务内容、附件对应和权限。
    </p>
    <p v-if="detail.summary">
      {{ detail.summary.recordDigestsMatched ? "逐表摘要一致。" : ""
      }}{{ detail.summary.fileDigestsMatched ? "文件摘要一致。" : ""
      }}{{
        detail.summary.health === "DEGRADED"
          ? "检查发现未就绪项，请按状态面板排查。"
          : ""
      }}
    </p>
    <div class="toolbar">
      <label class="ops-reason"
        >后续处理依据<input
          v-model.trim="reason"
          maxlength="1000"
          placeholder="重试或取消时必填" /></label
      ><button
        v-if="['FAILED', 'MANUAL', 'CANCELLED'].includes(detail.state)"
        class="primary"
        :disabled="busy || !reason"
        @click="control('retry')"
      >
        新建重试任务</button
      ><button
        v-if="['QUEUED', 'WAITING_WINDOW'].includes(detail.state)"
        class="secondary"
        :disabled="busy || !reason"
        @click="control('cancel')"
      >
        取消排队</button
      ><button class="secondary" @click="detail = null">收起详情</button>
    </div>
  </section>
  <section v-if="can('PLATFORM.BACKUP_MANAGE')" class="panel ops-panel">
    <h2>每日独立备份计划</h2>
    <form class="toolbar" @submit.prevent="savePlan">
      <label
        >计划状态<select v-model="plan.enabled">
          <option :value="false">暂停</option>
          <option :value="true">每天排队</option>
        </select></label
      ><label
        >上海时间（小时）<input
          v-model.number="plan.hourShanghai"
          type="number"
          min="0"
          max="23"
          required /></label
      ><label
        >保留天数<input
          v-model.number="plan.retentionDays"
          type="number"
          min="1"
          max="3650"
          required /></label
      ><label class="ops-reason"
        >变更依据<input
          v-model.trim="planReason"
          required
          maxlength="1000" /></label
      ><button class="primary" :disabled="busy">保存计划</button>
    </form>
    <p class="ops-note">
      默认保留30天。调整只作用于新备份，已有备份不缩短期限；当前阶段不自动删除到期备份。
    </p>
  </section>
</template>
<style scoped>
.ops-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  margin-bottom: 22px;
}
.ops-cards article {
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ops-cards strong {
  font-size: 22px;
  color: var(--ink);
}
.ops-cards span,
.ops-cards small,
.ops-note {
  color: var(--text-secondary);
}
.ops-panel {
  padding: 26px;
  margin: 22px 0;
}
.ops-section-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.ops-panel h2 {
  margin: 0 0 22px;
}
.ops-panel .toolbar {
  padding: 0;
  flex-wrap: wrap;
}
/* 有字段标题的表单按输入框底边对齐，避免按钮对齐到整组标签的中线。 */
.ops-panel form.toolbar,
.management-panel > form.toolbar {
  align-items: flex-end;
}
.management-panel > form.toolbar > span {
  display: flex;
  align-items: center;
  min-height: 38px;
}
.ops-reason {
  flex: 1;
  min-width: 200px;
}
.ops-note {
  font-size: 13px;
  line-height: 1.8;
  margin-top: 16px;
}
.ops-checks {
  display: flex;
  gap: 20px;
  flex-wrap: wrap;
  padding-top: 16px;
  color: var(--ink);
  font-size: 13px;
}
.ops-panel input[type="number"] {
  width: 110px;
}
.ops-panel label {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
</style>
