<script setup lang="ts">
import { computed, inject, onMounted, ref, watch } from "vue";
import { api, can, mutate, query, type User } from "./api";
import EmptyState from "./EmptyState.vue";
/** 电脑端人员交接，档案职责与 IT 转岗操作分别按能力开放。 */
interface Person {
  /** 员工标识。 */ id: string;
  /** 姓名。 */ name: string;
  /** 工号。 */ employeeNo: string;
}
interface Department {
  /** 部门标识。 */ id: string;
  /** 名称。 */ name: string;
}
interface Preview {
  /** 一次性影响摘要。 */ previewDigest: string;
  /** 原任姓名。 */ fromName: string;
  /** 接任姓名。 */ toName: string;
  /** 未完审批节点。 */ steps: { id: string; state: string }[];
  /** 未完目录事项。 */ catalogChanges: { id: string; state: string }[];
  /** 部门在途实物借阅数量。 */ outstandingPhysicalLoans: number;
}
interface Transfer {
  /** 带并发版本的当前员工资料。 */ user: User;
  /** 仅数量，不含业务正文。 */ counts: Record<string, number>;
  /** 必须先处理的任职阻碍。 */ blockers: string[];
}
const askPassword =
    inject<(title: string) => Promise<string | null>>("askPassword")!,
  inform = inject<(message: string) => void>("inform")!;
/** 选人、预览和确认保持在一个工作面板，修改人员后清除过期预览。 */
const tab = ref(can("ARCHIVE.CONFIG_MANAGE") ? "handover" : "transfer"),
  departments = ref<Department[]>([]),
  people = ref<Person[]>([]),
  department = ref(""),
  from = ref(""),
  fromName = ref(""),
  to = ref(""),
  preview = ref<Preview | null>(null),
  approval = ref(""),
  reason = ref(""),
  confirmed = ref(false),
  error = ref(""),
  busy = ref(false);
/** 员工查询受限为前五十条，部门转岗采用独立版本和批准依据。 */
const keyword = ref(""),
  users = ref<User[]>([]),
  transfer = ref<Transfer | null>(null),
  targetDepartment = ref(""),
  transferApproval = ref(""),
  handoverNote = ref(""),
  transferConfirmed = ref(false);
const names: Record<string, string> = {
  roles: "原有角色任职",
  personalPermissions: "个人权限配置",
  archiveGrants: "档案个人授权",
  driveGrants: "公盘个人授权",
  pendingWorkflows: "将终止的本人在审事项",
  outstandingPhysicalLoans: "仍需本人归还的实物",
};
const targets = computed(() =>
  people.value.filter((person) => person.id !== from.value),
);
/** 选择部门后只取本部门候选，加载代次阻止旧部门结果覆盖新选择。 */
let generation = 0;
async function loadOptions() {
  const current = ++generation;
  preview.value = null;
  to.value = "";
  from.value = "";
  busy.value = true;
  error.value = "";
  try {
    const data = await api<{
      departments: Department[];
      people: Person[];
      current: { user_id: string; name: string } | null;
    }>(
      `/admin/archive/archivist-handover-options?${query({ departmentId: department.value })}`,
    );
    if (current !== generation) return;
    departments.value = data.departments;
    people.value = data.people;
    from.value = data.current?.user_id || "";
    fromName.value = data.current?.name || "尚未配置档案员";
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    if (current === generation) busy.value = false;
  }
}
/** 明确范围后计算固定清单，不把页面展示数量当成服务器权威状态。 */
async function inspectHandover() {
  busy.value = true;
  error.value = "";
  try {
    preview.value = await api<Preview>(
      "/admin/archive/archivist-handovers/preview",
      {
        method: "POST",
        body: JSON.stringify({
          departmentId: department.value,
          fromUserId: from.value,
          toUserId: to.value,
        }),
      },
    );
    confirmed.value = false;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 提交固定摘要及批准依据；后端遇到事项变化整体拒绝，要求重新核对。 */
async function commitHandover() {
  if (
    !preview.value ||
    !confirmed.value ||
    !approval.value.trim() ||
    !reason.value.trim()
  )
    return;
  const password = await askPassword("确认档案员任职替换与未完事项交接");
  if (!password) return;
  busy.value = true;
  error.value = "";
  try {
    await mutate(
      "/admin/archive/archivist-handovers",
      "POST",
      {
        departmentId: department.value,
        fromUserId: from.value,
        toUserId: to.value,
        previewDigest: preview.value.previewDigest,
        approvalReference: approval.value,
        reason: reason.value,
      },
      department.value,
      "DEPARTMENT",
      "HANDOVER_ARCHIVIST",
      password,
    );
    preview.value = null;
    inform("档案员任职与待办已完成交接，双方需重新登录");
    await loadOptions();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
/** IT 员工选择只取有界管理列表，所有实物和授权仅显示数量。 */
async function search() {
  busy.value = true;
  error.value = "";
  try {
    const [result, options] = await Promise.all([
      api<{ items: { user: User }[] }>(
        `/admin/users?${query({ keyword: keyword.value, pageSize: 50, page: 1, status: "ACTIVE" })}`,
      ),
      api<{ items: Department[] }>("/admin/personnel-department-options"),
    ]);
    users.value = result.items.map((row) => row.user);
    departments.value = options.items;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 每次选人读取最新并发版本，清除上一人的确认状态。 */
async function selectUser(user: User) {
  busy.value = true;
  error.value = "";
  transfer.value = null;
  try {
    transfer.value = await api<Transfer>(
      `/admin/users/${user.id}/department-change-preview`,
    );
    targetDepartment.value = "";
    transferConfirmed.value = false;
    transferApproval.value = "";
    handoverNote.value = "";
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 按批准转岗，保留历史及实物归还责任；新岗位权限通过原角色配置另行办理。 */
async function commitTransfer() {
  const item = transfer.value;
  if (
    !item ||
    item.blockers.length ||
    !targetDepartment.value ||
    !transferConfirmed.value ||
    !transferApproval.value.trim() ||
    !handoverNote.value.trim()
  )
    return;
  const password = await askPassword("确认员工转岗并回收旧个人授权");
  if (!password) return;
  busy.value = true;
  error.value = "";
  try {
    await mutate(
      `/admin/users/${item.user.id}/department-changes`,
      "POST",
      {
        targetDepartmentId: targetDepartment.value,
        approvalRef: transferApproval.value,
        handoverNote: handoverNote.value,
      },
      item.user.id,
      "USER",
      "CHANGE_DEPARTMENT",
      password,
      item.user.rowVersion,
    );
    transfer.value = null;
    inform("转岗已完成，旧授权和会话已失效；请按批准依据配置新岗位权限");
    await search();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
watch(to, () => {
  preview.value = null;
  confirmed.value = false;
});
watch(tab, () => {
  error.value = "";
  if (tab.value === "handover") void loadOptions();
  else void search();
});
onMounted(() => {
  if (tab.value === "handover") void loadOptions();
  else void search();
});
</script>
<template>
  <section class="personnel">
    <header>
      <span class="eyebrow">组织连续性</span>
      <h1>{{ tab === "handover" ? "档案员交接" : "员工转岗" }}</h1>
      <p class="muted">
        先核对未完事项与责任，再办理职责变更。所有历史记录持续保留。
      </p>
    </header>
    <nav class="module-tabs" aria-label="职责变更">
      <button
        v-if="can('ARCHIVE.CONFIG_MANAGE')"
        :class="{ active: tab === 'handover' }"
        :disabled="busy"
        @click="tab = 'handover'"
      >
        档案员交接</button
      ><button
        v-if="can('PLATFORM.USERS_MANAGE')"
        :class="{ active: tab === 'transfer' }"
        :disabled="busy"
        @click="tab = 'transfer'"
      >
        员工转岗
      </button>
    </nav>
    <div v-if="tab === 'handover'" class="handover-layout">
      <section class="panel form-pane">
        <h2>01 · 选择交接双方</h2>
        <label
          >所属部门<select
            v-model="department"
            :disabled="busy"
            @change="loadOptions"
          >
            <option value="">请选择部门</option>
            <option v-for="item in departments" :key="item.id" :value="item.id">
              {{ item.name }}
            </option>
          </select></label
        ><label>原档案员<input :value="fromName" readonly /></label
        ><label
          >接任档案员<select v-model="to" :disabled="busy || !from">
            <option value="">请选择接任人</option>
            <option
              v-for="person in targets"
              :key="person.id"
              :value="person.id"
            >
              {{ person.name }} · {{ person.employeeNo }}
            </option>
          </select></label
        ><button
          class="primary"
          :disabled="busy || !from || !to"
          @click="inspectHandover"
        >
          预览交接影响
        </button>
        <p class="muted">
          接任人需在本部门有效在职，不能承接对本人申请的审批。
        </p>
      </section>
      <section class="panel form-pane">
        <h2>02 · 核对并确认</h2>
        <template v-if="preview"
          ><p>
            <strong>{{ preview.fromName }}</strong> →
            <strong>{{ preview.toName }}</strong>
          </p>
          <div class="impact-grid">
            <div>
              <strong>{{ preview.steps.length }}</strong
              ><span>待办及后续节点</span>
            </div>
            <div>
              <strong>{{ preview.catalogChanges.length }}</strong
              ><span>未完目录事项</span>
            </div>
            <div>
              <strong>{{ preview.outstandingPhysicalLoans }}</strong
              ><span>部门在途实物</span>
            </div>
          </div>
          <p class="muted">
            已完成审批保留原人；实物借还记录不改写；个人借阅授权不转给接任人。
          </p>
          <label
            >批准依据<input
              v-model="approval"
              maxlength="256"
              placeholder="批准单号或可追溯引用" /></label
          ><label
            >交接说明<textarea
              v-model="reason"
              rows="3"
              maxlength="1000"
              placeholder="档案资料、未完事项及在途实物核对情况"
            /></label
          ><label class="confirm"
            ><input
              v-model="confirmed"
              type="checkbox"
            />已核对交接范围，确认一次完成任职替换和待办移交</label
          ><button
            class="primary"
            :disabled="busy || !confirmed || !approval.trim() || !reason.trim()"
            @click="commitHandover"
          >
            确认交接
          </button></template
        >
        <div v-else class="empty-pane">
          选择交接双方后，这里将显示待办、目录事项与实物责任清单。
        </div>
      </section>
    </div>
    <div v-else class="handover-layout transfer-layout">
      <section class="panel form-pane employee-pane">
        <h2>查找员工</h2>
        <form class="search-person" @submit.prevent="search">
          <label class="list-filter-field"
            >姓名或工号<input
              v-model.trim="keyword"
              type="search"
              placeholder="输入姓名或工号"
              maxlength="100" /></label
          ><button class="primary" :disabled="busy">查询</button>
        </form>
        <p class="employee-list-caption muted" aria-live="polite">
          显示 {{ users.length }} 位员工<span v-if="users.length === 50">，请用姓名或工号缩小范围</span>
        </p>
        <div class="people-list">
          <button
            v-for="user in users"
            :key="user.id"
            :disabled="busy"
            :class="{ selected: transfer?.user.id === user.id }"
            :aria-pressed="transfer?.user.id === user.id"
            @click="selectUser(user)"
          >
            <strong>{{ user.name }}</strong
            ><small>{{ user.employeeNo }}</small>
          </button>
        </div>
        <EmptyState v-if="!users.length && !busy" icon="search" title="未找到员工" />
      </section>
      <section class="panel form-pane">
        <h2>核对转岗影响</h2>
        <template v-if="transfer"
          ><h3>{{ transfer.user.name }}</h3>
          <div class="impact-list">
            <div v-for="(count, code) in transfer.counts" :key="code">
              <span>{{ names[code] || code }}</span
              ><strong>{{ count }}</strong>
            </div>
          </div>
          <p
            v-for="blocker in transfer.blockers"
            :key="blocker"
            class="form-error"
          >
            {{ blocker }}
          </p>
          <label
            >新部门<select v-model="targetDepartment" :disabled="busy">
              <option value="">请选择批准的新部门</option>
              <option
                v-for="item in departments.filter(
                  (value) => value.id !== transfer?.user.departmentId,
                )"
                :key="item.id"
                :value="item.id"
              >
                {{ item.name }}
              </option>
            </select></label
          ><label
            >批准依据<input v-model="transferApproval" maxlength="256" /></label
          ><label
            >资料与实物责任交接说明<textarea
              v-model="handoverNote"
              rows="3"
              maxlength="2000"
            /></label
          ><label class="confirm"
            ><input
              v-model="transferConfirmed"
              type="checkbox"
            />确认回收旧个人权限和会话，未归还的实物仍由本人负责</label
          >
          <p class="muted">
            转岗完成后，按批准依据重新配置新岗位角色。已下载到电脑的文件不会因此自动收回。
          </p>
          <button
            class="primary"
            :disabled="
              busy ||
              !!transfer.blockers.length ||
              !transferConfirmed ||
              !targetDepartment ||
              !transferApproval.trim() ||
              !handoverNote.trim()
            "
            @click="commitTransfer"
          >
            确认转岗并回收旧授权
          </button></template
        >
        <div v-else class="empty-pane">
          选择员工，核对原有权限、在审事项与实物归还责任。
        </div>
      </section>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  </section>
</template>
<style scoped>
/* 电脑端双栏保持选择与影响核对并列，长工号换行而不撑宽页面。 */
.personnel header {
  margin-bottom: 28px;
}
.personnel h1 {
  margin: 7px 0 12px;
}
.handover-layout {
  display: grid;
  grid-template-columns: minmax(260px, 0.85fr) minmax(0, 1.3fr);
  gap: 24px;
  align-items: start;
}
.form-pane {
  min-width: 0;
  padding: clamp(16px, 2vw, 26px);
  display: grid;
  gap: 18px;
}
.form-pane h2 {
  font-size: 18px;
  margin: 0 0 6px;
}
.form-pane label {
  display: grid;
  gap: 8px;
}
.form-pane .primary {
  justify-self: start;
}
.form-pane .confirm {
  display: flex;
  flex-direction: row;
  gap: 9px;
  align-items: flex-start;
}
.confirm input {
  width: 16px;
  height: 16px;
  min-width: 16px;
  padding: 0;
  margin-top: 4px;
}
.impact-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 96px), 1fr));
  gap: 12px;
}
.impact-grid > div {
  padding: 18px 12px;
  background: var(--surface-muted);
  border-radius: 9px;
}
.impact-grid strong {
  display: block;
  font-size: 26px;
  color: var(--ink);
}
.impact-grid span {
  display: block;
  font-size: 12px;
  color: var(--ink);
  margin-top: 8px;
}
.impact-list > div {
  display: flex;
  justify-content: space-between;
  padding: 10px 0;
  border-bottom: 1px solid var(--line);
}
.empty-pane {
  min-height: 280px;
  display: grid;
  place-content: center;
  color: var(--text-secondary);
  padding: 25px;
  text-align: center;
}
.transfer-layout {
  grid-template-columns: minmax(270px, 360px) minmax(0, 1fr);
}
.employee-pane {
  gap: 14px;
}
.employee-list-caption {
  margin: 4px 0 0;
  font-size: 13px;
}
.search-person {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 10px;
}
.search-person :is(input, button) {
  min-height: 40px;
}
.employee-pane .people-list {
  gap: 0;
  border: 1px solid var(--line);
  border-radius: 8px;
}
.employee-pane .people-list:empty {
  display: none;
}
.employee-pane .people-list button {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  border: 0;
  border-radius: 0;
}
.employee-pane .people-list button + button {
  border-top: 1px solid var(--line);
}
.employee-pane .people-list button:is(:hover, .selected) {
  background: var(--brand-soft);
}
.employee-pane .people-list small {
  margin: 0;
}
@media (max-width: 1050px) {
  .handover-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
.search-person input {
  min-width: 0;
}
.people-list {
  max-height: 520px;
  overflow: auto;
  display: grid;
  gap: 7px;
}
.people-list button {
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface);
  padding: 13px;
  text-align: left;
  cursor: pointer;
}
.people-list button.selected {
  background: var(--surface-muted);
  border-color: var(--brand);
}
.people-list strong,
.people-list small {
  display: block;
  overflow-wrap: anywhere;
}
.people-list small {
  margin-top: 5px;
  color: var(--text-secondary);
}
</style>
