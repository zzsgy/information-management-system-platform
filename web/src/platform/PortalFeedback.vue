<script setup lang="ts">
import RichTextView from "./RichTextView.vue";
import MarkdownImageEditor from "./MarkdownImageEditor.vue";
import MarkdownImageView from "./MarkdownImageView.vue";
import { markdownImageUploads, type MarkdownImageDraft } from "./markdownImages";
import ListFilters from "./ListFilters.vue";
import { recentWeekFilters } from "./listDates";
import EmptyState from "./EmptyState.vue";
import { inject, onMounted, ref } from "vue";
import { api, mutate, query, type Page } from "./api";
import { portalParams, portalTime } from "./portal";
/** IT办理与员工本人记录使用不同接口，不能用前端筛选代替授权。 */
const props = defineProps<{ manage?: boolean }>();
interface Feedback {
  id: string;
  category: string;
  content: string;
  contentFormat?: "LEGACY" | "MARKDOWN";
  state: string;
  createdAt: string;
  rowVersion: number;
  applicantName: string;
  replies: {
    id: string;
    body: string;
    contentFormat?: "LEGACY" | "MARKDOWN";
    authorName: string;
    createdAt: string;
  }[];
}
const items = ref<Feedback[]>([]),
  detail = ref<Feedback | null>(null),
  page = ref(1),
  total = ref(0),
  category = ref("SUGGESTION"),
  content = ref(""),
  contentImages = ref<MarkdownImageDraft[]>([]),
  reply = ref(""),
  replyImages = ref<MarkdownImageDraft[]>([]),
  replyState = ref("RESOLVED"),
  busy = ref(false),
  preparing = ref(false),
  error = ref(""),
  creating = ref(false);
const askPassword =
  inject<(title: string) => Promise<string | null>>("askPassword")!;
/** 网络结果不确定时，相同提交内容沿用同一操作号；内容改变后使用新操作号。 */
let pending: { body: string; operation: string } | null = null;
const states: Record<string, string> = {
  OPEN: "待处理",
  PROCESSING: "处理中",
  RESOLVED: "已办结",
};
const categories: Record<string, string> = {
  SUGGESTION: "使用建议",
  ISSUE: "问题反馈",
  OTHER: "其他",
};
const prefix = () =>
  props.manage ? "/admin/portal/feedback" : "/portal/feedback";
async function load() {
  error.value = "";
  busy.value = true;
  try {
    const result = await api<Page<Feedback>>(
      `${prefix()}?${query({ page: page.value, pageSize: 10, ...listFilters.value })}`,
    );
    items.value = result.items;
    total.value = result.total;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function open(id: string) {
  error.value = "";
  detail.value = null;
  creating.value = false;
  try {
    detail.value = await api<Feedback>(`${prefix()}/${id}`);
    reply.value = "";
    replyImages.value = [];
  } catch (e) {
    error.value = (e as Error).message;
  }
}
async function submit() {
  if (busy.value || preparing.value) return;
  busy.value = true;
  error.value = "";
  try {
    const body = JSON.stringify({
      category: category.value,
      content: content.value,
      contentFormat: "MARKDOWN",
      images: markdownImageUploads(content.value, contentImages.value),
    });
    if (pending?.body !== body)
      pending = { body, operation: crypto.randomUUID() };
    detail.value = await api<Feedback>("/portal/feedback", {
      method: "POST",
      body,
      headers: { "X-Operation-Id": pending.operation },
    });
    pending = null;
    creating.value = false;
    content.value = "";
    contentImages.value = [];
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
async function respond() {
  if (!detail.value || busy.value || preparing.value) return;
  busy.value = true;
  error.value = "";
  try {
    const password = await askPassword("回复员工反馈并更新办理状态");
    if (password === null) return;
    detail.value = await mutate<Feedback>(
      `${prefix()}/${detail.value.id}/replies`,
      "POST",
      { body: reply.value, state: replyState.value, contentFormat: "MARKDOWN", images: markdownImageUploads(reply.value, replyImages.value) },
      detail.value.id,
      "PORTAL_FEEDBACK",
      "REPLY_FEEDBACK",
      password,
      detail.value.rowVersion,
    );
    reply.value = "";
    replyImages.value = [];
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
onMounted(async () => {
  await load();
  const id = portalParams().get("id");
  if (id) await open(id);
});
/** 当前列表已提交的筛选条件。 */
const listFilters = ref(recentWeekFilters("created"));
</script>
<template>
  <section>
    <div class="portal-heading">
      <div>
        <h1>{{ manage ? "反馈办理" : "意见反馈" }}</h1>
      </div>
      <button
        v-if="!manage && !detail && !creating"
        class="primary"
        @click="
          creating = true;
          detail = null;
        "
      >
        提交反馈</button
      ><button
        v-if="detail || creating"
        class="secondary"
        :disabled="busy || preparing"
        @click="
          detail = null;
          creating = false;
        "
      >
        返回列表
      </button>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <form v-if="creating" class="panel portal-form" @submit.prevent="submit">
      <label
        >反馈类型<select v-model="category">
          <option v-for="(name, key) in categories" :key="key" :value="key">
            {{ name }}
          </option>
        </select></label
      >
      <MarkdownImageEditor v-model="content" v-model:images="contentImages" v-model:preparing="preparing"
        label="反馈内容" :rows="8" :limit="1000" :disabled="busy"
        placeholder="请描述遇到的问题、操作步骤或改进建议。" />
      <p class="muted">
        反馈由 IT 处理，你可在此查看答复。
      </p>
      <div class="portal-actions">
        <button class="primary" :disabled="busy || preparing || !content.trim()">
          提交
        </button>
      </div>
    </form>
    <section v-else-if="detail" class="panel portal-form feedback-detail">
      <div class="portal-heading">
        <h2>{{ categories[detail.category] }}</h2>
        <span class="feedback-state" :data-state="detail.state">{{ states[detail.state] }}</span>
      </div>
      <div class="portal-meta">
        <span>{{ detail.applicantName }}</span
        ><time>{{ portalTime(detail.createdAt) }}</time>
      </div>
      <MarkdownImageView v-if="detail.contentFormat === 'MARKDOWN'" class="feedback-body"
        :value="detail.content" :image-base="`/api/v1/portal/feedback/${detail.id}/images/${detail.id}`" />
      <RichTextView v-else class="portal-plain-text feedback-body" :value="detail.content" />
      <h3>处理记录</h3>
      <article
        v-for="item in detail.replies"
        :key="item.id"
        class="portal-reply"
      >
        <div class="portal-meta">
          <strong>{{ item.authorName }}</strong
          ><time>{{ portalTime(item.createdAt) }}</time>
        </div>
        <MarkdownImageView v-if="item.contentFormat === 'MARKDOWN'"
          :value="item.body" :image-base="`/api/v1/portal/feedback/${detail.id}/images/${item.id}`" />
        <RichTextView v-else class="portal-plain-text" :value="item.body" />
      </article>
      <p v-if="!detail.replies.length" class="muted">
        反馈已收到，等待 IT 处理。
      </p>
      <form
        v-if="manage && detail.state !== 'RESOLVED'"
        @submit.prevent="respond"
      >
        <MarkdownImageEditor v-model="reply" v-model:images="replyImages" v-model:preparing="preparing"
          label="回复员工" :rows="5" :limit="2000" :disabled="busy" />
        <label
          >办理结果<select v-model="replyState">
            <option value="PROCESSING">继续处理中</option>
            <option value="RESOLVED">已办结</option>
          </select></label
        >
        <div class="portal-actions">
          <button class="primary" :disabled="busy || preparing || !reply.trim()">发送回复</button>
        </div>
      </form>
    </section>
    <section v-else class="panel portal-panel">
      <div class="portal-heading">
        <h2>{{ manage ? "员工反馈" : "我的反馈" }}</h2>
      </div>
      <ListFilters
        :fields="[
          { key: 'category', label: '反馈类型', options: categories },
          { key: 'state', label: '办理状态', options: states },
          { key: 'created', label: '提交日期', type: 'date' },
          ...(manage
            ? [{ key: 'applicant', label: '提交人', placeholder: '姓名或工号' }]
            : []),
        ]"
        :values="listFilters"
        :busy="busy"
        @apply="
          listFilters = $event;
          page = 1;
          load();
        "
      />
      <button
        v-for="item in items"
        :key="item.id"
        class="portal-list-row"
        @click="open(item.id)"
      >
        <span
          ><strong
            >{{ categories[item.category] }} · {{ item.applicantName }}</strong
          ><small>{{ portalTime(item.createdAt) }}</small></span
        ><span class="feedback-state" :data-state="item.state">{{ states[item.state] }}</span>
      </button>
      <EmptyState
        v-if="!items.length && !busy"
        icon="message"
        title="暂无反馈记录"
      />
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
          <span>共 {{ total }} 条</span
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
  </section>
</template>

<style scoped>
.feedback-body {
  margin: 10px 0 24px;
}
.feedback-state {
  display: inline-flex;
  flex: none;
  align-items: center;
  padding: 3px 9px;
  border-radius: 6px;
  color: var(--text-secondary);
  background: var(--surface-muted);
  font-size: .86rem;
  font-weight: 600;
}
.feedback-state[data-state="PROCESSING"] { color: var(--brand-hover); background: var(--brand-soft); }
.feedback-state[data-state="RESOLVED"] { color: var(--success); background: var(--success-soft); }
.feedback-detail > h3 { margin: 0 0 10px; }
.feedback-detail .portal-reply {
  margin: 0 0 10px;
  padding: 12px 16px;
  border: 0;
  border-inline-start: 3px solid var(--brand);
  border-radius: 0 8px 8px 0;
  background: var(--surface-muted);
}
.feedback-detail .portal-reply .portal-meta { margin-bottom: 6px; }
.feedback-detail > form { margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--line); }
</style>
