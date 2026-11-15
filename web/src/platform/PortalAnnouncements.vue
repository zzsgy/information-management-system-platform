<script setup lang="ts">
import ListFilters from "./ListFilters.vue";
import { recentWeekFilters } from "./listDates";
import EmptyState from "./EmptyState.vue";
import { inject, onMounted, onUnmounted, reactive, ref } from "vue";
import { api, query, COLLECTION, mutate, type Page } from "./api";
import { type Announcement, portalParams, portalTime } from "./portal";
/** 管理与员工详情复用同一信纸呈现，读取接口仍按权限分开。 */
const props = defineProps<{ manage?: boolean }>();
const rows = ref<Announcement[]>([]),
  selected = ref<Announcement | null>(null),
  total = ref(0),
  page = ref(1),
  view = ref<"CURRENT" | "EXPIRED" | "READ">("CURRENT"),
  busy = ref(false),
  error = ref(""),
  editing = ref(false);
const departments = ref<{ id: string; name: string }[]>([]);
const askPassword =
  inject<(title: string) => Promise<string | null>>("askPassword")!;
/** 本地时间控件提交时显式转换为ISO时刻，后端不猜测时区。 */
const form = reactive({
  title: "",
  category: "通知公告",
  body: "",
  state: "DRAFT",
  scope: "COMPANY",
  validFrom: "",
  validUntil: "",
  pinned: false,
  departmentIds: [] as string[],
});
const prefix = () =>
  props.manage ? "/admin/portal/announcements" : "/portal/announcements";
let refreshTimer: ReturnType<typeof setInterval> | undefined;
function localTime(value: string) {
  const d = new Date(value);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 16);
}
async function load() {
  busy.value = true;
  error.value = "";
  try {
    const data = await api<Page<Announcement>>(
      `${prefix()}?${query({ page: page.value, pageSize: 10, view: view.value, ...listFilters.value })}`,
    );
    rows.value = data.items;
    total.value = data.total;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 主动打开时记录首次查看，定时重验只读当前权限。 */
async function open(id: string, activity = true) {
  error.value = "";
  try {
    const markRead = !props.manage && activity;
    selected.value = await api<Announcement>(
      `${prefix()}/${id}${markRead ? "/read" : ""}`,
      markRead ? { method: "POST" } : {},
      activity,
    );
  } catch (e) {
    selected.value = null;
    error.value = (e as Error).message;
  }
}
/** 历史公告默认不限日期，避免较早发布的公告被最近一周筛掉。 */
function switchView(next: typeof view.value) {
  if (view.value === next) return;
  listFilters.value = next === "CURRENT" ? recentWeekFilters("published") : {};
  view.value = next;
  page.value = 1;
  void load();
}
function refresh() {
  if (selected.value && !props.manage && !editing.value)
    void open(selected.value.id, false);
}
async function edit(item?: Announcement) {
  error.value = "";
  try {
    selected.value = item
      ? await api<Announcement>(`${prefix()}/${item.id}`)
      : null;
    const current = selected.value;
    Object.assign(form, {
      title: current?.title || "",
      category: current?.category || "通知公告",
      body: current?.body || "",
      state: current?.state || "DRAFT",
      scope: current?.scope || "COMPANY",
      validFrom: localTime(current?.validFrom || new Date().toISOString()),
      validUntil: current?.validUntil ? localTime(current.validUntil) : "",
      pinned: current?.pinned || false,
      departmentIds: current?.departmentIds || [],
    });
    departments.value = (
      await api<{ items: typeof departments.value }>(
        "/admin/portal/departments",
      )
    ).items;
    editing.value = true;
  } catch (e) {
    error.value = (e as Error).message;
  }
}
/** 取消密码验证保留编辑内容；提交失败保留输入和原版本供刷新核对。 */
async function save() {
  busy.value = true;
  error.value = "";
  try {
    const password = await askPassword("保存公告及发布范围");
    if (password === null) return;
    const body = {
      ...form,
      validFrom: new Date(form.validFrom).toISOString(),
      validUntil: form.validUntil
        ? new Date(form.validUntil).toISOString()
        : null,
      departmentIds: form.scope === "COMPANY" ? [] : form.departmentIds,
    };
    selected.value = await mutate<Announcement>(
      `${prefix()}${selected.value ? "/" + selected.value.id : ""}`,
      selected.value ? "PUT" : "POST",
      body,
      selected.value?.id || COLLECTION,
      "PORTAL_ANNOUNCEMENT",
      "SAVE_ANNOUNCEMENT",
      password,
      selected.value?.rowVersion,
    );
    editing.value = false;
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
  refreshTimer = setInterval(refresh, 30000);
  window.addEventListener("focus", refresh);
});
onUnmounted(() => {
  clearInterval(refreshTimer);
  window.removeEventListener("focus", refresh);
});
/** 当前列表已提交的筛选条件。 */
const listFilters = ref(recentWeekFilters("published"));
</script>
<template>
  <section class="portal-announcements">
    <div class="portal-heading">
      <div>
        <span class="eyebrow">公司资讯</span>
        <h1>{{ manage ? "公告管理" : "通知公告" }}</h1>
      </div>
      <button v-if="manage && !editing" class="primary" @click="edit()">
        新建公告</button
      ><button
        v-if="selected || editing"
        class="secondary"
        @click="
          selected = null;
          editing = false;
        "
      >
        返回列表
      </button>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <form v-if="editing" class="panel portal-form" @submit.prevent="save">
      <label
        >公告标题<input v-model="form.title" maxlength="200" required
      /></label>
      <div class="portal-form-grid">
        <label
          >分类<select v-model="form.category">
            <option>通知公告</option>
            <option>公司动态</option>
            <option>制度通知</option>
          </select></label
        ><label
          >状态<select v-model="form.state">
            <option value="DRAFT">草稿</option>
            <option value="PUBLISHED">发布</option>
            <option value="WITHDRAWN">撤下</option>
          </select></label
        >
      </div>
      <label
        >正文<textarea
          v-model="form.body"
          rows="12"
          maxlength="10000"
          required
          placeholder="请输入公告正文，段落和换行将按原样展示。"
        />
      </label>
      <div class="portal-form-grid">
        <label
          >展示开始<input
            type="datetime-local"
            v-model="form.validFrom"
            required /></label
        ><label
          >展示结束（可选）<input
            type="datetime-local"
            v-model="form.validUntil" /></label
        ><label
          >发布范围<select v-model="form.scope">
            <option value="COMPANY">全公司</option>
            <option value="DEPARTMENTS">指定部门</option>
          </select></label
        ><label class="portal-check"
          ><input v-model="form.pinned" type="checkbox" />置顶展示</label
        >
      </div>
      <fieldset v-if="form.scope === 'DEPARTMENTS'" class="portal-departments">
        <legend>选择部门（不自动包含下级部门）</legend>
        <label v-for="d in departments" :key="d.id" class="portal-check"
          ><input
            v-model="form.departmentIds"
            type="checkbox"
            :value="d.id"
          />{{ d.name }}</label
        >
      </fieldset>
      <div class="portal-actions">
        <button type="button" class="secondary" @click="editing = false">
          取消</button
        ><button class="primary" :disabled="busy">保存公告</button>
      </div>
    </form>
    <template v-else-if="selected">
      <div class="portal-meta">
        <span>{{ selected.category }}</span
        ><span>发布人：{{ selected.publisherName || "尚未发布" }}</span
        ><span>部门：{{ selected.publisherDepartment || "—" }}</span
        ><time>{{ portalTime(selected.publishedAt) }}</time
        ><button v-if="manage" class="text-button" @click="edit(selected)">
          编辑公告
        </button>
      </div>
      <article class="portal-letterhead">
        <div class="portal-letter-content">
          <p class="portal-letter-label">{{ selected.category }}</p>
          <h1>{{ selected.title }}</h1>
          <div class="portal-letter-body">{{ selected.body }}</div>
          <div class="portal-letter-signature">
            <p>{{ selected.publisherDepartment }}</p>
            <time>{{
              selected.publishedAt
                ? portalTime(selected.publishedAt).slice(0, 10)
                : ""
            }}</time>
          </div>
        </div>
      </article>
    </template>
    <section v-else class="panel portal-panel">
      <div v-if="!manage" class="portal-tabs" aria-label="公告范围">
        <button
          v-for="(label, code) in {
            CURRENT: '当前公告',
            EXPIRED: '历史公告',
            READ: '已查看',
          }"
          :key="code"
          type="button"
          class="text-button"
          :class="{ selected: view === code }"
          :aria-pressed="view === code"
          :disabled="busy"
          @click="switchView(code)"
        >
          {{ label }}
        </button>
      </div>
      <ListFilters
        :key="view"
        :class="{ 'managed-announcement-filters': manage }"
        :fields="[
          { key: 'keyword', label: '公告标题' },
          {
            key: 'category',
            label: '公告类型',
            applyOnChange: !manage,
            options: {
              通知公告: '通知公告',
              公司动态: '公司动态',
              制度通知: '制度通知',
            },
          },
          { key: 'publisher', label: '发布人' },
          { key: 'published', label: '发布日期', type: 'date' },
          ...(manage
            ? [
                {
                  key: 'state',
                  label: '公告状态',
                  options: {
                    DRAFT: '草稿',
                    PUBLISHED: '已发布',
                    WITHDRAWN: '已撤下',
                  },
                },
              ]
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
      <p
        v-if="!manage && !error"
        class="announcement-query-status muted"
        role="status"
      >
        {{
          busy
            ? "正在查询公告…"
            : `${listFilters.category ? listFilters.category + " · " : ""}共 ${total} 条公告`
        }}
      </p>
      <article v-for="item in rows" :key="item.id" class="portal-notice-row">
        <button class="portal-notice-open" @click="open(item.id)">
          <strong
            ><span v-if="item.pinned" class="portal-pin">置顶</span
            >{{ item.title }}</strong
          ><span class="portal-meta"
            ><span>{{ item.category }}</span
            ><span>{{ item.publisherName || "尚未发布" }}</span
            ><span>{{ item.publisherDepartment || "—" }}</span
            ><time>{{ portalTime(item.publishedAt) }}</time
            ><span v-if="!manage && view === 'EXPIRED'">已到期</span>
            <span v-if="manage">{{
              { DRAFT: "草稿", PUBLISHED: "已发布", WITHDRAWN: "已撤下" }[
                item.state
              ]
            }}</span></span
          ></button
        ><button v-if="manage" class="text-button" @click="edit(item)">
          编辑
        </button>
      </article>
      <EmptyState
        v-if="!rows.length && !busy && !error"
        icon="bell"
        :title="
          Object.values(listFilters).some(Boolean)
            ? '没有符合筛选条件的公告'
            : view === 'READ'
              ? '暂无已查看的公告'
              : view === 'EXPIRED'
                ? '暂无历史公告'
                : '暂无公告'
        "
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
/* 管理端将标签置于控件上方，窄屏时按整列换行。 */
.platform-shell .managed-announcement-filters {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(148px, 100%), 1fr));
  align-items: end;
  gap: 14px;
  padding: 16px;
  margin-bottom: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface-muted);
}
.platform-shell .managed-announcement-filters :deep(.list-filter-field) {
  display: grid;
  gap: 7px;
  min-width: 0;
}
.managed-announcement-filters :deep(.list-filter-field :is(input, select)) {
  width: 100%;
  min-width: 0;
}
.announcement-query-status {
  margin: 8px 0 10px;
  font-size: 13px;
}
</style>
