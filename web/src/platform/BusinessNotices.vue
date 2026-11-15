<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import { api, dateTime } from "./api";

/** 只有受控引用和固定模板类型；来源题名和邮件正文不进入浏览器。 */
interface Notice {
  /** 本人通知标识。 */ id: string;
  /** 当前仍合法的来源类别。 */ sourceKind: string;
  /** 安全文案类型。 */ kind: string;
  /** 本人的已读状态。 */ state: string;
  /** 首次生成时刻。 */ createdAt: string;
}
/** 当前页、未读筛选及加载代次，防止较早请求覆盖新筛选。 */
const items = ref<Notice[]>([]),
  page = ref(1),
  total = ref(0),
  unread = ref(false),
  error = ref(""),
  busy = ref(false);
let generation = 0,
  alive = true;
/** 仅使用固定安全文案，不把服务端任意字符串作为HTML或链接执行。 */
const names: Record<string, string> = {
  EVIDENCE_RECORDED: "已有同意凭据经核验补录，请核对对应申请",
  CATALOG_CHANGED: "有一项目录更正已生效",
  RETENTION_SOON: "有档案将在30天内达到保管期限",
  RETENTION_DUE: "有档案已达到保管期限，请核对",
  TASK_AVAILABLE: "有一项审批待你办理",
  TASK_OVERDUE: "有一项审批已超过办理时限",
  WORKFLOW_APPROVED: "你的申请已批准",
  WORKFLOW_REJECTED: "你的申请已驳回",
  WORKFLOW_WITHDRAWN: "你的申请已撤回",
  WORKFLOW_CLOSED: "你的申请已结束",
  ELECTRONIC_DUE: "电子借阅临近到期",
  DUE_TOMORROW: "原件临近应还日期",
  DUE_TODAY: "原件应还日期已到",
  OVERDUE: "原件已逾期，请办理归还或续借",
  RETURN_REQUESTED: "有原件等待实际实收核验",
  RETURN_EXCEPTION: "原件归还核验存在异常",
  RETURN_CONFIRMED: "原件已复核归还",
};
/** 只主动刷新，不通过后台轮询延长空闲会话。 */
async function load() {
  const current = ++generation;
  busy.value = true;
  error.value = "";
  try {
    const data = await api<{ items: Notice[]; total: number }>(
      `/portal/business-notifications?page=${page.value}&pageSize=10&unreadOnly=${unread.value}`,
    );
    if (current === generation && alive) {
      items.value = data.items;
      total.value = data.total;
    }
  } catch (failure) {
    if (current === generation && alive)
      error.value = (failure as Error).message;
  } finally {
    if (current === generation && alive) busy.value = false;
  }
}
/** 已读不推进来源流程；跳转后业务接口仍逐次验证当前权限。 */
async function open(item: Notice) {
  try {
    await api(`/portal/business-notifications/${item.id}/read`, {
      method: "POST",
      headers: { "X-Operation-Id": crypto.randomUUID() },
    });
    item.state = "READ";
    location.hash = ["CATALOG_CHANGE", "RETENTION"].includes(item.sourceKind)
      ? "#/catalog"
      : item.sourceKind === "PHYSICAL_NOTICE"
        ? "#/workflow?tab=physical"
        : "#/workflow";
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
onMounted(load);
onUnmounted(() => {
  alive = false;
  generation++;
});
</script>
<template>
  <section class="business-notices panel">
    <div class="business-notice-heading">
      <div>
        <span class="eyebrow">与你有关的进展</span>
        <h2>业务通知</h2>
        <p class="muted">
          办理结果以当前业务记录为准，通知不会替代批准或延长期限。
        </p>
      </div>
      <div>
        <label
          ><input
            type="checkbox"
            v-model="unread"
            @change="
              page = 1;
              load();
            "
          />仅看未读</label
        >
      </div>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <article
      v-for="item in items"
      :key="item.id"
      :data-business-notice-id="item.id"
      class="business-notice"
    >
      <span class="business-dot" :class="{ read: item.state === 'READ' }" />
      <div>
        <strong>{{ names[item.kind] || "有一项业务状态更新" }}</strong
        ><small>{{ dateTime(item.createdAt) }}</small>
      </div>
      <button class="text-button" @click="open(item)">
        {{
          item.sourceKind === "PHYSICAL_NOTICE"
            ? "进入实物借还"
            : "进入流程中心"
        }}
      </button>
    </article>
    <p v-if="!items.length && !busy" class="business-empty">
      暂无当前有效的业务通知
    </p>
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
        <span>{{ total }} 条</span
        ><button
          class="secondary"
          :disabled="page === 1 || busy"
          @click="
            page--;
            load();
          "
        >
          上一页</button
        ><span>{{ page }}</span
        ><button
          class="secondary"
          :disabled="page * 10 >= total || busy"
          @click="
            page++;
            load();
          "
        >
          下一页
        </button>
      </div>
    </footer>
  </section>
</template>
<style scoped>
.business-notices {
  margin-top: 28px;
}
.business-notice-heading,
.business-notice-heading > div:last-child,
.business-notice,
.business-notice-heading h2 {
  margin: 10px 0;
  font-size: 20px;
}
.business-notice-heading p {
  font-size: 12px;
}
.business-notice-heading label {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  white-space: nowrap;
}
.business-notice-heading input {
  width: 16px;
  height: 16px;
  min-height: 0;
}
.business-notice {
  border-top: 1px solid #e8eee5;
  padding: 22px 0;
  font-size: 13px;
}
.business-notice > div {
  flex: 1;
}
.business-notice small {
  display: block;
  color: #89958b;
  margin-top: 8px;
}
.business-dot {
  width: 7px;
  height: 7px;
  background: var(--brand);
  border-radius: 50%;
}
.business-dot.read {
  background: var(--brand-soft);
}
.business-empty {
  padding: 36px;
  text-align: center;
  color: var(--text-secondary);
  font-size: 13px;
}
</style>
