<script setup lang="ts">
import { inject, onMounted, ref } from "vue";
import { api, COLLECTION, dateTime, mutate } from "./api";
/** 技术状态只展示容量、月份与任务，不接收本地路径或日志正文。 */
interface Month {
  month_start: string;
  state: string;
  sealed_at: string | null;
  archived_at: string | null;
}
interface Run {
  id: string;
  month_start: string;
  state: string;
  created_at: string;
  byte_size: number | null;
  sha256: string | null;
  error_code: string | null;
}
interface Sample {
  sampled_at: string;
  total_bytes: number;
  available_bytes: number;
  estimated_rows: number;
  state: string;
  details: {
    dailyGrowthBytes: number | null;
    forecastDays: number | null;
    databaseVolumeConfigured: boolean;
  };
}
interface Overview {
  enabled: boolean;
  archiveConfigured: boolean;
  budgetBytes: number;
  minimumFreeBytes: number;
  hotMonths: number;
  latest: Sample | null;
  months: Month[];
  runs: Run[];
}
const data = ref<Overview>(),
  busy = ref(false),
  error = ref("");
const askPassword =
  inject<(title: string) => Promise<string | null>>("askPassword")!;
const labels: Record<string, string> = {
  OPEN: "可追加",
  SEALED: "已封存",
  ARCHIVED: "双副本已核验",
  QUEUED: "等待归档",
  RUNNING: "正在归档",
  FAILED: "核验失败",
  MANUAL: "需人工核对",
  NORMAL: "正常",
  WARNING: "容量预警",
  CRITICAL: "严重预警",
};
function bytes(value: number | null | undefined) {
  if (value == null || value < 0) return "待采样";
  return value >= 1e9
    ? `${(value / 1e9).toFixed(2)} GB`
    : `${(value / 1e6).toFixed(1)} MB`;
}
async function load() {
  try {
    data.value = await api<Overview>("/admin/audit-storage");
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
async function sample() {
  busy.value = true;
  error.value = "";
  try {
    await api("/admin/audit-storage/sample", { method: "POST" });
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 归档仅提交UTC月初日期，后台任务核验完成后仍保持历史在线可查。 */
async function archive(month: string) {
  const password = await askPassword(
    `封存并归档 ${month.slice(0, 7)} 的操作记录`,
  );
  if (!password) return;
  busy.value = true;
  error.value = "";
  try {
    await mutate(
      "/admin/audit-storage/archives",
      "POST",
      { month },
      COLLECTION,
      "AUDIT_ARCHIVE",
      "ARCHIVE_LOGS",
      password,
    );
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
function eligible(month: string) {
  const cutoff = new Date();
  cutoff.setUTCDate(1);
  cutoff.setUTCMonth(cutoff.getUTCMonth() - (data.value?.hotMonths ?? 3) + 1);
  return month < cutoff.toISOString().slice(0, 10);
}
onMounted(load);
</script>
<template>
  <section class="page-heading">
    <div>
      <span class="eyebrow">容量与证据留存</span>
      <h1>日志存储与归档</h1>
      <p>按月封存，双副本核验；历史记录保留在线查询。</p>
    </div>
    <div class="actions">
      <button class="primary" :disabled="busy" @click="sample">
        {{ busy ? "正在处理…" : "采样容量" }}
      </button>
    </div>
  </section>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <template v-if="data">
    <div class="storage-metrics">
      <article class="panel">
        <span>日志与索引</span
        ><strong>{{ bytes(data.latest?.total_bytes) }}</strong
        ><small
          >预算 {{ bytes(data.budgetBytes) }} ·
          {{ labels[data.latest?.state ?? ""] || "尚未采样" }}</small
        >
      </article>
      <article class="panel">
        <span>数据库卷可用</span
        ><strong>{{ bytes(data.latest?.available_bytes) }}</strong
        ><small>预警下限 {{ bytes(data.minimumFreeBytes) }}</small>
      </article>
      <article class="panel">
        <span>日增长估算</span
        ><strong>{{ bytes(data.latest?.details?.dailyGrowthBytes) }}</strong
        ><small>采样积累满一小时后估算，非固定日均</small>
      </article>
    </div>
    <div class="storage-note">
      <span
        >保留最近 {{ data.hotMonths }} 个热月份，归档不删除记录。归档位置{{
          data.archiveConfigured ? "已配置" : "尚未配置"
        }}{{ data.enabled ? "。" : "，后台归档已暂停。" }}</span
      ><span v-if="data.latest"
        >最近采样 {{ dateTime(data.latest.sampled_at) }}</span
      >
    </div>
    <p
      v-if="data.latest && !data.latest.details.databaseVolumeConfigured"
      class="error"
    >
      尚未配置数据库主机的数据卷，无法核对实际剩余空间。
    </p>
    <section class="panel management-panel">
      <header class="list-toolbar">
        <h2>月份分区</h2>
        <span>按UTC自然月管理</span>
      </header>
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>月份</th>
              <th>状态</th>
              <th>封存时间</th>
              <th>归档完成</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="month in data.months" :key="month.month_start">
              <td>{{ month.month_start.slice(0, 7) }}</td>
              <td>
                <span class="status-badge">{{ labels[month.state] }}</span>
              </td>
              <td>{{ month.sealed_at ? dateTime(month.sealed_at) : "—" }}</td>
              <td>
                {{ month.archived_at ? dateTime(month.archived_at) : "—" }}
              </td>
              <td>
                <button
                  v-if="
                    month.state !== 'ARCHIVED' && eligible(month.month_start)
                  "
                  class="text-button"
                  :disabled="
                    busy ||
                    !data.archiveConfigured ||
                    data.runs.some(
                      (run) =>
                        run.month_start === month.month_start &&
                        ['QUEUED', 'RUNNING'].includes(run.state),
                    )
                  "
                  @click="archive(month.month_start)"
                >
                  封存并归档</button
                ><span v-else class="muted">{{
                  month.state === "ARCHIVED" ? "保留在线" : "热区保留"
                }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="list-footer">
        <div class="list-pagination">
          <span class="list-total">共 {{ data.months.length }} 个月份分区</span>
        </div>
      </footer>
    </section>
    <section class="panel management-panel archive-runs">
      <header class="list-toolbar">
        <h2>最近归档任务</h2>
        <span>仅双副本核验成功标记完成</span>
      </header>
      <div v-if="!data.runs.length" class="storage-empty">暂无归档任务</div>
      <div v-else class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>月份</th>
              <th>状态</th>
              <th>压缩容量</th>
              <th>摘要 / 结果</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="run in data.runs" :key="run.id">
              <td>{{ run.month_start.slice(0, 7) }}</td>
              <td>{{ labels[run.state] }}</td>
              <td>{{ bytes(run.byte_size) }}</td>
              <td>
                <code v-if="run.sha256" :title="run.sha256"
                  >{{ run.sha256.slice(0, 16) }}…</code
                ><span v-else>{{
                  run.error_code
                    ? "任务未完成，请保留在线数据并核对归档配置"
                    : "—"
                }}</span>
              </td>
            </tr>
          </tbody>
        </table>
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
          <span class="list-total">显示 {{ data.runs.length }} 项最近任务</span>
        </div>
      </footer>
    </section>
  </template>
  <footer v-else class="list-footer">
    <button
      type="button"
      class="secondary list-refresh"
      :disabled="busy"
      @click="load()"
    >
      刷新列表
    </button>
  </footer>
</template>
<style scoped>
.storage-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}
.storage-metrics article {
  padding: 18px 20px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.storage-metrics span,
.storage-metrics small,
.storage-note {
  color: var(--muted);
}
.storage-metrics strong {
  font-size: 25px;
  font-weight: 650;
}
.storage-note {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin: 18px 0;
  font-size: 13px;
}
.list-toolbar h2 {
  margin: 0;
  font-size: 15px;
}
.list-toolbar span {
  font-size: 13px;
  color: var(--muted);
}
.archive-runs {
  margin-top: 20px;
}
.storage-empty {
  padding: 32px;
  text-align: center;
  color: var(--muted);
}
th,
td {
  padding: 12px 20px;
}
.actions {
  display: flex;
  gap: 10px;
}
</style>
