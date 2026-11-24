<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import { router } from "./router";
import {
  session,
  can,
  login,
  logout,
  restoreSession,
  api,
  clearSession,
} from "./lib/api";
import { useAction } from "./lib/ui";
import FormDialog from "./components/FormDialog.vue";
import companyLogo from "./platform/assets/biolink-logo-white.png";
import type { Row, Field } from "./lib/model";
/** 当前页面和仅有权限的导航项。 */
const route = useRoute();
const navigation = computed(() =>
  router
    .getRoutes()
    .filter((item) => item.meta.title && can(String(item.meta.permission))),
);
/** 登录输入仅在组件内短暂保存，提交结束清空密码。 */
const username = ref("");
const password = ref("");
/** 小屏导航开关及密码修改对话框。 */
const menu = ref(false);
const passwordOpen = ref(false);
/** 登录与账号操作共用按钮锁，防止重复提交。 */
const { busy, run } = useAction();
/** 密码规则与后端保持一致；确认值只在浏览器校验，不传给后端。 */
const passwordFields: Field[] = [
  { key: "old_password", label: "原密码", type: "password", required: true },
  {
    key: "new_password",
    label: "新密码",
    type: "password",
    required: true,
    length: 128,
    help: "至少12个字符，修改后需要重新登录。",
  },
  {
    key: "confirm",
    label: "确认新密码",
    type: "password",
    required: true,
    length: 128,
  },
];
/** 恢复身份后再装载业务页面，避免未认证请求及界面闪烁。 */
onMounted(restoreSession);
/** 登录完成进入当前授权页面；不在日志、URL 或错误提示中带密码。 */
async function signIn() {
  try {
    await run(async () => {
      await login(username.value.trim(), password.value);
      if (!can(String(route.meta.permission)))
        await router.push(navigation.value[0]?.path ?? "/");
    });
  } finally {
    password.value = "";
  }
}
/** 修改密码后主动回到登录页，不声称失效令牌仍可使用。 */
async function changePassword(value: Row) {
  await run(async () => {
    if (value.new_password !== value.confirm)
      throw new Error("两次新密码不一致");
    if (value.new_password.length < 12) throw new Error("新密码至少12个字符");
    await api("/auth/change-password", "POST", {
      old_password: value.old_password,
      new_password: value.new_password,
    });
    passwordOpen.value = false;
    clearSession();
  }, "密码已修改，请重新登录");
}
</script>
<template>
  <div v-if="!session.ready" class="boot">正在连接信息管理系统…</div>
  <main v-else-if="!session.user" class="login-screen">
    <section class="login-intro">
      <img class="wordmark" :src="companyLogo" alt="BIO-LINK 百林科" />
      <h1>档案管理工作台</h1>
      <p>查询档案台账，办理借阅与审批，查看流转记录。</p>
    </section>
    <section class="login-card">
      <h2>登录档案工作台</h2>
      <p class="muted">使用管理员分配的账号访问</p>
      <form @submit.prevent="signIn">
        <label for="username">账号</label
        ><el-input
          id="username"
          v-model="username"
          autocomplete="username"
          required
          placeholder="请输入账号"
        /><label for="password">密码</label
        ><el-input
          id="password"
          v-model="password"
          type="password"
          show-password
          autocomplete="current-password"
          required
          placeholder="请输入密码"
        /><el-button
          native-type="submit"
          type="primary"
          :loading="busy"
          class="login-button"
          >登录</el-button
        >
      </form>
      <small>权限由所属部门及授权范围决定</small>
    </section>
  </main>
  <div v-else class="workspace">
    <div v-if="menu" class="shade" @click="menu = false" />
    <aside :class="['sidebar', { visible: menu }]">
      <div class="brand">
        <span class="brand-icon">档</span>
        <div>信息管理系统<small>档案管理工作台</small></div>
      </div>
      <p class="nav-caption">工作空间</p>
      <nav>
        <router-link
          v-for="item in navigation"
          :key="item.path"
          :to="item.path"
          @click="menu = false"
          ><span class="nav-dot" />{{ item.meta.title }}</router-link
        >
      </nav>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <el-button
          class="mobile-menu"
          aria-label="展开导航"
          @click="menu = !menu"
          >☰</el-button
        ><span
          >档案管理 <b>/ {{ route.meta.title }}</b></span
        >
        <div class="account">
          <span>{{ session.user.real_name || session.user.username }}</span
          ><el-button text @click="passwordOpen = true">修改密码</el-button
          ><el-button text :disabled="busy" @click="run(logout)"
            >退出</el-button
          >
        </div>
      </header>
      <main class="content">
        <router-view
          v-if="can(String(route.meta.permission))"
          v-slot="{ Component }"
          ><component
            :is="Component"
            :key="`${session.user.user_id}:${route.path}`" /></router-view
        ><el-empty
          v-else
          description="当前账号没有此页面的访问权限，请从左侧选择可用功能。"
        />
      </main>
    </div>
    <FormDialog
      v-model="passwordOpen"
      title="修改登录密码"
      :fields="passwordFields"
      :busy="busy"
      @save="changePassword"
    />
  </div>
</template>
