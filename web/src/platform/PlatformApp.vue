<script setup lang="ts">
import { trackInteractions } from "./interaction";

import PasswordRecovery from "./PasswordRecovery.vue";
import MessageService from "./MessageService.vue";
import {
  computed,
  defineAsyncComponent,
  onMounted,
  nextTick,
  onUnmounted,
  provide,
  ref,
  watch,
} from "vue";
import Icon from "../preview/PlatformIcon.vue";
/** 公司提供的原始透明 LOGO 随平台打包，登录前也可同源加载。 */
import biolinkLogo from "./assets/biolink-logo.png";
/** 登录页使用公司提供的白色版本，直接呈现在深色背景上。 */
import biolinkLogoWhite from "./assets/biolink-logo-white.png";
import {
  api,
  can,
  login,
  profile,
  setProfile,
  type Profile,
  type PasswordPromptOptions,
} from "./api";
import Workspace from "./Workspace.vue";
import AppearanceSettings from "./AppearanceSettings.vue";
import WorkspaceSidebar from "./WorkspaceSidebar.vue";
import ServiceHealthNotice from "./ServiceHealthNotice.vue";
/** 首页扩展页面仅在访问时加载，员工不下载全部内容管理表单。 */
const PortalAnnouncements = defineAsyncComponent(
  () => import("./PortalAnnouncements.vue"),
);
const PortalFeedback = defineAsyncComponent(
  () => import("./PortalFeedback.vue"),
);
const PortalAdmin = defineAsyncComponent(() => import("./PortalAdmin.vue"));
import PortalTasks from "./PortalTasks.vue";
/** 会议业务按需加载，统一待办保留会议与事项深链接。 */
const Meetings = defineAsyncComponent(() => import("./Meetings.vue"));
const MeetingWeeklyExcelEditor = defineAsyncComponent(() => import("./MeetingWeeklyExcelEditor.vue"));
import TodoReminderPopup from "./TodoReminderPopup.vue";
/** 搜索直接进入原业务授权列表，不在首页另存文件索引。 */
const searchText = ref("");
const searchModule = ref("drive");
function search() {
  const module = can(
    searchModule.value === "drive" ? "DRIVE.ACCESS" : "ARCHIVE.ACCESS",
  )
    ? searchModule.value
    : can("DRIVE.ACCESS")
      ? "drive"
      : "archive";
  location.hash = `#/${module}?keyword=${encodeURIComponent(searchText.value.trim())}`;
}

/** 管理页面按访问需求加载，员工首页不下载全部管理代码。 */
/** 凭据核验和人员交接独立按需加载，入口权限分别判断。 */
const EvidenceDesk = defineAsyncComponent(() => import("./EvidenceDesk.vue"));
const Personnel = defineAsyncComponent(() => import("./Personnel.vue"));
/** 批量台账与扫描作业按权限独立加载，仅电脑端工作区。 */
const Imports = defineAsyncComponent(() => import("./Imports.vue"));
const Scanning = defineAsyncComponent(() => import("./Scanning.vue"));
/** 预留业务模块仅提供布局预览，不加载数据源凭据或调用接入接口。 */
const DataIntake = defineAsyncComponent(() => import("./DataIntake.vue"));
const People = defineAsyncComponent(() => import("./People.vue"));
const Roles = defineAsyncComponent(() => import("./Roles.vue"));
const OperationsConsole = defineAsyncComponent(
  () => import("./OperationsConsole.vue"),
);
const Security = defineAsyncComponent(() => import("./Security.vue"));
const Audit = defineAsyncComponent(() => import("./Audit.vue"));
/** 公盘按需加载，仅电脑端布局，权限仍以每次API结果为准。 */
const Drive = defineAsyncComponent(() => import("./Drive.vue"));
/** 档案与流程按当前模块入口授权按需加载，公盘权限不开放这两个页面。 */
/** 目录维护按独立档案维护权加载，仅电脑端。 */
const Catalog = defineAsyncComponent(() => import("./Catalog.vue"));
const Archive = defineAsyncComponent(() => import("./Archive.vue"));
const Workflow = defineAsyncComponent(() => import("./Workflow.vue"));
/** 管理配置按实际组织或档案配置能力加载，两类内容分别鉴权。 */
/** 投递运维只按档案配置权限开放，不能由平台或公盘角色推导。 */
const Operations = defineAsyncComponent(() => import("./Operations.vue"));
const Settings = defineAsyncComponent(() => import("./Settings.vue"));
/** 当前导航来自 hash，不携带认证凭据。 */
const route = ref(location.hash.slice(2) || "home");
/** 批量导入入口可预选类型，导入页面再按实际权限过滤。 */
const initialImportType = computed(
  () => new URLSearchParams(route.value.split("?")[1]).get("type") ?? undefined,
);
/** 登录、首次设密与页面恢复状态分别管理。 */
const recoveryMode = ref(false),
  departmentName = ref("");
const loading = ref(true),
  busy = ref(false),
  error = ref(""),
  notice = ref("");
const employeeNo = ref(""),
  password = ref(""),
  nextPassword = ref(""),
  confirmPassword = ref("");
/** 本人再验证对话框；关闭时清空密码并结束挂起调用。 */
const verificationDialog = ref<HTMLDialogElement | null>(null),
  verifyPassword = ref(""),
  verifyTitle = ref("");
const verifyOptions = ref<PasswordPromptOptions>({});
let verifyResolve: ((value: string | null) => void) | null = null;
/** 本人改密对话框独立于管理再验证，不复用口令值。 */
const accountDialog = ref<HTMLDialogElement | null>(null),
  currentPassword = ref(""),
  newPassword = ref(""),
  accountError = ref("");
/** 平台 IT 任职独立于公盘及档案模块角色。 */
const systemAdministrator = computed(
  () =>
    !!profile.value?.roles.some(
      (role) => role.module === "PLATFORM" && role.code === "IT_ADMIN",
    ),
);
/** 当前有效导航，隐藏无权入口但不作为服务端授权依据。 */
const tabs = computed(() =>
  [
    { code: "home", name: "工作台", visible: true },
    { code: "announcements", name: "通知公告", visible: true },
    { code: "todos", name: "我的待办", visible: true },
    { code: "inbox", name: "我的消息", visible: true },
    { code: "feedback", name: "意见反馈", visible: true },
    {
      code: "portal-admin",
      name: "首页管理",
      visible:
        can("PLATFORM.ANNOUNCEMENTS_MANAGE") ||
        can("PLATFORM.SITES_MANAGE") ||
        can("PLATFORM.FEEDBACK_MANAGE"),
    },
    {
      code: "message-service",
      name: "消息服务",
      visible: can("PLATFORM.USERS_MANAGE"),
    },
    {
      code: "imports",
      name: "批量导入",
      visible:
        can("ARCHIVE.CATALOG_MANAGE") ||
        can("PLATFORM.USERS_MANAGE") ||
        can("PLATFORM.DEPARTMENTS_MANAGE"),
    },
    {
      code: "data-intake",
      name: "业务数据接入",
      visible: systemAdministrator.value,
    },
    {
      code: "scanning",
      name: "扫描作业",
      visible:
        can("ARCHIVE.CATALOG_MANAGE") || can("PLATFORM.SCAN_DEVICES_MANAGE"),
    },
    { code: "drive", name: "企业公盘", visible: can("DRIVE.ACCESS") },
    { code: "archive", name: "档案管理", visible: true },
    { code: "meetings", name: "会议预约", visible: !systemAdministrator.value },
    {
      code: "meeting-config",
      name: "会议配置",
      visible: systemAdministrator.value,
    },
    {
      code: "catalog",
      name: "目录与保管",
      visible: can("ARCHIVE.CATALOG_MANAGE"),
    },
    {
      code: "evidence",
      name: "凭据补录",
      visible: can("PLATFORM.EVIDENCE_RECORD"),
    },
    {
      code: "personnel",
      name: can("ARCHIVE.CONFIG_MANAGE") ? "档案员交接" : "员工转岗",
      visible: can("PLATFORM.USERS_MANAGE") || can("ARCHIVE.CONFIG_MANAGE"),
    },
    {
      code: "workflow",
      name: "流程中心",
      visible: can("ARCHIVE.ACCESS") || can("PLATFORM.DEPARTMENTS_MANAGE"),
    },
    {
      code: "operations",
      name: "通知投递",
      visible:
        can("PLATFORM.OPERATIONS_MANAGE") || can("ARCHIVE.CONFIG_MANAGE"),
    },
    {
      code: "settings",
      name: "管理配置",
      visible: systemAdministrator.value && can("PLATFORM.DEPARTMENTS_MANAGE"),
    },
    {
      code: "people",
      name: "组织与人员",
      visible: can("PLATFORM.USERS_MANAGE"),
    },
    {
      code: "roles",
      name: "角色与权限",
      visible: systemAdministrator.value && can("PLATFORM.ROLES_MANAGE"),
    },
    {
      code: "operations-console",
      name: "运维与保护",
      visible: can("PLATFORM.OPERATIONS_MANAGE"),
    },
    {
      code: "security",
      name: "安全管理",
      visible: can("PLATFORM.SECURITY_MANAGE"),
    },
    {
      code: "audit",
      name: "操作记录",
      visible: systemAdministrator.value,
    },
  ].filter((tab) => tab.visible),
);
/** 左侧管理导航排除员工业务与个人入口，模块权限仍由服务端执行。 */
const workspaceCodes = new Set([
  "home",
  "drive",
  "archive",
  "meetings",
  "workflow",
  "announcements",
  "todos",
  "feedback",
  "inbox",
]);
const managementTabs = computed(() =>
  tabs.value.filter((t) => !workspaceCodes.has(t.code)),
);
/** 管理功能固定分组，只保留本人有权使用的页面，空分组不占导航空间。 */
const managementGroups = computed(() =>
  [
    { name: "组织与配置", codes: ["people", "roles", "settings", "personnel"] },
    {
      name: "业务管理",
      codes: [
        "portal-admin",
        "meeting-config",
        "catalog",
        "imports",
        "scanning",
        "evidence",
        "data-intake",
      ],
    },
    {
      name: "运行与审计",
      codes: [
        "operations-console",
        "security",
        "message-service",
        "operations",
        "audit",
      ],
    },
  ]
    .map((group) => ({
      name: group.name,
      items: group.codes.flatMap((code) =>
        managementTabs.value.filter((tab) => tab.code === code),
      ),
    }))
    .filter((group) => group.items.length),
);
const allowedRoute = computed(() => {
  const requested = route.value.split("?")[0];
  if (requested === "meeting-weekly-excel") return requested;
  // 管理员的会议模块入口仍进配置，带具体会议或事项的链接交由业务接口鉴权。
  if (requested === "meetings" && systemAdministrator.value) {
    const meetingParams = new URLSearchParams(route.value.split("?")[1]);
    return meetingParams.get("id") || meetingParams.get("item") ? "meetings" : "meeting-config";
  }
  if (
    requested === "physical" &&
    can("ARCHIVE.ACCESS") &&
    (can("ARCHIVE.PHYSICAL_BORROW") || can("ARCHIVE.PHYSICAL_MANAGE"))
  )
    return "workflow";
  if (requested === "management")
    return managementGroups.value[0]?.items[0]?.code ?? "home";
  return tabs.value.some((tab) => tab.code === requested) ? requested : "home";
});
const inManagement = computed(() =>
  managementTabs.value.some((tab) => tab.code === allowedRoute.value),
);
/** 档案业务共用左侧导航；保留原路由以兼容待办、消息和历史收藏链接。 */
const archiveTabs = computed(() =>
  ["archive", "workflow"].flatMap((code) =>
    tabs.value
      .filter((tab) => tab.code === code)
      .map((tab) => ({
        ...tab,
        name: code === "archive" ? "档案查询" : tab.name,
      })),
  ),
);
const inArchive = computed(() =>
  archiveTabs.value.some((tab) => tab.code === allowedRoute.value),
);
type WorkspaceLayout = "cards" | "sidebar";
const workspaceLayout = ref<WorkspaceLayout>("cards");
const workspaceLayoutKey = computed(() => {
  const userId = profile.value?.user.id;
  return userId ? "ims.workspace.layout." + userId : "";
});

/** 同一账号的布局跨首页和模块页面使用，继续读取已有浏览器偏好。 */
watch(
  workspaceLayoutKey,
  (key) => {
    workspaceLayout.value = "cards";
    if (!key) return;
    try {
      if (localStorage.getItem(key) === "sidebar")
        workspaceLayout.value = "sidebar";
    } catch {
      // 存储不可用时仍可在当前会话切换。
    }
  },
  { immediate: true },
);

function selectWorkspaceLayout(next: WorkspaceLayout) {
  workspaceLayout.value = next;
  if (!workspaceLayoutKey.value) return;
  try {
    localStorage.setItem(workspaceLayoutKey.value, next);
  } catch {
    // 存储不可用时保留当前会话选择。
  }
}

const workspaceSidebar = computed(() => workspaceLayout.value === "sidebar");
/** 顶部卡片与左侧导航共用路由已确定的可见模块。 */
const workspaceApplications = computed(() => {
  const icons = {
    drive: "file",
    archive: "archive",
    meetings: "calendar",
    "data-intake": "database",
  };
  const apps = Object.entries(icons).flatMap(([code, icon]) =>
    tabs.value
      .filter((tab) => tab.code === code)
      .map(({ name }) => ({ code, name, icon })),
  );
  if (managementGroups.value.length)
    apps.push({ code: "management", name: "管理中心", icon: "shield" });
  return apps;
});

const currentWorkspaceModule = computed(() => {
  if (allowedRoute.value === "data-intake") return "data-intake";
  if (inManagement.value) return "management";
  if (inArchive.value) return "archive";
  return allowedRoute.value;
});
const workspaceSubnav = computed(() =>
  currentWorkspaceModule.value === "management"
    ? managementGroups.value
        .map((group) => ({
          ...group,
          items: group.items.filter((tab) => tab.code !== "data-intake"),
        }))
        .filter((group) => group.items.length)
    : currentWorkspaceModule.value === "archive"
      ? [{ name: "", items: archiveTabs.value }]
      : [],
);

/** 全局提示只使用已清洗的消息，不显示原始请求。 */
function inform(message: string) {
  notice.value = message;
  setTimeout(() => {
    notice.value = "";
  }, 5000);
}
/** 所有管理表单共用一次性再验证；取消不得继续写入。 */
async function askPassword(
  title: string,
  options: PasswordPromptOptions = {},
): Promise<string | null> {
  verifyTitle.value = title;
  verifyOptions.value = options;
  verifyPassword.value = "";
  verificationDialog.value?.showModal();
  return new Promise((resolve) => {
    verifyResolve = resolve;
  });
}
/** 关闭清除秘密及回调，避免后续弹窗误用上一次口令。 */
function finishVerification(accepted: boolean) {
  const value = accepted ? verifyPassword.value : null;
  verifyPassword.value = "";
  verificationDialog.value?.close();
  verifyResolve?.(value);
  verifyResolve = null;
}
provide("askPassword", askPassword);
provide("inform", inform);
/** 登录成功后回到本人工作台；不会显示静态模拟身份。 */
async function signIn() {
  busy.value = true;
  error.value = "";
  try {
    await login(employeeNo.value, password.value);
    if (!profile.value?.user.passwordSetupRequired) password.value = "";
    location.hash = "/home";
  } catch (failure) {
    error.value = (failure as Error).message;
    password.value = "";
  } finally {
    busy.value = false;
  }
}
/** 首次设密使用临时密码，完成后必须用新密码重新登录。 */
async function setupPassword() {
  if (nextPassword.value !== confirmPassword.value) {
    error.value = "两次输入的密码不一致";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    await api("/auth/initial-password", {
      method: "PUT",
      body: JSON.stringify({
        temporaryPassword: password.value,
        newPassword: nextPassword.value,
      }),
    });
    setProfile(null);
    password.value = "";
    nextPassword.value = "";
    confirmPassword.value = "";
    inform("密码已设置，请重新登录");
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 服务端退出成功后清空界面，失败时明确展示并保留重试入口。 */
async function signOut() {
  try {
    await api("/auth/logout", { method: "POST" });
    setProfile(null);
    password.value = "";
  } catch (failure) {
    inform((failure as Error).message);
  }
}
/** 打开时读取当前本人部门，显示信息不依赖管理员组织列表权限。 */
async function openAccount() {
  accountError.value = "";
  departmentName.value = "";
  accountDialog.value?.showModal();
  try {
    const data = await api<{ departmentName: string }>("/auth/profile");
    departmentName.value = data.departmentName;
  } catch (failure) {
    accountError.value = (failure as Error).message;
  }
}
/** 本人修改密码后重新登录，口令不会存入浏览器存储。 */
async function changePassword() {
  busy.value = true;
  accountError.value = "";
  try {
    await api("/auth/password", {
      method: "PUT",
      body: JSON.stringify({
        currentPassword: currentPassword.value,
        newPassword: newPassword.value,
      }),
    });
    accountDialog.value?.close();
    setProfile(null);
    inform("密码已修改，请重新登录");
  } catch (failure) {
    accountError.value = (failure as Error).message;
  } finally {
    busy.value = false;
    currentPassword.value = "";
    newPassword.value = "";
  }
}
/** 路由变更只影响呈现，不修改会话或权限。 */
function onHash() {
  route.value = location.hash.slice(2) || "home";
  // 模块切换后从页首开始，避免长列表的滚动位置遮住下一页标题。
  void nextTick(() => window.scrollTo(0, 0));
}
/** 服务端撤权导致的失效会关闭所有敏感对话框。 */
function onExpired() {
  finishVerification(false);
  accountDialog.value?.close();
  inform("登录已失效，请重新登录");
}
let stopInteractions: (() => void) | undefined;
onMounted(async () => {
  // 首次设密会话只允许认证接口，上报业务操作会被误计为连续越权。
  stopInteractions = trackInteractions(() =>
    profile.value?.user.passwordSetupRequired
      ? ""
      : (profile.value?.csrfToken ?? ""),
  );
  window.addEventListener("hashchange", onHash);
  window.addEventListener("ims-session-expired", onExpired);
  try {
    setProfile(await api<Profile>("/auth/me", {}, false));
  } catch {
    setProfile(null);
  } finally {
    loading.value = false;
  }
});
onUnmounted(() => {
  stopInteractions?.();
  window.removeEventListener("hashchange", onHash);
  window.removeEventListener("ims-session-expired", onExpired);
});
</script>

<template>
  <div v-if="loading" class="p-loading" role="status">正在打开工作台…</div>
  <main
    v-else-if="!profile || profile.user.passwordSetupRequired"
    class="login-layout"
  >
    <section class="login-story">
      <a class="brand" href="#/home"
        ><img
          class="company-logo"
          :src="biolinkLogoWhite"
          alt="BIO-LINK 百林科"
          width="1229"
          height="375"
      /></a>
      <div class="login-statement">
        <h1>信息管理系统</h1>
      </div>
      <small>企业工作平台</small>
    </section>
    <section class="login-form-panel">
      <div class="login-form-inner">
        <span class="eyebrow">WELCOME BACK</span>
        <h2>
          {{
            profile?.user.passwordSetupRequired
              ? "设置你的密码"
              : recoveryMode
                ? "找回密码"
                : "欢迎回来"
          }}
        </h2>
        <p class="muted">
          {{
            profile?.user.passwordSetupRequired
              ? "初始密码与工号一致。新密码至少8位，同时包含英文字母和数字，且须与工号不同。"
              : recoveryMode
                ? "通过绑定的公司邮箱验证身份并设置新密码。"
                : "使用公司工号登录，进入你的工作空间。"
          }}
        </p>
        <PasswordRecovery
          v-if="!profile && recoveryMode"
          @back="recoveryMode = false"
          @completed="
            recoveryMode = false;
            inform('密码已设置，请使用新密码登录');
          "
        />
        <form v-else-if="!profile" @submit.prevent="signIn">
          <label
            >公司工号<input
              v-model="employeeNo"
              autocomplete="username"
              maxlength="64"
              placeholder="请输入工号"
              required /></label
          ><label
            >登录密码<input
              v-model="password"
              type="password"
              autocomplete="current-password"
              maxlength="4096"
              placeholder="请输入密码"
              required
          /></label>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <button class="primary full" :disabled="busy">
            {{ busy ? "正在登录…" : "登录工作台"
            }}<Icon name="arrow" :size="18" />
          </button>
          <button
            class="text-button"
            type="button"
            @click="
              recoveryMode = true;
              error = '';
              password = '';
            "
          >
            忘记密码？通过公司邮箱找回
          </button>
          <p class="login-help">首次使用请联系管理员开通账号。</p>
        </form>
        <form v-else @submit.prevent="setupPassword">
          <label
            >初始密码<input
              v-model="password"
              type="password"
              autocomplete="current-password"
              required /></label
          ><label
            >设置新密码<input
              v-model="nextPassword"
              type="password"
              autocomplete="new-password"
              minlength="8"
              pattern="(?=.*[A-Za-z])(?=.*[0-9]).{8,}"
              title="至少8位，同时包含英文字母和数字"
              maxlength="4096"
              required /></label
          ><label
            >再次输入新密码<input
              v-model="confirmPassword"
              type="password"
              autocomplete="new-password"
              minlength="8"
              pattern="(?=.*[A-Za-z])(?=.*[0-9]).{8,}"
              title="至少8位，同时包含英文字母和数字"
              required
          /></label>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <button class="primary full" :disabled="busy">设置密码</button
          ><button class="text-button" type="button" @click="signOut">
            返回登录
          </button>
        </form>
      </div>
      <footer>公司内部使用 · 保护账号与资料安全</footer>
    </section>
  </main>
  <MeetingWeeklyExcelEditor v-else-if="allowedRoute === 'meeting-weekly-excel'" :key="route" :route="route" />
  <div
    v-else
    class="platform-shell"
    :class="{ 'with-workspace-sidebar': workspaceSidebar }"
  >
    <TodoReminderPopup :key="profile.user.id" />
    <header class="topbar">
      <a class="brand" href="#/home"
        ><img
          class="company-logo"
          :src="biolinkLogo"
          alt="BIO-LINK 百林科"
          width="706"
          height="215"
        />
        <div class="brand-title">
          <strong>信息管理系统</strong><small>企业工作平台</small>
        </div></a
      >
      <!-- 输入顺序与视觉顺序一致，键盘可依次操作关键词、范围和搜索。 -->
      <form
        v-if="can('DRIVE.ACCESS') || can('ARCHIVE.ACCESS')"
        class="portal-search"
        role="search"
        aria-label="资料搜索"
        @submit.prevent="search"
      >
        <input
          v-model="searchText"
          aria-label="搜索关键词"
          maxlength="100"
          placeholder="搜索有权查看的资料"
        />
        <select v-model="searchModule" aria-label="搜索范围">
          <option v-if="can('DRIVE.ACCESS')" value="drive">公盘文件</option>
          <option v-if="can('ARCHIVE.ACCESS')" value="archive">档案目录</option>
        </select>
        <button class="primary">搜索</button>
      </form>
      <div class="account-actions">
        <AppearanceSettings />
        <a v-if="allowedRoute !== 'home'" class="workspace-back" href="#/home">
          <Icon name="home" :size="16" aria-hidden="true" />
          返回工作台
        </a>
        <a
          class="portal-account-link"
          href="#/inbox"
          :class="{ active: allowedRoute === 'inbox' }"
          :aria-current="allowedRoute === 'inbox' ? 'page' : undefined"
          >我的消息</a
        >
        <a
          class="portal-account-link"
          href="#/feedback"
          target="_blank"
          rel="noopener"
          :class="{ active: allowedRoute === 'feedback' }"
          :aria-current="allowedRoute === 'feedback' ? 'page' : undefined"
          >意见反馈</a
        >
        <button class="profile-button" @click="openAccount()">
          <span class="avatar">{{ profile.user.name.slice(-1) }}</span
          ><span>{{ profile.user.name }}</span></button
        ><button class="text-button" @click="signOut">退出</button>
      </div>
    </header>
    <WorkspaceSidebar
      v-if="workspaceSidebar"
      :applications="workspaceApplications"
      :active-module="currentWorkspaceModule"
      :active-route="allowedRoute"
      :groups="workspaceSubnav"
    />
    <ServiceHealthNotice
      v-if="
        can('PLATFORM.OPERATIONS_MANAGE') &&
        allowedRoute !== 'operations-console'
      "
    />
    <main
      class="page-container"
      :class="{
        'management-layout': !workspaceSidebar && (inManagement || inArchive),
      }"
    >
      <aside
        v-if="!workspaceSidebar && inManagement"
        class="management-sidebar"
        aria-label="管理中心模块"
      >
        <div class="management-sidebar-heading">
          <Icon name="shield" :size="22" />
          <h2>管理中心</h2>
        </div>
        <nav aria-label="管理功能">
          <section
            v-for="group in managementGroups"
            :key="group.name"
            class="management-nav-group"
          >
            <h3>{{ group.name }}</h3>
            <a
              v-for="item in group.items"
              :key="item.code"
              :href="`#/${item.code}`"
              :class="{ active: allowedRoute === item.code }"
              :aria-current="allowedRoute === item.code ? 'page' : undefined"
            >
              <span>{{ item.name }}</span
              ><Icon name="chevron" :size="14" />
            </a>
          </section>
        </nav>
      </aside>
      <aside
        v-else-if="!workspaceSidebar && inArchive"
        class="management-sidebar"
        aria-label="档案管理模块"
      >
        <div class="management-sidebar-heading">
          <Icon name="archive" :size="22" />
          <h2>档案管理</h2>
        </div>
        <nav class="management-nav-group" aria-label="档案业务">
          <a
            v-for="item in archiveTabs"
            :key="item.code"
            :href="`#/${item.code}`"
            :class="{ active: allowedRoute === item.code }"
            :aria-current="allowedRoute === item.code ? 'page' : undefined"
          >
            <span>{{ item.name }}</span>
            <Icon name="chevron" :size="14" />
          </a>
        </nav>
      </aside>
      <!-- 工作台仅在当前登录会话内保留，其他模块仍按完整路由重新挂载。 -->
      <KeepAlive :key="profile.csrfToken">
        <Workspace
          v-if="allowedRoute === 'home'"
          :applications="workspaceApplications"
          :layout="workspaceLayout"
          @layout="selectWorkspaceLayout"
        />
      </KeepAlive>
      <div
        v-if="allowedRoute !== 'home'"
        class="platform-page-content"
        :key="route"
      >
        <PortalAnnouncements
          v-if="allowedRoute === 'announcements'"
        /><PortalFeedback v-else-if="allowedRoute === 'feedback'" /><PortalAdmin
          v-else-if="allowedRoute === 'portal-admin'"
        /><PortalTasks v-else-if="allowedRoute === 'todos'" /><PortalTasks
          v-else-if="allowedRoute === 'inbox'"
          inbox
        /><MessageService
          v-else-if="allowedRoute === 'message-service'"
        /><People v-else-if="allowedRoute === 'people'" /><Roles
          v-else-if="allowedRoute === 'roles'"
        /><Drive v-else-if="allowedRoute === 'drive'" /><Meetings
          v-else-if="
            allowedRoute === 'meetings' || allowedRoute === 'meeting-config'
          "
          :route="allowedRoute === 'meeting-config' ? 'meeting-config' : route"
        /><Catalog v-else-if="allowedRoute === 'catalog'" /><DataIntake
          v-else-if="allowedRoute === 'data-intake'"
        /><Imports
          v-else-if="allowedRoute === 'imports'"
          :initial-type="initialImportType"
        /><Scanning
          v-else-if="allowedRoute === 'scanning'"
        />
        <template v-else-if="allowedRoute === 'archive'">
          <Archive v-if="can('ARCHIVE.ACCESS')" />
          <section
            v-else
            class="panel archive-access-panel"
            aria-labelledby="archive-entry-title"
          >
            <div class="archive-entry-icon">
              <Icon name="archive" :size="30" />
            </div>
            <div class="page-heading">
              <div>
                <h1 id="archive-entry-title">档案管理</h1>
                <p>档案查询、受控阅读、借阅审批与纸质借还。</p>
              </div>
            </div>
            <h2>当前账号尚未配置档案角色</h2>
            <p v-if="can('ARCHIVE.INITIAL_ADMIN_ASSIGN')">
              当前还未配置档案管理员。请先建立档案负责人账号，再在角色与权限中指定首位档案超级管理员。
            </p>
            <p v-else>
              请联系档案管理员配置相应角色和使用范围，完成后重新登录即可进入。
            </p>
            <p class="muted">
              平台管理与档案权限分别配置；档案正文的查看、下载、打印按获批范围开放。
            </p>
            <div
              v-if="can('ARCHIVE.INITIAL_ADMIN_ASSIGN')"
              class="archive-entry-actions"
            >
              <a
                v-if="can('PLATFORM.USERS_MANAGE')"
                class="secondary"
                href="#/people"
                >组织与人员</a
              >
              <a class="primary" href="#/roles?module=ARCHIVE"
                >配置档案管理员</a
              >
            </div>
          </section> </template
        ><Workflow
          v-else-if="allowedRoute === 'workflow'"
          :key="route"
        /><Operations v-else-if="allowedRoute === 'operations'" /><EvidenceDesk
          v-else-if="allowedRoute === 'evidence'"
        /><Personnel v-else-if="allowedRoute === 'personnel'" /><Settings
          v-else-if="allowedRoute === 'settings'"
        /><OperationsConsole
          v-else-if="allowedRoute === 'operations-console'"
        /><Security
          v-else-if="allowedRoute === 'security'"
          :show-audit-link="systemAdministrator"
        /><Audit v-else-if="allowedRoute === 'audit'" />
      </div>
    </main>
    <footer class="page-footer">
      <span>BIO-LINK · 百林科</span><span>企业工作平台</span>
    </footer>
  </div>
  <div v-if="notice" class="toast" role="status">{{ notice }}</div>
  <dialog
    ref="verificationDialog"
    aria-labelledby="identity-verification-title"
    class="form-dialog flow-dialog flow-dialog-compact"
    @cancel.prevent="finishVerification(false)"
  >
    <form class="flow-dialog-shell" @submit.prevent="finishVerification(true)">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="lock" :size="22" /></span>
        <h2 id="identity-verification-title">
          {{ verifyOptions.heading || "验证本人身份" }}
        </h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭身份验证"
          @click="finishVerification(false)"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <p :class="verifyOptions.danger ? 'form-error' : 'muted'">
          {{ verifyTitle }}
        </p>
        <section class="flow-dialog-section">
          <h3>验证凭据</h3>
          <label
            >本人登录密码<input
              v-model="verifyPassword"
              type="password"
              autocomplete="current-password"
              required
              autofocus
          /></label>
        </section>
      </div>
      <footer class="flow-dialog-footer">
        <button
          type="button"
          class="secondary"
          @click="finishVerification(false)"
        >
          取消</button
        ><button :class="verifyOptions.danger ? 'secondary danger' : 'primary'">
          {{ verifyOptions.confirmLabel || "确认并继续" }}
        </button>
      </footer>
    </form>
  </dialog>
  <dialog
    ref="accountDialog"
    aria-labelledby="account-dialog-title"
    class="form-dialog flow-dialog flow-dialog-compact"
    @close="
      currentPassword = '';
      newPassword = '';
    "
  >
    <form class="flow-dialog-shell" @submit.prevent="changePassword">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="user" :size="22" /></span>
        <h2 id="account-dialog-title">个人账号</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭个人账号"
          @click="accountDialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section class="flow-dialog-overview">
          <h3>账号信息</h3>
          <p class="muted">
            {{ profile?.user.name }} · {{ profile?.user.employeeNo }}
          </p>
          <p class="account-email">{{ profile?.user.companyEmail }}</p>
          <p class="muted">所属部门：{{ departmentName || "—" }}</p>
          <p class="muted">
            当前角色：{{
              profile?.roles.map((role) => role.name).join("、") ||
              "未分配业务角色"
            }}
          </p>
        </section>
        <section class="flow-dialog-section">
          <h3>修改密码</h3>
          <label class="flow-inline-field"
            >当前密码<input
              v-model="currentPassword"
              type="password"
              autocomplete="current-password"
              required /></label
          ><label class="flow-inline-field"
            >新密码<input
              v-model="newPassword"
              type="password"
              autocomplete="new-password"
              minlength="8"
              pattern="(?=.*[A-Za-z])(?=.*[0-9]).{8,}"
              title="至少8位，同时包含英文字母和数字"
              required
          /></label>
        </section>
        <p v-if="accountError" class="form-error" role="alert">
          {{ accountError }}
        </p>
      </div>
      <footer class="flow-dialog-footer">
        <button type="button" class="secondary" @click="accountDialog?.close()">
          取消</button
        ><button class="primary" :disabled="busy">修改密码</button>
      </footer>
    </form>
  </dialog>
</template>
