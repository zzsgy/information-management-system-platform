<script setup lang="ts">
import EmptyState from "./EmptyState.vue";
import { computed, onActivated, ref } from "vue";
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
  drive: "共享资料，有序协作。",
  archive: "档案查询、审批与借还。",
  meetings: "预约会议室，处理参会邀请。",
  "data-intake": "数据源、映射与同步任务。",
  management: "组织、权限与运行维护。",
};
/** 返回时直接显示本会话已加载的内容，并在后台获取最新数据。 */
onActivated(async () => {
  if (refreshing) return;
  refreshing = true;
  error.value = "";
  try {
    const data = await api<{
      announcements: Announcement[];
      sites: Site[];
    }>("/portal/home");
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
  <div
    class="platform-page-content workspace"
    :class="{ 'workspace-with-sidebar': layout === 'sidebar' }"
  >
    <div class="workspace-intro">
      <section class="welcome">
        <div>
          <span class="eyebrow">工作台</span>
          <h1>{{ greeting }}，{{ profile?.user.name }}。</h1>
          <p>处理待办、查看公告，快速进入工作应用。</p>
        </div>
        <div class="workspace-controls">
          <div class="date-panel">
            <Icon name="calendar" :size="24" /><span>{{ date }}</span>
          </div>
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
      v-if="props.layout === 'cards'"
      class="application-grid portal-applications"
      aria-label="我的应用"
    >
      <a
        v-for="(item, index) in applications"
        :key="item.code"
        class="application-card"
        :class="'tone-' + (index % 3)"
        :href="'#/' + item.code"
      >
        <span class="application-icon" aria-hidden="true"
          ><Icon :name="item.icon" :size="22"
        /></span>
        <div class="application-copy">
          <h2>{{ item.name }}</h2>
          <p>{{ descriptions[item.code] }}</p>
        </div>
        <span class="application-trailing" aria-hidden="true"
          ><Icon name="arrow" :size="15"
        /></span>
      </a>
    </nav>
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
</template>

<style scoped>
.workspace-controls,
.workspace-layout-picker {
  display: flex;
  align-items: center;
}

.workspace-controls {
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 12px;
}

.workspace-layout-picker {
  flex: 0 0 auto;
  gap: 3px;
  padding: 3px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--surface);
}

.workspace-layout-picker button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 9px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary);
  font: inherit;
  font-size: 12px;
  white-space: nowrap;
  cursor: pointer;
}

.workspace-layout-picker button:hover,
.workspace-layout-picker button[aria-pressed="true"] {
  background: var(--brand-soft);
  color: var(--brand);
}

.workspace-layout-picker button[aria-pressed="true"] {
  font-weight: 600;
}

@media (max-width: 760px) {
  .workspace-with-sidebar .welcome {
    margin-bottom: 14px;
  }

  .workspace-with-sidebar .portal-home-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .workspace-controls {
    justify-content: flex-start;
  }
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
