<script setup lang="ts">
import Icon from "./PlatformIcon.vue";
import { hasManagement, type PreviewProfile, type PreviewView } from "./design-data";
/** 顶栏接收示例员工与当前页面。 */
defineProps<{ profile: PreviewProfile; view: PreviewView }>();
/** 导航和模块名称展示由外层原型处理。 */
const emit = defineEmits<{ navigate: [target: PreviewView]; module: [title: string, items: string[]] }>();
</script>
<template>
  <header class="p-topbar"><div class="p-topbar-inner">
    <a class="p-platform-brand" href="#/home" @click.prevent="emit('navigate', 'home')"><span class="p-brand-mark" aria-hidden="true"><i /><i /><i /><i /></span><div><strong>信息管理系统</strong><small>企业工作平台</small></div></a>
    <nav class="p-product-nav" aria-label="平台导航"><button :class="{ active: view === 'home' }" @click="emit('navigate', 'home')">工作台</button><button :class="{ active: view === 'drive' }" @click="emit('navigate', 'drive')">公盘</button><button :class="{ active: view === 'archive' }" @click="emit('navigate', 'archive')">档案管理</button></nav>
    <div class="p-topbar-actions"><label class="p-search"><Icon name="search" :size="17" /><input aria-label="搜索文件名或档案标题" placeholder="搜索文件名或档案标题" @keydown.enter.prevent="emit('module', '统一搜索', ['公盘文件名检索', '档案标题检索'])" /><span>↵</span></label><button class="p-feedback-button" @click="emit('module', '意见反馈', ['提交反馈', '处理结果'])"><Icon name="message" :size="18" /><span>意见反馈</span></button><button v-if="hasManagement(profile.id)" class="p-top-management" :class="{ active: view === 'admin' }" @click="emit('navigate', 'admin')"><Icon name="shield" :size="16" />管理后台</button><span class="p-topbar-separator" /><button class="p-icon-button p-help-top" aria-label="帮助中心" @click="emit('module', '帮助中心', ['平台使用指南', '公盘使用帮助', '档案借阅帮助'])"><Icon name="help" /></button><button class="p-icon-button p-notification-button" aria-label="消息通知" @click="emit('module', '消息通知', ['站内消息', '审批结果', '到期与归还提醒'])"><Icon name="bell" /><i /></button><button class="p-top-avatar" aria-label="个人中心" @click="emit('module', '个人中心', [profile.name, profile.department, `公盘角色：${profile.driveRole}`, `档案角色：${profile.archiveRole}`, '修改密码'])">{{ profile.name.slice(0, 1) }}</button></div>
  </div></header>
</template>
