<script setup lang="ts">
import ListFilters from "./ListFilters.vue";
import { recentWeekFilters } from "./listDates";
import { computed, inject, nextTick, onMounted, ref } from "vue";
import Icon from "../preview/PlatformIcon.vue";
import { api, can, dateTime, profile, query } from "./api";

/** 实物视图由服务端逐项裁剪；批准安全题名的员工没有库位、完整编号和他人身份。 */
interface Item {
  /** 当前业务对象标识。 */
  id: string;
  /** 实际盒或文件原件类型。 */
  objectType: string;
  /** 服务端裁剪后的可见题名。 */
  label: string;
  /** 经授权返回的原件编号。 */
  barcode?: string;
  /** 仅经办范围可见的库位。 */
  location?: string;
  /** 盒或件原件数量。 */
  quantity?: number;
  /** 用于拒绝覆盖并发修改的版本。 */
  rowVersion?: number;
  /** 整盒或相关分件当前仍被占用。 */
  occupied: boolean;
  /** 当前账号具有纸质申请能力。 */
  canApply: boolean;
  /** 当前账号可以维护该原件。 */
  canManage: boolean;
}
/** 交付清单固定所属子单及原件；待补批操作号用于原任务恢复。 */
interface Request {
  /** 当前业务对象标识。 */
  id: string;
  /** 所属申请父批次。 */
  batchId: string;
  /** 固定的实物原件标识。 */
  physicalItemId: string;
  /** 服务端裁剪后的可见题名。 */
  label: string;
  /** 区分同一原件的不同借阅申请。 */
  purpose: string;
  /** 经授权返回的原件编号。 */
  barcode: string;
  /** 实际盒或文件原件类型。 */
  objectType: string;
  /** 服务端真实业务状态。 */
  state: string;
  /** 此批准原件已有实际交付。 */
  delivered: boolean;
  /** 当前经办人可以登记实际交付。 */
  canDeliver: boolean;
  /** 待补批操作原幂等号。 */
  pendingOperationId?: string;
  /** 原交付操作冻结的核对说明。 */
  pendingCondition?: string;
  /** 批量补批必须沿用原完整交付范围。 */
  pendingPhysicalItemIds?: string[];
  /** 新流程的批准时刻和第一位经办人登记的交付时间。 */
  reservedAt?: string | null;
  pickupAt?: string | null;
}
/** 借出主状态只有实际使用或已归还，逾期与待复核分别展示。 */
interface Loan {
  /** 当前业务对象标识。 */
  id: string;
  /** 原借阅申请关联账号、姓名及当时可见的原件题名。 */
  borrowerId: string;
  borrowerName: string;
  label: string | null;
  /** 经授权返回的原件编号。 */
  barcode: string;
  /** 实际盒或文件原件类型。 */
  objectType: string;
  /** 服务端真实业务状态。 */
  state: string;
  /** 服务端核验后的实际借出时刻。 */
  actualDeliveryAt: string | null;
  /** 新流程从批准起占用；实际交付和第一人登记可为空。 */
  reservedAt?: string | null;
  pickupAt?: string | null;
  /** 按固定日历计算的应还截止。 */
  dueAt: string;
  /** 用于拒绝覆盖并发修改的版本。 */
  rowVersion: number;
  /** 存在尚未实收复核的归还轮次。 */
  returnPending?: boolean;
  /** 当前截止已经过去且原件仍在使用。 */
  overdue?: boolean;
  /** 当前借阅人可以提出归还声明。 */
  canRequestReturn?: boolean;
  /** 具备非本人实收复核职责。 */
  canReview?: boolean;
  /** 所属部门档案员可以登记本轮归还。 */
  canRegisterReturn?: boolean;
  /** 当前借阅人具备纸质续借能力。 */
  canRenew?: boolean;
}
/** 每轮实收核验留痕，异常轮次不能覆盖。 */
interface Return {
  /** 当前业务对象标识。 */
  id: string;
  /** 服务端真实业务状态。 */
  state: string;
  /** 申报的原件数量和完好情况。 */
  condition: string;
  /** 借阅人申报归还的盒或件数。 */
  reportedQuantity: number;
  /** 实际实收复核结论。 */
  reviewCondition?: string;
  /** 该轮归还申请提交时刻。 */
  submittedAt: string;
  /** 申报备注及已经登记的实际复核事实。 */
  note: string | null;
  reviewedByName: string | null;
  actualReceivedAt: string | null;
  receivedQuantity: number | null;
  reviewedAt: string | null;
  /** 新归还审批状态及档案员冻结的实物登记。 */
  approvalState?: string | null;
  registration?: {
    result: string;
    actualReceivedAt: string;
    actualQuantity: number;
    condition: string;
    allAttachmentsConfirmed: boolean;
    intactConfirmed: boolean;
  };
}
interface Detail {
  /** 原申请、实物交接与当前截止。 */
  loan: Loan & {
    memberLabels: string[];
    purpose: string;
    submittedAt: string;
    borrowerEmployeeNo: string;
    borrowerDepartmentName: string;
    deliveredByName: string;
    actualQuantity: number;
    condition: string;
    returnedAt: string | null;
  };
  /** 按时间倒序排列的归还核验轮次。 */
  returns: Return[];
  /** 服务端留存的借还事件。 */
  events: { id: string; eventType: string; occurredAt: string }[];
}
/** 密码仅用于当前精确动作的短期验证，不进入页面持久缓存。 */
const askPassword =
    inject<(title: string) => Promise<string | null>>("askPassword")!,
  inform = inject<(message: string) => void>("inform")!;
/** 当前页的数据、筛选和提交状态均只保存在本次组件内存。 */
const focusedRequest = ref(
  new URLSearchParams(window.location.hash.split("?")[1] || "").get(
    "request",
  ) || "",
);
const tab = ref(can("ARCHIVE.PHYSICAL_MANAGE") ? "requests" : "loans"),
  inventory = ref<Item[]>([]),
  requests = ref<Request[]>([]),
  loans = ref<Loan[]>([]),
  notices = ref<
    { id: string; loanId: string; kind: string; deadline: string }[]
  >([]),
  page = ref(1),
  total = ref(0),
  error = ref(""),
  busy = ref(false);
/** 对话框对象及查看范围由服务器再次验证，不构成授权依据。 */
const dialog = ref<HTMLDialogElement | null>(null),
  kind = ref(""),
  target = ref<Item | Request | Loan | null>(null),
  detail = ref<Detail | null>(null),
  /** 折叠只改变显示，保留当前记录的重新打开入口。 */
  detailExpanded = ref(false),
  detailLoading = ref(false),
  detailPanel = ref<HTMLElement | null>(null),
  scope = ref("MINE");
/** 对话框期间固定操作号，网络重试和待补批继续均复用它。 */
const operation = ref(""),
  purpose = ref(""),
  reason = ref(""),
  mode = ref("WORKDAYS"),
  days = ref(3),
  condition = ref(""),
  quantity = ref(1),
  result = ref("CONFIRM"),
  location = ref(""),
  attachments = ref(false),
  intact = ref(false),
  actual = ref("");
/** 操作入口取决于独立实物权限，状态与提醒文案只用于显示。 */
const managed = computed(() => can("ARCHIVE.PHYSICAL_MANAGE")),
  currentReturn = computed(() =>
    detail.value?.returns.find((value) => value.state === "PENDING"),
  ),
  uncollected = computed(() => {
    const loan = detail.value?.loan;
    return Boolean(loan?.reservedAt && !loan.actualDeliveryAt && !loan.pickupAt);
  }),
  showReturn = computed(() => {
    const loan = detail.value?.loan;
    return Boolean(loan?.state === "IN_USE" && loan.borrowerId === profile.value?.user.id
      && (loan.reservedAt || loan.canRequestReturn));
  }),
  reviewingRegistration = computed(() => kind.value === "REVIEW" && !!currentReturn.value?.registration),
  /** 归还核验阶段单独说明，逾期和待复核均不等于归还。 */
  loanProgress = computed(() => {
    const value = detail.value;
    if (!value) return null;
    if (value.loan.state === "RETURNED")
      return {
        label: "已归还",
        text: uncollected.value ? "双方已确认未取件，原件恢复可借。" : "原件已核对实收，本次借阅已结束。",
      };
    if (currentReturn.value?.approvalState === "PENDING")
      return { label: "归还审批中", text: "按审批顺序办理，原件继续占用。" };
    if (currentReturn.value?.approvalState === "APPROVED")
      return currentReturn.value.registration
        ? { label: "待超管确认", text: "所属部门档案员已登记，请档案超级管理员确认同一次交接。" }
        : { label: "待归还交接", text: "归还已获批，由档案所属部门档案员登记交接。" };
    if (currentReturn.value)
      return {
        label: "归还待复核",
        text: value.loan.canReview
          ? "请核对实际收到的原件、数量、附件和完好情况，再登记复核结果。"
          : "借阅人已提交归还申请，等待实收复核。确认前原件仍记在本次借阅下。",
      };
    if (["REJECTED", "WITHDRAWN"].includes(value.returns[0]?.approvalState || ""))
      return { label: "可重新申请归还", text: "上一轮归还审批已结束，原件仍占用。" };
    if (value.returns[0]?.state === "EXCEPTION")
      return {
        label: "归还异常",
        text: "原件仍记在本次借阅下，请查看下方复核说明。",
      };
    if (value.loan.reservedAt && !value.loan.actualDeliveryAt)
      return {
        label: value.loan.pickupAt ? "待另一位经办人确认取件" : "待交付",
        text: "原件已占用，应还时间从末级审批通过起算。",
      };
    return {
      label: "借阅中",
      text: value.loan.overdue
        ? "已超过应还时间，原件尚未确认归还。"
        : "原件已实际交付，归还后须核对实收才能结束本次借阅。",
    };
  }),
  returnStates: Record<string, string> = {
    PENDING: "归还待复核",
    CONFIRMED: "已确认实收",
    EXCEPTION: "归还异常",
  },
  titles: Record<string, string> = {
    DELIVER: "登记实际交付",
    RETURN: "申请归还",
    REVIEW: "复核实际归还",
    RENEW: "申请纸质续借",
    MOVE: "调整原件位置",
  },
  states: Record<string, string> = {
    PENDING: "审批中",
    APPROVED: "待交付",
    REJECTED: "已驳回",
    CLOSED: "已结束",
    WITHDRAWN: "已撤回",
    IN_USE: "借阅中",
    RETURNED: "已归还",
    EXCEPTION: "归还异常",
    CONFIRMED: "已确认",
  },
  noticeNames: Record<string, string> = {
    OVERDUE: "原件逾期，请办理归还或续借",
    DUE_TODAY: "原件到期提醒",
    DUE_TOMORROW: "原件临近到期",
    RETURN_REQUESTED: "有归还申请等待实收复核",
    RETURN_EXCEPTION: "归还核验有异常，请处理",
    RETURN_CONFIRMED: "原件已复核归还",
  };
/** 仅刷新需要的页；列表及计数均在服务器授权过滤后计算。 */
async function load() {
  error.value = "";
  try {
    if (tab.value === "inventory") {
      const value = await api<{ items: Item[]; total: number }>(
        `/archive/physical-items?page=${page.value}&pageSize=20&${query(listFilters.value)}`,
      );
      inventory.value = value.items;
      total.value = value.total;
    } else if (tab.value === "requests") {
      const value = await api<{ items: Request[]; total: number }>(
        `/archive/physical-delivery-requests?page=${page.value}&pageSize=20&${query(listFilters.value)}${focusedRequest.value ? "&requestId=" + encodeURIComponent(focusedRequest.value) : ""}`,
      );
      requests.value = value.items;
      total.value = value.total;
    } else if (tab.value === "loans") {
      const value = await api<{ items: Loan[]; total: number }>(
        `/archive/physical-loans?page=${page.value}&pageSize=20&scope=${scope.value}&${query(listFilters.value)}`,
      );
      loans.value = value.items;
      total.value = value.total;
    } else
      notices.value = (
        await api<{ items: typeof notices.value }>(
          "/archive/physical-notifications",
        )
      ).items;
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
/** 切换业务页同时清空跨页选择，避免误借上一页的原件。 */
function switchTab(value: string) {
  focusedRequest.value = "";
  tab.value = value;
  listFilters.value = recentWeekFilters(
    value === "loans" ? "delivery" : undefined,
  );
  page.value = 1;
  load();
}
/** 保留毫秒，避免刚批准后默认时间被截断到批准之前；仍由服务端严格校验。 */
function localTime(timestamp?: string | null) {
  const value = timestamp ? new Date(timestamp) : new Date();
  return new Date(+value - value.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 23);
}
function open(value: string, object: Item | Request | Loan | null = null) {
  error.value = "";
  kind.value = value;
  target.value = object;
  operation.value =
    value === "DELIVER"
      ? (object as Request).pendingOperationId || crypto.randomUUID()
      : crypto.randomUUID();
  purpose.value = "";
  reason.value = "";
  condition.value =
    value === "DELIVER" ? (object as Request).pendingCondition || "" : "";
  location.value = value === "MOVE" ? (object as Item).location || "" : "";
  quantity.value = ["RETURN", "REVIEW"].includes(value) && uncollected.value ? 0 : 1;
  mode.value = "WORKDAYS";
  days.value = 3;
  result.value = "CONFIRM";
  attachments.value = false;
  intact.value = false;
  actual.value = localTime(value === "DELIVER" ? (object as Request).pickupAt : undefined);
  const registration = value === "REVIEW" ? currentReturn.value?.registration : undefined;
  if (registration) {
    result.value = registration.result;
    condition.value = registration.condition;
    quantity.value = registration.actualQuantity;
    attachments.value = registration.allAttachmentsConfirmed;
    intact.value = registration.intactConfirmed;
    actual.value = localTime(registration.actualReceivedAt);
  }
  dialog.value?.showModal();
}
/** 显示原借出和每轮复核事实；未知任务号不能作为授权。 */
async function inspect(id: string, scroll = true) {
  if (detailLoading.value) return;
  detailLoading.value = true;
  error.value = "";
  try {
    detail.value = await api<Detail>(`/archive/physical-loans/${id}`);
    detailExpanded.value = true;
    if (scroll) {
      await nextTick();
      detailPanel.value?.scrollIntoView({ block: "nearest" });
    }
  } catch (failure) {
    detailExpanded.value = false;
    error.value = (failure as Error).message;
  } finally {
    detailLoading.value = false;
  }
}
/** 受敏感操作保护的凭据只对应当前对象与动作。 */
async function verify(type: string, id: string, action: string) {
  const password = await askPassword("验证本人身份，记录实际实物操作");
  if (password === null) return null;
  return (
    await api<{ verificationToken: string }>("/auth/reauthentications", {
      method: "POST",
      body: JSON.stringify({
        password,
        purpose: "MANAGE",
        objectIds: [id],
        scopeItems: [{ objectType: type, objectId: id, actions: [action] }],
      }),
    })
  ).verificationToken;
}
/** 每种操作只提交业务事实，不允许页面直接填写已归还、占用释放或已批准状态。 */
async function submit() {
  busy.value = true;
  error.value = "";
  try {
    let path = "",
      method = "POST",
      body: Record<string, unknown> = {},
      type = "",
      action = "",
      version: number | undefined;
    const id = target.value?.id || "";
    if (kind.value === "DELIVER") {
      const request = target.value as Request;
      path = `/archive/borrow-requests/${id}/deliveries`;
      body = {
        physicalItemIds: request.pendingPhysicalItemIds || [
          request.physicalItemId,
        ],
        actualDeliveryAt: new Date(actual.value).toISOString(),
        actualQuantity: request.pendingPhysicalItemIds?.length || 1,
        condition: condition.value,
      };
      if (!request.pendingOperationId) {
        type = "BORROW_REQUEST";
        action = "DELIVER_PHYSICAL";
      }
    }
    if (kind.value === "RETURN") {
      path = `/archive/physical-loans/${id}/return-requests`;
      body = {
        actualQuantity: quantity.value,
        condition: condition.value,
        note: reason.value || undefined,
      };
      version = (target.value as Loan).rowVersion;
    }
    if (kind.value === "REVIEW") {
      path = `/archive/physical-loans/${id}/return-reviews`;
      body = {
        returnRequestId: currentReturn.value!.id,
        result: result.value,
        actualReceivedAt: new Date(actual.value).toISOString(),
        actualQuantity: quantity.value,
        condition: condition.value,
        allAttachmentsConfirmed: attachments.value,
        intactConfirmed: intact.value,
      };
      if (currentReturn.value?.registration)
        body = { ...currentReturn.value.registration, returnRequestId: currentReturn.value.id };
      type = "PHYSICAL_LOAN";
      action = "REVIEW_PHYSICAL_RETURN";
      version = (target.value as Loan).rowVersion;
    }
    if (kind.value === "RENEW") {
      path = `/archive/physical-loans/${id}/renewals`;
      body = {
        purpose: purpose.value,
        reason: reason.value,
        period: { mode: mode.value, amount: days.value },
      };
    }
    if (kind.value === "MOVE") {
      path = `/archive/physical-items/${id}/location`;
      method = "PATCH";
      body = { location: location.value, reason: reason.value };
      type = "PHYSICAL_ITEM";
      action = "MOVE_PHYSICAL";
      version = (target.value as Item).rowVersion;
    }
    const proof = type ? await verify(type, id, action) : undefined;
    if (proof === null) return;
    const response = await api<{ state?: string; requiredStepId?: string }>(
      path,
      {
        method,
        headers: {
          "X-Operation-Id": operation.value,
          ...(proof ? { "X-Reauth-Token": proof } : {}),
          ...(version ? { "X-Expected-Version": String(version) } : {}),
        },
        body: JSON.stringify(body),
      },
    );
    if (response.state === "WAITING_APPROVAL")
      inform(
        "本次实际期限需要分管领导补批，尚未登记借出。批准后从原清单继续交付。",
      );
    else if (response.state === "WAITING_OTHER_HANDLER")
      inform("已登记本次交付，等待另一位档案管理员确认。");
    else if (response.state === "PENDING_APPROVAL")
      inform("归还申请已送审，实物交接确认后释放原件。");
    else if (response.state === "WAITING_SUPER_ADMIN")
      inform("归还交接已登记，等待档案超级管理员确认。");
    else
      inform(
        (
          {
            DELIVER: "实际交付已确认。",
            RETURN: "归还申请已提交，实际复核前仍保留占用。",
            REVIEW:
              result.value === "CONFIRM"
                ? "实物已确认归还并释放占用。"
                : "已记录归还异常，原件仍保留占用。",
            RENEW: "续借已送审，原截止与占用保持。",
            MOVE: "原件位置已更新。",
          } as Record<string, string>
        )[kind.value]!,
      );
    dialog.value?.close();
    await load();
    if (detail.value && ["RETURN", "REVIEW", "RENEW"].includes(kind.value))
      await inspect(detail.value.loan.id, false);
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 待办来源只控制初始页，详情和待交付查询都保留原授权。 */
onMounted(async () => {
  const params = new URLSearchParams(window.location.hash.split("?")[1] || "");
  const loan = params.get("loan");
  if (loan) {
    tab.value = "loans";
    listFilters.value = recentWeekFilters("delivery");
    await inspect(loan, false);
    // 以服务端已授权详情判断这笔是本人借阅还是经办事项。
    if (
      detail.value &&
      managed.value &&
      detail.value.loan.borrowerId !== profile.value?.user.id
    )
      scope.value = "MANAGE";
  } else if (
    params.get("view") === "requests" ||
    params.get("tab") === "requests"
  )
    tab.value = "requests";
  const detailError = error.value;
  await load();
  if (detailError) error.value = detailError;
});
/** 当前列表已提交的筛选条件。 */
const listFilters = ref<Record<string, string>>({});
</script>

<template>
  <section class="physical-workspace">
    <nav class="module-tabs" aria-label="实物业务分类">
      <button
        v-if="managed"
        :class="{ active: tab === 'inventory' }"
        @click="switchTab('inventory')"
      >
        实物台账</button
      ><button
        :class="{ active: tab === 'requests' }"
        @click="switchTab('requests')"
      >
        待交付清单</button
      ><button :class="{ active: tab === 'loans' }" @click="switchTab('loans')">
        借还记录</button
      ><button
        :class="{ active: tab === 'notices' }"
        @click="switchTab('notices')"
      >
        提醒与待办
      </button>
    </nav>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div class="physical-panel" v-if="tab === 'inventory'">
      <ListFilters
        :fields="[
          { key: 'keyword', label: '原件名称或编号' },
          {
            key: 'objectType',
            label: '原件形态',
            options: { BOX: '整盒原件', RECORD: '文件原件' },
          },
          {
            key: 'available',
            label: '占用状态',
            options: { true: '当前未借出', false: '仍被占用' },
          },
        ]"
        :values="listFilters"
        :key="tab"
        :busy="busy"
        @apply="
          listFilters = $event;
          page = 1;
          load();
        "
      />
      <div class="table-scroll">
        <table class="physical-table">
          <thead>
            <tr>
              <th>原件</th>
              <th>形态与位置</th>
              <th>当前状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in inventory"
              :key="item.id"
              :data-physical-id="item.id"
            >
              <td>
                <strong>{{ item.label }}</strong
                ><small v-if="item.barcode">{{ item.barcode }}</small>
              </td>
              <td>
                {{ item.objectType === "BOX" ? "整盒原件" : "文件原件"
                }}<small>{{ item.location || "由档案员核对交付位置" }}</small>
              </td>
              <td>
                <span class="physical-badge" :class="{ warn: item.occupied }">{{
                  item.occupied ? "仍被占用" : "当前未借出"
                }}</span>
              </td>
              <td>
                <button
                  v-if="item.canManage"
                  class="text-button"
                  @click="open('MOVE', item)"
                >
                  调整位置
                </button>
              </td>
            </tr>
            <tr v-if="!inventory.length">
              <td colspan="4" class="physical-empty">
                当前没有获准检索的实物，请先登记并完成纸质目录复核与题名开放。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div class="physical-panel" v-if="tab === 'requests'">
      <p class="muted">
        新申请末级批准后占用原件，两位经办人确认取件。旧申请在实际交付时占用。
      </p>
      <ListFilters
        :fields="[
          { key: 'keyword', label: '原件名称或编号' },
          {
            key: 'state',
            label: '审批状态',
            options: {
              PENDING: '审批中',
              APPROVED: '已批准',
              REJECTED: '已驳回',
              CLOSED: '已结束',
              WITHDRAWN: '已撤回',
            },
          },
        ]"
        :values="listFilters"
        :key="tab"
        :busy="busy"
        @apply="
          listFilters = $event;
          page = 1;
          load();
        "
      />
      <div class="table-scroll">
        <table class="physical-table">
          <thead>
            <tr>
              <th>批准原件</th>
              <th>状态</th>
              <th>实际交付</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="request in requests"
              :key="`${request.id}-${request.physicalItemId}`"
              :data-request-id="request.id"
            >
              <td>
                <strong>{{ request.label }}</strong
                ><small>{{ request.barcode }}</small>
                <small>{{ request.purpose }}</small>
              </td>
              <td>
                {{ request.delivered ? "已有借出记录" : states[request.state]
                }}<small
                  v-if="
                    request.pendingOperationId && request.state === 'PENDING'
                  "
                  >交付待领导补批，保留原操作号</small
                >
              </td>
              <td>
                <button
                  v-if="request.canDeliver"
                  class="primary"
                  @click="open('DELIVER', request)"
                >
                  {{
                    request.pendingOperationId ? "继续原交付" : request.pickupAt ? "确认交付" : "登记交付"
                  }}</button
                ><span v-else class="muted">{{
                  request.delivered
                    ? "见借还记录"
                    : "等待必要审批或所属部门办理"
                }}</span>
              </td>
            </tr>
            <tr v-if="!requests.length">
              <td colspan="3" class="physical-empty">暂无纸质申请。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div class="physical-panel" v-if="tab === 'loans'">
      <div class="physical-toolbar">
        <h2>借还记录</h2>
        <label v-if="managed" class="list-filter-field"
          >显示范围<select
            v-model="scope"
            @change="
              page = 1;
              load();
            "
          >
            <option value="MINE">我的借阅</option>
            <option value="MANAGE">我的经办范围</option>
          </select></label
        >
      </div>
      <ListFilters
        :fields="[
          { key: 'keyword', label: '原件名称或编号' },
          { key: 'borrower', label: '借阅人', placeholder: '姓名或工号' },
          {
            key: 'state',
            label: '借阅状态',
            options: { IN_USE: '借阅中', RETURNED: '已归还' },
          },
          { key: 'delivery', label: '实际交付日期', type: 'date' },
          { key: 'due', label: '应还日期', type: 'date' },
        ]"
        :values="listFilters"
        :key="tab"
        :busy="busy"
        @apply="
          listFilters = $event;
          page = 1;
          load();
        "
      />
      <div class="table-scroll">
        <table class="physical-table">
          <thead>
            <tr>
              <th>原件 / 编号</th>
              <th>借阅人</th>
              <th>实际交付</th>
              <th>应还截止</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="loan in loans" :key="loan.id" :data-loan-id="loan.id">
              <td>
                <strong>{{
                  loan.label ||
                  (loan.objectType === "BOX" ? "整盒原件" : "文件原件")
                }}</strong
                ><small>{{ loan.barcode }}</small>
              </td>
              <td>{{ loan.borrowerName }}</td>
              <td>{{ loan.actualDeliveryAt ? dateTime(loan.actualDeliveryAt) : "待交付" }}</td>
              <td>{{ dateTime(loan.dueAt) }}</td>
              <td>
                <span class="physical-badge" :class="{ warn: loan.overdue }">{{
                  loan.state === 'IN_USE' && !loan.actualDeliveryAt ? "待交付" : states[loan.state]
                }}</span
                ><small v-if="loan.returnPending">归还处理中 · 仍占用</small
                ><small v-if="loan.overdue">已逾期 · 仍占用</small>
              </td>
              <td>
                <button
                  class="text-button"
                  :disabled="detailLoading"
                  @click="inspect(loan.id)"
                >
                  查看借还
                </button>
              </td>
            </tr>
            <tr v-if="!loans.length">
              <td colspan="6" class="physical-empty">暂无实际借出记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
    <div class="physical-panel" v-if="tab === 'notices'">
      <div v-for="notice in notices" :key="notice.id" class="physical-notice">
        <div>
          <strong>{{ noticeNames[notice.kind] || "实物业务提醒" }}</strong
          ><small>原截止 {{ dateTime(notice.deadline) }}</small>
        </div>
        <button
          class="secondary"
          :disabled="detailLoading"
          @click="inspect(notice.loanId)"
        >
          查看借还
        </button>
      </div>
      <p v-if="!notices.length" class="physical-empty">
        暂无待查看的实物事项。
      </p>
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
        <template v-if="tab !== 'notices'">
          <span class="list-total">共 {{ total }} 条记录</span
          ><button
            class="secondary"
            :disabled="page === 1"
            @click="
              page--;
              load();
            "
          >
            上一页</button
          ><span>{{ page }}</span
          ><button
            class="secondary"
            :disabled="page * 20 >= total"
            @click="
              page++;
              load();
            "
          >
            下一页
          </button> </template
        ><span v-else class="list-total">共 {{ notices.length }} 条提醒</span>
      </div>
    </footer>
    <section
      v-if="detail && loanProgress && ['loans', 'notices'].includes(tab)"
      ref="detailPanel"
      class="physical-panel loan-detail"
      aria-labelledby="loan-detail-title"
    >
      <header class="flow-dialog-header loan-detail-heading">
        <span class="flow-dialog-icon"><Icon name="archive" :size="22" /></span>
        <div class="flow-dialog-title">
          <h2 id="loan-detail-title">原件借还详情</h2>
          <p class="loan-detail-summary">
            <strong>{{
              detail.loan.label ||
              (detail.loan.objectType === "BOX" ? "整盒原件" : "文件原件")
            }}</strong>
            <span>{{ detail.loan.barcode }}</span>
            <span>借阅人：{{ detail.loan.borrowerName }}</span>
          </p>
        </div>
        <div class="loan-detail-controls">
          <span
            class="physical-badge"
            :class="{
              warn: detail.loan.overdue || loanProgress.label === '归还异常',
            }"
            >{{ loanProgress.label }}</span
          >
          <button
            class="secondary"
            :aria-expanded="detailExpanded"
            aria-controls="loan-detail-body"
            :disabled="detailLoading"
            @click="
              detailExpanded
                ? (detailExpanded = false)
                : inspect(detail.loan.id)
            "
          >
            {{ detailExpanded ? "收起详情" : "展开详情" }}
          </button>
        </div>
      </header>
      <div id="loan-detail-body" v-show="detailExpanded">
        <div class="flow-dialog-body loan-detail-body">
          <section class="flow-dialog-overview loan-progress">
            <h3>当前办理</h3>
            <p>{{ loanProgress.text }}</p>
          </section>
          <div class="loan-detail-grid">
            <section class="loan-detail-group">
              <h3>借阅申请</h3>
              <dl class="loan-facts">
                <div>
                  <dt>借阅人</dt>
                  <dd>
                    {{ detail.loan.borrowerName }}（{{
                      detail.loan.borrowerEmployeeNo
                    }}）
                  </dd>
                </div>
                <div>
                  <dt>申请时所属部门</dt>
                  <dd>{{ detail.loan.borrowerDepartmentName }}</dd>
                </div>
                <div>
                  <dt>申请时间</dt>
                  <dd>{{ dateTime(detail.loan.submittedAt) }}</dd>
                </div>
                <div>
                  <dt>借阅用途</dt>
                  <dd>{{ detail.loan.purpose }}</dd>
                </div>
              </dl>
            </section>
            <section class="loan-detail-group">
              <h3>实物交接</h3>
              <dl class="loan-facts">
                <div>
                  <dt>原件类型</dt>
                  <dd>
                    {{
                      detail.loan.objectType === "BOX" ? "整盒原件" : "文件原件"
                    }}
                  </dd>
                </div>
                <div v-if="detail.loan.actualDeliveryAt">
                  <dt>交付数量</dt>
                  <dd>
                    {{ detail.loan.actualQuantity }}
                    {{ detail.loan.objectType === "BOX" ? "盒" : "件" }}
                  </dd>
                </div>
                <div>
                  <dt>交付经办人</dt>
                  <dd>{{ detail.loan.deliveredByName || "待双方确认" }}</dd>
                </div>
                <div>
                  <dt>实际交付</dt>
                  <dd>{{ detail.loan.actualDeliveryAt ? dateTime(detail.loan.actualDeliveryAt) : "待交付" }}</dd>
                </div>
                <div>
                  <dt>应还截止</dt>
                  <dd>
                    {{ dateTime(detail.loan.dueAt) }}
                    <span v-if="detail.loan.overdue" class="physical-badge warn"
                      >已逾期</span
                    >
                  </dd>
                </div>
                <div v-if="detail.loan.returnedAt">
                  <dt>实际归还</dt>
                  <dd>{{ dateTime(detail.loan.returnedAt) }}</dd>
                </div>
                <div v-if="detail.loan.condition">
                  <dt>交接情况</dt>
                  <dd>{{ detail.loan.condition }}</dd>
                </div>
              </dl>
              <details
                v-if="detail.loan.memberLabels?.length"
                class="loan-members"
              >
                <summary>
                  整盒包含的文件（{{ detail.loan.memberLabels.length }} 份）
                </summary>
                <ol>
                  <li
                    v-for="(label, index) in detail.loan.memberLabels"
                    :key="index"
                  >
                    {{ label }}
                  </li>
                </ol>
              </details>
            </section>
          </div>
          <section class="loan-return-history">
            <h3>归还与复核记录</h3>
            <p v-if="!detail.returns.length" class="muted">尚无归还申请。</p>
            <article
              v-for="returned in detail.returns"
              :key="returned.id"
              class="return-round"
            >
              <header>
                <strong>{{
                  returned.approvalState === "REJECTED" ? "归还审批已驳回"
                    : returned.approvalState === "WITHDRAWN" ? "归还申请已撤回"
                    : returned.state === "PENDING" && returned.approvalState === "PENDING"
                      ? "归还审批中" : returnStates[returned.state]
                }}</strong>
                <span class="muted"
                  >申请时间：{{ dateTime(returned.submittedAt) }}</span
                >
              </header>
              <dl class="loan-facts">
                <div>
                  <dt>申报归还数量</dt>
                  <dd>{{ returned.reportedQuantity }}</dd>
                </div>
                <div>
                  <dt>申报情况</dt>
                  <dd>{{ returned.condition }}</dd>
                </div>
                <div v-if="returned.note">
                  <dt>归还备注</dt>
                  <dd>{{ returned.note }}</dd>
                </div>
                <template v-if="returned.reviewedAt">
                  <div>
                    <dt>复核经办人</dt>
                    <dd>{{ returned.reviewedByName }}</dd>
                  </div>
                  <div>
                    <dt>复核时间</dt>
                    <dd>{{ dateTime(returned.reviewedAt) }}</dd>
                  </div>
                  <div v-if="returned.actualReceivedAt">
                    <dt>实际收到</dt>
                    <dd>{{ dateTime(returned.actualReceivedAt) }}</dd>
                  </div>
                  <div v-if="returned.receivedQuantity !== null">
                    <dt>实收数量</dt>
                    <dd>{{ returned.receivedQuantity }}</dd>
                  </div>
                  <div v-if="returned.reviewCondition">
                    <dt>复核说明</dt>
                    <dd>{{ returned.reviewCondition }}</dd>
                  </div>
                </template>
              </dl>
            </article>
          </section>
        </div>
        <footer
          v-if="
            currentReturn
              ? detail.loan.canReview || detail.loan.canRegisterReturn || showReturn
              : detail.loan.canRenew || detail.loan.canRequestReturn
          "
          class="flow-dialog-footer"
        >
          <button
            v-if="detail.loan.canRenew && !currentReturn"
            class="secondary"
            @click="open('RENEW', detail.loan)"
          >
            申请续借
          </button>
          <button
            v-if="showReturn"
            :disabled="!detail.loan.canRequestReturn || !!currentReturn"
            class="primary"
            @click="open('RETURN', detail.loan)"
          >
            申请归还
          </button>
          <button
            v-if="(detail.loan.canReview || detail.loan.canRegisterReturn) && currentReturn"
            class="primary"
            @click="open('REVIEW', detail.loan)"
          >
            {{ detail.loan.canRegisterReturn ? "登记归还交接" : "复核实收" }}
          </button>
        </footer>
      </div>
    </section>
  </section>
  <dialog
    ref="dialog"
    class="form-dialog flow-dialog physical-dialog"
    aria-labelledby="physical-operation-title"
    @cancel.prevent="!busy && dialog?.close()"
  >
    <form class="flow-dialog-shell" @submit.prevent="submit">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="archive" :size="22" /></span>
        <h2 id="physical-operation-title">{{ titles[kind] }}</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭实物操作"
          :disabled="busy"
          @click="dialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section v-if="kind === 'DELIVER'" class="flow-dialog-overview">
          <h3>交付说明</h3>
          <p class="muted">
            {{
              (target as Request).reservedAt
                ? "请核对实际交出的原件、数量和附件，由档案所属部门档案员与档案超级管理员分别确认。"
                : "请先核对实际交出的原件、数量及附件；需要补批时，系统暂不登记已借出。"
            }}
          </p>
        </section>
        <section v-if="kind === 'RENEW'" class="flow-dialog-overview">
          <h3>原借阅</h3>
          <p class="muted">
            原截止
            {{ dateTime((target as Loan).dueAt) }}。批准前保持原截止和占用。
          </p>
        </section>
        <template v-if="kind === 'RENEW'">
          <section class="flow-dialog-section">
            <h3>借阅用途</h3>
            <label>
              实际业务用途
              <textarea v-model="purpose" rows="3" maxlength="2000" required />
            </label>
          </section>
          <section class="flow-dialog-section">
            <h3>借阅期限</h3>
            <div class="flow-dialog-fields">
              <label class="flow-inline-field">
                期限规则
                <select v-model="mode">
                  <option value="WORKDAYS">工作日</option>
                  <option value="NATURAL_DAYS">自然日</option>
                </select>
              </label>
              <label class="flow-inline-field">
                期限天数
                <input
                  v-model.number="days"
                  type="number"
                  min="1"
                  max="30"
                  required
                />
              </label>
            </div>
            <p class="muted">
              默认 3 个工作日，按国家放假和调休安排计算。{{
                can("ARCHIVE.IT_SHARED_BORROW")
                  ? "IT 通用账号由档案超级管理员审批。"
                  : (target as Loan).reservedAt
                    ? "延期批准后，从原应还时间和延期批准时间中较晚的一刻起算。"
                    : "累计借出超过 7 个自然日需分管领导批准。"
              }}
            </p>
          </section>
        </template>
        <section
          v-if="['DELIVER', 'RETURN', 'REVIEW'].includes(kind)"
          class="flow-dialog-section"
        >
          <h3>{{ kind === "REVIEW" ? "实物核验" : "交接信息" }}</h3>
          <p v-if="uncollected && kind !== 'DELIVER'" class="muted">未取件，原件数量填 0。</p>
          <label v-if="kind === 'REVIEW'" class="flow-inline-field"
            >实物核验结论<select v-model="result" :disabled="reviewingRegistration">
              <option value="CONFIRM">{{ uncollected ? "确认未取件" : "数量、附件及完好情况全部符合" }}</option>
              <option value="EXCEPTION">未收到、少件或破损，登记异常</option>
            </select></label
          >
          <label
            v-if="['DELIVER', 'REVIEW'].includes(kind)"
            class="flow-inline-field"
            >{{ kind === "DELIVER" ? "实际交付时间" : uncollected ? "确认未取件时间" : "实际收到时间"
            }}<input
              v-model="actual"
              type="datetime-local"
              step="0.001"
              :readonly="reviewingRegistration || (kind === 'DELIVER' && !!(target as Request)?.pickupAt)"
              required
          /></label>
          <label
            v-if="['RETURN', 'REVIEW'].includes(kind)"
            class="flow-inline-field"
            >实际盒/件数量<input
              v-model.number="quantity"
              type="number"
              min="0"
              max="1"
              :readonly="uncollected || reviewingRegistration"
              required /></label
          ><label v-if="['DELIVER', 'RETURN', 'REVIEW'].includes(kind)"
            >数量、附件及完好说明<textarea
              v-model="condition"
              maxlength="2000"
              rows="3"
              :readonly="reviewingRegistration || (kind === 'DELIVER' && !!(target as Request)?.pickupAt)"
              required
            />
          </label>
          <div
            class="physical-confirm"
            v-if="kind === 'REVIEW' && result === 'CONFIRM' && !uncollected"
          >
            <label
              ><input
                type="checkbox"
                v-model="attachments"
                :disabled="reviewingRegistration"
              />已实际核对全部附件</label
            ><label
              ><input
                type="checkbox"
                v-model="intact"
                :disabled="reviewingRegistration"
              />已实际核对原件完好</label
            >
          </div>
        </section>
        <section v-if="kind === 'MOVE'" class="flow-dialog-section">
          <h3>存放位置</h3>
          <label class="flow-inline-field">
            新存放位置<input v-model="location" maxlength="512" required />
          </label>
        </section>
        <section
          v-if="['RENEW', 'MOVE', 'RETURN'].includes(kind)"
          class="flow-dialog-section"
        >
          <h3>{{ kind === "RETURN" ? "补充信息" : "变更说明" }}</h3>
          <label
            >{{ kind === "RETURN" ? "归还补充说明" : "实际原因"
            }}<textarea
              v-model="reason"
              maxlength="1000"
              :required="kind !== 'RETURN'"
              rows="2"
            />
          </label>
        </section>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button
          class="secondary"
          type="button"
          :disabled="busy"
          @click="dialog?.close()"
        >
          取消</button
        ><button
          class="primary"
          :disabled="
            busy ||
            (kind === 'REVIEW' &&
              result === 'CONFIRM' &&
              (!attachments || !intact || quantity !== 1))
          "
        >
          {{ busy ? "正在提交…" : "确认提交" }}
        </button>
      </footer>
    </form>
  </dialog>
</template>

<style scoped>
.physical-workspace {
  max-width: none;
  margin: 0 auto;
  padding: 0;
  min-width: 0;
}
.physical-panel {
  background: white;
  border: 1px solid var(--line);
  border-radius: 13px;
  padding: 26px 28px;
  margin-bottom: 20px;
  box-shadow: 0 8px 22px #28504104;
}
.physical-toolbar {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  margin-bottom: 20px;
}
.physical-toolbar form,
.physical-actions {
  display: flex;
  flex-wrap: wrap;
  min-width: 0;
  gap: 12px;
}
.physical-toolbar form input {
  width: min(240px, 100%);
}
.physical-toolbar h2 {
  font-size: 17px;
  margin: 0;
}
.physical-toolbar label {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
}
.physical-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.physical-table th {
  text-align: left;
  background: var(--surface-muted);
  color: var(--text-secondary);
  font-weight: 400;
  padding: 14px 12px;
}
.physical-table td {
  padding: 20px 12px;
  border-bottom: 1px solid var(--line);
}
.physical-table strong {
  display: block;
  color: var(--ink);
}
.physical-table small,
.physical-notice small {
  display: block;
  color: #87968d;
  margin-top: 7px;
}
.physical-badge {
  background: var(--surface-muted);
  color: var(--ink);
  padding: 5px 9px;
  border-radius: 4px;
  font-size: 11px;
}
.physical-badge.warn {
  color: #987333;
  background: #fbf3df;
}
.physical-empty {
  padding: 36px !important;
  text-align: center;
  color: var(--text-secondary);
}
.loan-detail-heading,
.loan-detail-controls,
.return-round header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px 20px;
}
.loan-detail-heading h2 {
  margin: 0;
  font-size: 17px;
}
.loan-detail-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 6px 14px;
  margin: 8px 0 0;
  color: var(--text-secondary);
  font-size: 13px;
}
.loan-detail-summary strong {
  color: var(--ink);
}
.loan-progress p {
  margin: 0;
  font-size: 13px;
}
.loan-detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 320px), 1fr));
  gap: 16px;
}
.loan-detail-group {
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 8px;
  min-width: 0;
}
.loan-detail-body h3 {
  margin: 0 0 12px;
  font-size: 14px;
}
.loan-facts {
  display: grid;
  gap: 8px;
  margin: 0;
  font-size: 13px;
}
.loan-facts > div {
  display: grid;
  grid-template-columns: 8em minmax(0, 1fr);
  gap: 8px;
}
.loan-facts dt {
  color: var(--text-secondary);
}
.loan-facts dd {
  margin: 0;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.loan-members {
  margin-top: 14px;
  font-size: 13px;
}
.loan-members summary {
  cursor: pointer;
}
.return-round {
  border-top: 1px solid var(--line);
  padding: 14px 0;
  font-size: 12px;
  color: var(--ink);
}
.return-round header {
  margin-bottom: 10px;
}
.physical-notice {
  display: flex;
  justify-content: space-between;
  padding: 18px 0;
  border-bottom: 1px solid #e8eee7;
  font-size: 13px;
}
.physical-dialog {
  --flow-dialog-width: 640px;
}
.physical-confirm {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 20px;
}
.physical-confirm label {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 7px;
  font-size: 12px;
}
.physical-confirm input {
  width: 16px;
  height: 16px;
  min-height: 0;
  flex: 0 0 auto;
}
.loan-detail {
  margin-top: 32px;
  padding: 0;
}
</style>
