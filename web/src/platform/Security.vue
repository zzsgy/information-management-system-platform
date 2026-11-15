<script setup lang="ts">
import ListFilters from "./ListFilters.vue";
import { recentWeekFilters } from "./listDates";
import { inject, onMounted, ref } from "vue";
import { api, COLLECTION, dateTime, mutate, query } from "./api";
/** 仅平台 IT 管理员可从安全管理跳转到操作记录。 */
defineProps<{ showAuditLink: boolean }>();
/** 队列只展示定位信息，不带档案名称和消息秘密。 */
interface Alert {
  id: string;
  userName: string;
  kind: string;
  state: string;
  observedCount: number;
  thresholdCount: number;
  createdAt: string;
  blockedUntil: string | null;
  messageState: string | null;
  rowVersion: number;
}
interface Retention {
  interactionYears: number;
  businessYears: number;
  rowVersion: number;
}
const rows = ref<Alert[]>([]),
  page = ref(1),
  total = ref(0),
  listFilters = ref({ ...recentWeekFilters("created"), state: "OPEN" }),
  error = ref(""),
  busy = ref(false),
  selected = ref<Alert | null>(null),
  reason = ref(""),
  release = ref(false),
  disposition = ref("REVIEWING");
const retention = ref<Retention>({
  interactionYears: 3,
  businessYears: 3,
  rowVersion: 1,
});
const askPassword =
  inject<(title: string) => Promise<string | null>>("askPassword")!;
const names: Record<string, string> = {
  AUDIT_CAPACITY: "日志容量预警",
  OPS_PROTECTION: "保护检查异常",
  OPS_REPLICA: "副本校验异常",
  OPS_BACKUP: "独立备份失败",
  OPS_RESTORE: "恢复演练失败",
  OPS_TASK: "维护结果待核对",
  LOGIN_LOCKS: "账号重复锁定",
  ACCESS_DENIED: "连续越权访问",
  BULK_DOWNLOAD: "批量下载",
  BULK_PRINT: "批量打印",
};
const delivery: Record<string, string> = {
  PENDING: "等待投递",
  RUNNING: "正在投递",
  RETRY: "等待重试",
  MANUAL: "需要核对",
  SENT: "通道已接受",
  CANCELLED: "已过期",
};
/** 告警分页与当前保留规则来自服务端。 */
async function load(reset = false) {
  if (reset) page.value = 1;
  busy.value = true;
  error.value = "";
  try {
    const data = await api<{
      items: Alert[];
      total: number;
      retention: Retention;
    }>(`/admin/security?${query({ page: page.value, ...listFilters.value })}`);
    rows.value = data.items;
    total.value = data.total;
    retention.value = data.retention;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
const history = ref<
  Array<{
    id: string;
    operatorName: string;
    state: string;
    reason: string;
    released: boolean;
    occurredAt: string;
  }>
>([]);
async function choose(row: Alert) {
  selected.value = row;
  reason.value = "";
  release.value = false;
  disposition.value = "REVIEWING";
  history.value = [];
  try {
    history.value = await api(`/admin/security/${row.id}/history`);
  } catch (e) {
    error.value = (e as Error).message;
  }
}
/** 处置与解除限流使用本人再验证、幂等号及并发版本。 */
async function dispose() {
  if (!selected.value || busy.value) return;
  const password = await askPassword("确认安全告警处置");
  if (!password) return;
  busy.value = true;
  try {
    const row = selected.value;
    await mutate(
      `/admin/security/${row.id}/disposition`,
      "POST",
      {
        state: disposition.value,
        reason: reason.value,
        release: release.value,
      },
      row.id,
      "SECURITY_ALERT",
      "DISPOSE_SECURITY",
      password,
      row.rowVersion,
    );
    selected.value = null;
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function saveRetention() {
  const password = await askPassword("确认延长日志保留期");
  if (!password) return;
  busy.value = true;
  try {
    await mutate(
      "/admin/audit-retention",
      "PUT",
      {
        interactionYears: retention.value.interactionYears,
        businessYears: retention.value.businessYears,
      },
      COLLECTION,
      "AUDIT_RETENTION",
      "EXTEND_RETENTION",
      password,
      retention.value.rowVersion,
    );
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
onMounted(() => void load());
</script>
<template>
  <section class="page-heading">
    <div>
      <span class="eyebrow">安全管理</span>
      <h1>告警与日志保留</h1>
      <p>核对异常操作，记录处置过程，按需恢复受限动作。</p>
    </div>
    <a v-if="showAuditLink" class="secondary" href="#/audit">查看操作记录</a>
  </section>
  <p v-if="error" role="alert" class="form-error">{{ error }}</p>
  <div class="security-rules">
    <article class="panel">
      <strong>登录保护</strong>
      <p>15分钟内连续5次密码错误，暂停15分钟；1小时内3次锁定触发告警。</p>
    </article>
    <article class="panel">
      <strong>行为保护</strong>
      <p>
        10分钟内10次访问被拒、50个不同文件下载或30个不同文件打印受理，告警并限流10分钟。
      </p>
    </article>
    <article class="panel">
      <strong>通知去重</strong>
      <p>同一账号、同一类型每小时最多一条通知。批准的完整批次清单单独识别。</p>
    </article>
  </div>
  <section class="panel management-panel">
    <ListFilters
      :fields="[
        {
          key: 'state',
          label: '处理状态',
          options: { OPEN: '待核对', REVIEWING: '处理中', RESOLVED: '已完成' },
        },
        { key: 'employee', label: '员工', placeholder: '姓名或工号' },
        { key: 'kind', label: '告警类型', options: names },
        { key: 'notificationState', label: '通知状态', options: delivery },
        { key: 'created', label: '发生日期', type: 'date' },
      ]"
      :defaults="{ state: 'OPEN' }"
      :values="listFilters"
      :busy="busy"
      @apply="
        listFilters = { ...$event, state: $event.state || 'ALL' };
        selected = null;
        load(true);
      "
    />
    <div class="table-scroll">
      <table>
        <thead>
          <tr>
            <th>发生时间</th>
            <th>员工</th>
            <th>告警类型</th>
            <th>触发数量</th>
            <th>限流至</th>
            <th>通知状态</th>
            <th>处理</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td>{{ dateTime(row.createdAt) }}</td>
            <td>{{ row.userName }}</td>
            <td>{{ names[row.kind] || row.kind }}</td>
            <td>{{ row.observedCount }} / {{ row.thresholdCount }}</td>
            <td>{{ row.blockedUntil ? dateTime(row.blockedUntil) : "—" }}</td>
            <td>{{ delivery[row.messageState || ""] || "未配置" }}</td>
            <td>
              <button class="secondary" @click="choose(row)">核对处置</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-if="!rows.length" class="empty-state">
      <h3>当前没有此状态的告警</h3>
      <p>异常事件触发后会自动进入队列。</p>
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
  </section>
  <form v-if="selected" class="panel security-form" @submit.prevent="dispose">
    <h2>处置 · {{ selected.userName }} · {{ names[selected.kind] }}</h2>
    <div v-for="item in history" :key="item.id" class="security-history">
      <strong>{{ item.operatorName }} · {{ dateTime(item.occurredAt) }}</strong>
      <p>{{ item.reason }}</p>
      <small>{{ item.released ? "已解除限流" : "保留原限制" }}</small>
    </div>
    <label
      >核对结果<select v-model="disposition">
        <option value="REVIEWING">继续处理</option>
        <option value="RESOLVED">处理完成</option>
      </select></label
    ><label
      >处理说明<textarea
        v-model="reason"
        maxlength="1000"
        required
        rows="3"
        placeholder="记录依据与结论，请勿粘贴密码或档案正文"
      ></textarea></label
    ><label class="security-check"
      ><input v-model="release" type="checkbox" />
      已核对，立即解除本次动作限流</label
    >
    <div>
      <button class="primary" :disabled="busy || selected.state === 'RESOLVED'">
        保存处置</button
      ><button class="secondary" type="button" @click="selected = null">
        取消
      </button>
    </div>
  </form>
  <section class="panel security-form">
    <h2>日志保留</h2>
    <p>
      年限只能延长。档案日志及历史证据随档案持续保留；当前阶段不执行日志删除。
    </p>
    <form class="toolbar" @submit.prevent="saveRetention">
      <label
        >界面操作（年）<input
          v-model.number="retention.interactionYears"
          type="number"
          min="3"
          max="100"
          required /></label
      ><label
        >平台与公盘关键日志（年）<input
          v-model.number="retention.businessYears"
          type="number"
          min="3"
          max="100"
          required /></label
      ><button class="primary" :disabled="busy">保存保留策略</button>
    </form>
    <p>
      通知投递状态和人工重试可在
      <a href="#/message-service">消息服务</a> 中核对。
    </p>
  </section>
</template>
<style scoped>
form.toolbar {
  align-items: flex-end;
}
.security-rules {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  margin-bottom: 22px;
}
.security-rules article {
  padding: 22px;
}
.security-rules p,
.security-form > p {
  color: var(--text-secondary);
  line-height: 1.7;
}
.security-form {
  padding: 26px;
  margin-top: 22px;
}
.security-form label {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 16px;
}
.security-form textarea {
  width: 100%;
  box-sizing: border-box;
}
.security-form .security-check {
  flex-direction: row;
  align-items: center;
}
.security-form button + button {
  margin-left: 12px;
}
.security-form .toolbar label {
  margin-bottom: 0;
}
</style>
