<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { api, dateTime, mutate, query } from "./api";
import EmptyState from "./EmptyState.vue";

/** 具名员工候选及服务端返回的当前行版本。 */
interface Candidate { id: string; name: string; employeeNo: string; departmentName: string; status: string }
interface Preview { user: Candidate & { rowVersion: number }; impact: Record<string, number> }
/** 离职时的人员资料与分页列表共用字段。 */
interface ArchiveSummary {
  id: string;
  employeeName: string;
  employeeNo: string;
  departmentName: string;
  departedAt: string;
  reason: string;
}
interface Archive extends ArchiveSummary {
  impact: Record<string, number>;
  recordCounts: { recordType: string; count: number }[];
}
interface ArchiveItem {
  recordType: string;
  sourceId: string;
  occurredAt: string;
  snapshot: Record<string, string | number | null>;
}

const askPassword = inject<(title: string, options: { heading: string; confirmLabel: string; danger: boolean }) => Promise<string | null>>("askPassword")!;
const inform = inject<(message: string) => void>("inform")!;
const tab = ref<"process" | "history">("process");
const keyword = ref("");
const searched = ref("");
const candidates = ref<Candidate[]>([]);
const archived = ref<ArchiveSummary[]>([]);
const listPage = ref(1);
const listNext = ref(false);
const preview = ref<Preview | null>(null);
const receipt = ref<Archive | null>(null);
const archiveItems = ref<ArchiveItem[]>([]);
const recordPage = ref(1);
const recordType = ref("");
const recordNext = ref(false);
const reason = ref("");
const confirmed = ref(false);
const busy = ref(false);
const error = ref("");
const pendingHandover = computed(() => preview.value &&
  (preview.value.impact.pendingApprovals || preview.value.impact.outstandingLoans || preview.value.impact.openTodos));

const impactLabels: Record<string, string> = {
  roles: "角色任职", personalPermissions: "个人权限", archiveGrants: "档案授权",
  driveGrants: "公盘授权", duties: "部门及公司职责", pendingApprovals: "待接手的审批节点",
  outstandingLoans: "未归还的实物", openTodos: "未办结的个人待办",
};
const recordLabels: Record<string, string> = {
  AUDIT: "操作记录", INTERACTION: "界面操作", ROLE: "角色任职", PERSONAL_PERMISSION: "个人权限",
  DEPARTMENT_DUTY: "部门审批职责", COMPANY_DUTY: "公司审批职责", TRANSFER: "转岗记录",
  ARCHIVE_GRANT: "档案授权", DRIVE_GRANT: "公盘授权", WORKFLOW: "提交的流程", WORKFLOW_STEP: "审批节点",
  APPROVAL: "审批决定", PHYSICAL_LOAN: "实物借还", ARCHIVE_RECORD: "档案登记", DRIVE_VERSION: "公盘文件版本",
  MEETING: "发起的会议", MEETING_INVITATION: "参会记录", TODO: "待办记录", FEEDBACK: "意见反馈",
  FEEDBACK_REPLY: "反馈回复", ANNOUNCEMENT: "公告发布", ANNOUNCEMENT_READ: "公告阅读",
};
const fieldLabels: Record<string, string> = {
  actorId: "操作人编号", module: "模块", resourceType: "对象类型", resourceId: "对象编号",
  action: "动作", result: "结果", requestId: "请求编号", severity: "严重度", page: "页面", control: "操作控件",
  role: "角色", departmentId: "部门编号", state: "状态", endedAt: "截止时间", permission: "权限代码",
  effect: "权限效果", scopeId: "范围编号", duty: "审批职责", fromDepartmentId: "原部门编号",
  toDepartmentId: "目标部门编号", operatorId: "经办人编号", recordId: "档案编号", businessType: "业务类型",
  businessId: "业务编号", workflowId: "流程编号", stepId: "节点编号", mode: "办理方式", dueAt: "截止日期",
  returnedAt: "归还时间", boxId: "档案盒编号", documentId: "文件编号", version: "版本号", source: "来源",
  kind: "类型", endsAt: "计划结束", cancelledAt: "取消时间", meetingId: "会议编号", reply: "参会回复",
  removedAt: "移除时间", creatorId: "创建人编号", assigneeId: "接收人编号", completedAt: "办结时间",
  category: "分类", feedbackId: "反馈编号", readAt: "阅读时间", announcementId: "公告编号",
};
const recordStates: Record<string, string> = {
  ACTIVE: "有效", ENDED: "已结束", REVOKED: "已撤销", EXPIRED: "已到期",
  PENDING: "待处理", WAITING: "等待中", IN_USE: "借出未还", RETURNED: "已归还",
  OPEN: "未完成", COMPLETED: "已完成", WITHDRAWN: "已撤回",
  APPROVED: "已通过", REJECTED: "已驳回", SUCCEEDED: "成功", DENIED: "拒绝",
  ACCEPTED: "已接受", DECLINED: "已拒绝", FAILED: "失败",
};

/** 保留已提交的搜索条件，列表翻页不受尚未提交的输入影响。 */
async function loadList(page = 1) {
  error.value = "";
  busy.value = true;
  try {
    if (tab.value === "process") {
      const data = await api<{ items: Candidate[] }>(
        "/admin/employee-departures/candidates?" + query({ keyword: searched.value }),
      );
      candidates.value = data.items;
    } else {
      const data = await api<{ items: ArchiveSummary[]; hasNext: boolean }>(
        "/admin/employee-departures?" + query({ keyword: searched.value, page }),
      );
      archived.value = data.items;
      listPage.value = page;
      listNext.value = data.hasNext;
    }
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}

function search() {
  searched.value = keyword.value.trim();
  preview.value = null;
  receipt.value = null;
  candidates.value = [];
  archived.value = [];
  listPage.value = 1;
  listNext.value = false;
  void loadList();
}

function switchTab(next: typeof tab.value) {
  if (tab.value === next) return;
  tab.value = next;
  keyword.value = "";
  search();
}

/** 重新选人后清除上一人的确认和事由。 */
async function selectUser(person: Candidate) {
  busy.value = true;
  error.value = "";
  preview.value = null;
  receipt.value = null;
  reason.value = "";
  confirmed.value = false;
  try {
    preview.value = await api<Preview>(`/admin/users/${person.id}/departure-preview`);
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}

/** 看档案时只读取独立快照，翻页与来源类别均由服务端筛选。 */
async function loadRecords(id: string, page = 1) {
  busy.value = true;
  error.value = "";
  try {
    const result = await api<{ archive: Archive; items: ArchiveItem[]; hasNext: boolean }>(
      `/admin/employee-departures/${id}/records?` + query({ page, recordType: recordType.value }),
    );
    receipt.value = result.archive;
    archiveItems.value = result.items;
    recordPage.value = page;
    recordNext.value = result.hasNext;
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}

function openArchive(id: string) {
  receipt.value = null;
  archiveItems.value = [];
  recordType.value = "";
  void loadRecords(id);
}

/** 关键操作复用本人密码确认、目标绑定、版本与幂等操作号。 */
async function depart() {
  const item = preview.value;
  if (!item || !reason.value.trim() || !confirmed.value) return;
  busy.value = true;
  error.value = "";
  try {
    const password = await askPassword(`确认办理 ${item.user.name}（${item.user.employeeNo}）离职`, {
      heading: "确认员工离职", confirmLabel: "确认离职并撤权", danger: true,
    });
    if (!password) return;
    const result = await mutate<Archive>(
      `/admin/users/${item.user.id}/departure`, "POST", { reason: reason.value.trim() },
      item.user.id, "USER", "EMPLOYEE_DEPARTURE", password, item.user.rowVersion,
    );
    candidates.value = candidates.value.filter((row) => row.id !== item.user.id);
    preview.value = null;
    receipt.value = result;
    inform("离职已生效，账号访问已撤销，历史档案已保存");
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}

async function viewReceipt() {
  const id = receipt.value?.id;
  if (!id) return;
  archiveItems.value = [];
  recordPage.value = 1;
  recordNext.value = false;
  recordType.value = "";
  tab.value = "history";
  keyword.value = receipt.value?.employeeNo ?? "";
  searched.value = keyword.value;
  await loadList();
  await loadRecords(id);
}

function fieldValue(key: string, value: string | number | null) {
  if (value === null) return "—";
  if (typeof value !== "string") return value;
  if (key.endsWith("At")) return dateTime(value);
  return ["state", "result", "reply"].includes(key) ? recordStates[value] || value : value;
}

onMounted(() => void loadList());
</script>

<template>
  <section class="departure">
    <header class="page-heading">
      <div>
        <h1>员工离职</h1>
        <p class="muted">核对人员和未完事项，办理后立即收回账号访问权限，并保存离职档案。</p>
      </div>
    </header>
    <nav class="module-tabs" aria-label="员工离职">
      <button type="button" :class="{ active: tab === 'process' }" :aria-pressed="tab === 'process'" :disabled="busy" @click="switchTab('process')">办理离职</button>
      <button type="button" :class="{ active: tab === 'history' }" :aria-pressed="tab === 'history'" :disabled="busy" @click="switchTab('history')">离职档案</button>
    </nav>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div class="departure-layout">
      <section class="panel departure-picker">
        <h2>{{ tab === "process" ? "查找员工" : "查找离职档案" }}</h2>
        <form @submit.prevent="search">
          <label for="departure-search">姓名或工号</label>
          <div class="departure-search">
            <input id="departure-search" v-model="keyword" maxlength="128" placeholder="输入姓名或工号" />
            <button class="primary" :disabled="busy">查询</button>
          </div>
        </form>
        <p v-if="tab === 'process' && candidates.length === 50" class="muted">显示前 50 位员工，可输入姓名或工号缩小范围。</p>
        <div v-if="tab === 'process'" class="departure-list">
          <button v-for="person in candidates" :key="person.id" type="button" :disabled="busy"
            :aria-pressed="preview?.user.id === person.id" @click="selectUser(person)">
            <span><strong>{{ person.name }}</strong><small>{{ person.departmentName }}</small></span>
            <span class="muted">{{ person.employeeNo }}</span>
          </button>
          <EmptyState v-if="!candidates.length && !busy" icon="people" title="未找到可办理离职的员工" />
        </div>
        <div v-else class="departure-list">
          <button v-for="item in archived" :key="item.id" type="button" :disabled="busy"
            :aria-pressed="receipt?.id === item.id" @click="openArchive(item.id)">
            <span><strong>{{ item.employeeName }}</strong><small>{{ item.departmentName }}</small></span>
            <span class="muted">{{ item.employeeNo }}</span>
          </button>
          <EmptyState v-if="!archived.length && !busy" icon="history" title="未找到离职档案" />
          <div v-if="listPage > 1 || listNext" class="departure-pages">
            <button type="button" class="secondary" :disabled="busy || listPage <= 1" @click="loadList(listPage - 1)">上一页</button>
            <button type="button" class="secondary" :disabled="busy || !listNext" @click="loadList(listPage + 1)">下一页</button>
          </div>
        </div>
      </section>
      <section v-if="tab === 'process'" class="panel departure-detail">
        <h2>核对离职影响</h2>
        <form v-if="preview" class="departure-confirm" @submit.prevent="depart">
          <h3>{{ preview.user.name }} · {{ preview.user.employeeNo }}</h3>
          <p class="muted">{{ preview.user.departmentName }} · {{ preview.user.status === "ACTIVE" ? "在用账号" : "已临时停用" }}</p>
          <dl class="impact-grid">
            <div v-for="(label, key) in impactLabels" :key="key"><dt>{{ label }}</dt><dd>{{ preview.impact[key] ?? 0 }}</dd></div>
          </dl>
          <p v-if="pendingHandover" class="departure-note">待接手审批、未办待办和未归还实物继续保留，请按原业务流程安排后续处理。</p>
          <label for="departure-reason">离职事由</label>
          <textarea id="departure-reason" v-model="reason" :disabled="busy" maxlength="1000" rows="3" required />
          <label class="departure-check">
            <input v-model="confirmed" type="checkbox" :disabled="busy" />
            <span>我已核对员工身份及未完事项，确认立即办理离职。</span>
          </label>
          <button type="submit" class="secondary danger" :disabled="busy || !reason.trim() || !confirmed">确认离职</button>
        </form>
        <div v-else-if="receipt" class="departure-result" role="status">
          <h3>{{ receipt.employeeName }} 已办理离职</h3>
          <p class="muted">{{ dateTime(receipt.departedAt) }} · {{ receipt.employeeNo }}</p>
          <button class="secondary" type="button" :disabled="busy" @click="viewReceipt">查看离职档案</button>
        </div>
        <EmptyState v-else icon="people" title="请选择要办理离职的员工" />
      </section>
      <section v-else class="panel departure-detail">
        <h2>离职档案</h2>
        <template v-if="receipt">
          <h3>{{ receipt.employeeName }} · {{ receipt.employeeNo }}</h3>
          <p class="muted">{{ receipt.departmentName }} · {{ dateTime(receipt.departedAt) }}</p>
          <p class="departure-reason">{{ receipt.reason }}</p>
          <dl class="impact-grid">
            <div v-for="(label, key) in impactLabels" :key="key"><dt>{{ label }}</dt><dd>{{ receipt.impact[key] ?? 0 }}</dd></div>
          </dl>
          <label class="departure-filter">
            历史类别
            <select v-model="recordType" :disabled="busy" @change="loadRecords(receipt.id)">
              <option value="">全部记录</option>
              <option v-for="row in receipt.recordCounts" :key="row.recordType" :value="row.recordType">
                {{ recordLabels[row.recordType] || row.recordType }}（{{ row.count }}）
              </option>
            </select>
          </label>
          <div v-if="archiveItems.length" class="table-scroll">
            <table class="directory-table departure-records">
              <thead><tr><th>记录类型</th><th>原记录时间</th><th>办理前的快照</th></tr></thead>
              <tbody>
                <tr v-for="row in archiveItems" :key="row.recordType + row.sourceId">
                  <td>{{ recordLabels[row.recordType] || row.recordType }}</td>
                  <td>{{ dateTime(row.occurredAt) }}</td>
                  <td>
                    <details>
                      <summary>查看记录</summary>
                      <dl class="record-fields">
                        <dt>原记录编号</dt><dd>{{ row.sourceId }}</dd>
                        <template v-for="(value, key) in row.snapshot" :key="key">
                          <dt>{{ fieldLabels[key] || key }}</dt><dd>{{ fieldValue(key, value) }}</dd>
                        </template>
                      </dl>
                    </details>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <EmptyState v-else-if="!busy" icon="history" title="此类别暂无归档记录" />
          <div v-if="recordPage > 1 || recordNext" class="departure-pages">
            <button class="secondary" type="button" :disabled="busy || recordPage <= 1" @click="loadRecords(receipt.id, recordPage - 1)">上一页</button>
            <span>第 {{ recordPage }} 页</span>
            <button class="secondary" type="button" :disabled="busy || !recordNext" @click="loadRecords(receipt.id, recordPage + 1)">下一页</button>
          </div>
        </template>
        <EmptyState v-else icon="history" title="请选择一份离职档案" />
      </section>
    </div>
  </section>
</template>

<style scoped>
.departure-layout {
  display: grid;
  grid-template-columns: minmax(230px, 320px) minmax(0, 1fr);
  align-items: start;
  gap: 16px;
  margin-top: 18px;
}
.departure-layout > .panel { min-width: 0; padding: 22px; }
.departure-layout h2 { margin: 0 0 16px; }
.departure-layout h3 { margin: 8px 0 4px; }
.departure-search {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  margin-top: 7px;
}
.departure-search input { width: 100%; }
.departure-list {
  max-height: min(55dvh, 560px);
  overflow: auto;
  margin-top: 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius-control);
}
.departure-list > button {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  text-align: left;
  overflow-wrap: anywhere;
  width: 100%;
  padding: 10px 12px;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: transparent;
  color: var(--ink);
}
.departure-list > button small { display: block; color: var(--text-secondary); margin-top: 3px; }
.departure-list > button:is(:hover, [aria-pressed="true"]) { background: var(--brand-soft); color: var(--brand); }
.departure-list > button:focus-visible { outline-offset: -2px; }
.impact-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 126px), 1fr));
  gap: 8px;
  margin: 14px 0;
}
.impact-grid > div {
  padding: 10px 12px;
  border-radius: var(--radius-control);
  background: var(--surface-muted);
}
.impact-grid dt { color: var(--text-secondary); font-size: .87rem; }
.impact-grid dd { margin: 4px 0 0; font-weight: 700; font-variant-numeric: tabular-nums; }
.departure-note {
  background: var(--warning-soft);
  color: var(--warning);
  padding: 10px 12px;
  border-radius: var(--radius-control);
}
.departure-confirm textarea { display: block; width: 100%; margin: 7px 0 12px; }
.departure label.departure-check {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.departure-check input {
  width: 16px;
  min-height: 0;
  height: 16px;
  flex: none;
  margin: 0;
}
.departure-filter { margin-bottom: 12px; }
.departure-reason { white-space: pre-wrap; overflow-wrap: anywhere; }
.departure-pages { display: flex; align-items: center; justify-content: end; gap: 8px; padding: 10px; }
.departure-records th:last-child { width: 49%; }
.departure-records summary { cursor: pointer; color: var(--brand); }
.record-fields { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 5px 10px; }
.record-fields dt { color: var(--text-secondary); }
.record-fields dd { margin: 0; overflow-wrap: anywhere; }
@media (max-width: 1050px) {
  .departure-layout { grid-template-columns: minmax(190px, 0.42fr) minmax(0, 1fr); }
}
@media (max-width: 760px) {
  .departure-layout { grid-template-columns: minmax(0, 1fr); }
}
</style>
