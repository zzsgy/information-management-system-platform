<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, query } from "./api";
import { type Announcement, portalTime } from "./portal";

const props = defineProps<{ announcement: Announcement }>();
const emit = defineEmits<{ close: [] }>();

/** 人数按公告范围统计，名单总数随当前状态筛选变化。 */
interface Readership {
  readCount: number;
  unreadCount: number;
  total: number;
  items: {
    employeeNo: string;
    name: string;
    departmentName: string;
    /** 首次打开时间，未读时为空。 */
    readAt: string | null;
  }[];
}
const filters = { ALL: "应读", UNREAD: "未读", READ: "已读" } as const;
const dialog = ref<HTMLDialogElement | null>(null);
const report = ref<Readership | null>(null);
const selected = ref<keyof typeof filters>("ALL");
const page = ref(1),
  pageSize = 10;
const busy = ref(false),
  error = ref("");

/** 切换阅读状态时从第一页开始，刷新时沿用当前状态和页码。 */
async function load(status = selected.value, nextPage = page.value) {
  selected.value = status;
  page.value = nextPage;
  busy.value = true;
  error.value = "";
  try {
    report.value = await api<Readership>(
      `/admin/portal/announcements/${props.announcement.id}/readership?${query({ status, page: nextPage, pageSize })}`,
    );
  } catch (e) {
    report.value = null;
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
onMounted(() => {
  dialog.value?.showModal();
  void load();
});
</script>

<template>
  <dialog
    ref="dialog"
    class="form-dialog flow-dialog"
    aria-labelledby="readership-heading"
    @close="emit('close')"
  >
    <div class="flow-dialog-shell">
      <header class="flow-dialog-header">
        <h2 id="readership-heading">阅读统计</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭阅读统计"
          autofocus
          @click="dialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <div class="flow-dialog-section">
          <h3>{{ announcement.title }}</h3>
          <p class="muted">
            按公告当前发布范围内启用的员工账号统计，排除 IT 通用账号。
          </p>
        </div>
        <nav v-if="report" class="portal-tabs" aria-label="按阅读状态筛选">
          <button
            v-for="(label, code) in filters"
            :key="code"
            type="button"
            class="text-button"
            :class="{ selected: selected === code }"
            :aria-pressed="selected === code"
            :disabled="busy"
            @click="load(code, 1)"
          >
            {{ label }}
            {{
              code === "ALL"
                ? report.readCount + report.unreadCount
                : code === "READ"
                  ? report.readCount
                  : report.unreadCount
            }}
            人
          </button>
        </nav>
        <p v-if="busy" class="muted" role="status">正在查询阅读名单…</p>
        <p v-else-if="error" class="form-error" role="alert">{{ error }}</p>
        <div v-else-if="report?.items.length" class="table-scroll">
          <table aria-label="公告阅读名单">
            <thead>
              <tr>
                <th scope="col">姓名</th>
                <th scope="col">工号</th>
                <th scope="col">部门</th>
                <th scope="col">状态</th>
                <th scope="col">首次阅读时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="employee in report.items" :key="employee.employeeNo">
                <td>{{ employee.name }}</td>
                <td>{{ employee.employeeNo }}</td>
                <td>{{ employee.departmentName }}</td>
                <td>{{ employee.readAt ? "已读" : "未读" }}</td>
                <td>
                  {{ employee.readAt ? portalTime(employee.readAt) : "—" }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else-if="report" class="muted" role="status">
          暂无{{ filters[selected] }}员工
        </p>
      </div>
      <footer class="list-footer">
        <button
          type="button"
          class="secondary list-refresh"
          :disabled="busy"
          @click="load()"
        >
          刷新名单
        </button>
        <div v-if="report" class="list-pagination">
          <span>共 {{ report.total }} 人</span>
          <button
            type="button"
            class="secondary"
            :disabled="busy || page === 1"
            @click="load(selected, page - 1)"
          >
            上一页
          </button>
          <span>{{ page }}</span>
          <button
            type="button"
            class="secondary"
            :disabled="busy || page * pageSize >= report.total"
            @click="load(selected, page + 1)"
          >
            下一页
          </button>
        </div>
      </footer>
    </div>
  </dialog>
</template>
