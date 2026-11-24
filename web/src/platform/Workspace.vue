<script setup lang="ts">
import EmptyState from "./EmptyState.vue";
import { computed, defineAsyncComponent, onActivated, onDeactivated, ref } from "vue";
import Icon from "../preview/PlatformIcon.vue";
import { api, profile } from "./api";
import {
  type Announcement,
  type Site,
  portalTime,
  siteCategories,
} from "./portal";
import PortalTasks from "./PortalTasks.vue";
import biolinkLogo from "./assets/biolink-logo.png";
import dingtalkIcon from "./assets/dingtalk-site-icon.png";
const AnnouncementReader = defineAsyncComponent(() => import("./AnnouncementReader.vue"));
const props = defineProps<{
  applications: { code: string; name: string; icon: string }[];
  layout: "cards" | "sidebar";
}>();
const emit = defineEmits<{ layout: [value: "cards" | "sidebar"] }>();

const announcements = ref<Announcement[]>([]),
  sites = ref<Site[]>([]),
  loaded = ref(false),
  error = ref("");
let refreshing = false;
const readerId = ref("");
const orderedAnnouncements = computed(() =>
  [...announcements.value].sort((a, b) => Number(!!a.viewed) - Number(!!b.viewed)),
);
function markRead(id: string) {
  const row = announcements.value.find((item) => item.id === id);
  if (row) row.viewed = true;
}
onDeactivated(() => { readerId.value = ""; });
/** 按已配置的网站域名匹配内置图标，其他网站保留名称首字。 */
const siteShortcuts = computed(() =>
  sites.value
    .filter((site) => site.category === "QUICK_LINK")
    .map((site) => {
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
/** 工作台会被缓存，重新进入时按北京时间更新日期和问候。 */
const today = ref(new Date());
const calendarFormat = new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  weekday: "long",
  hour: "numeric",
  hourCycle: "h23",
});
const calendar = computed(() => Object.fromEntries(
  calendarFormat.formatToParts(today.value).map(({ type, value }) => [type, value]),
));
const greeting = computed(() => {
  const hour = Number(calendar.value.hour);
  return hour < 12 ? "上午好" : hour < 18 ? "下午好" : "晚上好";
});
const descriptions: Record<string, string> = {
  drive: "共享资料，有序协作。",
  archive: "档案查询、审批与借还。",
  meetings: "预约会议室，处理参会邀请。",
  "data-intake": "数据源、映射与同步任务。",
  management: "组织、权限与运行维护。",
};
/** 返回时直接显示本会话已加载的内容，并在后台获取最新数据。 */
onActivated(async () => {
  today.value = new Date();
  if (refreshing) return;
  refreshing = true;
  error.value = "";
  try {
    const data = await api<{
      announcements: Announcement[];
      sites: Site[];
    }>("/portal/home");
    announcements.value = data.announcements;
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
  <div
    class="platform-page-content workspace"
    :class="{ 'workspace-with-sidebar': layout === 'sidebar' }"
  >
    <div class="workspace-intro">
      <section class="welcome" aria-labelledby="workspace-greeting">
        <div class="workspace-headline">
          <time class="calendar-leaf" :datetime="`${calendar.year}-${calendar.month}-${calendar.day}`">
            <span>{{ calendar.month }}月</span>
            <strong>{{ calendar.day }}</strong>
          </time>
          <div class="greeting-copy">
            <div class="greeting-meta"><span>工作台</span><span>{{ calendar.year }}年 · {{ calendar.weekday }}</span></div>
            <h1 id="workspace-greeting">{{ greeting }}，{{ profile?.user.name }}。</h1>
          </div>
        </div>
        <div class="workspace-controls">
          <div
            class="workspace-layout-picker"
            role="group"
            aria-label="工作台布局"
          >
            <button
              type="button"
              :aria-pressed="layout === 'cards'"
              @click="emit('layout', 'cards')"
            >
              <Icon name="grid" :size="16" />顶部卡片
            </button>
            <button
              type="button"
              :aria-pressed="layout === 'sidebar'"
              @click="emit('layout', 'sidebar')"
            >
              <Icon name="menu" :size="16" />左侧导航
            </button>
          </div>
        </div>
      </section>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    </div>

    <nav
      v-if="props.layout === 'cards' && applications.length"
      class="application-grid portal-applications"
      :style="{ '--application-count': applications.length }"
      aria-label="我的应用"
    >
      <a
        v-for="item in applications"
        :key="item.code"
        class="application-card"
        :href="'#/' + item.code"
      >
        <span class="application-icon" aria-hidden="true"
          ><Icon :name="item.icon" :size="22"
        /></span>
        <div class="application-copy">
          <h2>{{ item.name }}</h2>
          <p v-if="descriptions[item.code]">{{ descriptions[item.code] }}</p>
        </div>
        <span class="application-trailing" aria-hidden="true"
          ><Icon name="arrow" :size="15"
        /></span>
      </a>
    </nav>
    <div class="portal-home-grid">
      <div class="workspace-tasks"><PortalTasks compact /></div>
      <div class="workspace-reference">
        <section class="panel portal-panel workspace-news">
          <div class="portal-heading">
            <h2>通知公告</h2>
            <a class="text-button" href="#/announcements">查看公告与历史 ↗</a>
          </div>
          <a
            v-for="item in orderedAnnouncements"
            :key="item.id"
            class="portal-home-notice"
            :href="`#/announcements?id=${item.id}`"
            @click.exact.prevent="readerId = item.id"
            ><strong
              ><span v-if="item.pinned" class="portal-pin">置顶</span
              >{{ item.title }}
              <span class="announcement-read-state" :class="{ unread: !item.viewed }">
                {{ item.viewed ? "已读" : "未读" }}
              </span></strong
            ><span class="portal-meta"
              ><span class="announcement-owner" :class="{ mine: item.isCreator }">{{ item.isCreator ? "我创建的" : "他人创建的" }}</span>
              <span>{{ item.category }}</span
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
            <section
              v-if="officeSiteGroups.length"
              class="office-sites"
              aria-labelledby="office-sites-title"
            >
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
    <AnnouncementReader v-if="readerId" :id="readerId" @read="markRead" @close="readerId = ''" />
  </div>
</template>

<style scoped>
.workspace {
  container: workspace / inline-size;
}

.workspace .welcome {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 18px;
  padding: clamp(17px, 2.3vw, 32px);
  margin: 0 0 16px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: linear-gradient(105deg, var(--brand-soft), var(--surface) 78%);
}
.workspace-headline {
  display: flex;
  align-items: center;
  gap: clamp(14px, 1.6vw, 24px);
  min-width: 0;
}
.calendar-leaf {
  display: grid;
  flex: none;
  min-width: 4.6rem;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--brand) 24%, var(--line));
  border-radius: 10px;
  background: var(--surface);
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.calendar-leaf span {
  padding: 2px 6px;
  color: var(--brand-contrast);
  background: var(--brand);
  font-size: .8rem;
  font-weight: 600;
}
.calendar-leaf strong {
  padding: 2px 6px 5px;
  color: var(--ink);
  font-size: 2.05rem;
  line-height: 1.2;
}
.greeting-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin-bottom: 4px;
  color: var(--text-secondary);
  font-size: .9rem;
}
.greeting-meta span:first-child {
  color: var(--brand);
  font-weight: 650;
}
.workspace .welcome h1 {
  margin: 0;
  color: var(--ink);
  font-size: clamp(1.6rem, 2.2vw, 2.3rem);
  line-height: 1.35;
  overflow-wrap: anywhere;
}
.workspace-layout-picker {
  display: inline-flex;
  gap: 3px;
  padding: 3px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface-muted);
}
.workspace-layout-picker button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  padding: 6px 10px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  font: inherit;
  font-size: .86rem;
  white-space: nowrap;
  cursor: pointer;
}

.workspace-layout-picker button:hover {
  background: var(--brand-soft);
  color: var(--brand);
}
.workspace-layout-picker button[aria-pressed="true"] {
  background: var(--surface);
  color: var(--brand-hover);
  box-shadow: 0 1px 4px color-mix(in srgb, var(--ink) 18%, transparent);
  font-weight: 600;
}

.workspace .portal-applications {
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 13rem), 1fr));
  gap: 8px;
  max-width: calc(var(--application-count) * 19rem);
  margin: 0 0 20px;
}
.portal-applications .application-card {
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 10px;
  min-height: 68px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  background: var(--surface);
  box-shadow: none;
}
.portal-applications .application-card:is(:hover, :focus-visible) {
  background: var(--brand-soft);
  border-color: var(--brand);
}
.portal-applications .application-icon {
  width: 2.65rem;
  height: 2.65rem;
  border-radius: 9px;
  color: var(--brand-hover);
  background: var(--brand-soft);
}
.portal-applications .application-trailing {
  color: var(--text-secondary);
}
.workspace .portal-home-grid {
  flex: 0 0 auto;
  align-items: start;
  grid-template-columns: minmax(0, 1.25fr) minmax(0, 1fr);
  gap: 16px;
}
.workspace-reference {
  display: grid;
  gap: 16px;
  min-width: 0;
}
.workspace-tasks {
  min-width: 0;
}
.workspace .portal-home-grid :deep(.portal-heading) {
  flex-wrap: wrap;
  gap: 8px 12px;
}
.workspace .portal-home-grid :deep(.portal-heading h2) {
  font-size: 1.13rem;
}
.workspace-news .portal-home-notice {
  color: var(--ink);
  border-color: var(--line);
}
.workspace-news .portal-home-notice:hover strong {
  color: var(--brand-hover);
}
/* 侧栏和字号设置均会改变实际可用列宽，断点按工作台内容区判定。 */
@container workspace (max-width: 54rem) {
  .workspace .portal-home-grid { grid-template-columns: minmax(0, 1fr); }
}
@container workspace (max-width: 34rem) {
  .workspace .welcome { flex-direction: row; gap: 13px; padding: 15px; }
  .workspace .portal-applications {
    grid-template-columns: repeat(auto-fit, minmax(min(100%, 10rem), 1fr));
  }
  .portal-applications .application-card { grid-template-columns: auto minmax(0, 1fr); gap: 4px 8px; }
  .portal-applications .application-icon { width: 2rem; height: 2rem; }
  .application-icon svg { width: 1.25rem; height: 1.25rem; }
  .application-copy { display: contents; }
  .portal-applications .application-copy h2 { margin: 0; font-size: 1rem; }
  .portal-applications .application-copy p { grid-column: 1 / -1; margin: 0; }
  .portal-applications .application-trailing { display: none; }
}

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
