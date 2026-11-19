<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue";
import { api, dateTime } from "./api";
defineProps<{ showHealthy?: boolean }>();
interface Health {
  warning: boolean;
  identities: { name: string; state: string; validUntil: string | null }[];
  office: { state: string; checkedAt: string | null };
  runtimeChecks?: {name: string; ready: boolean; message: string; errorCode?: string; requestId?: string}[];
}
const health = ref<Health | null>(null),
  error = ref("");
let timer: ReturnType<typeof setInterval> | undefined,
  alive = true;
const labels: Record<string, string> = {
  READY: "正常",
  EXPIRING: "30天内到期，请安排续期",
  EXPIRED: "授权已到期，功能已阻断",
  DISABLED: "已停用",
  NOT_CONFIGURED: "尚未配置",
  SCOPE_MISMATCH: "授权范围配置不完整",
  CHECKING: "正在检查",
  OFFICE_VERSION_MISMATCH: "服务版本不匹配",
};
async function load() {
  try {
    const result = await api<Health>(
      "/admin/operations/service-health",
      {},
      false,
    );
    if (alive) {
      health.value = result;
      error.value = "";
    }
  } catch (failure) {
    if (alive) error.value = "服务状态检查失败：" + (failure as Error).message;
  }
}
onMounted(() => {
  void load();
  timer = setInterval(load, 60000);
  window.addEventListener("ims-service-config-changed", load);
});
onUnmounted(() => {
  alive = false;
  clearInterval(timer);
  window.removeEventListener("ims-service-config-changed", load);
});
</script>
<template>
  <section
    v-if="error || health?.warning || showHealthy"
    class="service-health"
    :class="{ warning: error || health?.warning }"
    aria-label="关键服务状态"
  >
    <strong>{{
      error ||
      (!health
        ? "正在检查关键服务"
        : health.warning
          ? "关键服务需要维护"
          : "关键服务状态正常")
    }}</strong>
    <a v-if="!showHealthy" href="#/operations-console">进入运维与保护配置服务</a>
    <details v-if="health" :open="showHealthy || health.warning">
      <summary>服务可用性及授权有效期（到期前30天提醒）</summary>
      <ul>
        <li v-for="item in health.identities" :key="item.name">
          {{ item.name }}：{{ labels[item.state] || "服务未就绪" }}；有效期至
          {{ item.validUntil ? dateTime(item.validUntil) : "未配置" }}
        </li>
        <li>
          Office连接：{{
            labels[health.office.state] || "连接失败，请检查后台服务"
          }}；最近检查
          {{
            health.office.checkedAt
              ? dateTime(health.office.checkedAt)
              : "尚未完成"
          }}
        </li>
        <li v-for="check in health.runtimeChecks" :key="check.name">{{ check.name }}：{{ check.message }}<span v-if="check.errorCode">（{{ check.errorCode }}）</span><span v-if="!check.ready && check.requestId">；检查请求编号：{{ check.requestId }}</span></li>
      </ul>
    </details>
  </section>
</template>
<style scoped>
.service-health {
  margin: 20px auto;
  padding: 16px 24px;
  max-width: 1560px;
  border: 1px solid #dbe6ec;
  border-radius: 10px;
  background: #fff;
}
.service-health.warning {
  background: #fff5ee;
  border-color: #efc5a8;
}
.service-health a {
  margin-left: 24px;
}
summary {
  margin-top: 10px;
  cursor: pointer;
}
ul {
  margin: 10px 0 0;
  padding-left: 22px;
  line-height: 1.8;
}
</style>
