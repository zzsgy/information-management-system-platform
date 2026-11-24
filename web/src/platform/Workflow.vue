<script setup lang="ts">
import ListFilters from "./ListFilters.vue";
import { recentWeekFilters } from "./listDates";
import { computed, inject, nextTick, onMounted, ref } from "vue";
import { api, can, dateTime, mutate, profile, query } from "./api";
import { archiveStates, classifications } from "./archiveFiles";
import CatalogChangeReview, {
  type CatalogChange,
} from "./CatalogChangeReview.vue";
import ArchiveReader from "./ArchiveReader.vue";
import PlatformIcon from "../preview/PlatformIcon.vue";
import EvidenceSubmission from "./EvidenceSubmission.vue";
import Notifications from "./Notifications.vue";
import Physical from "./Physical.vue";

/** 页面只展示必要节点与业务摘要，审批凭据和原件始终另行鉴权。 */
interface Task {
  /** 节点标识。 */ id: string;
  /** 必要职责。 */ dutyCode: string;
  /** 记录版本。 */ rowVersion: number;
  /** 业务类型。 */ businessType: string;
  /** 本人回避。 */ requiresReassignment?: boolean;
  /** 当前办理人。 */ assigneeName?: string;
  /** 申请人。 */ applicantName?: string;
  /** 提醒时刻。 */ dueAt?: string;
}
interface Request {
  /** 电子和纸质采用各自的交付及结束语义。 */ form?: string;
  /** 父批次。 */ id: string;
  /** 实际用途。 */ purpose: string;
  /** 状态。 */ state: string;
  /** 不同文件数。 */ itemCount: number;
  /** 整批最高密级。 */ parentMaxClassification: string;
  /** 送审时刻。 */ submittedAt: string;
  /** 原续借批次，空值表示初次借阅。 */ renewalOfBatchId?: string | null;
  /** 实际续借原因。 */ renewalReason?: string | null;
  /** 送审期限规则。 */ requestedPeriod?: { mode: string; amount: number };
  /** 末级审批时开始占用并按批准时刻起算的纸质批次。 */ paperHandoffs?: boolean;
}
interface Batch {
  /** 当前本人可续借，提交仍重验原来源。 */ canRenew: boolean;
  /** 本批次关联的续借历史。 */ renewals: {
    id: string;
    state: string;
    submittedAt: string;
  }[];
  /** 原父批信息。 */ batch: Request;
  /** 范围内部门子单。 */ requests: {
    id: string;
    state: string;
    rowVersion: number;
    workflowInstanceId: string;
    /** 本人当前可提交凭据的节点；服务端还会核对职责。 */ currentStepId?:
      | string
      | null;
    approvedUntil: string | null;
    /** 实时使用状态与审批状态分开。 */ usageState: string;
  }[];
  /** 原版本固定清单。 */ items: {
    recordId: string;
    label: string;
    /** 明确批准动作及打印范围，不能仅凭题名批准正文使用。 */ action?: string;
    printPages?: number[];
    copies?: number;
    classification: string;
    requestId: string;
    /** 续借保留原批准截止，不由申请表改写。 */ previousUntil?: string;
  }[];
}
/** 原申请的独立动作名称，不从查看权推导其他动作。 */
const actionLabels: Record<string, string> = {
  VIEW: "查看",
  DOWNLOAD: "下载",
  PRINT: "打印",
  BORROW_PHYSICAL: "纸质借阅",
};
interface Snapshot {
  /** 整盒归档审批固定的每份清单候选。 */
  electronicMembers?: {
    id: string;
    title: string;
    revisionId: string;
    pdfVersionId: string;
  }[];
  /** 送审题名。 */ title?: string;
  /** 原等级。 */ classification?: string;
  /** 对象类型。 */ objectType?: string;
  /** 质量证明。 */ qualityEvidenceRef?: string;
  /** 候选PDF。 */ pdfVersionId?: string;
  /** 授权原因。 */ reason?: string;
  /** 本部门一般基础阅读。 */ allowOwnerDepartmentView?: boolean;
  /** 原批准最迟时刻。 */ validUntil?: string | null;
  /** 安全题名。 */ safeTitle?: string | null;
  /** 安全检索策略。 */ titleVisibility?: string;
  /** 独立文件号。 */ fileNo?: string;
  /** 申请生效时刻。 */ validFrom?: string;
}
interface Detail {
  /** 本人已提交的在线决定。 */
  ownDecision: {
    result: string;
    comment: string;
    recordedAt: string;
    operatorName: string;
  } | null;
  /** 经节点权限校验后的申请人摘要；部门和时间沿用原送审记录。 */
  applicant: {
    id: string;
    name: string;
    employeeNo: string;
    departmentId: string;
    departmentName: string;
    submittedAt: string;
  };
  /** 节点当前状态。 */ step: {
    id: string;
    state: string;
    rowVersion: number;
    assigneeId: string;
    dutyCode: string;
  };
  /** 固定流程。 */ workflow: {
    id: string;
    businessType: string;
    state: string;
    applicantId: string;
  };
  /** 本次必要业务摘要。 */ business: {
    /** 固定目录前后值，不含原件内容。 */
    change?: CatalogChange;
    /** 旧更正事项因经办权限失效时仍可驳回，但不能批准。 */
    approvalBlockReason?: string | null;
    snapshot?: Snapshot;
    detail?: Batch;
    /** 纸质归还审批只包含交接说明及原已获权题名。 */
    purpose?: string;
    uncollected?: boolean;
    /** 本次电子使用申请是否要求无水印版本。 */
    unwatermarked?: boolean;
    items?: { recordId: string; label: string }[];
    audiences?: { id: string; name: string }[];
    users?: { id: string; name: string }[];
  };
}
interface Grant {
  /** 精确来源标识。 */ id: string;
  /** 文件号。 */ fileNo: string;
  /** 来源种类。 */ sourceType: string;
  /** 当前状态。 */ state: string;
  /** 实际有效性。 */ effective: boolean;
  /** 原批准截止。 */ validUntil: string;
  /** 授权并发版本。 */ policyVersion: number;
}
/** 基础开放策略独立于具体人员名单，撤销后不删除其他借阅来源。 */
interface Policy {
  /** 策略标识。 */ id: string;
  /** 文件号。 */ fileNo: string;
  /** 版本。 */ policyVersion: number;
  /** 状态。 */ state: string;
  /** 实际有效。 */ effective: boolean;
  /** 安全题名策略。 */ titleVisibility: string;
  /** 本部门基础阅读。 */ allowOwnerDepartmentView: boolean;
  /** 截止时刻。 */ validUntil: string | null;
}
/** 管理确认复用全局密码对话框，口令仅在本次操作使用。 */
const askPassword =
    inject<(title: string) => Promise<string | null>>("askPassword")!,
  inform = inject<(message: string) => void>("inform")!;
const initialQuery = new URLSearchParams(location.hash.split("?")[1] || "");
const initialTab = initialQuery.get("tab");
const canUsePhysical = computed(
  () =>
    can("ARCHIVE.ACCESS") &&
    (can("ARCHIVE.PHYSICAL_BORROW") || can("ARCHIVE.PHYSICAL_MANAGE") || initialQuery.has("loan")),
);
const tab = ref(
    canUsePhysical.value &&
      (initialTab === "physical" ||
        location.hash.split("?")[0] === "#/physical")
      ? "physical"
      : can("ARCHIVE.ACCESS") && initialTab === "requests"
        ? "requests"
        : can("ARCHIVE.ACCESS") && !can("ARCHIVE.IT_SHARED_BORROW")
          ? "tasks"
          : "progress",
  ),
  tasks = ref<Task[]>([]),
  requests = ref<Request[]>([]),
  attention = ref<Task[]>([]),
  progress = ref<
    {
      id: string;
      businessType: string;
      state: string;
      createdAt: string;
      dutyCode: string | null;
    }[]
  >([]),
  grants = ref<Grant[]>([]),
  policies = ref<Policy[]>([]),
  grantKind = ref("people"),
  page = ref(1),
  total = ref(0),
  error = ref(""),
  busy = ref(false),
  loading = ref(false);
const dialog = ref<HTMLDialogElement | null>(null),
  batchDialog = ref<HTMLDialogElement | null>(null),
  reassignDialog = ref<HTMLDialogElement | null>(null),
  revokeDialog = ref<HTMLDialogElement | null>(null),
  detail = ref<Detail | null>(null),
  batch = ref<Batch | null>(null),
  comment = ref(""),
  decision = ref("APPROVE"),
  reader = ref<InstanceType<typeof ArchiveReader> | null>(null);
/** 续借只复用原清单，表单网络重试期间保持同一操作号。 */
const renewalDialog = ref<HTMLDialogElement | null>(null),
  renewalPurpose = ref(""),
  renewalReason = ref(""),
  renewalMode = ref("WORKDAYS"),
  renewalDays = ref(3),
  renewalOperation = ref("");
const usageNames: Record<string, string> = {
  WAITING_DELIVERY: "批准后待实际交付",
  PHYSICAL_IN_USE: "原件借出中",
  ACTIVE: "当前有效",
  EXPIRED: "已到期",
  UNAVAILABLE: "当前不可用",
  UNAPPROVED: "尚未批准",
};
/** 撤权只针对用户明确选择的某一来源，不批量取消其他批准。 */
const subjectKeyword = ref(""),
  subjects = ref<{ id: string; name: string; employeeNo: string }[]>([]),
  subject = ref(""),
  revoking = ref<{
    id: string;
    fileNo: string;
    policyVersion: number;
    kind: "GRANT" | "POLICY";
  } | null>(null),
  revokeReason = ref("");
const assignmentTarget = ref<Task | null>(null),
  assignmentOptions = ref<{ id: string; name: string }[]>([]),
  newAssignee = ref(""),
  assignmentReason = ref(""),
  assignmentProof = ref("");
const admin = computed(
    () => can("PLATFORM.DEPARTMENTS_MANAGE") || can("ARCHIVE.CONFIG_MANAGE"),
  ),
  security = computed(() => can("ARCHIVE.GRANTS_MANAGE"));
const businessNames: Record<string, string> = {
  PHYSICAL_RETURN: "纸质归还审批",
  CATALOG_CHANGE: "目录更正与撤下复核",
  BORROW: "借阅审批",
  REGISTRATION: "归档复核",
  ACCESS_POLICY: "基础开放",
};
const dutyNames: Record<string, string> = {
  ARCHIVIST: "部门档案员",
  DEPARTMENT_MANAGER: "部门负责人",
  GENERAL_MANAGER: "总经理",
  SUPERVISING_LEADER: "所属部门分管领导",
  ARCHIVE_ADMIN: "档案超级管理员",
};
const actionable = computed(
  () =>
    detail.value?.step.state === "PENDING" &&
    detail.value.workflow.state === "PENDING" &&
    detail.value.step.assigneeId === profile.value?.user.id &&
    detail.value.workflow.applicantId !== profile.value?.user.id,
);
/** 任务列表在服务端按当前任职过滤，页面不显示失效职责的批准按钮。 */
async function load() {
  error.value = "";
  loading.value = true;
  try {
    if (tab.value === "tasks") {
      const data = await api<{ items: Task[]; total: number }>(
        `/portal/tasks?${query({ page: page.value, pageSize: 20, ...listFilters.value })}`,
      );
      tasks.value = data.items;
      total.value = data.total;
    } else if (tab.value === "requests") {
      const data = await api<{ items: Request[]; total: number }>(
        `/portal/requests?${query({ page: page.value, pageSize: 20, ...listFilters.value })}`,
      );
      requests.value = data.items;
      total.value = data.total;
    } else if (tab.value === "progress") {
      const data = await api<{ items: typeof progress.value; total: number }>(
        `/workflow/progress?${query({ page: page.value, pageSize: 20, ...listFilters.value })}`,
      );
      progress.value = data.items;
      total.value = data.total;
    } else if (tab.value === "attention") {
      attention.value = (
        await api<{ items: Task[] }>("/workflow/attention")
      ).items;
      total.value = attention.value.length;
    } else if (tab.value === "grants") {
      if (grantKind.value === "policies" && security.value)
        await loadPolicies();
      else await loadGrants();
    }
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    loading.value = false;
  }
}
/** 切换业务列表时重置页码，不沿用另一列表的分页水位。 */
function switchTab(value: string) {
  tab.value = value;
  listFilters.value = defaultListFilters(value);
  page.value = 1;
  void load();
}
/** 打开前重新取得当前节点和冻结业务范围，避免缓存待办冒充当前决定。 */
async function openTask(task: Pick<Task, "id">) {
  error.value = "";
  try {
    detail.value = await api<Detail>(`/workflow/steps/${task.id}`);
    comment.value = "";
    decision.value = detail.value.business.approvalBlockReason ? "" : "APPROVE";
    // 先挂载审批内容，原生弹窗才可将焦点放到实际控件上。
    await nextTick();
    dialog.value?.showModal();
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
/** 本人再验证后提交带版本的唯一决定；失败保留输入供核对。 */
async function decide() {
  if (!detail.value || !actionable.value || !decision.value) return;
  const selected = detail.value;
  const password = await askPassword(
    decision.value === "APPROVE"
      ? "对当前固定节点作出批准决定"
      : "驳回当前申请并保留意见",
  );
  if (password === null) return;
  busy.value = true;
  try {
    await mutate(
      `/workflow/steps/${selected.step.id}/decisions`,
      "POST",
      { result: decision.value, comment: comment.value },
      selected.step.id,
      "WORKFLOW_STEP",
      "DECIDE_STEP",
      password,
      selected.step.rowVersion,
    );
    dialog.value?.close();
    inform("审批结果已提交，业务按实际节点进度办理。");
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 获取本人原批次进度，审批者仅能看到获准部门子单。 */
async function openRequest(request: Pick<Request, "id">) {
  try {
    batch.value = await api<Batch>(`/archive/borrow-batches/${request.id}`);
    // 先渲染弹窗内容，让原生对话框能将焦点交给关闭按钮。
    await nextTick();
    if (!batchDialog.value?.open) batchDialog.value?.showModal();
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
/** 续借只填写用途、原因和期限，客户端不能改原清单、动作或路线。 */
function openRenewal() {
  if (!batch.value?.canRenew) return;
  renewalPurpose.value = batch.value.batch.purpose;
  renewalReason.value = "";
  renewalDays.value = 3;
  renewalMode.value = "WORKDAYS";
  renewalOperation.value = crypto.randomUUID();
  error.value = "";
  renewalDialog.value?.showModal();
}
/** 等待审批不延权；网络重试保持同一操作号并重读原结果。 */
async function submitRenewal() {
  if (!batch.value) return;
  busy.value = true;
  error.value = "";
  try {
    const result = await api<{ id: string }>(
      `/archive/borrow-batches/${batch.value.batch.id}/renewals`,
      {
        method: "POST",
        headers: { "X-Operation-Id": renewalOperation.value },
        body: JSON.stringify({
          purpose: renewalPurpose.value,
          reason: renewalReason.value,
          period: { mode: renewalMode.value, amount: renewalDays.value },
        }),
      },
    );
    renewalDialog.value?.close();
    batchDialog.value?.close();
    inform("续借已送审，原授权仍按原截止时间失效。");
    await openRequest(result);
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 撤回仅针对仍待审的子单，不能将已批准授权当作撤回处理。 */
async function withdraw(request: Batch["requests"][number]) {
  try {
    await api(`/archive/borrow-requests/${request.id}/withdraw`, {
      method: "POST",
      headers: {
        "X-Operation-Id": crypto.randomUUID(),
        "X-Expected-Version": String(request.rowVersion),
      },
      body: JSON.stringify({ reason: "本人撤回尚未完成的电子查看申请" }),
    });
    batchDialog.value?.close();
    inform("该部门子单已撤回，其他子单状态保持独立。");
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
/** 复核人检查候选也通过逐页水印接口，批准节点不会自动获得档案原件。 */
/** 复核检查使用本流程候选上下文，不能将候选作为正式借阅版本。 */
async function inspectMember(member: { title: string; pdfVersionId: string }) {
  if (!detail.value) return;
  const context = detail.value.workflow.id;
  dialog.value?.close();
  await reader.value?.open(
    {
      reviewContext: {
        type: "REGISTRATION",
        id: context,
        candidatePdfVersionId: member.pdfVersionId,
      },
    },
    member.title,
  );
}
async function inspect() {
  const current = detail.value;
  if (!current?.business.snapshot?.pdfVersionId) return;
  dialog.value?.close();
  await reader.value?.open(
    {
      reviewContext: {
        type:
          current.workflow.businessType === "CATALOG_CHANGE"
            ? "CATALOG_CHANGE"
            : "REGISTRATION",
        id: current.business.change?.id || current.workflow.id,
        candidatePdfVersionId: current.business.snapshot.pdfVersionId,
      },
    },
    current.business.snapshot.title || "待复核候选",
  );
}
/** 安全管理选择器只返回已有授权对象的员工最小资料。 */
/** 安全管理员按姓名或工号检索最小必要获权人员摘要。 */
async function findSubjects() {
  try {
    subjects.value = (
      await api<{ items: typeof subjects.value }>(
        `/admin/archive/grant-subjects?${query({ keyword: subjectKeyword.value })}`,
      )
    ).items;
    if (!subjects.value.some((value) => value.id === subject.value))
      subject.value = subjects.value[0]?.id || "";
    await loadGrants();
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
/** 分页读取本人或明确选定人员的批准来源及实际有效性。 */
async function loadGrants() {
  const data = await api<{ items: Grant[]; total: number }>(
    `/archive/access-grants?${query({ userId: security.value ? subject.value || undefined : undefined, page: page.value, pageSize: 20, ...grantFilters.value })}`,
  );
  grants.value = data.items;
  total.value = data.total;
}
/** 固定选中人员来源的并发版本，避免撤错另一份批准。 */
function openRevoke(grant: Grant) {
  revoking.value = { ...grant, kind: "GRANT" };
  revokeReason.value = "";
  revokeDialog.value?.showModal();
}
/** 基础策略和人员来源分开选择，避免把一种撤销误当作全文件封锁。 */
async function loadPolicies() {
  const data = await api<{ items: Policy[]; total: number }>(
    `/admin/archive/access-policies?${query({ page: page.value, pageSize: 20, ...grantFilters.value })}`,
  );
  policies.value = data.items;
  total.value = data.total;
}
/** 基础开放策略使用独立撤销动作，保留其他借阅来源。 */
function openPolicyRevoke(policy: Policy) {
  revoking.value = { ...policy, kind: "POLICY" };
  revokeReason.value = "";
  revokeDialog.value?.showModal();
}
/** 记录实际原因并由本人确认；服务端负责失效旧授权与阅读会话。 */
async function revoke() {
  const grant = revoking.value;
  if (!grant) return;
  const password = await askPassword(
    "只撤销选定批准来源，并立即终止该员工旧阅读会话",
  );
  if (password === null) return;
  busy.value = true;
  try {
    await mutate(
      `/admin/archive/${grant.kind === "POLICY" ? "access-policies" : "access-grants"}/${grant.id}/revoke`,
      "POST",
      { reason: revokeReason.value },
      grant.id,
      grant.kind === "POLICY" ? "ARCHIVE_POLICY" : "ARCHIVE_GRANT",
      grant.kind === "POLICY"
        ? "REVOKE_ARCHIVE_POLICY"
        : "REVOKE_ARCHIVE_GRANT",
      password,
      grant.policyVersion,
    );
    revokeDialog.value?.close();
    inform("选定来源已撤销，其他批准仍按各自范围核验。");
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 仅取得当前必要职责的合法非申请人候选，不开放任意人员指定。 */
async function openAssignment(task: Task) {
  try {
    assignmentTarget.value = task;
    assignmentOptions.value = (
      await api<{ items: typeof assignmentOptions.value }>(
        `/workflow/steps/${task.id}/assignment-options`,
      )
    ).items;
    newAssignee.value = assignmentOptions.value[0]?.id || "";
    assignmentReason.value = "";
    assignmentProof.value = "";
    reassignDialog.value?.showModal();
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
/** 改派保留必要职责、原节点和历史决定，不跳过审批环节。 */
async function reassign() {
  const task = assignmentTarget.value;
  if (!task) return;
  const password = await askPassword("将必要节点改派给当前合法非本人处理人");
  if (password === null) return;
  try {
    await mutate(
      `/workflow/steps/${task.id}/reassign`,
      "POST",
      {
        assigneeId: newAssignee.value,
        reason: assignmentReason.value,
        approvalReference: assignmentProof.value,
      },
      task.id,
      "WORKFLOW_STEP",
      "REASSIGN_STEP",
      password,
      task.rowVersion,
    );
    reassignDialog.value?.close();
    inform("当前节点已改派，原路线和历史决定保留。");
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
/** 首页跳转只传来源标识，详情仍经过原业务API重新鉴权。 */
onMounted(async () => {
  await load();
  const params = new URLSearchParams(location.hash.split("?")[1] || "");
  const step = params.get("step"),
    batchId = params.get("batch");
  if (step) await openTask({ id: step });
  else if (batchId) await openRequest({ id: batchId });
});
/** 当前列表已提交的筛选条件。 */
/** 待审批任务不按日期裁剪，申请和进度按提交或发起日期查询。 */
function defaultListFilters(view: string) {
  return recentWeekFilters(
    view === "progress"
      ? "created"
      : view === "requests"
        ? "submitted"
        : undefined,
  );
}
const listFilters = ref(defaultListFilters(tab.value));
const grantFilters = ref<Record<string, string>>({});
</script>
<template>
  <header class="page-heading">
    <div>
      <h1>流程中心</h1>
      <p class="muted">查看本人事项，按当前职责办理必要节点。</p>
    </div>
  </header>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  <section class="panel flow-panel">
    <nav class="module-tabs" aria-label="流程分类">
      <button
        v-if="can('PLATFORM.DEPARTMENTS_MANAGE')"
        :class="{ active: tab === 'progress' }" :aria-pressed="tab === 'progress'"
        @click="switchTab('progress')"
      >
        流程进度
      </button>
      <button
        v-if="can('ARCHIVE.ACCESS') && !can('ARCHIVE.IT_SHARED_BORROW')"
        :class="{ active: tab === 'tasks' }" :aria-pressed="tab === 'tasks'"
        @click="switchTab('tasks')"
      >
        待我办理</button
      ><button
        v-if="can('ARCHIVE.ACCESS')"
        :class="{ active: tab === 'requests' }" :aria-pressed="tab === 'requests'"
        @click="switchTab('requests')"
      >
        我的借阅</button
      ><button
        v-if="canUsePhysical"
        :class="{ active: tab === 'physical' }" :aria-pressed="tab === 'physical'"
        @click="switchTab('physical')"
      >
        纸质交付与归还</button
      ><button
        v-if="can('ARCHIVE.ACCESS')"
        :class="{ active: tab === 'notifications' }" :aria-pressed="tab === 'notifications'"
        @click="switchTab('notifications')"
      >
        到期提醒</button
      ><button
        v-if="can('ARCHIVE.ACCESS')"
        :class="{ active: tab === 'grants' }" :aria-pressed="tab === 'grants'"
        @click="switchTab('grants')"
      >
        {{ security ? "授权管理" : "我的授权" }}</button
      ><button
        v-if="admin"
        :class="{ active: tab === 'attention' }" :aria-pressed="tab === 'attention'"
        @click="switchTab('attention')"
      >
        需要改派
      </button>
    </nav>
    <div v-if="tab === 'grants' && security" class="grant-selector">
      <button
        class="secondary"
        :disabled="grantKind === 'people'"
        @click="
          grantKind = 'people';
          grantFilters = {};
          page = 1;
          load();
        "
      >
        人员批准来源</button
      ><button
        class="secondary"
        :disabled="grantKind === 'policies'"
        @click="
          grantKind = 'policies';
          grantFilters = {};
          page = 1;
          load();
        "
      >
        基础开放策略
      </button>
    </div>
    <div
      v-if="tab === 'grants' && security && grantKind === 'people'"
      class="grant-selector"
    >
      <label class="list-filter-field"
        >授权员工<input
          v-model="subjectKeyword"
          maxlength="100"
          placeholder="按姓名或工号选择获权员工"
          aria-label="检索授权员工" /></label
      ><button class="secondary" @click="findSubjects">检索</button
      ><label class="list-filter-field"
        >显示范围<select
          v-model="subject"
          aria-label="选择授权员工"
          @change="
            page = 1;
            loadGrants();
          "
        >
          <option value="">本人授权</option>
          <option
            v-for="person in subjects"
            :key="person.id"
            :value="person.id"
          >
            {{ person.name }} · {{ person.employeeNo }}
          </option>
        </select></label
      >
    </div>
    <ListFilters
      v-if="tab === 'grants'"
      :key="grantKind"
      :fields="[
        { key: 'number', label: '独立文件号' },
        ...(grantKind === 'people'
          ? [
              {
                key: 'source',
                label: '批准来源',
                options: { BORROW: '借阅批准', BASELINE: '基础批准' },
              },
            ]
          : [
              {
                key: 'visibility',
                label: '开放范围',
                options: { HIDDEN: '不开放题名', TITLE_ONLY: '批准安全题名' },
              },
            ]),
        {
          key: 'effective',
          label: '实际有效性',
          options: { true: '当前有效', false: '当前无效' },
        },
        { key: 'until', label: '截止日期', type: 'date' },
      ]"
      :values="grantFilters"
      :busy="loading"
      @apply="
        grantFilters = $event;
        page = 1;
        load();
      "
    />
    <ListFilters
      :fields="
        tab === 'progress'
          ? [
              {
                key: 'businessType',
                label: '事项类型',
                options: businessNames,
              },
              {
                key: 'state',
                label: '流程状态',
                options: {
                  PENDING: '审批中',
                  APPROVED: '已批准',
                  REJECTED: '已驳回',
                  WITHDRAWN: '已撤回',
                  CLOSED: '已结束',
                },
              },
              { key: 'dutyCode', label: '当前职责', options: dutyNames },
              { key: 'created', label: '发起日期', type: 'date' },
            ]
          : tab === 'requests'
            ? [
                { key: 'keyword', label: '申请用途' },
                {
                  key: 'form',
                  label: '借阅类型',
                  options: { ELECTRONIC: '电子借阅', PAPER: '纸质借阅' },
                },
                {
                  key: 'state',
                  label: '申请状态',
                  options: {
                    PENDING: '审批中',
                    APPROVED: '已批准',
                    PARTIAL: '部分批准',
                    REJECTED: '已驳回',
                    WITHDRAWN: '已撤回',
                    CLOSED: '已结束',
                  },
                },
                { key: 'submitted', label: '送审日期', type: 'date' },
              ]
            : [
                {
                  key: 'businessType',
                  label: '办理事项',
                  options: businessNames,
                },
                { key: 'dutyCode', label: '当前职责', options: dutyNames },
              ]
      "
      :values="listFilters"
      v-if="['progress', 'requests', 'tasks'].includes(tab)"
      :key="tab"
      :busy="loading"
      @apply="
        listFilters = $event;
        page = 1;
        load();
      "
    />
    <Notifications
      v-if="tab === 'notifications'"
      @open="openRequest({ id: $event })"
    />
    <Physical v-if="tab === 'physical' && canUsePhysical" />
    <div
      v-if="!['notifications', 'physical'].includes(tab)"
      class="table-scroll"
    >
      <table v-if="tab === 'tasks'" class="flow-table">
        <thead>
          <tr>
            <th>办理事项</th>
            <th>当前职责</th>
            <th>待办状态</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="task in tasks" :key="task.id" :data-step-id="task.id">
            <td>
              <strong>{{ businessNames[task.businessType] }}</strong
              ><small>固定路线 · 当前必要节点</small>
            </td>
            <td>{{ dutyNames[task.dutyCode] }}</td>
            <td>
              <span class="flow-chip">{{
                task.requiresReassignment ? "需本人回避" : "待我办理"
              }}</span>
            </td>
            <td>
              <button class="text-button" @click="openTask(task)">
                查看并办理
              </button>
            </td>
          </tr>
          <tr v-if="!tasks.length">
            <td colspan="4" class="flow-empty">
              {{ loading ? "正在读取待办…" : "当前暂无需你办理的节点" }}
            </td>
          </tr>
        </tbody>
      </table>
      <table v-else-if="tab === 'requests'" class="flow-table">
        <thead>
          <tr>
            <th>申请用途</th>
            <th>资料范围</th>
            <th>状态</th>
            <th>送审时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="request in requests"
            :key="request.id"
            :data-batch-id="request.id"
          >
            <td class="purpose-cell">
              <small v-if="request.form" class="flow-chip">
                {{ request.form === "PAPER" ? "纸质" : "电子" }}
              </small>
              <small v-if="request.renewalOfBatchId" class="flow-chip"
                >续借</small
              >
              <span>{{ request.purpose }}</span>
            </td>
            <td>
              {{ request.itemCount }}份 ·
              {{ classifications[request.parentMaxClassification] }}
            </td>
            <td>
              <span class="flow-chip">{{ archiveStates[request.state] }}</span>
            </td>
            <td>{{ dateTime(request.submittedAt) }}</td>
            <td>
              <button class="text-button" @click="openRequest(request)">
                查看进度
              </button>
            </td>
          </tr>
          <tr v-if="!requests.length">
            <td colspan="5" class="flow-empty">暂无本人借阅申请</td>
          </tr>
        </tbody>
      </table>
      <table
        v-else-if="tab === 'grants' && grantKind === 'people'"
        class="flow-table"
      >
        <thead>
          <tr>
            <th>独立文件号</th>
            <th>批准来源</th>
            <th>实际有效性</th>
            <th>批准截止</th>
            <th v-if="security">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="grant in grants" :key="grant.id" :data-grant-id="grant.id">
            <td>{{ grant.fileNo }}</td>
            <td>
              {{ grant.sourceType === "BORROW" ? "借阅批准" : "基础批准" }} ·
              查看
            </td>
            <td>
              <span class="flow-chip">{{
                grant.effective
                  ? "当前有效"
                  : grant.state === "ACTIVE"
                    ? "当前不可用"
                    : archiveStates[grant.state] || "当前不可用"
              }}</span>
            </td>
            <td>{{ dateTime(grant.validUntil) }}</td>
            <td v-if="security">
              <button
                v-if="grant.state === 'ACTIVE' || grant.state === 'EXPIRED'"
                class="text-button"
                @click="openRevoke(grant)"
              >
                撤销该来源
              </button>
            </td>
          </tr>
          <tr v-if="!grants.length">
            <td :colspan="security ? 5 : 4" class="flow-empty">
              当前范围暂无具体人员授权
            </td>
          </tr>
        </tbody>
      </table>
      <table v-else-if="tab === 'grants' && security" class="flow-table">
        <thead>
          <tr>
            <th>独立文件号</th>
            <th>开放范围</th>
            <th>实际状态</th>
            <th>有效至</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="policy in policies" :key="policy.id">
            <td>{{ policy.fileNo }}</td>
            <td>
              {{
                policy.titleVisibility === "HIDDEN"
                  ? "不开放题名"
                  : "批准安全题名"
              }}{{
                policy.allowOwnerDepartmentView ? " · 本部门一般文件阅读" : ""
              }}
            </td>
            <td>
              <span class="flow-chip">{{
                policy.effective
                  ? "当前有效"
                  : policy.state === "ACTIVE"
                    ? "当前不可用"
                    : archiveStates[policy.state]
              }}</span>
            </td>
            <td>
              {{ policy.validUntil ? dateTime(policy.validUntil) : "长期有效" }}
            </td>
            <td>
              <button
                v-if="policy.state === 'ACTIVE'"
                class="text-button"
                @click="openPolicyRevoke(policy)"
              >
                撤销基础开放
              </button>
            </td>
          </tr>
          <tr v-if="!policies.length">
            <td colspan="5" class="flow-empty">暂无基础开放策略</td>
          </tr>
        </tbody>
      </table>
      <table v-else-if="tab === 'progress'" class="flow-table">
        <thead>
          <tr>
            <th>事项类型</th>
            <th>当前状态</th>
            <th>当前职责</th>
            <th>发起时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in progress" :key="item.id">
            <td>{{ businessNames[item.businessType] }}</td>
            <td>{{ archiveStates[item.state] || item.state }}</td>
            <td>{{ item.dutyCode ? dutyNames[item.dutyCode] : "—" }}</td>
            <td>{{ dateTime(item.createdAt) }}</td>
          </tr>
          <tr v-if="!progress.length">
            <td colspan="4" class="flow-empty">暂无流程记录</td>
          </tr>
        </tbody>
      </table>
      <table v-else-if="tab === 'attention'" class="flow-table">
        <thead>
          <tr>
            <th>事项类型</th>
            <th>必要职责</th>
            <th>原办理人</th>
            <th>申请人</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="task in attention" :key="task.id">
            <td>{{ businessNames[task.businessType] }}</td>
            <td>{{ dutyNames[task.dutyCode] }}</td>
            <td>{{ task.assigneeName }}</td>
            <td>{{ task.applicantName }}</td>
            <td>
              <button class="text-button" @click="openAssignment(task)">
                配置合法办理人
              </button>
            </td>
          </tr>
          <tr v-if="!attention.length">
            <td colspan="5" class="flow-empty">
              暂无需要回避或职责更替的当前节点
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <footer
      class="list-footer"
      v-if="!['notifications', 'physical'].includes(tab)"
    >
      <button
        type="button"
        class="secondary list-refresh"
        :disabled="loading"
        @click="load()"
      >
        刷新列表
      </button>
      <div class="list-pagination">
        <span class="list-total">共 {{ total }} 条</span
        ><template v-if="tab !== 'attention' && total > 20">
          <button
            class="secondary"
            :disabled="page <= 1"
            @click="
              page--;
              load();
            "
          >
            上一页</button
          ><span>{{ page }} / {{ Math.ceil(total / 20) }}</span
          ><button
            class="secondary"
            :disabled="page * 20 >= total"
            @click="
              page++;
              load();
            "
          >
            下一页
          </button>
        </template>
      </div>
    </footer>
  </section>
  <dialog
    ref="dialog"
    class="form-dialog flow-dialog"
    aria-labelledby="flow-decision-title"
  >
    <form v-if="detail" class="flow-dialog-shell" @submit.prevent="decide">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"
          ><PlatformIcon name="task" :size="22"
        /></span>
        <h2 id="flow-decision-title">
          {{
            detail.business.detail?.batch.form === "PAPER"
              ? detail.business.detail.batch.renewalReason
                ? "纸质续借审批"
                : "纸质借阅审批"
              : detail.business.detail?.batch.renewalOfBatchId
                ? "电子续借审批"
                : businessNames[detail.workflow.businessType]
          }}
        </h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭流程详情"
          @click="dialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section
          class="flow-dialog-overview"
          aria-labelledby="flow-stage-title"
        >
          <h3 id="flow-stage-title">当前流程</h3>
          <dl class="borrow-progress-facts">
            <div>
              <dt>审批节点</dt>
              <dd>{{ dutyNames[detail.step.dutyCode] }}</dd>
            </div>
            <div>
              <dt>办理状态</dt>
              <dd>{{ archiveStates[detail.workflow.state] }}</dd>
            </div>
          </dl>
        </section>
        <section
          v-if="detail.ownDecision"
          aria-labelledby="flow-own-decision-title"
        >
          <h3 id="flow-own-decision-title">本人办理记录</h3>
          <dl class="borrow-progress-facts">
            <div>
              <dt>办理人</dt>
              <dd>{{ detail.ownDecision.operatorName }}</dd>
            </div>
            <div>
              <dt>处理结论</dt>
              <dd>
                {{ detail.ownDecision.result === "APPROVE" ? "同意" : "驳回" }}
              </dd>
            </div>
            <div>
              <dt>办理时间</dt>
              <dd>{{ dateTime(detail.ownDecision.recordedAt) }}</dd>
            </div>
            <div v-if="detail.ownDecision.comment">
              <dt>审批意见</dt>
              <dd>{{ detail.ownDecision.comment }}</dd>
            </div>
          </dl>
        </section>
        <section aria-labelledby="flow-applicant-title">
          <h3 id="flow-applicant-title">
            {{
              detail.workflow.businessType === "BORROW"
                ? "借阅人信息"
                : "申请人信息"
            }}
          </h3>
          <dl class="borrow-progress-facts flow-applicant-facts">
            <div>
              <dt>姓名</dt>
              <dd>{{ detail.applicant.name }}</dd>
            </div>
            <div>
              <dt>工号</dt>
              <dd>{{ detail.applicant.employeeNo }}</dd>
            </div>
            <div>
              <dt>申请时所属部门</dt>
              <dd>{{ detail.applicant.departmentName }}</dd>
            </div>
            <div>
              <dt>申请时间</dt>
              <dd>{{ dateTime(detail.applicant.submittedAt) }}</dd>
            </div>
          </dl>
        </section>
        <section
          v-if="detail.business.change"
          aria-labelledby="flow-change-title"
        >
          <h3 id="flow-change-title">更正与撤下信息</h3>
          <CatalogChangeReview :change="detail.business.change" />
        </section>
        <section
          v-if="detail.business.snapshot"
          class="flow-dialog-section"
          aria-labelledby="flow-snapshot-title"
        >
          <h3 id="flow-snapshot-title">
            {{ detail.business.snapshot.title || "基础开放范围确认" }}
          </h3>
          <p v-if="detail.business.snapshot.classification">
            保密等级：{{
              classifications[detail.business.snapshot.classification]
            }}
          </p>
          <p v-if="detail.business.snapshot.qualityEvidenceRef" class="muted">
            该候选已关联人工质量确认，复核仍需独立完成。
          </p>
          <button
            v-if="
              ['REGISTRATION', 'CATALOG_CHANGE'].includes(
                detail.workflow.businessType,
              ) && detail.business.snapshot.pdfVersionId
            "
            type="button"
            class="secondary"
            @click="inspect"
          >
            检查本人水印候选
          </button>
          <div
            v-if="detail.business.snapshot.electronicMembers?.length"
            class="frozen-documents"
          >
            <h4>
              本盒电子清单文档 ·
              {{ detail.business.snapshot.electronicMembers.length }} 份
            </h4>
            <div
              v-for="member in detail.business.snapshot.electronicMembers"
              :key="member.id"
            >
              <span>{{ member.title }}</span
              ><button
                type="button"
                class="text-button"
                @click="inspectMember(member)"
              >
                检查文档
              </button>
            </div>
          </div>
          <p v-if="detail.business.snapshot.reason">
            业务原因：{{ detail.business.snapshot.reason }}
          </p>
          <div
            v-if="detail.workflow.businessType === 'ACCESS_POLICY'"
            class="policy-review"
          >
            <p>独立文件号：{{ detail.business.snapshot.fileNo }}</p>
            <p>
              安全题名：{{ detail.business.snapshot.safeTitle || "未登记" }}
            </p>
            <p>
              {{
                detail.business.snapshot.titleVisibility === "HIDDEN"
                  ? "不开放题名检索"
                  : "申请开放安全题名检索"
              }}
            </p>
            <p>
              检索受众：{{
                detail.business.audiences
                  ?.map((value) => value.name)
                  .join("、") || "无"
              }}
            </p>
            <p v-if="detail.business.users?.length">
              具体查看名单：{{
                detail.business.users.map((value) => value.name).join("、")
              }}
            </p>
            <p>
              有效范围：{{
                detail.business.snapshot.validFrom
                  ? dateTime(detail.business.snapshot.validFrom)
                  : ""
              }}
              至
              {{
                detail.business.snapshot.validUntil
                  ? dateTime(detail.business.snapshot.validUntil)
                  : "长期有效"
              }}
            </p>
          </div>
          <p v-if="detail.workflow.businessType === 'ACCESS_POLICY'">
            {{
              detail.business.snapshot.allowOwnerDepartmentView
                ? "申请开放本部门一般文件基础阅读。"
                : "不开放本部门默认正文阅读。"
            }}
          </p>
        </section>
        <template v-if="detail.business.detail">
          <section
            class="flow-dialog-section"
            aria-labelledby="flow-borrow-title"
          >
            <h3 id="flow-borrow-title">申请概况</h3>
            <p v-if="detail.business.unwatermarked">申请无水印电子版</p>
            <dl class="borrow-progress-facts">
              <div class="borrow-progress-purpose">
                <dt>借阅用途</dt>
                <dd>{{ detail.business.detail.batch.purpose }}</dd>
              </div>
              <div
                v-if="detail.business.detail.batch.renewalReason"
                class="borrow-progress-purpose"
              >
                <dt>续借原因</dt>
                <dd>{{ detail.business.detail.batch.renewalReason }}</dd>
              </div>
              <div v-if="detail.business.detail.batch.requestedPeriod">
                <dt>申请期限</dt>
                <dd>
                  {{ detail.business.detail.batch.requestedPeriod.amount
                  }}{{
                    detail.business.detail.batch.requestedPeriod.mode ===
                    "WORKDAYS"
                      ? "个工作日"
                      : "个自然日"
                  }}
                </dd>
              </div>
              <div>
                <dt>原批文件数量</dt>
                <dd>{{ detail.business.detail.batch.itemCount }} 份</dd>
              </div>
              <div>
                <dt>原批最高等级</dt>
                <dd>
                  {{
                    classifications[
                      detail.business.detail.batch.parentMaxClassification
                    ]
                  }}
                </dd>
              </div>
            </dl>
            <p class="muted">
              <template v-if="detail.business.detail.batch.requestedPeriod">{{
                detail.business.detail.batch.form === "PAPER"
                  ? detail.business.detail.batch.renewalReason
                    ? "续借从原截止与批准时刻中较晚者起算。"
                    : detail.business.detail.batch.paperHandoffs
                      ? "末级批准后占用原件，应还时间按批准时刻计算。"
                      : "应还截止按实际交付时刻计算。"
                  : "最终截止按批准生效时刻计算。"
              }}</template>
              本节点仅处理所属部门子单。
            </p>
          </section>
          <section aria-labelledby="flow-approval-files-title">
            <h3 id="flow-approval-files-title">借阅文件</h3>
            <ul class="borrow-progress-list">
              <li
                v-for="item in detail.business.detail.items"
                :key="`${item.requestId}-${item.recordId}-${item.action || 'VIEW'}`"
                class="borrow-progress-file"
              >
                <PlatformIcon name="file" :size="18" />
                <div class="borrow-progress-file-text">
                  <strong>{{ item.label }}</strong>
                  <small>
                    {{ actionLabels[item.action || "VIEW"]
                    }}<template v-if="item.action === 'PRINT'">
                      · 第{{ item.printPages?.join(",") }}页 · 累计{{
                        item.copies
                      }}份 </template
                    ><template v-if="item.previousUntil">
                      · 原截止{{ dateTime(item.previousUntil) }}
                    </template>
                  </small>
                </div>
                <span class="flow-chip">{{
                  classifications[item.classification]
                }}</span>
              </li>
            </ul>
          </section>
        </template>
        <section v-if="detail.workflow.businessType === 'PHYSICAL_RETURN'" class="flow-dialog-section">
          <h3>归还说明</h3>
          <p>{{ detail.business.uncollected ? "未取件" : "归还纸质原件" }}</p>
          <p>{{ detail.business.purpose }}</p>
          <ul>
            <li v-for="item in detail.business.items" :key="item.recordId">{{ item.label }}</li>
          </ul>
        </section>
        <section
          class="flow-dialog-section"
          aria-labelledby="flow-opinion-title"
        >
          <h3 id="flow-opinion-title">审批处理</h3>
          <template v-if="actionable">
            <p
              v-if="detail.business.approvalBlockReason"
              class="form-error"
              role="alert"
            >
              {{ detail.business.approvalBlockReason }}
            </p>
            <label class="flow-inline-field"
              >处理结论
              <select v-model="decision">
                <option
                  v-if="detail.business.approvalBlockReason"
                  value=""
                  disabled
                >
                  请选择处理结论
                </option>
                <option
                  value="APPROVE"
                  :disabled="!!detail.business.approvalBlockReason"
                >
                  同意
                </option>
                <option value="REJECT">驳回</option>
              </select>
            </label>
            <label
              >审批意见（选填）
              <textarea v-model="comment" maxlength="2000" rows="3" />
            </label>
          </template>
          <p v-else class="muted">
            当前不能由本人作出决定；申请人与必要审批人重合时需按合法职责改派。
          </p>
        </section>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button type="button" class="secondary" @click="dialog?.close()">
          关闭
        </button>
        <button
          v-if="actionable"
          type="submit"
          class="primary"
          :disabled="busy || !decision"
        >
          提交决定
        </button>
      </footer>
    </form>
  </dialog>
  <dialog
    ref="batchDialog"
    class="form-dialog flow-dialog"
    aria-labelledby="borrow-progress-title"
  >
    <div v-if="batch" :key="batch.batch.id" class="flow-dialog-shell">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"
          ><PlatformIcon name="task" :size="22"
        /></span>
        <h2 id="borrow-progress-title">借阅办理进度</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭借阅进度"
          @click="batchDialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section
          class="flow-dialog-overview"
          aria-labelledby="borrow-overview-title"
        >
          <h3 id="borrow-overview-title">申请概况</h3>
          <dl class="borrow-progress-facts">
            <div class="borrow-progress-purpose">
              <dt>借阅用途</dt>
              <dd>{{ batch.batch.purpose }}</dd>
            </div>
            <div>
              <dt>借阅方式</dt>
              <dd>{{ batch.batch.form === "PAPER" ? "纸质" : "电子" }}</dd>
            </div>
            <div>
              <dt>文件数量</dt>
              <dd>{{ batch.batch.itemCount }} 份</dd>
            </div>
            <div>
              <dt>原批最高等级</dt>
              <dd>
                {{ classifications[batch.batch.parentMaxClassification] }}
              </dd>
            </div>
          </dl>
          <p
            v-if="batch.batch.renewalOfBatchId"
            class="muted borrow-progress-note"
          >
            本次为关联原借阅的续借申请。待审或驳回期间，按原记录的截止时间办理。
          </p>
        </section>
        <section aria-labelledby="borrow-files-title">
          <h3 id="borrow-files-title">借阅文件</h3>
          <ul class="borrow-progress-list">
            <li
              v-for="item in batch.items"
              :key="`${item.requestId}-${item.recordId}-${item.action || 'VIEW'}`"
              class="borrow-progress-file"
            >
              <PlatformIcon name="file" :size="18" />
              <div class="borrow-progress-file-text">
                <strong>{{ item.label }}</strong>
                <small>
                  {{ actionLabels[item.action || "VIEW"]
                  }}<template v-if="item.action === 'PRINT'">
                    · 第{{ item.printPages?.join(",") }}页 · 累计{{
                      item.copies
                    }}份
                  </template>
                </small>
              </div>
              <span class="flow-chip">{{
                classifications[item.classification]
              }}</span>
            </li>
          </ul>
        </section>
        <section aria-labelledby="borrow-results-title">
          <h3 id="borrow-results-title">部门审批结果</h3>
          <ul class="borrow-progress-list">
            <li
              v-for="(request, index) in batch.requests"
              :key="request.id"
              class="borrow-progress-request"
            >
              <div class="borrow-progress-request-heading">
                <strong>部门子单 {{ index + 1 }}</strong>
                <span
                  class="flow-chip"
                  :class="{ 'borrow-approved': request.state === 'APPROVED' }"
                >
                  {{ archiveStates[request.state] }}
                </span>
                <button
                  v-if="request.state === 'PENDING'"
                  type="button"
                  class="text-button"
                  @click="withdraw(request)"
                >
                  撤回此子单
                </button>
              </div>
              <dl
                v-if="request.approvedUntil || request.state === 'APPROVED'"
                class="borrow-progress-facts"
              >
                <div v-if="request.approvedUntil">
                  <dt>有效至</dt>
                  <dd>{{ dateTime(request.approvedUntil) }}</dd>
                </div>
                <div v-if="request.state === 'APPROVED'">
                  <dt>使用状态</dt>
                  <dd>{{ usageNames[request.usageState] }}</dd>
                </div>
              </dl>
              <EvidenceSubmission
                v-if="request.currentStepId && request.state === 'PENDING'"
                :key="request.currentStepId"
                :step-id="request.currentStepId"
              />
            </li>
          </ul>
        </section>
        <section
          v-if="batch.renewals?.length"
          aria-labelledby="borrow-renewals-title"
        >
          <h3 id="borrow-renewals-title">续借记录</h3>
          <ul class="borrow-progress-list">
            <li v-for="renewal in batch.renewals" :key="renewal.id">
              <button
                type="button"
                class="borrow-progress-history"
                @click="openRequest(renewal)"
              >
                <span class="flow-chip">{{
                  archiveStates[renewal.state]
                }}</span>
                <span>{{ dateTime(renewal.submittedAt) }}</span>
                <PlatformIcon name="chevron" :size="16" />
              </button>
            </li>
          </ul>
        </section>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button type="button" class="secondary" @click="batchDialog?.close()">
          关闭
        </button>
        <button
          v-if="batch.canRenew"
          type="button"
          class="primary"
          @click="openRenewal"
        >
          申请续借
        </button>
      </footer>
    </div>
  </dialog>
  <dialog
    ref="renewalDialog"
    class="form-dialog flow-dialog flow-dialog-compact"
    aria-labelledby="flow-renewal-title"
  >
    <form class="flow-dialog-shell" @submit.prevent="submitRenewal">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"
          ><PlatformIcon name="clock" :size="22"
        /></span>
        <h2 id="flow-renewal-title">申请电子续借</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭续借申请"
          @click="renewalDialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section
          class="flow-dialog-overview"
          aria-labelledby="flow-renewal-scope"
        >
          <h3 id="flow-renewal-scope">续借范围</h3>
          <p class="muted">
            沿用原批准范围和最高等级，重新办理审批。原授权按原截止时间到期。
          </p>
        </section>
        <section
          class="flow-dialog-section"
          aria-labelledby="flow-renewal-details"
        >
          <h3 id="flow-renewal-details">续借说明</h3>
          <label
            >续借业务用途
            <textarea
              v-model="renewalPurpose"
              required
              maxlength="2000"
              rows="2"
            />
          </label>
          <label
            >续借原因
            <textarea
              v-model="renewalReason"
              required
              maxlength="1000"
              rows="3"
            />
          </label>
        </section>
        <section
          class="flow-dialog-section"
          aria-labelledby="flow-renewal-period"
        >
          <h3 id="flow-renewal-period">续借期限</h3>
          <div class="flow-dialog-fields">
            <label class="flow-inline-field"
              >期限规则
              <select v-model="renewalMode">
                <option value="WORKDAYS">工作日</option>
                <option value="NATURAL_DAYS">自然日</option>
              </select>
            </label>
            <label class="flow-inline-field"
              >续借天数
              <input
                v-model.number="renewalDays"
                type="number"
                required
                min="1"
                max="30"
              />
            </label>
          </div>
          <p class="muted">
            未到期时从原截止日继续计算；审批期间到期时，从新批准生效日计算。单次新授权最长
            30 个自然日。
          </p>
        </section>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button type="button" class="secondary" @click="renewalDialog?.close()">
          取消
        </button>
        <button type="submit" class="primary" :disabled="busy">
          提交续借申请
        </button>
      </footer>
    </form>
  </dialog>
  <dialog
    ref="revokeDialog"
    class="form-dialog flow-dialog flow-dialog-compact"
    aria-labelledby="flow-revoke-title"
  >
    <form class="flow-dialog-shell" @submit.prevent="revoke">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"
          ><PlatformIcon name="lock" :size="22"
        /></span>
        <h2 id="flow-revoke-title">撤销指定批准来源</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭撤权"
          @click="revokeDialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section
          class="flow-dialog-overview"
          aria-labelledby="flow-revoke-scope"
        >
          <h3 id="flow-revoke-scope">撤销对象</h3>
          <dl class="borrow-progress-facts">
            <div>
              <dt>文件编号</dt>
              <dd>{{ revoking?.fileNo }}</dd>
            </div>
          </dl>
          <p class="muted borrow-progress-note">
            撤销后该来源立即失效，相关员工的旧登录和旧阅读会话将结束。
          </p>
        </section>
        <section
          class="flow-dialog-section"
          aria-labelledby="flow-revoke-details"
        >
          <h3 id="flow-revoke-details">撤销说明</h3>
          <label
            >实际撤权原因
            <textarea
              v-model="revokeReason"
              maxlength="1000"
              required
              rows="3"
            />
          </label>
        </section>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button type="button" class="secondary" @click="revokeDialog?.close()">
          取消
        </button>
        <button type="submit" class="primary" :disabled="busy">
          撤销该来源
        </button>
      </footer>
    </form>
  </dialog>
  <dialog
    ref="reassignDialog"
    class="form-dialog flow-dialog flow-dialog-compact"
    aria-labelledby="flow-reassign-title"
  >
    <form class="flow-dialog-shell" @submit.prevent="reassign">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"
          ><PlatformIcon name="people" :size="22"
        /></span>
        <h2 id="flow-reassign-title">配置合法办理人</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭节点改派"
          @click="reassignDialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section
          class="flow-dialog-overview"
          aria-labelledby="flow-reassign-scope"
        >
          <h3 id="flow-reassign-scope">当前节点</h3>
          <dl v-if="assignmentTarget" class="borrow-progress-facts">
            <div>
              <dt>必要职责</dt>
              <dd>{{ dutyNames[assignmentTarget.dutyCode] }}</dd>
            </div>
            <div v-if="assignmentTarget.assigneeName">
              <dt>原办理人</dt>
              <dd>{{ assignmentTarget.assigneeName }}</dd>
            </div>
          </dl>
          <p class="muted borrow-progress-note">
            节点沿用原审批职责。请选择具备该职责且并非申请人的人员。
          </p>
        </section>
        <section
          class="flow-dialog-section"
          aria-labelledby="flow-reassign-details"
        >
          <h3 id="flow-reassign-details">改派信息</h3>
          <label class="flow-inline-field"
            >当前合法人选
            <select v-model="newAssignee" required>
              <option value="" disabled>暂无可用人选</option>
              <option
                v-for="person in assignmentOptions"
                :key="person.id"
                :value="person.id"
              >
                {{ person.name }}
              </option>
            </select>
          </label>
          <p v-if="!assignmentOptions.length" class="muted">
            请先由组织管理配置合法职责持有人，再办理节点改派。
          </p>
          <label
            >实际改派原因
            <textarea
              v-model="assignmentReason"
              required
              maxlength="1000"
              rows="2"
            />
          </label>
          <label class="flow-inline-field"
            >批准依据
            <input v-model="assignmentProof" required maxlength="256" />
          </label>
        </section>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button
          type="button"
          class="secondary"
          @click="reassignDialog?.close()"
        >
          取消
        </button>
        <button type="submit" class="primary" :disabled="!newAssignee">
          确认改派
        </button>
      </footer>
    </form>
  </dialog>
  <ArchiveReader ref="reader" @failure="error = $event" />
</template>
<style scoped>
/* 电脑端流程工作台，先显示用途、状态与必要职责，隐藏内部UUID和实现配置。 */
.flow-panel {
  padding: 0 clamp(14px, 2vw, 28px) 22px;
  min-height: 360px;
}
.flow-table {
  width: 100%;
  min-width: 570px;
  border-collapse: collapse;
  font-size: 13px;
}
.flow-table th {
  text-align: left;
  font-weight: 600;
  color: var(--text-secondary);
  background: var(--surface-muted);
  padding: 14px 15px;
}
.flow-table td {
  padding: 16px 15px;
  border-bottom: 1px solid var(--line);
}
.flow-table strong {
  font-size: 14px;
  font-weight: 600;
}
.flow-table small {
  display: block;
  color: var(--text-secondary);
  font-size: 12px;
  margin-top: 7px;
}
.flow-chip {
  display: inline-block;
  font-size: 11px;
  color: var(--ink);
  background: var(--surface-muted);
  border-radius: 6px;
  padding: 6px 9px;
}
.flow-empty {
  height: 230px;
  text-align: center;
  color: var(--text-secondary);
}
.purpose-cell {
  max-width: 380px;
  overflow-wrap: anywhere;
}
/* 正文独立滚动，长清单和窄屏下标题与底部操作始终可见。 */
.borrow-progress-facts.flow-applicant-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px 20px;
}
.borrow-progress-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  margin: 0;
  font-size: 13px;
}
.borrow-progress-facts > div {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}
.borrow-progress-purpose {
  flex-basis: 100%;
}
.borrow-progress-facts dt {
  flex-shrink: 0;
  color: var(--text-secondary);
}
.borrow-progress-facts dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.borrow-progress-purpose dd {
  white-space: pre-wrap;
}
.borrow-progress-note {
  margin: 8px 0 0;
}
.borrow-progress-list {
  margin: 0;
  padding: 0;
  list-style: none;
  border: 1px solid var(--line);
  border-radius: 8px;
}
.borrow-progress-list > li + li {
  border-top: 1px solid var(--line);
}
.borrow-progress-file,
.borrow-progress-request {
  padding: 12px 14px;
  font-size: 13px;
}
.borrow-progress-file,
.borrow-progress-request-heading {
  display: flex;
  align-items: center;
  gap: 10px;
}
.borrow-progress-file > svg {
  flex-shrink: 0;
  color: var(--brand);
}
.borrow-progress-file-text {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}
.borrow-progress-file-text small {
  display: block;
  color: var(--text-secondary);
}
.borrow-progress-file .flow-chip {
  flex-shrink: 0;
}
.borrow-progress-request-heading {
  flex-wrap: wrap;
}
.borrow-progress-request-heading > button {
  margin-left: auto;
}
.borrow-progress-request .borrow-progress-facts {
  margin-top: 8px;
}
.borrow-progress-request .borrow-approved {
  color: var(--success);
  background: var(--success-soft);
}
.borrow-progress-history {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 10px 14px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--ink);
  text-align: left;
}
.borrow-progress-history:hover {
  background: var(--surface-muted);
}
.borrow-progress-history > svg {
  margin-left: auto;
}
@media (max-width: 520px) {
  .borrow-progress-facts.flow-applicant-facts {
    grid-template-columns: minmax(0, 1fr);
  }
}
.grant-selector {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin: 10px 0 22px;
}
.grant-selector input { width: min(280px, 100%); }
.grant-selector select { width: min(260px, 100%); }
</style>

<style scoped>
.frozen-documents {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 16px;
  margin: 20px 0;
}
.frozen-documents h4 {
  margin: 0 0 12px;
}
.frozen-documents > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  overflow-wrap: anywhere;
  padding: 12px 0;
  border-top: 1px solid var(--line);
}
</style>
