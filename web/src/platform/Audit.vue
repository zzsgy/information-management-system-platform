<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { ApiError, responseRequestId, responseError, requestFetch, api, can, dateTime, mutate, profile, query } from "./api";
import AuditStorage from "./AuditStorage.vue";
import DateRangePreset from "./DateRangePreset.vue";
import { recentDateRange } from "./listDates";
const section = ref("events");
/** 审计视图只接收可公开字段，不接收原始请求或幂等摘要。 */
interface AuditRow {
  id: string;
  occurredAt: string;
  actorId: string | null;
  /** 关联账号当前的工号。 */
  actorEmployeeNo: string | null;
  module: string;
  action: string;
  /** 服务端用固定路由模板解析出的操作说明。 */
  operationLabel?: string | null;
  result: string;
  resourceType: string;
  requestId: string;
}
const kind = ref("BUSINESS"),
  employee = ref(""),
  requestId = ref(""),
  queryId = ref(""),
  exporting = ref(false),
  loadedFilter = ref("");
const askPassword =
  inject<(title: string) => Promise<string | null>>("askPassword")!;
const rows = ref<AuditRow[]>([]),
  page = ref(1),
  total = ref<number | null>(null),
  counting = ref(false),
  hasNext = ref(false),
  error = ref(""),
  loading = ref(false),
  result = ref("");
/** 本页默认最近七天，显式限定查询时间避免全历史无界扫描。 */
const defaultRange = recentDateRange(7);
const from = ref(defaultRange.from),
  to = ref(defaultRange.to);
const module = ref("PLATFORM");
let version = 0;
/** 只保存服务端游标，返回上一页仍使用同一查询截点，不保存业务记录副本。 */
const cursors = ref<string[]>([]),
  nextCursor = ref<string | null>(null);
/** 常用动作本地化，未知动作仍保留稳定代码方便核对。 */
const names: Record<string, string> = {
  HTTP_GET: "查询（对象未记录）",
  HTTP_POST: "提交（对象未记录）",
  HTTP_PUT: "编辑（对象未记录）",
  HTTP_PATCH: "编辑（对象未记录）",
  HTTP_DELETE: "删除（对象未记录）",
  AUDIT_QUERY: "查询操作记录",
  EXPORT_AUDIT: "批准日志导出",
  EXPORT_RESPONSE_PREPARED: "日志文件已准备",
  DISPOSE_SECURITY: "安全告警处置",
  EXTEND_RETENTION: "延长保留期",
  ARCHIVE_LOGS: "提交日志归档",
  ARCHIVE_VERIFIED: "核验日志归档",
  LOGIN: "登录",
  LOGOUT: "退出登录",
  INITIALIZE: "初始化平台",
  CREATE_USER: "新增人员",
  UPDATE_USER: "修改人员资料",
  CREATE_DEPARTMENT: "新增部门",
  UPDATE_DEPARTMENT: "修改部门",
  USER_STATUS: "变更账号状态",
  ASSIGN_ROLES: "分配角色",
  ROLE_PERMISSIONS: "调整角色权限",
  REAUTH: "本人身份验证",
  PASSWORD_CHANGE: "修改密码",
  UPDATE_OWN_MOBILE: "修改本人手机号",
  REVOKE_SESSION: "结束登录",
  PERMISSION_DENIED: "无权操作",
  CSRF_INVALID: "请求验证失败",
  SESSION_INVALID: "登录失效",
  VERIFY_PASSWORD: "验证密码",
};
/** 固定意图代码转中文，业务正文从不作为操作标签。 */
function actionName(row: AuditRow) {
  if (row.result !== "INTENT")
    return row.operationLabel || names[row.action] || row.action;
  const [page, action] = row.action.split(":");
  const pages: Record<string, string> = {
    workspace: "工作台",
    audit: "操作记录",
    security: "安全管理",
    archive: "档案",
    drive: "公盘",
    workflow: "审批",
    physical: "纸质借还",
    roles: "角色权限",
    users: "人员管理",
    config: "管理配置",
    feedback: "意见反馈",
    announcements: "公告",
    tasks: "待办消息",
    other: "其他页面",
  };
  const actions: Record<string, string> = {
    ACTIVATE: "操作控件",
    SUBMIT: "提交表单",
    CHANGE: "调整选项",
    NAVIGATE: "切换页面",
    CANCEL: "取消操作",
    EXPAND: "展开内容",
  };
  return `${pages[page!] || page} · ${actions[action!] || action}`;
}
function resultName(value: string) {
  return (
    (
      {
        SUCCEEDED: "业务成功",
        FAILED: "处理失败",
        MANUAL: "待人工核对",
        DENIED: "已拒绝",
        INTENT: "用户意图",
        HTTP_200: "响应完成",
        HTTP_201: "已创建",
        HTTP_202: "已受理",
      } as Record<string, string>
    )[value] ||
    (value.startsWith("HTTP_FAILED_")
      ? `请求未完成 (${value.slice(12)})`
      : value)
  );
}
/** 导出绑定最近一次成功查询，改变表单条件后必须重新查询。 */
function filters() {
  return {
    from: new Date(`${from.value}T00:00:00+08:00`).toISOString(),
    to: new Date(
      new Date(`${to.value}T00:00:00+08:00`).getTime() + 86400000,
    ).toISOString(),
    module: module.value,
    result: result.value,
    kind: kind.value,
    employee: employee.value,
    requestId: requestId.value,
  };
}
/** 比较表单原值，日期暂时清空时不在渲染阶段解析日期。 */
const filterSignature = () =>
  JSON.stringify([
    from.value,
    to.value,
    module.value,
    result.value,
    kind.value,
    employee.value,
    requestId.value,
  ]);
const canExport = computed(
  () =>
    !!queryId.value &&
    can(`${module.value}.AUDIT_EXPORT`) &&
    loadedFilter.value === filterSignature(),
);
/** 原查询票据再验证后下载；浏览器收到的仍是当前权限重新过滤后的有界CSV。 */
async function download() {
  if (!canExport.value || exporting.value) return;
  const id = queryId.value,
    password = await askPassword("导出当前查询的操作记录");
  if (!password) return;
  exporting.value = true;
  error.value = "";
  try {
    await mutate(
      `/admin/audit-exports/${id}`,
      "POST",
      {},
      id,
      "AUDIT_EXPORT",
      "EXPORT_AUDIT",
      password,
    );
    const response = await requestFetch(`/api/v1/admin/audit-exports/${id}/file`, {
      credentials: "same-origin",
      cache: "no-store",
      headers: {
        "X-CSRF-Token": profile.value?.csrfToken ?? "",
        "X-Request-Id": crypto.randomUUID(),
      },
    });
    if (!response.ok) throw await responseError(response, "文件下载失败");
    if (response.headers.get("Content-Type")?.split(";")[0] !== "text/csv")
      throw new ApiError("AUDIT_EXPORT_FORMAT_INVALID", "服务器返回的操作记录不是CSV文件，未保存文件", responseRequestId(response), response.status);
    const blob = await response.blob();
    if (!blob.size) throw new ApiError("AUDIT_EXPORT_CONTENT_EMPTY", "服务器返回的操作记录文件为空", responseRequestId(response), response.status);
    const url = URL.createObjectURL(blob),
      link = document.createElement("a");
    link.href = url;
    link.download = "操作记录.csv";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    exporting.value = false;
  }
}
/** 筛选创建查询票据，后续只传随机游标；过期响应不能覆盖新时间范围。 */
async function load(reset = false) {
  if (reset) {
    page.value = 1;
    total.value = null;
    cursors.value = [];
    queryId.value = "";
    loadedFilter.value = "";
    nextCursor.value = null;
    hasNext.value = false;
  }
  const current = ++version;
  loading.value = true;
  error.value = "";
  try {
    const signature = filterSignature(),
      cursor = cursors.value[page.value - 1];
    const data = await api<{
      items: AuditRow[];
      queryId: string;
      cursor: string;
      nextCursor: string | null;
      hasNext: boolean;
    }>(
      `/admin/audit-events?${query(cursor ? { cursor } : { ...filters(), pageSize: 20 })}`,
    );
    if (current !== version) return;
    queryId.value = data.queryId;
    loadedFilter.value = signature;
    rows.value = data.items;
    cursors.value[page.value - 1] = data.cursor;
    nextCursor.value = data.nextCursor;
    hasNext.value = data.hasNext;
  } catch (failure) {
    if (current === version) error.value = (failure as Error).message;
  } finally {
    if (current === version) loading.value = false;
  }
}
/** 精确总数由用户按需获取，大时间范围统计不会阻塞每次翻页。 */
async function countRows() {
  if (!queryId.value || counting.value) return;
  const id = queryId.value,
    current = version;
  counting.value = true;
  error.value = "";
  try {
    const data = await api<{ total: number }>(
      `/admin/audit-events/${id}/count`,
    );
    if (current === version) total.value = data.total;
  } catch (failure) {
    if (current === version) error.value = (failure as Error).message;
  } finally {
    counting.value = false;
  }
}
/** 表单已变化时先开启新查询，避免按旧游标展示新筛选条件下的记录。 */
async function movePage(step: number) {
  if (loadedFilter.value !== filterSignature()) {
    await load(true);
    return;
  }
  if (step > 0 && nextCursor.value)
    cursors.value[page.value] = nextCursor.value;
  const before = page.value;
  page.value += step;
  await load();
  if (error.value) page.value = before;
}
onMounted(() => void load());
</script>
<template>
  <nav
    v-if="can('PLATFORM.SECURITY_MANAGE')"
    class="module-tabs"
    aria-label="操作记录功能"
  >
    <button
      :class="{ active: section === 'events' }"
      @click="section = 'events'"
    >
      操作记录</button
    ><button
      :class="{ active: section === 'storage' }"
      @click="section = 'storage'"
    >
      存储与归档
    </button>
  </nav>
  <AuditStorage v-if="section === 'storage'" />
  <template v-else>
    <section class="page-heading">
      <div>
        <span class="eyebrow">审计查询</span>
        <h1>操作记录</h1>
        <p>
          可查询全公司操作记录。界面操作仅代表操作意图，导出需独立权限。
        </p>
      </div>
      <button
        v-if="can(`${module}.AUDIT_EXPORT`)"
        class="secondary"
        :disabled="!canExport || loading || exporting"
        @click="download"
      >
        {{ exporting ? "正在导出…" : "导出本次查询" }}
      </button>
    </section>
    <section class="panel management-panel">
      <form
        id="audit-filters"
        class="toolbar audit-toolbar"
        @submit.prevent="load(true)"
      >
        <DateRangePreset
          label="操作日期范围"
          :from="from"
          :to="to"
          :busy="loading"
          required
          @apply="
            from = $event.from;
            to = $event.to;
            load(true);
          "
        />
        <label class="list-filter-field"
          >开始日期<input v-model="from" type="date" required /></label
        ><label class="list-filter-field"
          >结束日期<input v-model="to" type="date" required /></label
        ><label class="list-filter-field"
          >模块<select v-model="module" @change="kind = 'BUSINESS'">
            <option value="PLATFORM">平台</option>
            <option value="DRIVE">公盘</option>
            <option value="ARCHIVE">档案</option>
          </select></label
        ><label class="list-filter-field"
          >结果<select v-model="result">
            <option value="">全部结果</option>
            <option value="SUCCEEDED">成功</option>
            <option value="DENIED">已拒绝</option>
            <option value="HTTP_200">HTTP响应完成</option>
            <option value="INTENT">用户意图</option>
          </select></label
        ><label class="list-filter-field"
          >记录类型<select v-model="kind">
            <option value="BUSINESS">服务端结果</option>
            <option v-if="module === 'PLATFORM'" value="INTERACTION">
              界面操作意图
            </option>
          </select></label
        ><label class="list-filter-field"
          >员工<input
            v-model.trim="employee"
            maxlength="200"
            placeholder="姓名或工号" /></label
        ><label class="list-filter-field"
          >请求编号<input
            v-model.trim="requestId"
            maxlength="36"
            placeholder="可选：完整请求编号" /></label
        ><button class="secondary" :disabled="loading">查询</button>
      </form>
      <p v-if="error" class="form-error inset" role="alert">{{ error }}</p>
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>发生时间</th>
              <th>操作者</th>
              <th>操作</th>
              <th>对象类别</th>
              <th>结果</th>
              <th>请求编号</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td>{{ dateTime(row.occurredAt) }}</td>
              <td>
                {{ row.actorId ? row.actorEmployeeNo || "账号未找到" : "系统" }}
              </td>
              <td>
                <strong>{{ actionName(row) }}</strong>
              </td>
              <td>
                {{
                  {
                    BUTTON: "按钮",
                    LINK: "链接",
                    FORM: "表单",
                    SELECT: "选项",
                    INPUT: "输入控件",
                    SEARCH: "搜索",
                    DETAILS: "展开控件",
                    DIALOG: "对话框",
                    ROUTE: "页面导航",
                    HTTP_REQUEST: "接口响应",
                    USER: "员工账号",
                    DEPARTMENT: "部门",
                    ROLE: "角色",
                    SESSION: "登录会话",
                    COLLECTION: "管理目录",
                    REQUEST: "访问请求",
                    AUDIT_ARCHIVE: "日志归档",
                    AUDIT_QUERY: "审计查询",
                  }[row.resourceType as "USER"] || row.resourceType
                }}
              </td>
              <td>
                <span
                  class="status-badge"
                  :class="{
                    rejected:
                      ['DENIED', 'FAILED'].includes(row.result) ||
                      row.result.startsWith('HTTP_FAILED_'),
                  }"
                  >{{ resultName(row.result) }}</span
                >
              </td>
              <td>
                <code class="request-id" :title="row.requestId">{{
                  row.requestId.slice(0, 8)
                }}</code>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="!rows.length && !loading" class="empty-state">
        <h3>此时间范围内没有记录</h3>
        <p>调整筛选条件后再次查询。</p>
      </div>
      <footer class="list-footer">
        <button
          type="submit"
          form="audit-filters"
          class="secondary list-refresh"
          :disabled="loading"
        >
          刷新列表
        </button>
        <div class="list-pagination">
          <span>第 {{ page }} 页</span>
          <div>
            <button
              class="secondary"
              :disabled="
                loading ||
                counting ||
                !queryId ||
                loadedFilter !== filterSignature()
              "
              @click="countRows"
            >
              {{ counting ? "正在统计…" : "统计总数" }}
            </button>
            <button
              class="secondary"
              :disabled="page <= 1 || loading"
              @click="movePage(-1)"
            >
              上一页</button
            ><button
              class="secondary"
              :disabled="!hasNext || loading"
              @click="movePage(1)"
            >
              下一页
            </button>
          </div>
          <span class="list-total">{{
            total === null ? "总数未统计" : `共 ${total} 条`
          }}</span>
        </div>
      </footer>
    </section>
  </template>
</template>

<style scoped>
.audit-toolbar {
  flex-wrap: wrap;
}
.audit-toolbar input {
  max-width: 200px;
}
</style>
