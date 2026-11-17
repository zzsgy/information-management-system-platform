<script setup lang="ts">
import {
  computed,
  defineAsyncComponent,
  inject,
  nextTick,
  onMounted,
  onUnmounted,
  ref,
  watch,
} from "vue";
import Icon from "../preview/PlatformIcon.vue";
import { useCompanyEmail } from "./companyEmail";
import {
  api,
  COLLECTION,
  mutate,
  profile,
  query,
  type Department,
  type Page,
  type User,
} from "./api";
const Imports = defineAsyncComponent(() => import("./Imports.vue"));
/** 复用全局验证和提示；取消验证直接中止提交。 */
const askPassword =
  inject<(title: string) => Promise<string | null>>("askPassword")!;
const inform = inject<(message: string) => void>("inform")!;
/** 名片字段仅在人员管理返回。 */
interface DirectoryUser extends User {
  organizationName: string | null;
  jobTitle: string | null;
  supervisorId: string | null;
  mobilePhone: string | null;
  contactEmail: string | null;
}
/** 列表状态按当前页维护，任何查询过期后都不能覆盖较新的结果。 */
const tab = ref<"users" | "departments">("users"),
  keyword = ref(""),
  /** 按当前人员或部门标签页提交的补充条件。 */
  departmentFilter = ref(""),
  parentFilter = ref(""),
  emailFilter = ref(""),
  status = ref(""),
  page = ref(1),
  total = ref(0),
  hasNext = ref(false),
  loading = ref(false),
  error = ref("");
const companyCode = ref("");
/** 关闭导入弹窗时清理本次选中的文件和预检页面。 */
const importDialog = ref<HTMLDialogElement | null>(null),
  importOpen = ref(false);
const users = ref<DirectoryUser[]>([]),
  departments = ref<Department[]>([]),
  departmentOptions = ref<Department[]>([]);
let queryVersion = 0;
/** 编辑只复制当前记录；保存之前不修改表格中的服务端结果。 */
const dialog = ref<HTMLDialogElement | null>(null),
  editUser = ref<DirectoryUser | null>(null),
  editDepartment = ref<Department | null>(null),
  saving = ref(false),
  formError = ref("");
const employeeNo = ref(""),
  name = ref(""),
  departmentId = ref(""),
  code = ref(""),
  parentId = ref(""),
  sortOrder = ref(0);
const organizationName = ref(""),
  jobTitle = ref(""),
  mobilePhone = ref(""),
  contactEmail = ref(""),
  supervisorId = ref(""),
  supervisorKeyword = ref(""),
  supervisorOptions = ref<User[]>([]),
  selectedSupervisor = ref<User | null>(null),
  supervisorSearching = ref(false);
let supervisorSearchVersion = 0;

/** 按姓名或工号查询在职主管候选，避免把整份员工名单放入下拉框。 */
async function searchSupervisors() {
  const version = ++supervisorSearchVersion;
  supervisorSearching.value = true;
  formError.value = "";
  try {
    const result = await api<Page<{ user: User }>>(
      `/admin/users?${query({ keyword: supervisorKeyword.value, status: "ACTIVE", pageSize: 20 })}`,
    );
    if (version === supervisorSearchVersion) {
      supervisorOptions.value = result.items.map(({ user }) => user)
        .filter((person) => person.id !== editUser.value?.id);
    }
  } catch (failure) {
    if (version === supervisorSearchVersion) formError.value = (failure as Error).message;
  } finally {
    if (version === supervisorSearchVersion) supervisorSearching.value = false;
  }
}
const supervisorChoices = computed(() => {
  const choices = supervisorOptions.value;
  const selected = selectedSupervisor.value;
  return selected && !choices.some((person) => person.id === selected.id)
    ? [selected, ...choices]
    : choices;
});
const {
  email,
  edited: emailEdited,
  suggestion: suggestedEmail,
  fill: fillCompanyEmail,
  edit: editCompanyEmail,
  reset: resetCompanyEmail,
} = useCompanyEmail(
  name,
  computed(() => tab.value === "users" && !editUser.value),
);
/** 回车提交先补齐空邮箱，再走浏览器原有必填和格式校验。 */
async function submitWithCompanyEmail(event: KeyboardEvent) {
  fillCompanyEmail();
  await nextTick();
  (event.target as HTMLInputElement).form?.requestSubmit();
}
/** 工号和部门仅对有效的全系统 IT 角色开放；服务端仍独立核验。 */
const canEditIdentity = computed(
  () =>
    profile.value?.roles.some(
      (role) => role.module === "PLATFORM" && role.code === "IT_ADMIN",
    ) ?? false,
);
const departmentChanged = computed(
  () => !!editUser.value && departmentId.value !== editUser.value.departmentId,
);
const changeReason = ref("");
const statusDialog = ref<HTMLDialogElement | null>(null),
  statusUser = ref<User | null>(null),
  reason = ref("");
/** 成功创建或重置后，IT 在当前页面领取工号初始密码；不提供个人密码查询。 */
const credentialDialog = ref<HTMLDialogElement | null>(null),
  issuedEmployeeNo = ref(""),
  issuedName = ref(""),
  copyNotice = ref("");
async function showInitialPassword(userId: string) {
  if (!canEditIdentity.value) return;
  const user = await api<User>(`/admin/users/${userId}`);
  if (!user.passwordSetupRequired || user.status !== "ACTIVE") return;
  issuedEmployeeNo.value = user.employeeNo;
  issuedName.value = user.name;
  copyNotice.value = "";
  credentialDialog.value?.showModal();
}
function clearInitialPassword() {
  credentialDialog.value?.close();
  issuedEmployeeNo.value = issuedName.value = copyNotice.value = "";
}
async function copyInitialPassword() {
  try {
    await navigator.clipboard.writeText(issuedEmployeeNo.value);
    copyNotice.value = "初始密码已复制";
  } catch {
    copyNotice.value = "复制未完成，请选中上方初始密码后复制";
  }
}
onUnmounted(clearInitialPassword);
/** 重置保留再验证、原因和版本检查，成功后展示初始密码领取框。 */
const resetDialog = ref<HTMLDialogElement | null>(null),
  resetUser = ref<User | null>(null),
  resetReason = ref("");
async function resetPassword() {
  if (!resetUser.value) return;
  saving.value = true;
  formError.value = "";
  try {
    const password = await askPassword(
      "确认重置该员工密码，原登录将立即失效。请输入本人密码。",
    );
    if (password === null) return;
    const user = resetUser.value;
    await mutate(
      `/admin/users/${user.id}/password-reset`,
      "POST",
      { reason: resetReason.value },
      user.id,
      "USER",
      "RESET_PASSWORD",
      password,
      user.rowVersion,
    );
    resetDialog.value?.close();
    inform("密码已重置为工号，员工登录后须修改密码");
    await load();
    await showInitialPassword(user.id);
  } catch (failure) {
    formError.value = (failure as Error).message;
  } finally {
    saving.value = false;
  }
}
const departmentNames = computed(
  () =>
    new Map(
      departmentOptions.value.map((department) => [
        department.id,
        department.name,
      ]),
    ),
);
/** 逐页读取组织选项，超过一页仍能正确选择；单次请求保持 100 条上限。 */
async function loadDepartments() {
  const rows: Department[] = [];
  let next = true,
    current = 1;
  while (next) {
    const result = await api<
      Page<{ department: Department }> & { companyCode: string }
    >(`/admin/departments?${query({ page: current, pageSize: 100 })}`);
    companyCode.value = result.companyCode;
    rows.push(...result.items.map((row) => row.department));
    next = result.hasNext;
    current++;
  }
  departmentOptions.value = rows;
}
/** 只在提交搜索或翻页时请求，避免每次按键触发数据库查询。 */
async function load() {
  const version = ++queryVersion;
  loading.value = true;
  error.value = "";
  try {
    const result = await api<Page<{ user?: DirectoryUser; department?: Department }>>(
      `/admin/${tab.value}?${query({ page: page.value, pageSize: 20, keyword: keyword.value, status: status.value, departmentId: tab.value === "users" ? departmentFilter.value : undefined, parentId: tab.value === "departments" ? parentFilter.value : undefined, email: tab.value === "users" ? emailFilter.value : undefined })}`,
    );
    if (version !== queryVersion) return;
    users.value = result.items.flatMap((row) => (row.user ? [row.user] : []));
    departments.value = result.items.flatMap((row) =>
      row.department ? [row.department] : [],
    );
    total.value = result.total;
    hasNext.value = result.hasNext;
  } catch (failure) {
    if (version === queryVersion) error.value = (failure as Error).message;
  } finally {
    if (version === queryVersion) loading.value = false;
  }
}
/** 人员编辑展示真实可修改字段；部门调整须说明原因，不在表单中隐式新增角色。 */
function openForm(user?: DirectoryUser, department?: Department) {
  editUser.value = user ?? null;
  changeReason.value = "";
  editDepartment.value = department ?? null;
  formError.value = "";
  employeeNo.value = user?.employeeNo ?? "";
  name.value = user?.name ?? department?.name ?? "";
  resetCompanyEmail(user?.companyEmail ?? "");
  organizationName.value = user?.organizationName ?? "";
  jobTitle.value = user?.jobTitle ?? "";
  mobilePhone.value = user?.mobilePhone ?? "";
  contactEmail.value = user?.contactEmail ?? "";
  supervisorId.value = user?.supervisorId ?? "";
  supervisorKeyword.value = "";
  supervisorOptions.value = [];
  selectedSupervisor.value = null;
  supervisorSearching.value = false;
  const searchVersion = ++supervisorSearchVersion;
  if (user?.supervisorId) {
    void api<User>(`/admin/users/${user.supervisorId}`)
      .then((person) => {
        if (editUser.value?.id === user.id) selectedSupervisor.value = person;
      }).catch((failure: Error) => {
        if (searchVersion === supervisorSearchVersion) formError.value = failure.message;
      });
  }
  departmentId.value = user?.departmentId ?? "";
  code.value = department?.code ?? "";
  parentId.value = department?.parentId ?? "";
  sortOrder.value = department?.sortOrder ?? 0;
  dialog.value?.showModal();
}
/** 保存前再次验证，版本和操作号由统一 API 客户端发送。 */
async function save() {
  saving.value = true;
  formError.value = "";
  let createdUserId = "";
  try {
    const password = await askPassword(
      `确认${editUser.value || editDepartment.value ? "修改" : "新增"}${tab.value === "users" ? "人员" : "部门"}，请输入本人密码。`,
    );
    if (password === null) return;
    if (tab.value === "users") {
      const user = editUser.value;
      const body = user
        ? {
            name: name.value,
            companyEmail: email.value,
            ...(canEditIdentity.value
              ? {
                  employeeNo: employeeNo.value,
                  departmentId: departmentId.value,
                }
              : {}),
            ...(departmentChanged.value
              ? { changeReason: changeReason.value }
              : {}),
          }
        : {
            employeeNo: employeeNo.value,
            name: name.value,
            companyEmail: email.value,
            departmentId: departmentId.value,
          };
      const details = {
        ...(user ? { organizationName: organizationName.value || null } : {}),
        jobTitle: jobTitle.value || null,
        supervisorId: supervisorId.value || null,
        mobilePhone: mobilePhone.value || null,
        contactEmail: contactEmail.value || null,
      };
      const result = await mutate<User>(
        `/admin/users${user ? `/${user.id}` : ""}`,
        user ? "PATCH" : "POST",
        { ...body, ...details },
        user?.id ?? COLLECTION,
        user ? "USER" : "COLLECTION",
        user ? "UPDATE_USER" : "CREATE_USER",
        password,
        user?.rowVersion,
      );
      if (!user) createdUserId = result.id;
    } else {
      const department = editDepartment.value;
      await mutate(
        `/admin/departments${department ? `/${department.id}` : ""}`,
        department ? "PATCH" : "POST",
        {
          name: name.value,
          code: code.value,
          parentId: parentId.value || null,
          sortOrder: sortOrder.value,
        },
        department?.id ?? COLLECTION,
        department ? "DEPARTMENT" : "COLLECTION",
        department ? "UPDATE_DEPARTMENT" : "CREATE_DEPARTMENT",
        password,
        department?.rowVersion,
      );
      await loadDepartments();
    }
    dialog.value?.close();
    inform("已保存");
    await load();
    if (createdUserId) await showInitialPassword(createdUserId);
  } catch (failure) {
    formError.value = (failure as Error).message;
  } finally {
    saving.value = false;
  }
}
/** 账号启停是明确独立动作，说明回收后果并要求原因。 */
async function changeStatus() {
  const user = statusUser.value;
  if (!user) return;
  saving.value = true;
  formError.value = "";
  try {
    const password = await askPassword(
      `确认${user.status === "ACTIVE" ? "停用" : "启用"} ${user.name} 的账号。`,
    );
    if (password === null) return;
    await mutate(
      `/admin/users/${user.id}/status`,
      "PUT",
      {
        status: user.status === "ACTIVE" ? "DISABLED" : "ACTIVE",
        reason: reason.value,
      },
      user.id,
      "USER",
      "USER_STATUS",
      password,
      user.rowVersion,
    );
    statusDialog.value?.close();
    inform("账号状态已更新");
    await load();
  } catch (failure) {
    formError.value = (failure as Error).message;
  } finally {
    saving.value = false;
  }
}
/** 搜索和切页统一刷新，筛选变化回到第一页。 */
function search() {
  page.value = 1;
  void load();
}
function flip(delta: number) {
  page.value += delta;
  void load();
}
watch(tab, () => {
  keyword.value = "";
  status.value = "";
  departmentFilter.value = "";
  parentFilter.value = "";
  emailFilter.value = "";
  search();
});
onMounted(async () => {
  try {
    await loadDepartments();
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  }
});
</script>
<template>
  <section class="page-heading">
    <div>
      <span class="eyebrow">组织管理</span>
      <h1>组织与人员</h1>
      <p>维护员工账号与部门信息 · 公司编号 {{ companyCode }}</p>
    </div>
    <div class="people-heading-actions">
      <button
        v-if="tab === 'users'"
        type="button"
        class="secondary"
        @click="importOpen = true; importDialog?.showModal()"
      >
        批量导入人员
      </button>
      <button class="primary" @click="openForm()">
        <Icon name="plus" :size="17" />{{
          tab === "users" ? "新增人员" : "新增部门"
        }}
      </button>
    </div>
  </section>
  <section class="panel management-panel">
    <div class="section-tabs" role="tablist">
      <button
        role="tab"
        :aria-selected="tab === 'users'"
        :class="{ active: tab === 'users' }"
        @click="tab = 'users'"
      >
        人员管理</button
      ><button
        role="tab"
        :aria-selected="tab === 'departments'"
        :class="{ active: tab === 'departments' }"
        @click="tab = 'departments'"
      >
        部门管理
      </button>
    </div>
    <form class="toolbar" @submit.prevent="search">
      <label class="list-filter-field"
        ><span>{{ tab === "users" ? "姓名或工号" : "部门名称或编码" }}</span
        ><input
          v-model="keyword"
          :aria-label="
            tab === 'users' ? '搜索姓名或工号' : '搜索部门名称或编码'
          "
          :placeholder="
            tab === 'users' ? '搜索姓名或工号' : '搜索部门名称或编码'
          "
          maxlength="200" /></label
      ><label class="list-filter-field"
        >{{ tab === "users" ? "账号状态" : "部门状态"
        }}<select
          v-model="status"
          :aria-label="tab === 'users' ? '筛选账号状态' : '筛选部门状态'"
          @change="search"
        >
          <option value="">全部状态</option>
          <option value="ACTIVE">正常</option>
          <option value="DISABLED">已停用</option>
        </select></label
      ><label v-if="tab === 'users'" class="list-filter-field"
        >所属部门
        <select v-model="departmentFilter">
          <option value="">全部部门</option>
          <option v-for="d in departmentOptions" :key="d.id" :value="d.id">
            {{ d.name }}
          </option>
        </select>
      </label>
      <label v-else class="list-filter-field"
        >上级部门
        <select v-model="parentFilter">
          <option value="">全部上级部门</option>
          <option v-for="d in departmentOptions" :key="d.id" :value="d.id">
            {{ d.name }}
          </option>
        </select>
      </label>
      <label v-if="tab === 'users'" class="list-filter-field"
        >公司邮箱<input v-model="emailFilter" type="search" maxlength="200"
      /></label>
      <button class="secondary" :disabled="loading">搜索</button>
    </form>
    <p v-if="error" class="form-error inset" role="alert">{{ error }}</p>
    <div v-if="loading" class="loading-line" role="status">正在读取…</div>
    <div class="table-scroll">
      <table v-if="tab === 'users'" class="directory-table">
        <thead>
          <tr>
            <th>姓名 / 工号</th>
            <th>所属部门</th>
            <th>公司邮箱</th>
            <th>账号状态</th>
            <th class="right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in users" :key="user.id">
            <td>
              <strong>{{ user.name }}</strong
              ><small>{{ user.employeeNo }}</small>
            </td>
            <td data-label="所属部门">
              {{ departmentNames.get(user.departmentId) || "—" }}
            </td>
            <td data-label="公司邮箱">{{ user.companyEmail }}</td>
            <td>
              <span
                class="status-badge"
                :class="{ inactive: user.status === 'DISABLED' }"
                >{{
                  user.status === "DISABLED"
                    ? "已停用"
                    : user.passwordSetupRequired
                      ? "待设密"
                      : "正常"
                }}</span
              >
            </td>
            <td class="right actions">
              <button
                v-if="user.id !== profile?.user.id && user.status === 'ACTIVE'"
                class="text-button"
                @click="
                  resetUser = user;
                  resetReason = '';
                  formError = '';
                  resetDialog?.showModal();
                "
              >
                重置密码
              </button>
              <button class="text-button" @click="openForm(user)">编辑</button
              ><button
                v-if="user.id !== profile?.user.id"
                class="text-button"
                :class="{ danger: user.status === 'ACTIVE' }"
                @click="
                  statusUser = user;
                  reason = '';
                  formError = '';
                  statusDialog?.showModal();
                "
              >
                {{ user.status === "ACTIVE" ? "停用" : "启用" }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <table v-else class="directory-table">
        <thead>
          <tr>
            <th>部门名称</th>
            <th>部门编码</th>
            <th>上级部门</th>
            <th>状态</th>
            <th class="right">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="department in departments" :key="department.id">
            <td>
              <strong>{{ department.name }}</strong>
            </td>
            <td data-label="部门编码">{{ department.code }}</td>
            <td data-label="上级部门">
              {{
                department.parentId
                  ? departmentNames.get(department.parentId)
                  : "顶级部门"
              }}
            </td>
            <td>
              <span
                class="status-badge"
                :class="{ inactive: department.status === 'DISABLED' }"
                >{{ department.status === "ACTIVE" ? "正常" : "已停用" }}</span
              >
            </td>
            <td class="right">
              <button
                class="text-button"
                @click="openForm(undefined, department)"
              >
                编辑
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div
      v-if="!loading && !users.length && !departments.length"
      class="empty-state"
    >
      <Icon name="search" :size="28" />
      <h3>没有找到匹配记录</h3>
      <p>试试调整搜索条件。</p>
    </div>
    <footer class="list-footer">
      <button
        type="button"
        class="secondary list-refresh"
        :disabled="loading"
        @click="load()"
      >
        刷新列表
      </button>
      <div class="list-pagination">
        <span>第 {{ page }} 页 · 每页 20 条</span>
        <div>
          <button
            class="secondary"
            :disabled="page <= 1 || loading"
            @click="flip(-1)"
          >
            上一页</button
          ><button
            class="secondary"
            :disabled="!hasNext || loading"
            @click="flip(1)"
          >
            下一页
          </button>
        </div>
        <span class="list-total"
          >共 {{ total }} {{ tab === "users" ? "位人员" : "个部门" }}</span
        >
      </div>
    </footer>
  </section>
  <dialog
    ref="importDialog"
    aria-labelledby="people-import-title"
    class="form-dialog flow-dialog people-import"
    @close="importOpen = false"
  >
    <div class="flow-dialog-shell">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="upload" :size="22" /></span>
        <h2 id="people-import-title">批量导入人员</h2>
        <button type="button" class="icon-button" aria-label="关闭批量导入" @click="importDialog?.close()">×</button>
      </header>
      <div class="flow-dialog-body">
        <Imports v-if="importOpen" employee-only @changed="load()" />
      </div>
      <footer class="flow-dialog-footer">
        <button type="button" class="secondary" @click="importDialog?.close()">关闭</button>
      </footer>
    </div>
  </dialog>
  <dialog
    ref="dialog"
    aria-labelledby="people-editor-title"
    class="form-dialog flow-dialog flow-dialog-compact people-editor"
  >
    <form class="flow-dialog-shell" @submit.prevent="save">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="people" :size="22" /></span>
        <h2 id="people-editor-title">
          {{ editUser || editDepartment ? "编辑" : "新增"
          }}{{ tab === "users" ? "人员" : "部门" }}
        </h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭编辑"
          @click="dialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <template v-if="tab === 'users'"
          ><section class="flow-dialog-section">
            <h3>人员信息</h3>
            <label class="flow-inline-field"
              >公司工号<input
                v-model="employeeNo"
                maxlength="64"
                :disabled="!!editUser && !canEditIdentity"
                required /></label
            ><label class="flow-inline-field"
              >姓名<input
                v-model="name"
                maxlength="128"
                required
                @blur="fillCompanyEmail()" /></label
            ><label class="flow-inline-field"
              >所属部门<select
                v-model="departmentId"
                :disabled="!!editUser && !canEditIdentity"
                required
              >
                <option value="" disabled>请选择部门</option>
                <option
                  v-for="department in departmentOptions.filter(
                    (item) => item.status === 'ACTIVE',
                  )"
                  :key="department.id"
                  :value="department.id"
                >
                  {{ department.name }}
                </option>
              </select></label
            >
          </section>
          <section class="flow-dialog-section">
            <h3>组织与职位</h3>
            <label v-if="editUser" class="flow-inline-field">
              企业／组织<input v-model.trim="organizationName" maxlength="200" placeholder="选填" />
            </label>
            <label class="flow-inline-field">
              职位<input v-model.trim="jobTitle" maxlength="128" :required="!editUser" />
            </label>
            <div class="supervisor-field">
              <label>
                查找主管
                <span class="inline-search">
                  <input v-model.trim="supervisorKeyword" type="search" maxlength="100"
                    placeholder="主管姓名或工号" @keydown.enter.prevent="searchSupervisors" />
                  <button type="button" class="secondary" :disabled="supervisorSearching"
                    @click="searchSupervisors">查找</button>
                </span>
              </label>
              <label>
                直属主管
                <select v-model="supervisorId" :required="!editUser"
                  @change="selectedSupervisor = supervisorChoices.find(person => person.id === supervisorId) ?? null">
                  <option value="">{{ editUser ? "未指定" : "请选择直属主管" }}</option>
                  <option v-if="supervisorId && !supervisorChoices.some(person => person.id === supervisorId)" :value="supervisorId">
                    已指定主管
                  </option>
                  <option v-for="person in supervisorChoices" :key="person.id" :value="person.id">
                    {{ person.name }} · {{ person.employeeNo }}
                  </option>
                </select>
              </label>
            </div>
          </section>
          <section class="flow-dialog-section">
            <h3>联系方式</h3>
            <label class="flow-inline-field">
              手机号<input v-model.trim="mobilePhone" type="tel" maxlength="32" :required="!editUser" placeholder="可包含国际区号" />
            </label>
            <label class="flow-inline-field">
              联系邮箱<input v-model.trim="contactEmail" type="email" maxlength="254" placeholder="选填，可填写个人邮箱" />
            </label>
            <label
              >公司邮箱
              <div class="inline-search company-email-field">
                <input
                  :value="email"
                  @input="
                    editCompanyEmail(($event.target as HTMLInputElement).value)
                  "
                  @focus="fillCompanyEmail()"
                  @blur="fillCompanyEmail()"
                  @keydown.enter.prevent="submitWithCompanyEmail"
                  aria-label="公司邮箱"
                  type="email"
                  maxlength="254"
                  placeholder="名拼音.姓拼音@biolink.com"
                  required
                /><button
                  v-if="!editUser"
                  type="button"
                  class="secondary"
                  :disabled="!suggestedEmail"
                  @click="fillCompanyEmail(true)"
                >
                  按姓名生成
                </button>
              </div>
              <small v-if="!editUser" class="field-note">{{
                emailEdited
                  ? "已保留手动填写的邮箱；清空后可恢复自动生成。"
                  : "按姓名自动填充，可直接修改。"
              }}</small></label
            >
          </section>
          <section class="flow-dialog-section" v-if="departmentChanged">
            <h3>部门变更</h3>
            <p class="field-note">
              变更部门会回收原部门授权，公司级角色保留；档案员或审批负责人须先完成职责交接。
            </p>
            <label
              >部门变更原因<textarea
                v-model="changeReason"
                maxlength="2000"
                placeholder="说明部门调整或资料更正原因"
                required
              />
            </label>
          </section>
          <p
            v-if="editUser && employeeNo !== editUser.employeeNo"
            class="field-note"
          >
            保存后请使用新工号重新登录，密码不变。
          </p>
          <p v-if="!editUser" class="field-note">
            初始密码与工号一致，24小时内首次登录须改密。新密码至少8位，并同时包含英文字母和数字。
          </p></template
        >
        <section v-else class="flow-dialog-section">
          <h3>部门信息</h3>
          <label class="flow-inline-field"
            >部门名称<input v-model="name" maxlength="128" required /></label
          ><label class="flow-inline-field"
            >部门编码<input v-model="code" maxlength="64" required /></label
          ><label class="flow-inline-field"
            >上级部门<select v-model="parentId">
              <option value="">无（顶级部门）</option>
              <option
                v-for="department in departmentOptions.filter(
                  (item) =>
                    item.id !== editDepartment?.id && item.status === 'ACTIVE',
                )"
                :key="department.id"
                :value="department.id"
              >
                {{ department.name }}
              </option>
            </select></label
          ><label class="flow-inline-field"
            >排序<input
              v-model.number="sortOrder"
              type="number"
              min="0"
              max="100000"
              required
          /></label>
        </section>
        <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button type="button" class="secondary" @click="dialog?.close()">
          取消</button
        ><button class="primary" :disabled="saving">
          {{ saving ? "正在保存…" : "保存" }}
        </button>
      </footer>
    </form>
  </dialog>
  <dialog
    ref="statusDialog"
    aria-labelledby="people-status-title"
    class="form-dialog flow-dialog flow-dialog-compact"
  >
    <form class="flow-dialog-shell" @submit.prevent="changeStatus">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="lock" :size="22" /></span>
        <h2 id="people-status-title">
          {{ statusUser?.status === "ACTIVE" ? "停用账号" : "启用账号" }}
        </h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭状态修改"
          @click="statusDialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section class="flow-dialog-overview">
          <h3>状态变更说明</h3>
          <p class="muted">
            {{
              statusUser?.status === "ACTIVE"
                ? "停用后将结束当前登录并回收角色。重新启用时需要重新分配角色。"
                : "启用后保留历史记录，角色需要重新核准分配。"
            }}
          </p>
        </section>
        <section class="flow-dialog-section">
          <h3>变更记录</h3>
          <label
            >操作原因<textarea
              v-model="reason"
              required
              maxlength="1000"
              rows="3"
            />
          </label>
        </section>
        <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button type="button" class="secondary" @click="statusDialog?.close()">
          取消</button
        ><button class="primary" :disabled="saving">
          确认{{ statusUser?.status === "ACTIVE" ? "停用" : "启用" }}
        </button>
      </footer>
    </form>
  </dialog>
  <dialog
    ref="resetDialog"
    aria-labelledby="people-reset-title"
    class="form-dialog flow-dialog flow-dialog-compact"
  >
    <form class="flow-dialog-shell" @submit.prevent="resetPassword">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="lock" :size="22" /></span>
        <h2 id="people-reset-title">重置员工密码</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭密码重置"
          @click="resetDialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section class="flow-dialog-overview">
          <h3>重置对象</h3>
          <p>{{ resetUser?.name }} · {{ resetUser?.employeeNo }}</p>
          <p class="muted">
            原登录和旧凭据将失效。密码重置为当前工号，24小时内登录须修改密码；IT
            可在成功后直接查看和复制初始密码。
          </p>
        </section>
        <section class="flow-dialog-section">
          <h3>重置记录</h3>
          <label
            >重置原因<textarea
              v-model="resetReason"
              maxlength="1000"
              required
            />
          </label>
        </section>
        <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button class="secondary" type="button" @click="resetDialog?.close()">
          取消</button
        ><button class="primary" :disabled="saving">确认重置</button>
      </footer>
    </form>
  </dialog>
  <dialog
    ref="credentialDialog"
    aria-labelledby="people-credential-title"
    class="form-dialog flow-dialog flow-dialog-compact"
    @cancel.prevent="clearInitialPassword"
    @close="issuedEmployeeNo = issuedName = copyNotice = ''"
  >
    <div class="flow-dialog-shell">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="lock" :size="22" /></span>
        <h2 id="people-credential-title">领取初始密码</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭初始密码"
          @click="clearInitialPassword"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section class="flow-dialog-section">
          <h3>员工凭据</h3>
          <p>{{ issuedName }} · {{ issuedEmployeeNo }}</p>
          <label
            >初始密码<input
              :value="issuedEmployeeNo"
              readonly
              aria-label="员工初始密码"
          /></label>
          <p class="muted">
            初始密码与工号一致，请交给该员工。24小时内登录后必须修改；新密码至少8位，同时包含英文字母和数字，且不能继续使用工号。
          </p>
        </section>
        <p v-if="copyNotice" role="status">{{ copyNotice }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button type="button" class="secondary" @click="clearInitialPassword">
          关闭</button
        ><button type="button" class="primary" @click="copyInitialPassword">
          复制初始密码
        </button>
      </footer>
    </div>
  </dialog>
</template>

<style scoped>
.people-import {
  width: min(780px, calc(100vw - 32px));
}

.people-heading-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.people-editor .flow-dialog-body .flow-inline-field {
  grid-template-columns: 5em minmax(0, 1fr);
}
.supervisor-field {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(210px, 100%), 1fr));
  gap: 12px;
}
.supervisor-field .inline-search input,
.supervisor-field select {
  min-width: 0;
}
.company-email-field {
  align-items: center;
}
.company-email-field input {
  min-width: 0;
}
.company-email-field button {
  flex-shrink: 0;
  white-space: nowrap;
}
</style>
