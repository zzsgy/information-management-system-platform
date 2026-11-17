<script setup lang="ts">
import Icon from "../preview/PlatformIcon.vue";
import { computed, inject, onMounted, ref } from "vue";
import { ElOption, ElSelect } from "element-plus";
import "element-plus/es/components/select/style/css";
import {
  api,
  mutate,
  profile,
  query,
  can,
  setProfile,
  type Page,
  type Role,
  type User,
  type Profile,
} from "./api";
/** IT 可管理全部模块角色，其他账号的可见范围仍由后端判定。 */
const modules = [
  { code: "PLATFORM", name: "平台" },
  { code: "DRIVE", name: "公盘" },
  { code: "ARCHIVE", name: "档案" },
];
const selectedModule = ref("PLATFORM"),
  roles = ref<Role[]>([]),
  error = ref(""),
  loading = ref(false);
/** 平台 IT 可配置各模块角色。 */
const systemAdministrator = computed(() =>
  profile.value?.roles.some(
    (role) => role.module === "PLATFORM" && role.code === "IT_ADMIN",
  ),
);
const askPassword =
    inject<(title: string) => Promise<string | null>>("askPassword")!,
  inform = inject<(message: string) => void>("inform")!;
/** 分配表单只显示已授权人员视图，列表不加载无关邮箱信息。 */
const dialog = ref<HTMLDialogElement | null>(null),
  selectedUserId = ref(""),
  users = ref<Pick<User, "id" | "name" | "employeeNo" | "departmentId">[]>([]),
  target = ref<(typeof users.value)[number] | null>(null),
  selectedRoles = ref<string[]>([]),
  allRoles = ref<Role[]>([]),
  saving = ref(false),
  formError = ref("");
const usersLoading = ref(false),
  usersPage = ref(1),
  moreUsers = ref(false);
let usersKeyword = "", usersRequest = 0;
/** 任职版本来自 UserRoles，选择视图不需要暴露完整账号管理字段。 */
const authVersion = ref(0);
/** 公盘只允许一个有效角色，选择新角色时替换旧值，不生成后端必然拒绝的组合。 */
const selectedDriveRole = computed({
  get: () =>
    selectedRoles.value.find((id) =>
      allRoles.value.some((role) => role.id === id && role.module === "DRIVE"),
    ) ?? "",
  set: (id: string) => {
    selectedRoles.value = selectedRoles.value.filter(
      (value) =>
        !allRoles.value.some(
          (role) => role.id === value && role.module === "DRIVE",
        ),
    );
    if (id) selectedRoles.value.push(id);
  },
});
const availableModules = computed(() =>
  modules.filter(
    (module) =>
      can(`${module.code}.ROLES_MANAGE`) ||
      (module.code === "ARCHIVE" && can("ARCHIVE.INITIAL_ADMIN_ASSIGN")),
  ),
);
/** 权限定义及允许/拒绝配置分别存放，避免把现有拒绝误读成允许。 */
interface Permission {
  id: string;
  code: string;
  name: string;
}
interface RoleDetail {
  role: Role;
  permissions: Permission[];
  assignments: { permissionId: string; effect: "ALLOW" | "DENY" }[];
}
const permissionDialog = ref<HTMLDialogElement | null>(null),
  editingRole = ref<Role | null>(null),
  permissions = ref<Permission[]>([]),
  effects = ref<Record<string, string>>({}),
  permissionError = ref("");
const canEditRole = computed(
  () =>
    editingRole.value &&
    !(
      editingRole.value.module === "PLATFORM" &&
      editingRole.value.code === "IT_ADMIN"
    ) &&
    can(`${editingRole.value.module}.ROLES_MANAGE`) &&
    (systemAdministrator.value ||
      !profile.value?.roles.some((role) => role.id === editingRole.value?.id)),
);
/** 平台 IT 的固定管理权限单独展示。 */
async function inspectRole(role: Role) {
  permissionError.value = "";
  editingRole.value = role;
  effects.value = {};
  try {
    const detail = await api<RoleDetail>(`/admin/roles/${role.id}`);
    editingRole.value = detail.role;
    permissions.value =
      can(`${role.module}.ROLES_MANAGE`) &&
      !(role.module === "PLATFORM" && role.code === "IT_ADMIN")
        ? (
            await api<Page<Permission>>(
              `/admin/permissions?${query({ module: role.module, pageSize: 100 })}`,
            )
          ).items
        : detail.permissions;
    for (const assignment of detail.assignments)
      effects.value[assignment.permissionId] = assignment.effect;
    permissionDialog.value?.showModal();
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
/** 空配置表示不授予，明确拒绝保留 DENY 语义，不能通过勾选简化丢失安全含义。 */
async function savePermissions() {
  const role = editingRole.value;
  if (!role || !canEditRole.value) return;
  saving.value = true;
  permissionError.value = "";
  try {
    const password = await askPassword(
      `确认调整「${role.name}」的权限，受影响员工需要重新登录。`,
    );
    if (password === null) return;
    const body = {
      permissions: permissions.value
        .filter((permission) => effects.value[permission.id])
        .map((permission) => ({
          permissionId: permission.id,
          scopePolicy: { code: "COMPANY" },
          effect: effects.value[permission.id],
        })),
    };
    await mutate(
      `/admin/roles/${role.id}/permissions`,
      "PUT",
      body,
      role.id,
      "ROLE",
      "ROLE_PERMISSIONS",
      password,
      role.rowVersion,
    );
    permissionDialog.value?.close();
    inform("角色权限已更新");
    await load(selectedModule.value);
  } catch (failure) {
    permissionError.value = (failure as Error).message;
  } finally {
    saving.value = false;
  }
}
/** 分模块读取固定角色，不把三个业务域合成一个超级角色列表。 */
async function load(module: string) {
  selectedModule.value = module;
  loading.value = true;
  error.value = "";
  try {
    roles.value = (
      await api<Page<Role>>(`/admin/roles?${query({ module, pageSize: 100 })}`)
    ).items;
  } catch (failure) {
    roles.value = [];
    error.value = (failure as Error).message;
  } finally {
    loading.value = false;
  }
}
/** 打开分配框时从服务端读取当前可管理模块，首位档案管理员建立后该例外自动消失。 */
async function openAssignment() {
  formError.value = "";
  target.value = null;
  selectedUserId.value = "";
  usersPage.value = 1;
  usersKeyword = "";
  moreUsers.value = false;
  users.value = [];
  selectedRoles.value = [];
  allRoles.value = [];
  for (const module of availableModules.value) {
    try {
      allRoles.value.push(
        ...(
          await api<Page<Role>>(
            `/admin/roles?${query({ module: module.code, pageSize: 100 })}`,
          )
        ).items,
      );
    } catch {
      /* 被撤销的初始化例外不会进入选择列表。 */
    }
  }
  dialog.value?.showModal();
  await loadUsers();
}
/** 打开即加载人员下拉选项；继续使用最小选择视图和服务端分页。 */
async function loadUsers(keyword = "", append = false) {
  const request = ++usersRequest;
  usersKeyword = keyword.trim();
  usersLoading.value = true;
  formError.value = "";
  try {
    const full = can("PLATFORM.USERS_MANAGE");
    const purpose = full
      ? "ACCOUNT_ADMIN"
      : can("DRIVE.ROLES_MANAGE")
        ? "DRIVE_GRANT"
        : "ARCHIVE_GRANT";
    const result = await api<Page<{ selector: (typeof users.value)[number] }>>(
      `/admin/users?${query({ keyword: usersKeyword || undefined, page: append ? usersPage.value + 1 : 1, pageSize: 100, status: "ACTIVE", purpose, projection: "SELECTOR" })}`,
    );
    if (request !== usersRequest) return;
    const options = result.items
      .map((item) => item.selector)
      .filter(
        (user) =>
          systemAdministrator.value || user.id !== profile.value?.user.id,
      );
    users.value = [...(append ? users.value : []), ...options].sort(
      (a, b) =>
        a.name.localeCompare(b.name, "zh-CN") ||
        a.employeeNo.localeCompare(b.employeeNo),
    );
    usersPage.value = result.page;
    moreUsers.value = result.hasNext;
  } catch (failure) {
    if (request === usersRequest) formError.value = (failure as Error).message;
  } finally {
    if (request === usersRequest) usersLoading.value = false;
  }
}
/** 选择员工后读取真实任职和授权版本，不沿用上一次表单内容。 */
async function chooseUser() {
  const user = users.value.find((item) => item.id === selectedUserId.value);
  target.value = null;
  selectedRoles.value = [];
  formError.value = "";
  if (!user) return;
  try {
    const result = await api<{ roles: Role[]; authVersion: number }>(
      `/admin/users/${user.id}/roles`,
    );
    if (selectedUserId.value !== user.id) return;
    target.value = user;
    selectedRoles.value = result.roles.map((role) => role.id);
    authVersion.value = result.authVersion;
  } catch (failure) {
    if (selectedUserId.value !== user.id) return;
    formError.value = (failure as Error).message;
    target.value = null;
  }
}
/** 直接提交角色选择；任职差异及交接要求由后端核对。 */
async function save() {
  const user = target.value;
  if (!user) return;
  saving.value = true;
  formError.value = "";
  try {
    const password = await askPassword(
      `确认调整 ${user.name} 的模块角色，原登录将立即失效。`,
    );
    if (password === null) return;
    const body = {
      roles: selectedRoles.value.map((id) => {
        const role = allRoles.value.find((item) => item.id === id)!;
        return {
          roleId: id,
          ...(["DEPARTMENT_ARCHIVIST", "DEPARTMENT_MANAGER"].includes(role.code)
            ? { departmentId: user.departmentId }
            : {}),
        };
      }),
    };
    await mutate(
      `/admin/users/${user.id}/roles`,
      "PUT",
      body,
      user.id,
      "USER",
      "ASSIGN_ROLES",
      password,
      authVersion.value,
    );
    dialog.value?.close();
    inform("角色配置已保存");
    setProfile(await api<Profile>("/auth/me", {}, false));
    await load(
      availableModules.value.some(
        (module) => module.code === selectedModule.value,
      )
        ? selectedModule.value
        : (availableModules.value[0]?.code ?? "PLATFORM"),
    );
  } catch (failure) {
    formError.value = (failure as Error).message;
  } finally {
    saving.value = false;
  }
}
/** 从档案开通指引进入时定位档案域；参数不能打开本人无权管理的模块。 */
onMounted(() => {
  const requested = new URLSearchParams(location.hash.split("?")[1]).get(
    "module",
  );
  void load(
    availableModules.value.find((module) => module.code === requested)?.code ??
      availableModules.value[0]?.code ??
      "PLATFORM",
  );
});
</script>
<template>
  <section class="page-heading">
    <div>
      <span class="eyebrow">访问管理</span>
      <h1>角色与权限</h1>
      <p>由平台 IT 管理员配置平台、公盘和档案的角色与权限。</p>
    </div>
    <button class="primary" @click="openAssignment">分配人员角色</button>
  </section>
  <section class="panel management-panel">
    <div class="section-tabs">
      <button
        v-for="module in availableModules"
        :key="module.code"
        :class="{ active: selectedModule === module.code }"
        @click="load(module.code)"
      >
        {{ module.name }}角色
      </button>
    </div>
    <div class="inset">
      <p class="info-note">
        平台 IT 负责系统管理，公盘和档案业务按各自角色和授权办理。
      </p>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <p v-if="loading" class="muted" role="status">正在读取角色…</p>
      <div class="roles-grid">
        <article v-for="role in roles" :key="role.id" class="role-card">
          <span class="role-dot" :class="role.module.toLowerCase()" />
          <h2>{{ role.name }}</h2>
          <span class="chip">{{
            modules.find((module) => module.code === role.module)?.name
          }}</span>
          <p>权限版本 {{ role.permissionVersion }}</p>
          <small class="muted">{{ role.code }}</small
          ><button class="text-button role-inspect" @click="inspectRole(role)">
            查看权限 →
          </button>
        </article>
      </div>
    </div>
    <footer class="list-footer">
      <button
        type="button"
        class="secondary list-refresh"
        :disabled="loading"
        @click="load(selectedModule)"
      >
        刷新列表
      </button>
      <div class="list-pagination">
        <span class="list-total">显示 {{ roles.length }} 个角色</span>
      </div>
    </footer>
  </section>
  <dialog
    ref="dialog"
    aria-labelledby="role-assignment-title"
    class="form-dialog flow-dialog"
  >
    <form class="flow-dialog-shell" @submit.prevent="save">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="people" :size="22" /></span>
        <h2 id="role-assignment-title">分配人员角色</h2>
        <button
          class="icon-button"
          type="button"
          aria-label="关闭角色分配"
          @click="dialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section class="flow-dialog-section">
          <h3>选择员工</h3>
          <label class="flow-inline-field"
            >选择人员
            <ElSelect
              v-model="selectedUserId"
              aria-label="选择人员"
              filterable
              remote
              :teleported="false"
              :remote-method="loadUsers"
              :loading="usersLoading"
              :disabled="saving"
              placeholder="输入姓名或工号搜索"
              loading-text="正在查找员工…"
              no-data-text="没有匹配的在职人员"
              @change="chooseUser"
            >
              <ElOption
                v-for="user in users"
                :key="user.id"
                :value="user.id"
                :label="`${user.name}（${user.employeeNo}）`"
              />
            </ElSelect>
          </label>
          <button
            v-if="moreUsers"
            type="button"
            class="text-button"
            :disabled="usersLoading"
            @click="loadUsers(usersKeyword, true)"
          >
            加载更多人员
          </button>
          <p
            v-if="selectedUserId && !target && !formError"
            class="muted"
            role="status"
          >
            正在读取该员工的角色…
          </p>
          <div v-if="target" class="selected-person">
            正在配置：<strong>{{ target.name }}</strong
            ><span>{{ target.employeeNo }}</span>
          </div>
        </section>
        <section class="flow-dialog-section">
          <h3>角色配置</h3>
          <fieldset
            v-for="module in modules.filter((item) =>
              allRoles.some((role) => role.module === item.code),
            )"
            :key="module.code"
            class="role-options"
            :disabled="!target || saving"
          >
            <legend>
              {{ module.name }}角色{{
                module.code === "DRIVE" ? "（单选）" : ""
              }}
            </legend>
            <label v-if="module.code === 'DRIVE'" class="checkbox-label"
              ><input
                v-model="selectedDriveRole"
                type="radio"
                name="drive-role"
                value=""
              />未分配公盘角色</label
            >
            <label
              v-for="role in allRoles.filter(
                (item) => item.module === module.code,
              )"
              :key="role.id"
              class="checkbox-label"
              ><input
                v-if="module.code === 'DRIVE'"
                v-model="selectedDriveRole"
                type="radio"
                name="drive-role"
                :value="role.id"
              /><input
                v-else
                v-model="selectedRoles"
                type="checkbox"
                :value="role.id"
              />{{ role.name }}</label
            >
          </fieldset>
        </section>
        <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button type="button" class="secondary" @click="dialog?.close()">
          取消</button
        ><button class="primary" :disabled="saving || !target">保存角色</button>
      </footer>
    </form>
  </dialog>
  <dialog
    ref="permissionDialog"
    aria-labelledby="role-permission-title"
    class="form-dialog flow-dialog"
  >
    <form class="flow-dialog-shell" @submit.prevent="savePermissions">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="shield" :size="22" /></span>
        <h2 id="role-permission-title">{{ editingRole?.name }} · 权限配置</h2>
        <button
          class="icon-button"
          type="button"
          aria-label="关闭权限配置"
          @click="permissionDialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <p
          v-if="
            editingRole?.module === 'PLATFORM' &&
            editingRole?.code === 'IT_ADMIN'
          "
          class="info-note"
        >
          IT
          管理员固定拥有以下系统管理权限。授予或撤销此资格，请在“分配人员角色”中调整。
        </p>
        <p v-else-if="!canEditRole" class="info-note">
          当前角色仅供查看，本人正在使用的角色不能自行调整。
        </p>
        <section class="flow-dialog-section">
          <h3>权限清单</h3>
          <div class="permission-list">
            <label v-for="permission in permissions" :key="permission.id"
              ><span
                >{{ permission.name }}<small>{{ permission.code }}</small></span
              ><select
                v-model="effects[permission.id]"
                :disabled="!canEditRole"
                :aria-label="permission.name"
              >
                <option :value="undefined">未授予</option>
                <option value="ALLOW">允许</option>
                <option value="DENY">明确拒绝</option>
              </select></label
            >
          </div>
        </section>
        <p v-if="permissionError" class="form-error" role="alert">
          {{ permissionError }}
        </p>
      </div>
      <footer class="flow-dialog-footer">
        <button
          class="secondary"
          type="button"
          @click="permissionDialog?.close()"
        >
          关闭</button
        ><button v-if="canEditRole" class="primary" :disabled="saving">
          保存权限
        </button>
      </footer>
    </form>
  </dialog>
</template>
