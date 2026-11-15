<script setup lang="ts">
import { computed, ref } from "vue";
import Icon from "./PlatformIcon.vue";
import FeedbackPreview from "./FeedbackPreview.vue";
import RoleSettingsPreview from "./RoleSettingsPreview.vue";
import { applications, tasks, notices, websites, type PreviewApplication, type PreviewIdentity } from "./preview-data";
import "./home.css"; // 首页独立布局样式，避免影响已确认的简洁登录页。

/** 所有功能入口均转交外层展示预览说明，不连接现有业务系统。 */
const emit = defineEmits<{ explain: [title: string, detail: string] }>();
/** 父级设计工具条指定的模拟身份；此属性不能充当真实权限凭证。 */
const props = defineProps<{ identity: PreviewIdentity }>();
/** 管理员弹窗只存在于管理员预览，切回普通用户时随组件一起销毁。 */
const roleSettings = ref<InstanceType<typeof RoleSettingsPreview> | null>(null);
/** 应用搜索只筛选当前设计稿的示例卡片。 */
const search = ref("");
/** 反馈表单的本地组件实例，仅用于打开预览，不发送业务请求。 */
const feedbackPreview = ref<InstanceType<typeof FeedbackPreview> | null>(null);
/** 待办筛选与展开状态只读取本地示例。 */
const taskFilter = ref("全部");
const showAllTasks = ref(false);
/** 先按模拟身份选择入口再筛选名称，普通用户的搜索结果不出现管理卡片。 */
const visibleApplications = computed(() => applications.filter((application) => (!application.administratorOnly || props.identity === "it-admin") && `${application.name}${application.category}`.includes(search.value.trim())));
/** 待办的类别计数与列表使用同一组示例，保持展示一致。 */
const taskTabs = computed(() => ["全部", "待审批", "待处理"].map((label) => ({ label, count: tasks.filter((task) => label === "全部" || task.kind === label).length })));
const filteredTasks = computed(() => tasks.filter((task) => taskFilter.value === "全部" || task.kind === taskFilter.value));
const visibleTasks = computed(() => showAllTasks.value ? filteredTasks.value : filteredTasks.value.slice(0, 4));
/** 日期来自浏览器本地时间；问候对象明确为设计稿示例用户。 */
const today = new Date();
const dateLabel = new Intl.DateTimeFormat("zh-CN", { month: "long", day: "numeric", weekday: "long" }).format(today);
const greeting = today.getHours() < 12 ? "上午好" : today.getHours() < 18 ? "下午好" : "晚上好";
/** 只滚动到对应布局区块，不创建正式业务页面或权限逻辑。 */
function navigate(target: string) {
  if (target === "workspace-top") {
    window.scrollTo({ top: 0, behavior: "smooth" }); // 点击品牌回到完整页首，保留顶栏可见。
    return;
  }
  document.getElementById(target)?.scrollIntoView({ behavior: "smooth", block: "start" });
}
/** 任何未实现的业务入口都明确告知目前仅为界面设计。 */
function previewEntry(title: string) {
  emit("explain", title, `这里是「${title}」的入口示意。本次仅设计统一登录页和平台首页，具体功能将在确认界面后逐步接入。`);
}
/** 根据所点击的卡片展示界面；权限设置只打开本地设计稿，不触发真实授权操作。 */
function openApplication(application: PreviewApplication) {
  if (application.id === "roles") {
    if (props.identity === "it-admin") roleSettings.value?.open();
    return;
  }
  previewEntry(application.name);
}
</script>

<template>
  <div class="p-workspace">
    <div class="p-main-area">
      <header class="p-topbar">
        <div class="p-topbar-inner">
          <a class="p-platform-brand" href="#/home" aria-label="信息管理系统，返回首页顶部" @click.prevent="navigate('workspace-top')"><span class="p-brand-mark" aria-hidden="true"><i /><i /><i /><i /></span><div><strong>信息管理系统</strong><small>企业工作平台</small></div></a>
          <div class="p-topbar-actions">
            <label class="p-search"><Icon name="search" :size="17" /><input v-model="search" aria-label="搜索平台应用" placeholder="搜索平台应用" /><span>/</span></label>
            <button class="p-feedback-button" aria-haspopup="dialog" @click="feedbackPreview?.open()"><Icon name="message" :size="18" /><span>意见反馈</span></button>
            <span class="p-topbar-separator" />
            <button class="p-icon-button p-help-top" aria-label="帮助中心" @click="previewEntry('帮助中心')"><Icon name="help" /></button>
            <button class="p-icon-button p-notification-button" aria-label="查看通知公告" @click="navigate('notices')"><Icon name="bell" /><i /></button>
            <button class="p-top-avatar" aria-label="查看个人设置" @click="previewEntry('个人设置')">林</button>
          </div>
        </div>
      </header>
      <main id="workspace-top" class="p-dashboard">
        <section class="p-greeting-row">
          <div><div class="p-greeting-kicker"><Icon name="sun" :size="17" /> 每一天，都从有序开始</div><h1>{{ greeting }}，林晓<span class="p-greeting-period">。</span></h1><p>欢迎回到你的工作空间，今天也让协作更简单。</p></div>
          <div class="p-date-card"><span class="p-calendar-icon"><Icon name="calendar" :size="22" /></span><div><span>{{ dateLabel }}</span><small>在这里，连接每一份工作</small></div></div>
        </section>

        <section id="applications" class="p-applications" aria-label="平台应用">
          <div class="p-application-grid">
            <article v-for="application in visibleApplications" :key="application.id" :class="['p-application-card', application.tone]">
              <div class="p-app-top"><span :class="['p-app-icon', application.tone]"><Icon :name="application.icon" :size="31" /></span><span :class="['p-category-tag', { planned: application.planned }]">{{ application.category }}</span></div>
              <h3>{{ application.name }}</h3>
              <button v-if="!application.planned" class="p-app-enter" @click="openApplication(application)"><span>进入{{ application.name }}</span><span class="p-arrow-circle"><Icon name="arrow" :size="17" /></span></button>
              <button v-else class="p-app-enter p-app-planned" @click="previewEntry(application.name)"><span><Icon name="clock" :size="15" /> 更多可能，敬请期待</span><Icon name="plus" :size="17" /></button>
              <div class="p-card-orbit" aria-hidden="true" />
            </article>
          </div>
          <div v-if="!visibleApplications.length" class="p-search-empty"><Icon name="search" :size="24" /><p>没有找到匹配的应用</p><button class="p-text-button" @click="search = ''">查看全部应用</button></div>
        </section>

        <div class="p-content-columns">
            <section id="tasks" class="p-panel p-tasks-panel" aria-labelledby="tasks-heading">
              <div class="p-panel-heading"><h2 id="tasks-heading">我的待办<span class="p-heading-count">6</span></h2><button class="p-text-button" @click="showAllTasks = !showAllTasks">{{ showAllTasks ? '收起列表' : '查看全部' }}<Icon name="chevron" :size="15" /></button></div>
              <div class="p-task-tabs" aria-label="筛选待办"><button v-for="tab in taskTabs" :key="tab.label" :class="{ active: taskFilter === tab.label }" :aria-pressed="taskFilter === tab.label" @click="taskFilter = tab.label; showAllTasks = false">{{ tab.label }}<span>{{ tab.count }}</span></button><span class="p-task-note">按更新时间排序</span></div>
              <div class="p-task-list"><button v-for="task in visibleTasks" :key="task.id" class="p-task-row" @click="previewEntry(task.title)"><span :class="['p-row-icon', task.tone]"><Icon :name="task.icon" :size="19" /></span><span class="p-task-info"><span class="p-task-title">{{ task.title }}<small v-if="task.urgent" class="p-urgent">优先</small></span><span class="p-task-meta"><b>{{ task.source }}</b><i />{{ task.person }}<span class="p-task-department"> · {{ task.department }}</span></span></span><span class="p-task-right"><span :class="['p-task-state', { handling: task.kind === '待处理' }]">{{ task.kind }}</span><small>{{ task.time }}</small></span><Icon name="chevron" :size="15" /></button></div>
              <div class="p-panel-bottom"><Icon name="shield" :size="14" /><span>各系统待办，在一个工作台集中查看</span></div>
            </section>

            <section id="notices" class="p-panel p-notices-panel" aria-labelledby="notices-heading">
              <div class="p-panel-heading"><h2 id="notices-heading">通知公告<span class="p-new-dot" /></h2><button class="p-text-button" @click="previewEntry('全部公告')">更多<Icon name="chevron" :size="15" /></button></div>
              <div class="p-notice-list">
                <button v-for="notice in notices" :key="notice.title" class="p-notice" @click="previewEntry(notice.title)">
                  <span class="p-notice-title"><small v-if="notice.pinned">置顶</small>{{ notice.title }}</span>
                  <!-- 完整保留发布来源；窄屏自动换行，不省略姓名、部门或时间。 -->
                  <span class="p-notice-meta">
                    <span class="p-notice-category">{{ notice.category }}</span>
                    <span class="p-notice-publisher" :aria-label="`发布人：${notice.publisher}`"><Icon name="user" :size="12" />{{ notice.publisher }}</span>
                    <span class="p-notice-department" :aria-label="`所属部门：${notice.department}`">{{ notice.department }}</span>
                    <time :datetime="notice.publishedAt" :aria-label="`发布时间：${notice.publishedAt.slice(0, 16).replace('T', ' ')}`"><Icon name="clock" :size="12" />{{ notice.publishedAt.slice(0, 16).replace('T', ' ') }}</time>
                  </span>
                </button>
              </div>
            </section>
            <section class="p-panel p-websites-panel" aria-labelledby="websites-heading">
              <div class="p-panel-heading"><h2 id="websites-heading">常用网站</h2><Icon name="external" :size="16" /></div>
              <ul class="p-website-list">
                <li v-for="website in websites" :key="website.name">
                  <a class="p-website-link" :href="website.url" target="_blank" rel="noopener noreferrer" :aria-label="`${website.name}，在新标签页打开`"><span class="p-website-mark" aria-hidden="true">{{ website.name.slice(0, 1) }}</span><span>{{ website.name }}</span><Icon name="external" :size="17" /></a>
                </li>
              </ul>
            </section>
        </div>
        <footer class="p-dashboard-footer"><span><span class="p-footer-mark" /> IMS · 企业信息管理平台</span><span>连接信息，也连接每一个你。</span></footer>
      </main>
      <FeedbackPreview ref="feedbackPreview" />
      <RoleSettingsPreview v-if="identity === 'it-admin'" ref="roleSettings" />
    </div>
  </div>
</template>
