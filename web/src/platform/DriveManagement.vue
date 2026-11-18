<script setup lang="ts">
import { computed, inject, onMounted, ref } from "vue";
import { api, mutate, COLLECTION, query } from "./api";
import Icon from "../preview/PlatformIcon.vue";
interface Folder {
  id: string;
  name: string;
  zone: string;
  departmentId: string;
  parentId: string | null;
  rowVersion: number;
}
interface Delegation {
  id: string;
  allowedActions: string[];
  version: number;
  folderName?: string;
  departmentName?: string;
}
interface Grant {
  id: string;
  subjectName: string;
  action: string;
  effect: string;
  version: number;
}
interface Options {
  itAdmin: boolean;
  delegations: Delegation[];
  people: { id: string; name: string; employeeNo: string }[];
  departments: { id: string; name: string }[];
  managerRoleId: string;
}
const props = defineProps<{
    folder?: Folder;
    documentId?: string;
    /** 文件授权时用于标明当前资源。 */
    documentName?: string;
    documentFolderId?: string;
  }>(),
  emit = defineEmits<{
    close: [];
    changed: [folder?: { id: string; name: string; rowVersion: number } | null];
    rootCreated: [folder: Folder];
    openFolder: [folder: Folder];
  }>(),
  ask = inject<(title: string) => Promise<string | null>>("askPassword")!;
const options = ref<Options>(),
  createdFolder = ref<Folder>(),
  grants = ref<Grant[]>([]),
  delegations = ref<Delegation[]>([]),
  error = ref(""),
  notice = ref(""),
  busy = ref(false),
  keyword = ref(""),
  name = ref(props.folder?.name || ""),
  newFolderName = ref(""),
  folderReason = ref(""),
  delegationReason = ref(""),
  department = ref(""),
  zone = ref("DEPARTMENT_SHARED"),
  person = ref(""),
  grantAction = ref("UPLOAD"),
  effect = ref("ALLOW"),
  reason = ref(""),
  delegation = ref(""),
  selectedActions = ref(["VIEW", "DOWNLOAD", "UPLOAD"]);
const resourceFolder = computed(
  () => props.folder?.id || props.documentFolderId,
);
/** 授权表单与底部提交按钮使用同一份委托范围。 */
const canGrant = computed(
  () => !!resourceFolder.value && !!options.value?.delegations.length,
);
const labels: Record<string, string> = {
  VIEW: "阅读",
  DOWNLOAD: "下载",
  UPLOAD: "上传",
  EDIT: "编辑",
  DELETE: "删除本人文件",
  MOVE: "移动本人文件",
  RESTORE: "恢复本人文件",
  PURGE: "永久清理",
  FOLDER_MANAGE: "子目录管理",
  COLLABORATE: "协作管理",
};
const allowed = computed(
  () =>
    options.value?.delegations
      .find((d) => d.id === delegation.value)
      ?.allowedActions.filter((a) =>
        (props.documentId
          ? ["VIEW", "DOWNLOAD", "EDIT", "DELETE", "MOVE", "RESTORE"]
          : ["VIEW", "DOWNLOAD", "UPLOAD", "DELETE", "MOVE", "RESTORE"]
        ).includes(a),
      ) || [],
);
async function load() {
  try {
    options.value = await api<Options>(
      `/drive/access-options?${query({ folderId: resourceFolder.value, keyword: keyword.value })}`,
    );
    delegation.value = options.value.delegations[0]?.id || "";
    if (!allowed.value.includes(grantAction.value))
      grantAction.value = allowed.value[0] || "";
    if (resourceFolder.value)
      grants.value = (
        await api<{ items: Grant[] }>(
          `/drive/resource-grants?${query({ resourceId: props.documentId || resourceFolder.value, resourceType: props.documentId ? "DOCUMENT" : "FOLDER" })}`,
        )
      ).items;
    if (options.value.itAdmin)
      delegations.value = (
        await api<{ items: Delegation[] }>("/admin/drive/delegations")
      ).items;
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
async function run(
  path: string,
  method: string,
  body: unknown,
  id: string,
  type: string,
  action: string,
  version?: number,
) {
  busy.value = true;
  error.value = "";
  try {
    const password = await ask("确认公盘管理配置");
    if (!password) return;
    const result = await mutate<Folder>(path, method, body, id, type, action, password, version);
    if (action === "CREATE_FOLDER" && !props.folder) {
      createdFolder.value = result;
      newFolderName.value = "";
      notice.value = "";
      emit("rootCreated", result);
      return;
    }
    notice.value = "配置已保存。";
    if (action === "RECYCLE_FOLDER") {
      emit("close");
      emit("changed", null);
      return;
    }
    emit("changed", action === "RENAME_FOLDER" ? result : undefined);
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
function createFolder() {
  const folder = props.folder;
  return run(
    "/drive/folders",
    "POST",
    {
      parentId: folder?.id,
      zone: folder?.zone || zone.value,
      departmentId: folder?.departmentId || department.value,
      name: newFolderName.value,
    },
    COLLECTION,
    "DRIVE_COLLECTION",
    "CREATE_FOLDER",
  );
}
function grant() {
  if (!resourceFolder.value) return;
  return run(
    "/drive/resource-grants",
    "POST",
    {
      subjectId: person.value,
      resourceType: props.documentId ? "DOCUMENT" : "FOLDER",
      resourceId: props.documentId || resourceFolder.value,
      action: grantAction.value,
      effect: effect.value,
      delegationId: delegation.value,
      reason: reason.value,
    },
    resourceFolder.value,
    "FOLDER",
    "CREATE_GRANT",
  );
}
onMounted(load);
</script>
<template>
  <div class="manage-shade" @click.self="!busy && emit('close')">
    <section
      class="manage-sheet panel flow-dialog-shell"
      :class="{ 'file-grant-dialog': documentId }"
      role="dialog"
      aria-modal="true"
      :aria-label="documentId ? '人员授权' : '公盘管理配置'"
    >
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"
          ><Icon :name="documentId ? 'people' : 'folder'" :size="22"
        /></span>
        <div class="flow-dialog-title manage-title">
          <span class="eyebrow">公盘管理</span>
          <h2>
            {{ documentId ? "人员授权" : folder?.name || "分区与委托配置" }}
          </h2>
          <p class="muted">
            {{
              documentId
                ? "按具体人员配置文件的可用动作。"
                : folder
                  ? "维护当前目录、子目录及人员授权。"
                  : "建立资料目录，配置部门负责人的管理范围。"
            }}
          </p>
        </div>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭公盘管理配置"
          :disabled="busy"
          @click="emit('close')"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body manage-body">
        <section v-if="documentName" class="flow-dialog-overview">
          <h3>授权对象</h3>
          <p>{{ documentName }}</p>
        </section>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <p v-if="notice" role="status">{{ notice }}</p>
        <template v-if="options">
          <form
            v-if="
              !documentId &&
              (folder
                ? options.delegations.some((d) =>
                    d.allowedActions.includes('FOLDER_MANAGE'),
                  )
                : options.itAdmin)
            "
            @submit.prevent="createFolder"
          >
            <h3>
              <Icon name="folder" :size="18" />{{
                folder ? "新建子目录" : "新建部门顶层目录"
              }}
            </h3>
            <p class="muted card-description">
              {{
                folder
                  ? "子目录沿用当前目录的分区与所属部门。"
                  : "创建后可在公盘「全部可见」空间的「浏览目录」中找到。"
              }}
            </p>
            <div class="field-row create-fields">
              <label
                >目录名称<input
                  v-model="newFolderName"
                  placeholder="例如：项目资料"
                  maxlength="128"
                  required /></label
              ><template v-if="!folder"
                ><label
                  >所属部门<select v-model="department" required>
                    <option value="">选择部门</option>
                    <option
                      v-for="item in options.departments"
                      :key="item.id"
                      :value="item.id"
                    >
                      {{ item.name }}
                    </option>
                  </select></label
                ><label
                  >分区<select v-model="zone">
                    <option value="COMPANY_SHARED">公司共享</option>
                    <option value="DEPARTMENT_SHARED">部门共享</option>
                    <option value="DEPARTMENT_INTERNAL">部门内部</option>
                  </select></label
                ></template
              >
            </div>
            <div class="form-actions">
              <template v-if="createdFolder">
                <span class="created-folder" role="status">已创建「{{ createdFolder.name }}」</span>
                <button
                  type="button"
                  class="secondary"
                  :disabled="busy"
                  @click="emit('openFolder', createdFolder)"
                >
                  查看目录
                </button>
              </template>
              <button class="primary" :disabled="busy">
                <Icon name="plus" :size="16" />创建目录
              </button>
            </div>
          </form>
          <form
            v-if="
              folder &&
              !documentId &&
              (folder.parentId
                ? options.delegations.some((d) =>
                    d.allowedActions.includes('FOLDER_MANAGE'),
                  )
                : options.itAdmin)
            "
            @submit.prevent="
              run(
                `/drive/folders/${folder.id}`,
                'PATCH',
                { name, reason: folderReason },
                folder.id,
                'FOLDER',
                'RENAME_FOLDER',
                folder.rowVersion,
              )
            "
          >
            <h3>目录维护</h3>
            <div class="field-row">
              <label
                >新名称<input v-model="name" required maxlength="128" /></label
              ><label
                >操作原因<input
                  v-model="folderReason"
                  required
                  maxlength="1000"
              /></label>
            </div>
            <div class="form-actions">
              <button class="secondary" :disabled="busy">重命名目录</button
              ><button
                type="button"
                class="secondary"
                :disabled="busy || !folderReason"
                @click="
                  run(
                    `/drive/folders/${folder.id}`,
                    'DELETE',
                    { reason: folderReason },
                    folder.id,
                    'FOLDER',
                    'RECYCLE_FOLDER',
                    folder.rowVersion,
                  )
                "
              >
                回收空目录
              </button>
            </div>
            <p class="muted">
              包含文件、历史版本或在途上传的目录不能直接回收。
            </p>
          </form>
          <form
            v-if="
              options.itAdmin &&
              folder &&
              !folder.parentId &&
              folder.departmentId &&
              !documentId
            "
            @submit.prevent="
              run(
                '/admin/drive/delegations',
                'POST',
                {
                  departmentId: folder.departmentId,
                  folderId: folder.id,
                  roleId: options.managerRoleId,
                  allowedActions: selectedActions,
                  reason: delegationReason,
                },
                COLLECTION,
                'DRIVE_COLLECTION',
                'CREATE_DELEGATION',
              )
            "
          >
            <h3><Icon name="people" :size="18" />委托当前部门负责人</h3>
            <p class="muted">在任公盘负责人默认可向本部门目录上传。这里配置其他管理和员工授权范围。</p>
            <div class="choices">
              <label v-for="(label, key) in labels" :key="key"
                ><input
                  v-model="selectedActions"
                  type="checkbox"
                  :value="key"
                />{{ label }}</label
              >
            </div>
            <label
              >委托原因<input
                v-model="delegationReason"
                required
                maxlength="1000"
            /></label>
            <div class="form-actions">
              <button
                class="primary"
                :disabled="busy || !selectedActions.length"
              >
                建立委托
              </button>
            </div>
          </form>
          <form
            v-if="canGrant"
            :id="documentId ? 'drive-person-grant' : undefined"
            class="manage-grant"
            @submit.prevent="grant"
          >
            <section class="flow-dialog-section">
              <h3>选择员工</h3>
              <div class="field-row">
                <label
                  >查找员工<input
                    v-model="keyword"
                    placeholder="姓名或工号"
                    maxlength="100" /></label
                ><button type="button" class="secondary" @click="load">
                  查询</button
                ><label
                  >具体人员<select v-model="person" required>
                    <option value="">选择员工</option>
                    <option
                      v-for="item in options.people"
                      :key="item.id"
                      :value="item.id"
                    >
                      {{ item.name }} · {{ item.employeeNo }}
                    </option>
                  </select></label
                >
              </div>
            </section>
            <section class="flow-dialog-section">
              <h3>授权内容</h3>
              <p class="muted">
                上传不代表编辑；删除、移动和恢复仅适用于该员工本人上传的文件。
              </p>
              <div class="field-row">
                <label
                  >动作<select v-model="grantAction" required>
                    <option v-for="item in allowed" :key="item" :value="item">
                      {{ labels[item] }}
                    </option>
                  </select></label
                ><label
                  >效果<select v-model="effect">
                    <option value="ALLOW">允许</option>
                    <option value="DENY">明确拒绝</option>
                  </select></label
                ><label
                  >原因<input v-model="reason" maxlength="1000" required
                /></label>
              </div>
            </section>
            <div v-if="!documentId" class="form-actions">
              <button class="primary" :disabled="busy">保存授权</button>
            </div>
          </form>
          <section v-if="grants.length" class="manage-card flow-dialog-section">
            <h3>当前人员授权</h3>
            <table>
              <thead>
                <tr>
                  <th>人员</th>
                  <th>动作</th>
                  <th>效果</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in grants" :key="item.id">
                  <td>{{ item.subjectName }}</td>
                  <td>{{ labels[item.action] }}</td>
                  <td>{{ item.effect === "ALLOW" ? "允许" : "明确拒绝" }}</td>
                  <td>
                    <button
                      class="text-button"
                      :disabled="busy"
                      @click="
                        run(
                          `/drive/resource-grants/${item.id}`,
                          'DELETE',
                          {},
                          item.id,
                          'DRIVE_GRANT',
                          'REVOKE_GRANT',
                          item.version,
                        )
                      "
                    >
                      撤销
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </section>
          <div v-if="options.itAdmin" class="manage-card">
            <div class="card-heading">
              <h3><Icon name="shield" :size="18" />有效委托</h3>
              <span class="muted">{{ delegations.length }} 项</span>
            </div>
            <table>
              <thead>
                <tr>
                  <th>部门 / 目录</th>
                  <th>动作</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-if="!delegations.length">
                  <td colspan="3" class="manage-empty">
                    <Icon name="people" :size="24" /><span>暂无有效委托</span
                    ><small>进入具体目录后，可配置部门负责人的管理范围。</small>
                  </td>
                </tr>
                <tr v-for="item in delegations" :key="item.id">
                  <td>{{ item.departmentName }} / {{ item.folderName }}</td>
                  <td>
                    {{ item.allowedActions.map((a) => labels[a]).join("、") }}
                  </td>
                  <td>
                    <button
                      class="text-button"
                      :disabled="busy"
                      @click="
                        run(
                          `/admin/drive/delegations/${item.id}`,
                          'DELETE',
                          {},
                          item.id,
                          'DRIVE_DELEGATION',
                          'REVOKE_DELEGATION',
                          item.version,
                        )
                      "
                    >
                      撤销委托
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </div>
      <footer class="flow-dialog-footer">
        <button
          type="button"
          class="secondary"
          :disabled="busy"
          @click="emit('close')"
        >
          {{ documentId && canGrant ? "取消" : "关闭" }}
        </button>
        <button
          v-if="documentId && canGrant"
          form="drive-person-grant"
          type="submit"
          class="primary"
          :disabled="busy"
        >
          保存授权
        </button>
      </footer>
    </section>
  </div>
</template>
<style scoped>
.manage-shade {
  position: fixed;
  inset: 0;
  z-index: 80;
  background: #172b3b60;
  backdrop-filter: blur(3px);
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 16px;
}
.manage-sheet {
  width: min(1080px, 100%);
  max-height: calc(100dvh - 32px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 0;
  box-sizing: border-box;
  border-radius: 16px;
  box-shadow: 0 20px 80px #152d4533;
}
.manage-title .eyebrow {
  margin-bottom: 8px;
}
.manage-title p {
  margin: 7px 0 0;
  font-size: 12px;
}
.manage-body {
  grid-auto-rows: max-content;
}
.manage-body > form,
.manage-card {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 20px 22px;
  margin: 0;
  background: white;
  overflow: hidden;
}
.file-grant-dialog {
  width: min(720px, 100%);
  border-radius: 12px;
}
.file-grant-dialog .manage-body > .manage-grant,
.file-grant-dialog .manage-card {
  border: 0;
  padding: 0;
}
.manage-grant {
  display: grid;
  gap: 16px;
}
.manage-grant .field-row {
  margin: 0;
}
.manage-body h3 {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 15px;
  margin: 0 0 16px;
}
.file-grant-dialog .flow-dialog-section h3 {
  margin: 0;
}
.file-grant-dialog .flow-dialog-overview p {
  overflow-wrap: anywhere;
}
.manage-body h3 .p-icon {
  color: var(--brand);
}
.manage-body .muted {
  font-size: 12px;
}
.card-description {
  margin: -7px 0 16px;
}
.card-heading {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: baseline;
}
.manage-sheet label {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 0;
  min-width: 0;
  font-size: 13px;
}
.manage-sheet form > label {
  margin: 16px 0;
}
.field-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px 24px;
  align-items: end;
  margin: 16px 0;
}
.create-fields {
  grid-template-columns: 1.2fr 1fr 0.9fr;
}
.field-row:has(> button) {
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 22px;
  margin: 18px -22px -20px;
  border-top: 1px solid var(--line);
  background: #fafcfd;
}
.created-folder {
  align-self: center;
  margin-right: auto;
  min-width: 0;
  overflow-wrap: anywhere;
}
.choices {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px 20px;
  padding: 8px 0;
}
.choices label {
  display: flex;
  flex-direction: row;
  align-items: center;
  font-size: 13px;
}
.manage-sheet table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}
.manage-sheet th {
  background: var(--surface-muted);
  color: var(--text-secondary);
  font-weight: 500;
}
.manage-sheet td:last-child {
  white-space: nowrap;
}
.manage-sheet th:last-child {
  width: 88px;
}
.manage-sheet .manage-empty {
  text-align: center;
  padding: 28px 12px;
  color: var(--muted);
}
.manage-empty > * {
  display: block;
  margin: 7px auto;
}
@media (max-width: 940px) {
  .create-fields {
    grid-template-columns: 1fr 1fr;
  }
  .choices {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 640px) {
  .field-row,
  .create-fields,
  .field-row:has(> button) {
    grid-template-columns: minmax(0, 1fr);
  }
  .choices {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .manage-sheet th,
  .manage-sheet td {
    padding: 12px 5px;
    font-size: 12px;
  }
}
.manage-sheet th,
.manage-sheet td {
  padding: 14px 8px;
  border-bottom: 1px solid var(--line);
  text-align: left;
  font-size: 13px;
  overflow-wrap: anywhere;
  white-space: normal;
}
</style>
