<script setup lang="ts">
import Icon from "../preview/PlatformIcon.vue";
import { inject, onMounted, ref } from "vue";
import ListFilters from "./ListFilters.vue";
import { recentWeekFilters } from "./listDates";
import { api, dateTime, mutate, query } from "./api";
/** 技术队列不接收邮箱、密文、验证码或外部服务凭据。 */
interface MessageRow {
  /** IT可核对告警对应员工；不返回邮箱或任何凭据。 */
  employeeNo: string | null;
  recipientName: string | null;
  id: string;
  kind: string;
  channel: string;
  state: string;
  attempts: number;
  createdAt: string;
  expiresAt: string;
  nextRunAt: string;
  lastError: string | null;
  rowVersion: number;
}
const rows = ref<MessageRow[]>([]),
  page = ref(1),
  total = ref(0),
  busy = ref(false),
  error = ref(""),
  mailMode = ref(""),
  alertMode = ref(""),
  reason = ref("");
const target = ref<MessageRow | null>(null),
  dialog = ref<HTMLDialogElement | null>(null);
const askPassword =
  inject<(title: string) => Promise<string | null>>("askPassword")!;
const inform = inject<(message: string) => void>("inform")!;
const states: Record<string, string> = {
  PENDING: "等待发送",
  RUNNING: "正在发送",
  RETRY: "等待重试",
  SENT: "通道已接受",
  MANUAL: "需要人工核对",
  CANCELLED: "已失效停止",
};
const kinds: Record<string, string> = {
  RECOVERY: "邮箱找回",
  TEMP_PASSWORD: "临时密码",
  LOGIN_LOCKS: "重复锁定告警",
};
const listFilters = ref(recentWeekFilters("created"));
async function load() {
  busy.value = true;
  error.value = "";
  try {
    const result = await api<{
      items: MessageRow[];
      total: number;
      mailMode: string;
      alertMode: string;
    }>(
      `/admin/account-messages?${query({ page: page.value, pageSize: 20, ...listFilters.value })}`,
    );
    rows.value = result.items;
    total.value = result.total;
    mailMode.value = result.mailMode;
    alertMode.value = result.alertMode;
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 人工核对未知结果后再重试，需当前版本及本人再验证，不能重新生成验证码。 */
async function retry() {
  if (!target.value) return;
  busy.value = true;
  error.value = "";
  try {
    const password = await askPassword(
      "请确认已核对通道结果，再输入本人密码重试。",
    );
    if (password === null) return;
    const row = target.value;
    await mutate(
      `/admin/account-messages/${row.id}/retry`,
      "POST",
      { reason: reason.value },
      row.id,
      "NOTIFICATION_DELIVERY",
      "RETRY_ACCOUNT_MESSAGE",
      password,
      row.rowVersion,
    );
    dialog.value?.close();
    inform("已重新排队，原凭据有效期保持不变");
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <section class="page-heading">
    <div>
      <span class="eyebrow">账号与消息</span>
      <h1>消息服务</h1>
      <p>查看账号邮件和技术告警的实际处理结果。</p>
    </div>
  </section>
  <section class="panel message-service-summary">
    <div>
      <span class="muted">邮件通道</span
      ><strong>{{
        mailMode === "SMTP" ? "公司 SMTP" : "本机隔离收件箱"
      }}</strong>
    </div>
    <div>
      <span class="muted">技术告警</span
      ><strong>{{
        alertMode === "DINGTALK" ? "钉钉机器人" : "尚未配置"
      }}</strong>
    </div>
    <p>
      “通道已接受”表示发送服务确认接收，不代表员工已阅读。发送结果未知时，请先核对通道记录。
    </p>
  </section>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  <section class="panel">
    <ListFilters
      :fields="[
        { key: 'recipient', label: '接收人', placeholder: '姓名或工号' },
        { key: 'kind', label: '消息类型', options: kinds },
        { key: 'state', label: '投递状态', options: states },
        { key: 'created', label: '建立日期', type: 'date' },
        { key: 'expires', label: '失效日期', type: 'date' },
      ]"
      :values="listFilters"
      :busy="busy"
      @apply="
        listFilters = $event;
        page = 1;
        load();
      "
    />
    <div class="table-scroll">
      <table class="directory-table">
        <thead>
          <tr>
            <th>事项 / 员工</th>
            <th>状态</th>
            <th>尝试次数</th>
            <th>创建时间</th>
            <th>结果说明</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td>
              <strong>{{ kinds[row.kind] || row.kind }}</strong
              ><small
                >{{ row.recipientName || "系统" }} ·
                {{ row.employeeNo || "—" }} <br />{{
                  row.channel === "MAIL" ? "邮件" : "钉钉"
                }}</small
              >
            </td>
            <td>{{ states[row.state] }}</td>
            <td>{{ row.attempts }}</td>
            <td>{{ dateTime(row.createdAt) }}</td>
            <td>
              {{
                row.lastError === "DELIVERY_UNKNOWN"
                  ? "发送结果未知，请核对通道"
                  : row.lastError === "DELIVERY_UNAVAILABLE"
                    ? "通道暂不可用"
                    : row.lastError === "CREDENTIAL_EXPIRED"
                      ? "凭据已失效"
                      : row.lastError || "—"
              }}
            </td>
            <td>
              <button
                v-if="row.state === 'MANUAL'"
                class="text-button"
                @click="
                  target = row;
                  reason = '';
                  dialog?.showModal();
                "
              >
                核对后重试
              </button>
            </td>
          </tr>
          <tr v-if="!rows.length">
            <td colspan="6" class="muted">暂无账号邮件或技术告警</td>
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
        <button
          class="secondary"
          :disabled="busy || page <= 1"
          @click="
            page--;
            load();
          "
        >
          上一页</button
        ><span>第 {{ page }} 页</span
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
        <span class="list-total">共 {{ total }} 条</span>
      </div>
    </footer>
  </section>
  <dialog
    ref="dialog"
    aria-labelledby="message-retry-title"
    class="form-dialog flow-dialog flow-dialog-compact"
  >
    <form class="flow-dialog-shell" @submit.prevent="retry">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="message" :size="22" /></span>
        <h2 id="message-retry-title">核对并重试</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭重试"
          @click="dialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section class="flow-dialog-overview">
          <h3>重试说明</h3>
          <p class="muted">
            若邮件可能已经发出，请先核对发送服务记录。过期凭据请重新申请。
          </p>
        </section>
        <section class="flow-dialog-section">
          <h3>核对结果</h3>
          <label
            >核对结果与原因<textarea
              v-model="reason"
              maxlength="1000"
              required
            />
          </label>
        </section>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button class="secondary" type="button" @click="dialog?.close()">
          取消</button
        ><button class="primary" :disabled="busy">确认重试</button>
      </footer>
    </form>
  </dialog>
</template>
