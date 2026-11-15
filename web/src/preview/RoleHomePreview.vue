<script setup lang="ts">
import { computed, ref } from "vue";
import Icon from "./PlatformIcon.vue";
import { getPreviewTasks, hasManagement, profiles, type PreviewIdentity, type PreviewView } from "./design-data";
import { notices, websites } from "./preview-data";
import "./home.css";
/** 接收工具条指定的模拟身份。 */
const props = defineProps<{ identity: PreviewIdentity }>();
/** 只在原型内切换页面和打开名称弹层。 */
const emit = defineEmits<{ navigate: [target: PreviewView]; module: [title: string, items: string[]] }>();
const profile = computed(() => profiles[props.identity]); // 当前虚构员工。
const tasks = computed(() => getPreviewTasks(props.identity)); // 身份对应的示例待办。
const taskFilter = ref("全部"); // 本地待办筛选。
const taskTabs = computed(() => ["全部", ...(tasks.value.some((task) => task.kind === "待审批") ? ["待审批"] : []), "待处理"].map((label) => ({ label, count: tasks.value.filter((task) => label === "全部" || task.kind === label).length })));
const visibleTasks = computed(() => tasks.value.filter((task) => taskFilter.value === "全部" || task.kind === taskFilter.value));
/** 应用卡片名称和独立页面一一对应。 */
const applications = computed(() => [
  { id: "drive" as PreviewView, name: "公盘", icon: "folder", tone: "jade", category: "文件协作", caption: profile.value.driveCaption },
  { id: "archive" as PreviewView, name: "档案管理", icon: "archive", tone: "blue", category: "档案业务", caption: profile.value.archiveCaption },
  ...(hasManagement(props.identity) ? [{ id: "admin" as PreviewView, name: "管理后台", icon: "shield", tone: "violet", category: "管理", caption: props.identity === "it-admin" ? "平台与公盘管理" : "档案配置与业务管理" }] : []),
]);
const today = new Date(); // 浏览器本地日期，仅用于问候。
const dateLabel = new Intl.DateTimeFormat("zh-CN", { month: "long", day: "numeric", weekday: "long" }).format(today);
const greeting = today.getHours() < 12 ? "上午好" : today.getHours() < 18 ? "下午好" : "晚上好";
</script>
<template>
  <main class="p-dashboard">
    <section class="p-greeting-row"><div><div class="p-greeting-kicker"><Icon name="sun" :size="17" />每一天，都从有序开始</div><h1>{{ greeting }}，{{ profile.name }}<span class="p-greeting-period">。</span></h1><p>欢迎回到你的工作空间，今天也让协作更简单。</p></div><div class="p-date-card"><span class="p-calendar-icon"><Icon name="calendar" :size="22" /></span><div><span>{{ dateLabel }}</span><small>{{ profile.department }} · {{ profile.label }}</small></div></div></section>
    <section class="p-identity-strip" aria-label="当前身份与使用范围"><span class="p-identity-avatar">{{ profile.name.slice(0, 1) }}</span><div class="p-identity-person"><strong>{{ profile.label }}</strong><span>{{ profile.department }}</span></div><div class="p-scope-item"><span>公盘 · {{ profile.driveRole }}</span><strong>{{ profile.driveScope }}</strong></div><div class="p-scope-item"><span>档案 · {{ profile.archiveRole }}</span><strong>{{ profile.archiveScope }}</strong></div></section>
    <section class="p-applications" aria-label="平台应用"><div class="p-application-grid"><article v-for="application in applications" :key="application.id" :class="['p-application-card', application.tone]"><div class="p-app-top"><span :class="['p-app-icon', application.tone]"><Icon :name="application.icon" :size="31" /></span><span class="p-category-tag">{{ application.category }}</span></div><h3>{{ application.name }}</h3><button class="p-app-enter" @click="emit('navigate', application.id)"><span>{{ application.caption }}</span><span class="p-arrow-circle"><Icon name="arrow" :size="17" /></span></button><div class="p-card-orbit" aria-hidden="true" /></article></div></section>
    <div class="p-content-columns">
      <section class="p-panel p-tasks-panel" aria-labelledby="tasks-heading"><div class="p-panel-heading"><h2 id="tasks-heading">我的待办<span class="p-heading-count">{{ tasks.length }}</span></h2><button class="p-text-button" @click="emit('module', '我的待办', ['待我处理', ...(tasks.some(task => task.kind === '待审批') ? ['待我审批'] : []), '已办事项'])">查看全部<Icon name="chevron" :size="15" /></button></div><div class="p-task-tabs" aria-label="筛选待办"><button v-for="tab in taskTabs" :key="tab.label" :class="{ active: taskFilter === tab.label }" :aria-pressed="taskFilter === tab.label" @click="taskFilter = tab.label">{{ tab.label }}<span>{{ tab.count }}</span></button><span class="p-task-note">按更新时间排序</span></div><div class="p-task-list"><button v-for="task in visibleTasks" :key="task.title" class="p-task-row" @click="emit('module', task.title, task.kind === '待审批' ? ['申请信息', '审批进度', '处理记录'] : ['事项详情', '相关记录'])"><span :class="['p-row-icon', task.tone]"><Icon :name="task.icon" :size="19" /></span><span class="p-task-info"><span class="p-task-title">{{ task.title }}</span><span class="p-task-meta"><b>{{ task.source }}</b><i />{{ task.person }}<span class="p-task-department"> · {{ task.department }}</span></span></span><span class="p-task-right"><span :class="['p-task-state', { handling: task.kind === '待处理' }]">{{ task.kind }}</span><small>{{ task.time }}</small></span><Icon name="chevron" :size="15" /></button></div><div class="p-home-personal-links"><button @click="emit('module', '我的申请', ['申请进度', '历史申请'])"><Icon name="file" :size="17" />我的申请<Icon name="chevron" :size="13" /></button><button @click="emit('module', '我的借阅', ['电子借阅', '纸质借阅', '续借申请'])"><Icon name="archive" :size="17" />我的借阅<Icon name="chevron" :size="13" /></button></div><div class="p-panel-bottom"><Icon name="shield" :size="14" /><span>各系统待办，在一个工作台集中查看</span></div></section>
      <section class="p-panel p-notices-panel" aria-labelledby="notices-heading"><div class="p-panel-heading"><h2 id="notices-heading">通知公告<span class="p-new-dot" /></h2><button class="p-text-button" @click="emit('module', '通知公告', ['全部公告', '平台公告', '使用帮助'])">更多<Icon name="chevron" :size="15" /></button></div><div class="p-notice-list"><button v-for="notice in notices" :key="notice.title" class="p-notice" @click="emit('module', notice.title, [notice.category, `发布人：${notice.publisher}`, `所属部门：${notice.department}`, `发布时间：${notice.publishedAt.slice(0, 16).replace('T', ' ')}`])"><span class="p-notice-title"><small v-if="notice.pinned">置顶</small>{{ notice.title }}</span><span class="p-notice-meta"><span class="p-notice-category">{{ notice.category }}</span><span class="p-notice-publisher" :aria-label="`发布人：${notice.publisher}`"><Icon name="user" :size="12" />{{ notice.publisher }}</span><span class="p-notice-department" :aria-label="`所属部门：${notice.department}`">{{ notice.department }}</span><time :datetime="notice.publishedAt">{{ notice.publishedAt.slice(0, 16).replace('T', ' ') }}</time></span></button></div></section>
      <section class="p-panel p-websites-panel" aria-labelledby="websites-heading"><div class="p-panel-heading"><h2 id="websites-heading">常用网站</h2><Icon name="external" :size="16" /></div><ul class="p-website-list"><li v-for="website in websites" :key="website.name"><a class="p-website-link" :href="website.url" target="_blank" rel="noopener noreferrer"><span class="p-website-mark">{{ website.name.slice(0, 1) }}</span><span>{{ website.name }}</span><Icon name="external" :size="17" /></a></li></ul></section>
    </div>
    <footer class="p-dashboard-footer"><span><span class="p-footer-mark" />IMS · 企业信息管理平台</span><span>连接信息，也连接每一个你。</span></footer>
  </main>
</template>
