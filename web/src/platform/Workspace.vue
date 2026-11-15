<script setup lang="ts">
import EmptyState from "./EmptyState.vue";
import { computed, onActivated, ref } from "vue";
import Icon from "../preview/PlatformIcon.vue";
import { api, profile } from "./api";
import { type Announcement, type Site, portalTime, siteCategories } from "./portal";
import PortalTasks from "./PortalTasks.vue";
import biolinkLogo from "./assets/biolink-logo.png";
import dingtalkIcon from "./assets/dingtalk-site-icon.png";
/** 入口复用外层导航的可见性，数据接入预览仅供 IT 管理员查看。 */
const props = defineProps<{
  hasManagement: boolean;
  hasMeetingBooking: boolean;
  hasDataIntakePreview: boolean;
}>();
/** 应用入口、公告和外链只使用真实服务结果，不引入设计稿示例数据。 */
const applications = ref<
    { code: string; name: string; entry: string; canManage: boolean }[]
  >([]),
  announcements = ref<Announcement[]>([]),
  sites = ref<Site[]>([]),
  loaded = ref(false),
  error = ref("");
let refreshing = false;
/** 按已配置的网站域名匹配内置图标，其他网站保留名称首字。 */
const siteShortcuts = computed(() =>
  sites.value.filter((site) => site.category === "QUICK_LINK").map((site) => {
    const host = new URL(site.url).hostname.replace(/^www\./, "");
    return {
      ...site,
      icon:
        host === "biolink.city"
          ? biolinkLogo
          : host === "dingtalk.com"
            ? dingtalkIcon
            : null,
      showBrandMark: host === "biolink.city",
    };
  }),
);
/** 办公工具与快捷入口共用首页返回的启用网站。 */
const officeSiteGroups = computed(() =>
  (["ONLINE_TOOL", "SOFTWARE_DOWNLOAD"] as const)
    .map((category) => ({
      category,
      name: siteCategories[category],
      sites: sites.value.filter((site) => site.category === category),
    }))
    .filter((group) => group.sites.length),
);
const date = new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai",
  month: "long",
  day: "numeric",
  weekday: "long",
}).format(new Date());
const greeting = computed(() => {
  const hour = Number(
    new Intl.DateTimeFormat("en-US", {
      timeZone: "Asia/Shanghai",
      hour: "numeric",
      hour12: false,
    }).format(new Date()),
  );
  return hour < 12 ? "上午好" : hour < 18 ? "下午好" : "晚上好";
});
const descriptions: Record<string, string> = {
  DRIVE: "共享资料，有序协作。",
  ARCHIVE: "档案查询、审批与借还。",
  MEETINGS: "预约会议室，处理参会邀请。",
  DATA_INTAKE: "数据源、映射与同步任务。",
  MANAGEMENT: "组织、权限与运行维护。",
};
/** 档案入口可被发现，但未获档案授权时仅展示开通指引，不请求档案数据。 */
const workspaceApplications = computed(() => {
  const items = applications.value
    .filter((item) => item.code === "DRIVE" || item.code === "ARCHIVE" ||
      (item.code === "MEETINGS" && props.hasMeetingBooking))
    .map((item) =>
      item.code === "ARCHIVE" ? { ...item, name: "档案管理" } : item,
    );
  if (!items.some((item) => item.code === "ARCHIVE"))
    items.splice(items.some((item) => item.code === "DRIVE") ? 1 : 0, 0, {
      code: "ARCHIVE",
      name: "档案管理",
      entry: "#/archive",
      canManage: false,
    });
  if (props.hasDataIntakePreview)
    items.push({
      code: "DATA_INTAKE",
      name: "业务数据接入",
      entry: "#/data-intake",
      canManage: false,
    });
  if (props.hasManagement)
    items.push({
      code: "MANAGEMENT",
      name: "管理中心",
      entry: "#/management",
      canManage: true,
    });
  return items;
});
/** 返回时直接显示本会话已加载的内容，并在后台获取最新数据。 */
onActivated(async () => {
  if (refreshing) return;
  refreshing = true;
  error.value = "";
  try {
    const data = await api<{
      applications: typeof applications.value;
      announcements: Announcement[];
      sites: Site[];
    }>("/portal/home");
    applications.value = data.applications;
    // 服务端先按发布时间取最近五条，未查看优先展示且组内保持原顺序。
    announcements.value = [...data.announcements].sort(
      (a, b) => Number(!!a.viewed) - Number(!!b.viewed),
    );
    sites.value = data.sites;
    loaded.value = true;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    refreshing = false;
  }
});
</script>
<template>
  <div class="platform-page-content">
    <section class="welcome">
      <div>
        <span class="eyebrow">工作台</span>
        <h1>{{ greeting }}，{{ profile?.user.name }}。</h1>
        <p>处理待办、查看公告，快速进入工作应用。</p>
      </div>
      <div class="date-panel">
        <Icon name="calendar" :size="24" /><span>{{ date }}</span>
      </div>
    </section>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <section class="application-grid portal-applications" aria-label="我的应用">
      <a
        v-for="(item, index) in workspaceApplications"
        :key="item.code"
        class="application-card"
        :class="`tone-${index % 3}`"
        :href="item.entry"
        ><span class="application-icon" aria-hidden="true"
          ><Icon
            :name="
              item.code === 'ARCHIVE'
                ? 'archive'
                : item.code === 'MANAGEMENT'
                  ? 'shield'
                  : item.code === 'DATA_INTAKE'
                    ? 'database'
                    : item.code === 'MEETINGS'
                      ? 'calendar'
                      : 'file'
            "
            :size="22"
        /></span>
        <div class="application-copy">
          <h2>{{ item.name }}</h2>
          <p v-if="descriptions[item.code]">{{ descriptions[item.code] }}</p>
        </div>
        <span class="application-trailing">
          <Icon name="arrow" :size="16" aria-hidden="true" /> </span
      ></a>
    </section>
    <div class="portal-home-grid">
      <PortalTasks compact />
      <section class="panel portal-panel">
        <div class="portal-heading">
          <h2>通知公告</h2>
          <a class="text-button" href="#/announcements">查看公告与历史 ↗</a>
        </div>
        <a
          v-for="item in announcements"
          :key="item.id"
          class="portal-home-notice"
          :href="`#/announcements?id=${item.id}`"
          ><strong
            ><span v-if="item.pinned" class="portal-pin">置顶</span
            >{{ item.title }}
            <span v-if="!item.viewed" class="status-badge">未查看</span></strong
          ><span class="portal-meta"
            ><span>{{ item.category }}</span
            ><span>{{ item.publisherName }}</span
            ><span>{{ item.publisherDepartment }}</span
            ><time>{{ portalTime(item.publishedAt) }}</time></span
          ></a
        >
        <EmptyState
          v-if="loaded && !announcements.length"
          icon="bell"
          title="暂无公告"
          hint="这里显示最近发布的 5 条公告，包括已到期公告"
        />
      </section>
      <aside class="portal-home-side">
        <section class="panel portal-panel">
          <div class="portal-heading">
            <h2>常用网站</h2>
            <span class="muted">快捷访问</span>
          </div>
          <ul v-if="siteShortcuts.length" class="portal-site-grid">
            <li v-for="site in siteShortcuts" :key="site.id">
              <a
                class="portal-site"
                :href="site.url"
                target="_blank"
                rel="noopener noreferrer"
              >
                <span class="portal-site-icon" aria-hidden="true">
                  <span
                    v-if="site.icon"
                    :class="{ 'portal-site-brand-mark': site.showBrandMark }"
                  >
                    <img :src="site.icon" alt="" />
                  </span>
                  <span v-else>{{ site.name.slice(0, 1) }}</span>
                </span>
                <strong>{{ site.name }}</strong>
              </a>
            </li>
          </ul>
          <EmptyState v-else-if="loaded" icon="external" title="暂无常用网站" />
          <section v-if="officeSiteGroups.length" class="office-sites" aria-labelledby="office-sites-title">
            <h3 id="office-sites-title">办公工具</h3>
            <details v-for="group in officeSiteGroups" :key="group.category">
              <summary>
                <span>{{ group.name }}</span>
                <Icon name="chevron" :size="16" />
              </summary>
              <ul>
                <li v-for="site in group.sites" :key="site.id">
                  <a :href="site.url" target="_blank" rel="noopener noreferrer">
                    <span>{{ site.name }}</span>
                    <Icon name="external" :size="13" />
                  </a>
                </li>
              </ul>
            </details>
          </section>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.office-sites {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--line);
}
.office-sites h3 {
  margin: 0 0 10px;
  font-size: 14px;
}
.office-sites details + details {
  margin-top: 8px;
}
.office-sites summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--surface-muted);
  cursor: pointer;
  font-size: 13px;
}
.office-sites summary::-webkit-details-marker {
  display: none;
}
.office-sites details[open] summary .p-icon {
  transform: rotate(90deg);
}
.office-sites ul {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 122px), 1fr));
  gap: 2px 8px;
  margin: 8px 0 0;
  padding: 0;
  list-style: none;
}
.office-sites a {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  min-height: 36px;
  padding: 4px 8px;
  border-radius: 6px;
  color: var(--ink);
  font-size: 13px;
  overflow-wrap: anywhere;
}
.office-sites a .p-icon {
  flex-shrink: 0;
  color: var(--text-secondary);
}
.office-sites a:hover {
  background: var(--surface-muted);
  color: var(--brand-hover);
}
</style>
