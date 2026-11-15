<script setup lang="ts">
import { computed, ref } from "vue";
import Icon from "./PlatformIcon.vue";
import { getModuleGroups, profiles, type PreviewIdentity, type PreviewView } from "./design-data";
/** 功能页显示名称卡片，不连接业务逻辑。 */
const props = defineProps<{ view: PreviewView; identity: PreviewIdentity }>();
/** 名称卡片打开只读模块弹层。 */
const emit = defineEmits<{ navigate: [target: PreviewView]; module: [title: string, items: string[]] }>();
const profile = computed(() => profiles[props.identity]); // 当前示例身份。
const groups = computed(() => getModuleGroups(props.view, props.identity)); // 当前页的分组名称。
const activeGroup = ref("all"); // 侧栏本地选中分组。
const visibleGroups = computed(() => activeGroup.value === "all" ? groups.value : groups.value.filter((group) => group.id === activeGroup.value));
const totalModules = computed(() => groups.value.reduce((sum, group) => sum + group.modules.length, 0)); // 模块数量，非业务统计。
const pageInfo = computed(() => props.view === "drive" ? { title: "公盘", english: "SHARED WORKSPACE", icon: "folder", summary: "集中共享，有序协作", badge: profile.value.driveRole } : props.view === "archive" ? { title: "档案管理", english: "ARCHIVE CENTER", icon: "archive", summary: "让每一份档案都有迹可循", badge: profile.value.archiveRole } : { title: "管理后台", english: "ADMINISTRATION", icon: "shield", summary: "平台设置与业务管理", badge: props.identity === "it-admin" ? "IT 管理员" : "档案超级管理员" });
const scope = computed(() => props.view === "drive" ? profile.value.driveScope : props.view === "archive" ? profile.value.archiveScope : props.identity === "it-admin" ? "平台管理 · 公盘管理" : "档案配置 · 档案业务管理"); // 当前模拟范围。
/** 切换分组只筛选名称卡片并滚动到页面顶部。 */
function selectGroup(id: string) { activeGroup.value = id; window.scrollTo({ top: 0, behavior: "smooth" }); }
</script>
<template>
  <div :class="['p-module-layout', `is-${view}`]">
    <aside class="p-module-sidebar" aria-label="功能模块导航"><button class="p-sidebar-back" @click="emit('navigate', 'home')"><Icon name="home" :size="16" />返回工作台<Icon name="chevron" :size="13" /></button><div class="p-sidebar-system"><span><Icon :name="pageInfo.icon" :size="23" /></span><div><strong>{{ pageInfo.title }}</strong><small>{{ pageInfo.english }}</small></div></div><p class="p-sidebar-caption">工作空间</p><button :class="['p-sidebar-item', { active: activeGroup === 'all' }]" @click="selectGroup('all')"><Icon name="grid" :size="18" />{{ view === 'admin' ? '管理总览' : '功能总览' }}<span>{{ totalModules }}</span></button><p class="p-sidebar-caption">{{ view === 'admin' ? '管理分组' : '功能分组' }}</p><button v-for="group in groups" :key="group.id" :class="['p-sidebar-item', { active: activeGroup === group.id }]" @click="selectGroup(group.id)"><Icon :name="group.icon" :size="18" />{{ group.name }}<span>{{ group.modules.length }}</span></button><div class="p-sidebar-identity"><span class="p-sidebar-avatar">{{ profile.name.slice(0, 1) }}</span><div><strong>{{ profile.name }}</strong><small>{{ pageInfo.badge }}</small></div></div></aside>
    <main class="p-module-main"><div class="p-module-breadcrumb"><button @click="emit('navigate', 'home')">工作台</button><Icon name="chevron" :size="12" /><span>{{ pageInfo.title }}</span><template v-if="activeGroup !== 'all'"><Icon name="chevron" :size="12" /><span>{{ visibleGroups[0]?.name }}</span></template></div><section class="p-module-hero"><div class="p-module-hero-text"><div class="p-module-kicker">{{ pageInfo.english }}</div><h1>{{ pageInfo.title }}<span>{{ pageInfo.badge }}</span></h1><p>{{ pageInfo.summary }}</p></div><div class="p-module-hero-art" aria-hidden="true"><div class="p-art-ring" /><Icon :name="pageInfo.icon" :size="62" /></div></section><div class="p-module-scope"><Icon name="shield" :size="16" /><span>当前使用范围</span><strong>{{ scope }}</strong></div><section v-for="group in visibleGroups" :key="group.id" class="p-module-section"><div class="p-module-section-heading"><h2><Icon :name="group.icon" :size="19" />{{ group.name }}</h2><span>{{ String(group.modules.length).padStart(2, '0') }} 个模块</span></div><div class="p-module-grid"><button v-for="module in group.modules" :key="module.name" class="p-module-tile" @click="emit('module', module.name, module.children)"><span class="p-module-tile-icon"><Icon :name="module.icon" :size="23" /></span><strong>{{ module.name }}</strong><Icon name="arrow" :size="17" class="p-module-tile-arrow" /></button></div></section><footer class="p-module-footer"><span><span class="p-footer-mark" />IMS · 企业信息管理平台</span><span>{{ pageInfo.title }}</span></footer></main>
  </div>
</template>
