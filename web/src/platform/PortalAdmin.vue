<script setup lang="ts">
import Icon from "../preview/PlatformIcon.vue";
import { computed, inject, onMounted, reactive, ref } from "vue";
import { api, can, COLLECTION, mutate } from "./api";
import { type Site, type SiteCategory, portalParams, siteCategories } from "./portal";
import PortalAnnouncements from "./PortalAnnouncements.vue";
import PortalFeedback from "./PortalFeedback.vue";
import EmptyState from "./EmptyState.vue";
/** 内容维护权只开放公告，网站和反馈分别由IT授权控制。 */
const tabs = computed(() =>
  [
    {
      code: "announcements",
      name: "公告管理",
      action: "PLATFORM.ANNOUNCEMENTS_MANAGE",
    },
    { code: "sites", name: "常用网站", action: "PLATFORM.SITES_MANAGE" },
    { code: "feedback", name: "反馈办理", action: "PLATFORM.FEEDBACK_MANAGE" },
  ].filter((t) => can(t.action)),
);
const initial = portalParams().get("tab");
const tab = ref(
    tabs.value.some((t) => t.code === initial) ? initial : tabs.value[0]?.code,
  ),
  sites = ref<Site[]>([]),
  target = ref<Site | null>(null),
  busy = ref(false),
  error = ref(""),
  formError = ref("");
/** 原生弹窗约束键盘焦点，关闭后恢复到列表中的触发按钮。 */
const siteDialog = ref<HTMLDialogElement | null>(null);
/** 按首页分组维护链接，新建时默认归入正在查看的分组。 */
const category = ref<SiteCategory>("QUICK_LINK");
const visibleSites = computed(() => sites.value.filter((site) => site.category === category.value));
const form = reactive({
  name: "",
  url: "",
  category: "QUICK_LINK" as SiteCategory,
  sortOrder: 0,
  enabled: true,
});
const askPassword =
  inject<(title: string) => Promise<string | null>>("askPassword")!;
async function load() {
  if (!can("PLATFORM.SITES_MANAGE")) return;
  error.value = "";
  try {
    sites.value = (await api<{ items: Site[] }>("/admin/portal/sites")).items;
  } catch (e) {
    error.value = (e as Error).message;
  }
}
function edit(site?: Site) {
  target.value = site || null;
  Object.assign(form, {
    name: site?.name || "",
    url: site?.url || "",
    category: site?.category ?? category.value,
    sortOrder: site?.sortOrder ?? Math.min((visibleSites.value.at(-1)?.sortOrder ?? -1) + 1, 10000),
    enabled: site?.enabled ?? true,
  });
  formError.value = "";
  siteDialog.value?.showModal();
}
async function save() {
  busy.value = true;
  formError.value = "";
  try {
    const password = await askPassword("保存常用网站配置");
    if (password === null) return;
    await mutate(
      `/admin/portal/sites${target.value ? "/" + target.value.id : ""}`,
      target.value ? "PUT" : "POST",
      form,
      target.value?.id || COLLECTION,
      "PORTAL_SITE",
      "SAVE_SITE",
      password,
      target.value?.rowVersion,
    );
    siteDialog.value?.close();
    category.value = form.category;
    await load();
  } catch (e) {
    formError.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 密码确认同时显示待删除的网站名称。 */
async function remove(site: Site) {
  busy.value = true;
  error.value = "";
  try {
    const password = await askPassword(`确认删除「${site.name}」并从员工首页移除`);
    if (password === null) return;
    await mutate(
      `/admin/portal/sites/${site.id}`,
      "DELETE",
      {},
      site.id,
      "PORTAL_SITE",
      "DELETE_SITE",
      password,
      site.rowVersion,
    );
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
onMounted(load);
</script>
<template>
  <div class="portal-admin">
    <nav class="module-tabs" aria-label="首页管理分类">
      <button
        v-for="item in tabs"
        :key="item.code"
        class="secondary"
        :class="{ selected: tab === item.code }"
        :aria-pressed="tab === item.code"
        @click="tab = item.code"
      >
        {{ item.name }}
      </button>
    </nav>
    <PortalAnnouncements v-if="tab === 'announcements'" manage /><PortalFeedback
      v-else-if="tab === 'feedback'"
      manage
    />
    <section v-else-if="tab === 'sites'">
      <div class="portal-heading sites-heading">
        <div>
          <h1>常用网站</h1>
          <p class="muted">维护工作台的常用网站、在线工具和软件下载。</p>
        </div>
        <button class="primary" :disabled="busy" @click="edit()">添加网站</button>
      </div>
      <nav class="module-tabs site-categories" aria-label="网站分组">
        <button
          v-for="(label, key) in siteCategories"
          :key="key"
          class="secondary"
          :class="{ selected: category === key }"
          :aria-pressed="category === key"
          @click="category = key"
        >
          {{ label }}
        </button>
      </nav>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <dialog
        ref="siteDialog"
        class="form-dialog flow-dialog flow-dialog-compact site-editor"
        aria-labelledby="site-dialog-title"
        @cancel="busy && $event.preventDefault()"
      >
        <form class="flow-dialog-shell" @submit.prevent="save">
          <header class="flow-dialog-header">
            <span class="flow-dialog-icon"
              ><Icon name="external" :size="22"
            /></span>
            <h2 id="site-dialog-title">
              {{ target ? "编辑网站" : "添加网站" }}
            </h2>
            <button
              type="button"
              class="icon-button"
              aria-label="关闭网站编辑"
              :disabled="busy"
              @click="siteDialog?.close()"
            >
              ×
            </button>
          </header>
          <div class="flow-dialog-body">
            <section class="flow-dialog-section">
              <h3>网站信息</h3>
              <label class="flow-inline-field"
                >网站名称<input
                  v-model="form.name"
                  maxlength="80"
                  required
                  autofocus /></label
              ><label class="flow-inline-field"
                >完整网址<input
                  v-model="form.url"
                  type="url"
                  maxlength="2000"
                  required
                  placeholder="https://"
              /></label>
              <label class="flow-inline-field">
                网站分组
                <select v-model="form.category">
                  <option v-for="(label, key) in siteCategories" :key="key" :value="key">
                    {{ label }}
                  </option>
                </select>
              </label>
              <div class="portal-form-grid">
                <label
                  >显示顺序<input
                    v-model.number="form.sortOrder"
                    type="number"
                    min="0"
                    max="10000" /></label
                ><label class="portal-check"
                  ><input v-model="form.enabled" type="checkbox" />启用</label
                >
              </div>
            </section>
            <p v-if="formError" class="form-error" role="alert">
              {{ formError }}
            </p>
          </div>
          <footer class="flow-dialog-footer">
            <button
              type="button"
              class="secondary"
              :disabled="busy"
              @click="siteDialog?.close()"
            >
              取消</button
            ><button class="primary" :disabled="busy">保存网站</button>
          </footer>
        </form>
      </dialog>
      <section class="panel sites-panel" aria-label="常用网站列表">
        <!-- 固定字段分列，长网址省略显示并保留完整提示，避免撑高每条记录。 -->
        <div class="table-scroll">
          <table class="sites-table">
            <colgroup>
              <col class="site-name-column" />
              <col />
              <col class="site-order-column" />
              <col class="site-status-column" />
              <col class="site-action-column" />
            </colgroup>
            <thead>
              <tr>
                <th scope="col">网站名称</th>
                <th scope="col">网址</th>
                <th scope="col">显示顺序</th>
                <th scope="col">状态</th>
                <th scope="col" class="site-action">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="site in visibleSites" :key="site.id">
                <td>
                  <strong class="site-name" :title="site.name">{{
                    site.name
                  }}</strong>
                </td>
                <td>
                  <span class="site-url" :title="site.url">{{ site.url }}</span>
                </td>
                <td class="site-order">{{ site.sortOrder }}</td>
                <td>
                  <span
                    class="site-status"
                    :class="{ enabled: site.enabled }"
                    >{{ site.enabled ? "已启用" : "已停用" }}</span
                  >
                </td>
                <td class="site-action">
                  <button
                    class="text-button"
                    :aria-label="`编辑网站：${site.name}`"
                    :disabled="busy"
                    @click="edit(site)"
                  >
                    编辑
                  </button>
                  <button
                    class="text-button"
                    :aria-label="`删除网站：${site.name}`"
                    :disabled="busy"
                    @click="remove(site)"
                  >
                    删除
                  </button>
                </td>
              </tr>
              <tr v-if="!visibleSites.length">
                <td colspan="5">
                  <EmptyState
                    icon="external"
                    title="当前分组暂无网站"
                    hint="点击“添加网站”配置员工首页入口。"
                  />
                </td>
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
            <span class="list-total">共 {{ visibleSites.length }} 条</span>
          </div>
        </footer>
      </section>
    </section>
  </div>
</template>

<style scoped>
.sites-heading {
  align-items: center;
  margin-bottom: 18px;
}
.sites-heading h1 {
  margin: 0;
}
.sites-heading p {
  margin: 7px 0 0;
  font-size: 13px;
}
.site-categories {
  margin-bottom: 12px;
}
.sites-panel {
  padding: 0;
  overflow: hidden;
}
.sites-table {
  width: 100%;
  table-layout: fixed;
  margin: 0;
}
.site-name-column {
  width: 220px;
}
.site-order-column {
  width: 100px;
}
.site-status-column {
  width: 110px;
}
.site-action-column {
  width: 116px;
}
.sites-table th {
  padding: 12px 20px;
  white-space: nowrap;
}
.sites-table td {
  padding: 12px 20px;
  line-height: 22px;
}
.sites-table tbody tr:last-child td {
  border-bottom: 0;
}
.site-name,
.site-url {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.site-url,
.site-order {
  color: var(--text-secondary);
}
.site-status {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 5px;
  background: var(--surface-muted);
  color: var(--text-secondary);
  font-size: 12px;
  white-space: nowrap;
}
.site-status.enabled {
  color: #23724b;
  background: #eef8f1;
}
.site-action {
  text-align: right;
  white-space: nowrap;
}
.site-action button + button {
  margin-left: 12px;
}
.site-action button {
  min-height: 0;
  padding: 0;
  line-height: 22px;
}
.site-editor label {
  margin-bottom: 18px;
}
.site-editor .portal-check {
  margin-bottom: 0;
}
@media (max-width: 1360px) {
  .site-name-column {
    width: 180px;
  }
  .sites-table th,
  .sites-table td {
    padding-left: 16px;
    padding-right: 16px;
  }
}
</style>
