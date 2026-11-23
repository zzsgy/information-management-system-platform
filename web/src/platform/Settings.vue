<script setup lang="ts">
import Icon from "../preview/PlatformIcon.vue";
import { computed, inject, onMounted, ref } from "vue";
import { api, can, COLLECTION, mutate, profile, query } from "./api";

/** 组织配置只投影管理所需字段，不读取档案题名或正文。 */
interface Department {
  /** 部门标识。 */ id: string;
  /** 业务名称。 */ name: string;
  /** 编码。 */ code: string;
  /** 并发版本。 */ rowVersion: number;
}
/** 当前合法人员及任职依据。 */
interface Duty {
  /** 任职历史标识。 */ id: string;
  /** 部门。 */ departmentId?: string;
  /** 任职员工。 */ userId: string;
  /** 独立职责。 */ dutyType?: string;
  /** 员工姓名。 */ name: string;
  /** 工号。 */ employeeNo: string;
  /** 当前生效。 */ validFrom: string;
  /** 变更原因。 */ changeReason?: string;
  /** 批准依据。 */ approvalReference?: string;
}
/** 两类配置权限独立，平台组织管理者不能借入口修改档案授权。 */
const organization = can("PLATFORM.DEPARTMENTS_MANAGE"),
  archive = can("ARCHIVE.CONFIG_MANAGE");
/** 档案登记字典只向平台 IT 管理员开放维护。 */
const fileTypeAdmin = !!profile.value?.roles.some(
  (role) => role.module === "PLATFORM" && role.code === "IT_ADMIN",
);
const tab = ref(organization ? "organization" : "archive"),
  error = ref(""),
  busy = ref(false);
const askPassword =
    inject<(title: string) => Promise<string | null>>("askPassword")!,
  inform = inject<(message: string) => void>("inform")!;
const fileTypes = ref<string[]>([]);
const fileTypeName = ref("");
const loadingFileTypes = ref(false);

/** 进入文件类型页时刷新选项，反映其他管理员已添加的名称。 */
async function openFileTypes() {
  tab.value = "fileTypes";
  error.value = "";
  loadingFileTypes.value = true;
  try {
    fileTypes.value = (
      await api<{ items: string[] }>("/admin/archive/file-types")
    ).items;
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    loadingFileTypes.value = false;
  }
}

/** 新增立即进入登记选项，审计和本人再验证由服务端处理。 */
async function addFileType() {
  const name = fileTypeName.value.trim();
  if (!name || busy.value) return;
  busy.value = true;
  error.value = "";
  try {
    const password = await askPassword("确认添加档案文件类型");
    if (password === null) return;
    const added = await mutate<{ name: string }>(
      "/admin/archive/file-types",
      "POST",
      { name },
      COLLECTION,
      "COLLECTION",
      "CREATE_ARCHIVE_FILE_TYPE",
      password,
    );
    fileTypes.value.push(added.name);
    fileTypeName.value = "";
    inform("文件类型已添加。");
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 仅保留当前列表和表单；口令交给全局一次性验证弹窗。 */
const departments = ref<Department[]>([]),
  duties = ref<Duty[]>([]),
  generalManager = ref<Duty | null>(null),
  departmentId = ref("");
const dutyDialog = ref<HTMLDialogElement | null>(null),
  dutyType = ref("DEPARTMENT_MANAGER"),
  person = ref(""),
  keyword = ref(""),
  reason = ref(""),
  approvalRef = ref("");
const candidates = ref<{ id: string; name: string; employeeNo: string }[]>([]);
const department = computed(() =>
  departments.value.find((d) => d.id === departmentId.value),
);
const currentDuties = computed(() =>
  duties.value.filter((d) => d.departmentId === departmentId.value),
);
const dutyNames: Record<string, string> = {
  DEPARTMENT_MANAGER: "部门负责人",
  SUPERVISING_LEADER: "分管领导",
  GENERAL_MANAGER: "公司总经理",
};

/** 读取当前合法组织职责，首次选中部门后保留员工正在查看的范围。 */
async function loadOrganization() {
  const data = await api<{
    departments: Department[];
    duties: Duty[];
    generalManager: Duty[];
  }>("/admin/organization-duties");
  departments.value = data.departments;
  duties.value = data.duties;
  generalManager.value = data.generalManager[0] || null;
  if (!departmentId.value) departmentId.value = data.departments[0]?.id || "";
}
/** 查询符合当前职责任职条件的候选，部门负责人限本部门。 */
async function findCandidates() {
  try {
    candidates.value = (
      await api<{ items: typeof candidates.value }>(
        `/admin/organization-duties/candidates?${query({ dutyType: dutyType.value, departmentId: departmentId.value, keyword: keyword.value })}`,
      )
    ).items;
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
/** 配置表单固定当前职责，提交不会为目标员工自动增加模块角色。 */
async function editDuty(type: string) {
  dutyType.value = type;
  keyword.value = "";
  reason.value = "";
  approvalRef.value = "";
  error.value = "";
  const old =
    type === "GENERAL_MANAGER"
      ? generalManager.value
      : currentDuties.value.find((d) => d.dutyType === type);
  person.value = old?.userId || "";
  await findCandidates();
  dutyDialog.value?.showModal();
}
/** 组织写入需本人再验证、批准依据和版本；失败保留表单供核对。 */
async function saveDuty() {
  const password = await askPassword(
    "确认变更" + dutyNames[dutyType.value] + "审批职责",
  );
  if (password === null) return;
  busy.value = true;
  error.value = "";
  try {
    if (dutyType.value === "GENERAL_MANAGER")
      await mutate(
        "/admin/company-duties/general-manager",
        "PUT",
        {
          userId: person.value,
          expectedAssignmentId: generalManager.value?.id || null,
          changeReason: reason.value,
          approvalRef: approvalRef.value,
        },
        COLLECTION,
        "COMPANY_DUTY",
        "GENERAL_MANAGER_DUTY",
        password,
      );
    else {
      if (!department.value) throw new Error("请选择部门");
      await mutate(
        `/admin/departments/${department.value.id}/management-duties`,
        "PUT",
        {
          duties: [
            {
              dutyType: dutyType.value,
              userId: person.value,
              validFrom: new Date().toISOString(),
            },
          ],
          changeReason: reason.value,
          approvalRef: approvalRef.value,
        },
        department.value.id,
        "DEPARTMENT",
        "MANAGEMENT_DUTIES",
        password,
        department.value.rowVersion,
      );
    }
    dutyDialog.value?.close();
    await loadOrganization();
    inform("职责已更新。旧任职待办需在流程中心按合法职责改派。");
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 只加载当前角色获准的管理区域，避免把隐藏按钮当成权限隔离。 */
onMounted(async () => {
  try {
    if (organization) await loadOrganization();
  } catch (failure) {
    error.value = (failure as Error).message;
  }
});
</script>

<template>
  <section class="settings-heading">
    <span class="eyebrow">管理配置</span>
    <h1>管理配置</h1>
    <p class="muted">维护组织审批职责与档案授权配置。</p>
  </section>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  <nav class="module-tabs" aria-label="配置分类">
    <button
      v-if="organization"
      :class="{ active: tab === 'organization' }"
      @click="tab = 'organization'"
    >
      组织审批职责</button
    ><button
      v-if="archive"
      :class="{ active: tab === 'archive' }"
      @click="tab = 'archive'"
    >
      档案授权配置
    </button>
    <button
      v-if="fileTypeAdmin"
      :class="{ active: tab === 'fileTypes' }"
      @click="openFileTypes"
    >
      档案文件类型
    </button>
  </nav>
  <section v-if="tab === 'organization' && organization" class="settings-grid">
    <article class="panel settings-card">
      <div class="settings-title">
        <div>
          <span class="eyebrow">公司职责</span>
          <h2>总经理审批职责</h2>
        </div>
        <button class="secondary" @click="editDuty('GENERAL_MANAGER')">
          配置总经理
        </button>
      </div>
      <h3>{{ generalManager?.name || "尚未配置" }}</h3>
      <p class="muted">
        {{ generalManager?.employeeNo || "请选择已有档案入口权限的有效员工" }}
      </p>
      <p v-if="generalManager?.approvalReference">
        批准依据：{{ generalManager.approvalReference }}
      </p>
      <p class="settings-note">
        公司总经理职责独立于公盘角色。任职变更结束旧任职，历史批准保持原记录。
      </p>
    </article>
    <article class="panel settings-card">
      <div class="settings-title">
        <div>
          <span class="eyebrow">部门职责</span>
          <h2>负责人及分管领导</h2>
        </div>
        <label class="list-filter-field"
          >所属部门<select v-model="departmentId" aria-label="选择职责部门">
            <option v-for="item in departments" :key="item.id" :value="item.id">
              {{ item.name }}
            </option>
          </select></label
        >
      </div>
      <div
        v-for="type in ['DEPARTMENT_MANAGER', 'SUPERVISING_LEADER']"
        :key="type"
        class="duty-row"
      >
        <div>
          <strong>{{ dutyNames[type] }}</strong>
          <p>
            {{
              currentDuties.find((d) => d.dutyType === type)?.name || "尚未配置"
            }}
          </p>
          <small class="muted">{{
            currentDuties.find((d) => d.dutyType === type)?.approvalReference ||
            "暂无批准依据记录"
          }}</small>
        </div>
        <button
          class="secondary"
          :disabled="!department"
          @click="editDuty(type)"
        >
          配置{{ dutyNames[type] }}
        </button>
      </div>
      <p class="settings-note">
        更换职责会终止受影响员工的旧登录。必要审批节点保留，可在流程中心交接给当前合法人员。
      </p>
    </article>
  </section>
  <section v-if="tab === 'archive' && archive" class="panel settings-card">
    <span class="eyebrow">独立档案授权</span>
    <h2>基础开放与人员批准</h2>
    <p class="muted">
      在档案中心选择具体已发布文件，明确安全题名受众、本部门一般文件查看范围与期限，再提交真实业务审批。
    </p>
    <div class="settings-links">
      <a class="primary" href="#/archive">前往档案中心</a
      ><a class="secondary" href="#/workflow">查看审批与撤权</a>
    </div>
    <p class="settings-note">
      维护目录或组织职责均不授予正文。批准固定文件修订和PDF版本，撤销时只处理选定来源。
    </p>
  </section>
  <section
    v-if="tab === 'fileTypes' && fileTypeAdmin"
    class="panel settings-card"
  >
    <div class="settings-title">
      <h2>档案文件类型</h2>
    </div>
    <form class="file-type-form" @submit.prevent="addFileType">
      <label for="file-type-name">类型名称</label>
      <input
        id="file-type-name"
        v-model="fileTypeName"
        required
        maxlength="100"
        :disabled="busy"
      />
      <button
        class="primary"
        :disabled="busy || loadingFileTypes || !fileTypeName.trim()"
      >
        添加文件类型
      </button>
    </form>
    <p v-if="loadingFileTypes" class="muted" role="status">正在读取文件类型…</p>
    <ul v-else class="file-type-list" aria-label="登记下拉选项">
      <li v-for="name in fileTypes" :key="name">{{ name }}</li>
    </ul>
  </section>
  <dialog
    ref="dutyDialog"
    aria-labelledby="duty-editor-title"
    class="form-dialog flow-dialog flow-dialog-compact"
  >
    <form class="flow-dialog-shell" @submit.prevent="saveDuty">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="people" :size="22" /></span>
        <h2 id="duty-editor-title">配置{{ dutyNames[dutyType] }}</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭职责配置"
          @click="dutyDialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section class="flow-dialog-overview">
          <h3>配置说明</h3>
          <p v-if="dutyType === 'DEPARTMENT_MANAGER'" class="muted">
            已配置档案入口权限的本部门在职员工可在首次登录前任职。职责立即生效并保留任职历史。
          </p>
          <p v-else class="muted">
            人员需先具备有效档案入口权限，职责立即生效并保留任职历史。
          </p>
        </section>
        <section class="flow-dialog-section">
          <h3>人员与任职记录</h3>
          <div class="candidate-search">
            <input
              v-model="keyword"
              aria-label="检索职责候选"
              placeholder="姓名或工号"
              maxlength="100"
            /><button type="button" class="secondary" @click="findCandidates">
              检索
            </button>
          </div>
          <label
            >审批职责人员<select v-model="person" required>
              <option value="">请选择当前合法人员</option>
              <option
                v-for="candidate in candidates"
                :key="candidate.id"
                :value="candidate.id"
              >
                {{ candidate.name }} · {{ candidate.employeeNo }}
              </option>
            </select></label
          ><label
            >职责变更原因<textarea
              v-model="reason"
              required
              maxlength="1000"
              rows="3"
            /></label
          ><label
            >职责批准依据<input v-model="approvalRef" required maxlength="256"
          /></label>
        </section>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button type="button" class="secondary" @click="dutyDialog?.close()">
          取消</button
        ><button class="primary" :disabled="busy || !person">
          保存职责配置
        </button>
      </footer>
    </form>
  </dialog>
</template>
<style scoped>
/* 电脑端采用宽屏分区与稳定表格，不在小屏降级隐藏必要配置字段。 */
.settings-heading {
  padding: 44px 0 28px;
}
.settings-heading h1 {
  font-size: 24px;
  margin: 6px 0;
}
.settings-grid {
  display: grid;
  grid-template-columns: 1fr 1.5fr;
  gap: 24px;
}
.settings-card {
  padding: 30px;
}
.settings-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
}
.settings-title h2 {
  margin: 8px 0 18px;
  font-size: 20px;
}
.settings-title select {
  max-width: 340px;
}
.settings-note {
  font-size: 13px;
  line-height: 1.9;
  background: var(--surface-muted);
  padding: 16px;
  color: var(--ink);
  border-radius: 8px;
  margin-top: 24px;
}
.duty-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 24px 0;
  border-top: 1px solid var(--line);
}
.duty-row p {
  margin: 10px 0;
}
.settings-links {
  display: flex;
  gap: 16px;
  margin-top: 24px;
}
.settings-links a {
  text-decoration: none;
}
.candidate-search {
  display: flex;
  gap: 12px;
  margin: 18px 0;
}
.candidate-search input {
  flex: 1;
}
.file-type-form {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 20px;
}
.file-type-form input {
  flex: 0 1 320px;
  min-width: 0;
}
.file-type-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0;
  margin: 0;
  list-style: none;
}
.file-type-list li {
  padding: 6px 12px;
  border-radius: 6px;
  background: var(--surface-muted);
  overflow-wrap: anywhere;
}
</style>
