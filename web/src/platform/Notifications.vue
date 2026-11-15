<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, dateTime, query } from "./api";
/** 通知只含期限与业务引用，不显示秘密题名或原件信息。 */
interface Notice {
  /** 稳定通知。 */ id: string;
  /** 原借阅批次。 */ batchId: string;
  /** 安全文案。 */ title: string;
  /** 期限说明。 */ message: string;
  /** 当前批准截止。 */ deadline: string;
  /** 已读状态。 */ state: string;
  /** 投递时刻。 */ createdAt: string;
}
/** 点击仍由业务接口鉴权，通知自身不是批准凭据。 */
const emit = defineEmits<{ open: [id: string] }>();
const items = ref<Notice[]>([]),
  page = ref(1),
  total = ref(0),
  error = ref(""),
  busy = ref(false);
/** 页面仅主动刷新，后台轮询不能延长用户空闲会话。 */
async function load() {
  busy.value = true;
  error.value = "";
  try {
    const result = await api<{ items: Notice[]; total: number }>(
      `/portal/notifications?${query({ page: page.value, pageSize: 20 })}`,
    );
    items.value = result.items;
    total.value = result.total;
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 已读可重试且不改变期限，然后打开当前合法业务详情。 */
async function open(item: Notice) {
  try {
    await api(`/portal/notifications/${item.id}/read`, {
      method: "POST",
      headers: { "X-Operation-Id": crypto.randomUUID() },
    });
    item.state = "READ";
    emit("open", item.batchId);
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
onMounted(() => void load());
</script>
<template>
  <section class="notice-list">
    <div class="notice-heading">
      <div>
        <h2>借阅到期提醒</h2>
        <p class="muted">
          到期前一天及当天提醒；续借或撤权后，旧期限提醒自动停止。
        </p>
      </div>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <article
      v-for="item in items"
      :key="item.id"
      :data-notice-id="item.id"
      class="notice-item"
    >
      <span class="notice-dot" :class="{ read: item.state === 'READ' }" />
      <div>
        <strong>{{ item.title }}</strong>
        <p>批准截止：{{ dateTime(item.deadline) }}</p>
        <small class="muted">{{ item.message }}</small>
      </div>
      <button class="secondary" @click="open(item)">查看借阅</button>
    </article>
    <p v-if="!items.length && !busy" class="notice-empty">
      暂无当前有效的到期提醒
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
        <span class="list-total">共 {{ total }} 条</span>
        <button
          class="secondary"
          :disabled="page === 1 || busy"
          @click="
            page--;
            load();
          "
        >
          上一页</button
        ><span>{{ page }} / {{ Math.max(1, Math.ceil(total / 20)) }}</span
        ><button
          class="secondary"
          :disabled="page * 20 >= total || busy"
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
/* 电脑端按期限卡片排列，通知不复制档案题名和秘密摘要。 */
.notice-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 28px 0;
}
.notice-heading h2 {
  font-size: 20px;
}
.notice-item {
  display: flex;
  align-items: center;
  gap: 20px;
  border-top: 1px solid #e7eddf;
  padding: 26px 0;
}
.notice-item > div {
  flex: 1;
}
.notice-item p {
  font-size: 14px;
  margin: 12px 0;
}
.notice-item small {
  line-height: 1.8;
}
.notice-dot {
  width: 8px;
  height: 8px;
  background: #277259;
  border-radius: 50%;
}
.notice-dot.read {
  background: #c9d4c2;
}
.notice-empty {
  padding: 70px;
  text-align: center;
  color: #7b8b71;
}
</style>
