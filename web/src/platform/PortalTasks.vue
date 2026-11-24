<script setup lang="ts">
import ListFilters from "./ListFilters.vue";
import { recentWeekFilters } from "./listDates";
import EmptyState from "./EmptyState.vue";
import EmployeeTodoDialog from "./EmployeeTodoDialog.vue";
import TodoDesktopSettings from "./TodoDesktopSettings.vue";
import { computed, onActivated, onDeactivated, onMounted, ref } from "vue";
import { api, query, type Page } from "./api";
import { portalTime, messageNames } from "./portal";
/** 紧凑模式用于首页；完整模式提供全部分页，避免把首页预览数量当总数。 */
const props = withDefaults(
  defineProps<{ inbox?: boolean; compact?: boolean }>(),
  { inbox: false, compact: false },
);
/** 工作台预览最多五条，完整待办和消息列表分别按十条和十五条向服务端请求。 */
const pageSize = computed(() => (props.compact ? 5 : props.inbox ? 15 : 10));
interface Item {
  id: string;
  title: string;
  category: string;
  dueAt: string | null;
  content?: string | null;
  entry: string;
  kind: string;
  state: string;
  createdAt: string;
  /** 已办记录的实际完成时间。 */
  completedAt: string | null;
  source: string;
}
const items = ref<Item[]>([]),
  total = ref(0),
  page = ref(1),
  filter = ref(
    !props.inbox &&
      !props.compact &&
      new URLSearchParams(location.hash.split("?")[1]).get("tab") ===
        "completed"
      ? "COMPLETED"
      : "",
  ),
  busy = ref(false),
  error = ref("");
const pageCount = computed(() =>
  Math.max(1, Math.ceil(total.value / pageSize.value)),
);
const editor = ref<{ id?: string; assign?: boolean }>();
const canAssign = ref(false);
/** 关闭邀请保留在历史，状态仍反映本人实际回复。 */
const meetingTaskStates: Record<string, string> = {
  MEETING_REPLY_PENDING: "待回复参会",
  MEETING_MATERIALS_PENDING: "待提交会前材料",
  MEETING_FEEDBACK_PENDING: "待提交会后反馈",
  MEETING_FEEDBACK_DONE: "反馈已提交",
  MEETING_MINUTES_AVAILABLE: "纪要已发布",
  MEETING_CANCELLED: "会议已取消",
  ACCEPTED: "已回复 · 参加",
  DECLINED: "已回复 · 不能参加",
  INVITE_EXPIRED: "已截止 · 未回复",
  INVITE_CANCELLED: "会议已取消 · 未回复",
  MATERIAL_SUBMITTED: "已提交",
  MATERIAL_EXPIRED: "已截止 · 未提交",
  MATERIAL_CANCELLED: "会议已取消 · 未提交",
  OVERDUE: "逾期",
};
const todoStates: Record<string, string> = {
  OPEN: "待办理",
  SUBMITTED: "待确认",
  COMPLETED: "已完成",
};
/** 工作台后台刷新时保留当前列表，切换分类仍清空旧结果。 */
async function load(keepRows = false) {
  busy.value = true;
  error.value = "";
  if (!keepRows) items.value = [];
  try {
    const data = await api<Page<Item>>(
      `/portal/${props.inbox ? "inbox" : "dashboard-tasks"}?${query({ page: page.value, pageSize: pageSize.value, ...(props.inbox ? { unreadOnly: filter.value || "false", ...listFilters.value } : { category: props.compact && !filter.value ? "RECENT" : filter.value, ...listFilters.value }) })}`,
    );
    items.value = data.items;
    total.value = data.total;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function open(item: Item) {
  try {
    if (props.inbox) {
      const result = await api<{ entry: string }>(
        `/portal/inbox/${item.id}/read`,
        { method: "POST" },
      );
      location.hash = result.entry;
    } else if (item.source === "PERSONAL" || item.source === "ASSIGNED") {
      editor.value = { id: item.id };
    } else location.hash = item.entry;
  } catch (e) {
    error.value = (e as Error).message;
    void load();
  }
}
async function changed() {
  editor.value = undefined;
  await load();
  if (!items.value.length && page.value > 1) {
    page.value--;
    await load();
  }
}
onActivated(() => {
  if (props.compact && !busy.value) void load(true);
});
onDeactivated(() => {
  editor.value = undefined;
});
onMounted(async () => {
  if (!props.compact) void load();
  if (props.inbox) return;
  if (!props.compact) {
    const id = new URLSearchParams(location.hash.split("?")[1] || "").get(
      "item",
    );
    if (id) editor.value = { id };
  }
  try {
    canAssign.value = (
      await api<{ canAssign: boolean }>("/portal/todos/candidates")
    ).canAssign;
  } catch (e) {
    error.value = (e as Error).message;
  }
});
/** 当前列表已提交的筛选条件。 */
const listFilters = ref(
  recentWeekFilters(
    props.inbox
      ? "created"
      : props.compact
        ? undefined
        : taskDateField(filter.value),
  ),
);
/** 待办总览包含未来到期的事项，仅纯历史日期的分类默认限时。 */
function taskDateField(category: string) {
  return ["COMPLETED", "PERSONAL", "ASSIGNED"].includes(category)
    ? "listed"
    : undefined;
}
/** 进入全部时清除筛选；日期含义变化时恢复对应分类的默认范围。 */
function switchFilter(value: string) {
  if (!props.inbox && !value && !filter.value) return;
  if (
    (!props.inbox && !value) ||
    (filter.value === "COMPLETED") !== (value === "COMPLETED") ||
    taskDateField(filter.value) !== taskDateField(value)
  ) {
    listFilters.value = recentWeekFilters(taskDateField(value));
  }
  filter.value = value;
  page.value = 1;
  void load();
}
</script>
<template>
  <section class="panel portal-panel personal-queue">
    <div class="portal-heading">
      <component :is="compact ? 'h2' : 'h1'">{{ inbox ? "我的消息" : "我的待办" }}</component>
      <div class="todo-heading-actions">
        <button v-if="!inbox" class="secondary" @click="editor = {}">
          新建待办
        </button>
        <TodoDesktopSettings v-if="!inbox" />
        <button
          v-if="canAssign"
          class="primary"
          @click="editor = { assign: true }"
        >
          分派任务
        </button>
      </div>
    </div>
    <div class="portal-filters">
      <button
        v-for="option in inbox
          ? [
              ['', '全部'],
              ['true', '未读'],
            ]
          : [
              ['', '全部'],
              ['APPROVAL', '待审批'],
              ['HANDLING', '待办理'],
              ...(!compact
                ? [
                    ['PERSONAL', '个人待办'],
                    ['ASSIGNED', '分派任务'],
                    ['COMPLETED', '已办与关闭'],
                  ]
                : []),
            ]"
        :key="option[0]"
        class="text-button"
        :class="{ selected: filter === option[0] }"
        :aria-pressed="filter === option[0]"
        @click="switchFilter(option[0]!)"
      >
        {{ option[1] }}
      </button>
    </div>
    <ListFilters
      :key="
        inbox ? 'messages' : filter === 'COMPLETED' ? 'completed' : taskDateField(filter) || (filter ? 'active' : 'all')
      "
      :fields="
        inbox
          ? [{ key: 'created', label: '消息日期', type: 'date' }]
          : [
              { key: 'keyword', label: '待办名称' },
              {
                key: 'listed',
                label: filter === 'COMPLETED' ? '完成日期' : '待办日期',
                type: 'date',
              },
            ]
      "
      :values="listFilters"
      v-if="!compact"
      :busy="busy"
      @apply="
        listFilters = $event;
        page = 1;
        load();
      "
    />
    <nav
      v-if="!compact && !error"
      class="list-pagination portal-pagination"
      aria-label="列表分页"
    >
      <span aria-live="polite">共 {{ total }} 条 · 每页 {{ pageSize }} 条</span>
      <button
        type="button"
        class="secondary"
        :disabled="page === 1 || busy"
        @click="
          page--;
          load();
        "
      >
        上一页
      </button>
      <span>第 {{ page }} / {{ pageCount }} 页</span>
      <button
        type="button"
        class="secondary"
        :disabled="page >= pageCount || busy"
        @click="
          page++;
          load();
        "
      >
        下一页
      </button>
    </nav>
    <div :class="{ 'workspace-panel-body': compact }">
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <!-- 工作台保留刷新前的行，会议操作入口须等最新流程阶段返回后才能打开。 -->
      <button
        v-for="item in items"
        :key="item.id"
        class="portal-list-row"
        :disabled="compact && busy && item.source === 'MEETING'"
        @click="open(item)"
      >
        <span v-if="inbox" class="inbox-state" :class="{ unread: item.state !== 'READ' }">
          {{ item.state === 'READ' ? '已读' : '未读' }}
        </span>
        <span v-else class="portal-dot" :class="{ read: item.category === 'COMPLETED' }" aria-hidden="true" />
        <span
          ><strong>{{
            inbox ? item.content || messageNames[item.kind] || "业务状态有更新" : item.title
          }}</strong>
          <span
            v-if="compact && !inbox"
            class="status-badge compact-task-status pending"
            >未处理</span
          >
          <small v-if="!inbox && item.source === 'MEETING'">
            {{ meetingTaskStates[item.state] || (item.category === "COMPLETED" ? "已处理" : "待办理") }}
            <template v-if="item.dueAt"> · 截止 {{ portalTime(item.dueAt) }}</template>
          </small>
          <small v-else
            >{{
              portalTime(
                item.category === "COMPLETED"
                  ? item.completedAt
                  : inbox || item.source !== "SYSTEM"
                    ? item.createdAt
                    : item.dueAt,
              )
            }}{{
              inbox
                ? ""
                : item.source === "SYSTEM"
                  ? item.category === "COMPLETED"
                    ? item.state === "APPROVED"
                      ? " · 已同意"
                      : " · 已驳回"
                    : item.category === "HANDLING"
                      ? " · 待办理"
                      : " · 待审批"
                  : ` · ${item.source === "PERSONAL" ? "个人待办" : "分派任务"} · ${todoStates[item.state]}`
            }}</small
          ></span
        ><span aria-hidden="true">↗</span>
      </button>
      <EmptyState
        v-if="!items.length && !busy && !error"
        :icon="inbox ? 'message' : 'task'"
        :title="
          inbox
            ? '暂无新消息'
            : filter === 'COMPLETED'
              ? '暂无已办记录'
              : '当前没有待办事项'
        "
      />
      <p v-if="busy && !items.length" class="muted">正在加载…</p>
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
      <div v-if="compact && !inbox" class="list-pagination">
        <a href="#/todos" class="todo-list-link">
          查看待办与已办 <span aria-hidden="true">→</span>
        </a>
      </div>
    </footer>
  </section>
  <EmployeeTodoDialog
    v-if="editor"
    v-bind="editor"
    @close="editor = undefined"
    @changed="changed"
  />
</template>

<style scoped>
.personal-queue > .portal-heading {
  flex-wrap: wrap;
  gap: 10px 16px;
}
.personal-queue .portal-list-row > span:nth-last-child(2) {
  min-width: 0;
  overflow-wrap: anywhere;
}
.inbox-state {
  flex: none;
  padding: 2px 8px;
  border-radius: 6px;
  color: var(--text-secondary);
  background: var(--surface-muted);
  font-size: .85rem;
}
.inbox-state.unread {
  color: var(--brand-hover);
  background: var(--brand-soft);
  font-weight: 600;
}
.portal-pagination {
  padding: 8px 0 12px;
  font-size: 13px;
}
.todo-heading-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.todo-list-link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  padding: 6px 12px;
  border: 1px solid var(--brand);
  border-radius: 8px;
  color: var(--brand-hover);
  background: var(--brand-soft);
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
}
.todo-list-link:hover {
  color: var(--brand-contrast);
  background: var(--brand-hover);
}
.todo-list-link:focus-visible {
  outline: 2px solid var(--brand);
  outline-offset: 2px;
}
.compact-task-status {
  margin-inline-start: 8px;
  white-space: nowrap;
}
.compact-task-status.pending {
  background: var(--brand-soft);
  color: var(--brand);
}
</style>
