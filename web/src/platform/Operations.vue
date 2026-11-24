<script setup lang="ts">
import ListFilters from "./ListFilters.vue";
import Icon from "../preview/PlatformIcon.vue";
import { inject, onMounted, onUnmounted, ref } from "vue";
import { api, dateTime, query } from "./api";
import EmptyState from "./EmptyState.vue";
/** 运维列表为最小收件人及投递摘要，不包含邮箱全文、消息正文或私有路径。 */
interface Delivery {
  /** 原投递标识及服务端幂等键。 */ id: string;
  /** 明确接收员工工号。 */ employeeNo: string;
  /** 当前接收人显示名称。 */ recipientName: string;
  /** 投递状态。 */ state: string;
  /** 历史累计尝试，不因人工重试清零。 */ attemptCount: number;
  /** 下次自动处理时间。 */ nextRunAt: string;
  /** 脱敏错误码。 */ lastError: string | null;
  /** 并发版本。 */ rowVersion: number;
}
/** 页面筛选、租约状态与对话框只保存在组件内存。 */
const items = ref<Delivery[]>([]),
  page = ref(1),
  total = ref(0),
  state = ref("MANUAL"),
  enabled = ref(false),
  transport = ref("LOCAL_DEVELOPMENT_ONLY"),
  /** 以服务端实际配置展示重试间隔，单位秒。 */ retrySeconds = ref<number[]>(
    [],
  ),
  error = ref(""),
  busy = ref(false),
  target = ref<Delivery | null>(null),
  reason = ref(""),
  operation = ref(""),
  dialog = ref<HTMLDialogElement | null>(null);
const askPassword =
    inject<(title: string) => Promise<string | null>>("askPassword")!,
  inform = inject<(message: string) => void>("inform")!;
let generation = 0,
  alive = true;
/** 状态文案不推断真实公司邮件是否送达，本批仅为本机演练。 */
const states: Record<string, string> = {
  PENDING: "等待投递",
  RUNNING: "正在投递",
  RETRY: "等待重试",
  SENT: "通道已接受",
  MANUAL: "需要人工处理",
  CANCELLED: "来源失效已停止",
};
/** 计数和当前页共用服务端权限筛选；旧请求不得覆盖当前筛选。 */
async function load() {
  const current = ++generation;
  error.value = "";
  try {
    const data = await api<{
      items: Delivery[];
      total: number;
      enabled: boolean;
      transport: string;
      retrySeconds: number[];
    }>(
      `/admin/archive/notification-deliveries?page=${page.value}&pageSize=20&${query(listFilters.value)}${state.value ? "&state=" + state.value : ""}`,
    );
    if (current === generation && alive) {
      items.value = data.items;
      total.value = data.total;
      enabled.value = data.enabled;
      transport.value = data.transport;
      retrySeconds.value = data.retrySeconds;
    }
  } catch (failure) {
    if (current === generation && alive)
      error.value = (failure as Error).message;
  }
}
/** 每次打开固定本次操作号，网络重试继续沿用，不允许选择正在领取的任务。 */
function open(item: Delivery) {
  target.value = item;
  reason.value = "";
  operation.value = crypto.randomUUID();
  error.value = "";
  dialog.value?.showModal();
}
/** 人工排队仅作用于原任务，不能重新批准业务或修改收件人。 */
async function retry() {
  if (!target.value) return;
  busy.value = true;
  error.value = "";
  try {
    const password = await askPassword("修复投递原因后，重新排队原通知");
    if (password === null) return;
    const id = target.value.id,
      proof = await api<{ verificationToken: string }>(
        "/auth/reauthentications",
        {
          method: "POST",
          body: JSON.stringify({
            password,
            purpose: "MANAGE",
            objectIds: [id],
            scopeItems: [
              {
                objectType: "NOTIFICATION_DELIVERY",
                objectId: id,
                actions: ["RETRY_NOTIFICATION"],
              },
            ],
          }),
        },
      );
    await api(`/admin/archive/notification-deliveries/${id}/retry`, {
      method: "POST",
      headers: {
        "X-Operation-Id": operation.value,
        "X-Reauth-Token": proof.verificationToken,
        "X-Expected-Version": String(target.value.rowVersion),
      },
      body: JSON.stringify({ reason: reason.value }),
    });
    dialog.value?.close();
    inform("原通知已重新排队，审批与借阅结果保持原记录。");
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
onMounted(load);
onUnmounted(() => {
  alive = false;
  generation++;
});
/** 当前列表已提交的筛选条件。 */
const listFilters = ref<Record<string, string>>({});
</script>
<template>
  <section class="operations-workspace">
    <header class="operations-heading">
      <div>
        <span class="eyebrow">投递有据，异常可追</span>
        <h1>通知投递</h1>
        <p class="muted">查看重试进展，在修复原因后继续原投递。</p>
      </div>
    </header>
    <div class="operations-status">
      <strong>{{ enabled ? "投递工作器已启用" : "投递工作器未启用" }}</strong
      ><span>{{
        transport === "SMTP"
          ? "当前通道：公司 SMTP · 服务端接受不代表员工已阅读"
          : "当前通道：私有本机投递箱 · 不代表已发公司邮箱"
      }}</span
      ><span
        >自动重试间隔：{{
          retrySeconds
            .map((value) =>
              value % 60 === 0 ? `${value / 60}分钟` : `${value}秒`,
            )
            .join("、")
        }}；耗尽后转人工处理。</span
      >
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <section class="panel delivery-panel">
      <div class="list-toolbar operations-toolbar">
        <h2>投递队列</h2>
        <label class="list-filter-field"
          >显示状态<select
            v-model="state"
            @change="
              page = 1;
              load();
            "
          >
            <option value="">全部状态</option>
            <option
              v-for="(label, value) in states"
              :key="value"
              :value="value"
            >
              {{ label }}
            </option>
          </select></label
        >
      </div>
      <ListFilters
        :fields="[
          { key: 'recipient', label: '接收人', placeholder: '姓名或工号' },
          { key: 'next', label: '下次投递日期', type: 'date' },
        ]"
        :values="listFilters"
        @apply="
          listFilters = $event;
          page = 1;
          load();
        "
      />
      <div class="table-scroll">
        <table>
          <thead>
            <tr>
              <th>接收人</th>
              <th>当前状态</th>
              <th>累计尝试</th>
              <th>下次处理</th>
              <th>结果与操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in items"
              :key="item.id"
              :data-delivery-id="item.id"
            >
              <td>
                <strong>{{ item.recipientName }}</strong
                ><small>{{ item.employeeNo }}</small>
              </td>
              <td>
                <span
                  class="delivery-badge"
                  :class="{ manual: item.state === 'MANUAL' }"
                  >{{ states[item.state] }}</span
                >
              </td>
              <td>{{ item.attemptCount }} 次</td>
              <td>
                {{
                  ["RETRY", "PENDING"].includes(item.state)
                    ? dateTime(item.nextRunAt)
                    : "—"
                }}
              </td>
              <td>
                <small v-if="item.lastError">{{
                  item.lastError === "DELIVERY_UNAVAILABLE"
                    ? "投递通道未确认，请检查后处理"
                    : "来源或接收人当前不可用"
                }}</small
                ><button
                  v-if="item.state === 'MANUAL'"
                  class="text-button"
                  @click="open(item)"
                >
                  修复后重试
                </button>
              </td>
            </tr>
            <tr v-if="!items.length">
              <td colspan="5" class="operations-empty">
                <EmptyState icon="message" title="当前筛选暂无投递记录" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="list-footer">
        <button type="button" class="secondary list-refresh" @click="load()">
          刷新列表
        </button>
        <div class="list-pagination">
          <button
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
          </button>
          <span class="list-total">共 {{ total }} 条</span>
        </div>
      </footer>
    </section>
  </section>
  <dialog
    ref="dialog"
    aria-labelledby="delivery-retry-title"
    class="form-dialog flow-dialog flow-dialog-compact"
    @cancel.prevent="!busy && dialog?.close()"
  >
    <form class="flow-dialog-shell" @submit.prevent="retry">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="bell" :size="22" /></span>
        <h2 id="delivery-retry-title">重新排队原通知</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭人工重试"
          :disabled="busy"
          @click="dialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section class="flow-dialog-overview">
          <h3>重试说明</h3>
          <p class="muted">
            累计尝试与原投递号继续保留，不重新办理审批或借阅。
          </p>
        </section>
        <section class="flow-dialog-section">
          <h3>处理记录</h3>
          <label
            >已修复情况与实际原因<textarea
              v-model="reason"
              maxlength="1000"
              rows="3"
              required
            />
          </label>
        </section>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button
          type="button"
          class="secondary"
          :disabled="busy"
          @click="dialog?.close()"
        >
          取消</button
        ><button class="primary" :disabled="busy">
          {{ busy ? "正在排队…" : "确认重试" }}
        </button>
      </footer>
    </form>
  </dialog>
</template>
<style scoped>
.operations-workspace {
  min-width: 0;
  padding: 0;
}
.operations-heading,
.operations-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}
.operations-heading h1 {
  font-size: 24px;
  margin: 6px 0;
}
.operations-heading p {
  font-size: 13px;
}
.operations-status {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 20px;
  background: var(--brand-soft);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 16px;
  margin: 20px 0 18px;
  color: var(--ink);
  font-size: 12px;
  line-height: 1.6;
}
.operations-status strong {
  color: var(--brand);
}
.delivery-panel {
  overflow: hidden;
}
.operations-toolbar h2 {
  margin: 0;
  font-size: 15px;
}
.operations-toolbar label {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 10px;
  margin: 0;
  font-size: 12px;
}
.operations-toolbar select {
  width: 176px;
}
.operations-workspace table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.operations-workspace th {
  text-align: left;
  background: var(--surface-muted);
  color: var(--text-secondary);
  padding: 12px 20px;
  font-weight: 500;
}
.operations-workspace td {
  padding: 12px 20px;
  border-bottom: 1px solid var(--line);
}
.operations-workspace tbody tr:last-child td {
  border-bottom: 0;
}
.operations-workspace small {
  display: block;
  color: var(--text-secondary);
  margin-top: 6px;
  font-size: 11px;
}
.delivery-badge {
  padding: 6px 9px;
  background: var(--brand-soft);
  color: var(--ink);
  border-radius: 4px;
}
.delivery-badge.manual {
  background: var(--warning-soft);
  color: var(--warning);
}
.operations-workspace td.operations-empty {
  padding: 0;
}
</style>
