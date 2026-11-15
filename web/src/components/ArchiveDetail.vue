<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { api, can, fileBlob, saveBlob } from "../lib/api";
import { confirmAction, useAction } from "../lib/ui";
import { display, options, type Row, type Field } from "../lib/model";
import DataTable from "./DataTable.vue";
import FormDialog from "./FormDialog.vue";
/** 待查看的档案主键；设为 null 关闭抽屉。 */
const id = defineModel<number | null>({ default: null });
/** 由父级触发编辑或刷新列表。 */
const emit = defineEmits<{ changed: []; edit: [row: Row] }>();
/** 当前详情、附件版本、变更日志和共享清单。 */
const detail = ref<Row>({});
const files = ref<Row[]>([]);
const changes = ref<Row[]>([]);
const grants = ref<Row[]>([]);
/** 抽屉页签与只在显式打开共享时读取的主体目录。 */
const tab = ref("info");
const users = ref<Row[]>([]);
const departments = ref<Row[]>([]);
/** 附件、共享及加载操作的防重复提交锁。 */
const { busy, run } = useAction();
/** 共享表单的主体类型由独立选择器决定，切换会重新打开干净表单。 */
const grantOpen = ref(false);
const grantType = ref("user");
/** 附件预览仅允许服务端确认的被动媒体类型。 */
const previewOpen = ref(false);
const previewUrl = ref("");
const previewType = ref("");
const previewText = ref("");
/** 共享权限中文名称与服务端动作一一对应。 */
const grantPermissions: Record<string, string> = {
  "archive.read": "查看档案",
  "archive.write": "修改档案",
  "file.preview": "预览附件",
  "file.download": "下载附件",
  "file.upload": "上传附件",
  "borrow.apply": "申请借阅",
  "borrow.approve": "审批借阅",
  "destroy.apply": "申请销毁",
  "destroy.approve": "审批销毁",
  "destroy.execute": "执行销毁",
};
/** 每次只授予一个明确动作；期限空白代表长期授权。 */
const grantFields = computed<Field[]>(() => [
  {
    key: "grantee_id",
    label: grantType.value === "user" ? "授权人员" : "授权部门",
    type: "select",
    required: true,
    options: options(
      grantType.value === "user" ? users.value : departments.value,
      "name",
    ),
  },
  {
    key: "permission_code",
    label: "允许操作",
    type: "select",
    required: true,
    options: Object.entries(grantPermissions)
      .filter(([key]) => can(key))
      .map(([value, label]) => ({ value, label })),
  },
  {
    key: "expires_at",
    label: "授权截止日期",
    type: "date",
    help: "选择日期的当天23:59（北京时间）截止；留空为长期。",
  },
]);
/** 详情字段只展示白名单，不泄露存储路径等内部实现。 */
const detailLabels: Record<string, string> = {
  archive_no: "档案编号",
  archive_name: "档案名称",
  status: "状态",
  category_name: "档案分类",
  dept_name: "所属部门",
  owner_name: "负责人",
  security_name: "密级",
  retention_name: "保管期限",
  formation_date: "形成日期",
  archive_date: "存档日期",
  expiry_date: "到期日期",
  file_type: "文件类型",
  storage_type: "保存类型",
  physical_location: "原件位置",
  cabinet_no: "柜号",
  column_no: "列号",
  layer_no: "层号",
  copy_count: "份数",
  volume_no: "卷号",
  page_count: "页数",
  keyword: "关键词",
  summary: "内容摘要",
  remark: "备注",
};
/** 重新读取主档案和基础明细；共享另行校验权限，不影响普通只读用户。 */
async function load() {
  if (!id.value) return;
  const requestedId = id.value; // 请求绑定当前档案，抽屉切换后不交付旧对象数据。
  const [record, attachments, logs] = await Promise.all([
    api(`/archives/${requestedId}`),
    api<Row[]>(`/files?archive_id=${requestedId}`),
    api<Row[]>(`/archives/${requestedId}/update-logs`),
  ]);
  if (id.value !== requestedId) return;
  detail.value = record;
  files.value = attachments;
  changes.value = logs;
}
watch(id, (value) => {
  tab.value = "info";
  detail.value = {};
  files.value = [];
  changes.value = [];
  grants.value = [];
  closePreview();
  if (value)
    void run(load).then(() => {
      // 切换发生在读请求锁内时，旧读结束后补读当前目标，不能把旧详情配给新主键。
      if (id.value && id.value !== value) void run(load);
    });
});
/** 仅在共享页签打开时加载；后端继续判断本部门或全范围管理资格。 */
async function loadGrants() {
  if (!id.value || tab.value !== "grants") return;
  await run(async () => {
    const requestedId = id.value; // 共享记录同样绑定原档案，不跨抽屉覆盖。
    const loaded = await api<Row[]>(`/archives/${requestedId}/grants`);
    if (id.value !== requestedId) return;
    grants.value = loaded;
    [users.value, departments.value] = await Promise.all([
      api<Row[]>("/archives/share-subjects?type=user"),
      api<Row[]>("/archives/share-subjects?type=dept"),
    ]);
  });
}
/** 清理对象地址，关闭详情或退出账号时不能残留敏感预览。 */
function closePreview() {
  previewOpen.value = false;
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value);
  previewUrl.value = "";
  previewText.value = "";
}
watch(previewOpen, (value) => {
  if (!value) closePreview();
});
onBeforeUnmount(closePreview);
/** 上传100MB以内单文件，同名文件由后端自动形成新版本。 */
async function upload(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  await run(async () => {
    if (file.size > 100 * 1024 * 1024) throw new Error("单个附件不得超过100MB");
    const body = new FormData();
    body.append("archive_id", String(id.value));
    body.append("file", file);
    await api("/files/upload", "POST", body);
    await load();
    emit("changed");
  }, "附件已上传");
  input.value = "";
}
/** 预览使用独立接口，主动内容不降级为未授权下载。 */
async function preview(file: Row) {
  await run(async () => {
    closePreview();
    const blob = await fileBlob(`/files/${file.id}/preview`);
    const mime = blob.type.split(";")[0];
    if (
      ![
        "application/pdf",
        "image/png",
        "image/jpeg",
        "image/gif",
        "text/plain",
      ].includes(mime)
    )
      throw new Error("此格式不支持在线预览，请在具备下载权限时下载查看。");
    previewType.value = mime;
    if (mime === "text/plain") {
      if (blob.size > 2 * 1024 * 1024)
        throw new Error("文本超过2MB，请下载查看。");
      previewText.value = await blob.text();
    } else previewUrl.value = URL.createObjectURL(blob);
    previewOpen.value = true;
  });
}
/** 下载请求保留服务端鉴权和审计。 */
async function download(file: Row) {
  await run(async () =>
    saveBlob(await fileBlob(`/files/${file.id}/download`), file.file_name),
  );
}
/** 正式归档是独立业务动作，不使用通用编辑篡改状态。 */
async function archive() {
  await run(async () => {
    await confirmAction(`将“${detail.value.archive_name}”正式归档？`);
    await api(`/archives/${id.value}/archive`, "POST");
    await load();
    emit("changed");
  }, "档案已归档");
}
/** 授权截止时间使用明确东八区偏移，避免不同电脑时区改变含义。 */
async function saveGrant(value: Row) {
  await run(async () => {
    await api(`/archives/${id.value}/grants`, "POST", {
      ...value,
      grantee_type: grantType.value,
      expires_at: value.expires_at
        ? `${value.expires_at}T23:59:59+08:00`
        : null,
    });
    grantOpen.value = false;
    grants.value = await api<Row[]>(`/archives/${id.value}/grants`);
  }, "共享授权已保存");
}
/** 撤销指定授权，不能批量删除其他共享记录。 */
async function revoke(row: Row) {
  await run(async () => {
    await confirmAction("撤销此项共享权限？");
    await api(`/archives/${id.value}/grants/${row.id}`, "DELETE");
    grants.value = await api<Row[]>(`/archives/${id.value}/grants`);
  }, "授权已撤销");
}
/** 将主体编号映射为已授权读取的最小姓名/部门名称。 */
function subject(row: Row) {
  return (
    (row.grantee_type === "user" ? users.value : departments.value).find(
      (item) => item.id === row.grantee_id,
    )?.name ?? row.grantee_id
  );
}
/** 将审计快照投影成中文字段对照，异常快照不执行、不作为HTML显示。 */
function changeRows(row: Row): Row[] {
  try {
    const before = JSON.parse(row.before_json || "{}"); // 修改前的白名单字段快照。
    const after = JSON.parse(row.after_json || "{}"); // 修改后字段值。
    return (row.changed_fields ?? []).map((key: string) => ({
      field: detailLabels[key] ?? key,
      before: before[key],
      after: after[key],
    }));
  } catch {
    return [
      {
        field: "记录格式异常",
        before: "请联系管理员核查",
        after: "原记录已保留",
      },
    ];
  }
}
</script>
<template>
  <el-drawer
    :model-value="id !== null"
    title="档案详情"
    size="min(900px, 100%)"
    @close="id = null"
  >
    <h2>{{ detail.archive_name }}</h2>
    <p class="muted">{{ detail.archive_no }} · {{ display(detail.status) }}</p>
    <div class="action-row">
      <el-button
        v-if="
          can('archive.write') && ['draft', 'archive'].includes(detail.status)
        "
        :disabled="busy"
        @click="emit('edit', detail)"
        >修改档案</el-button
      ><el-button
        v-if="can('archive.write') && detail.status === 'draft'"
        type="primary"
        :loading="busy"
        @click="archive"
        >正式归档</el-button
      >
    </div>
    <el-tabs v-model="tab" @tab-change="loadGrants"
      ><el-tab-pane label="基本信息" name="info"
        ><dl class="detail-grid">
          <div v-for="(label, key) in detailLabels" :key="key">
            <dt>{{ label }}</dt>
            <dd>{{ display(detail[key]) }}</dd>
          </div>
        </dl></el-tab-pane
      >
      <el-tab-pane label="电子附件" name="files"
        ><p class="empty-note">
          同名附件上传将保留历史版本。单个文件最大100MB。
        </p>
        <label
          v-if="
            can('file.upload') && ['draft', 'archive'].includes(detail.status)
          "
          class="action-row"
          >上传附件
          <input
            type="file"
            class="file-input"
            :disabled="busy"
            aria-label="上传附件"
            @change="upload" /></label
        ><DataTable
          :rows="files"
          :loading="busy"
          :columns="[
            { key: 'file_name', label: '文件名称', width: 220 },
            { key: 'version_no', label: '版本', width: 65 },
            { key: 'file_size', label: '大小（字节）' },
            { key: 'uploaded_at', label: '上传时间', width: 180 },
          ]"
          ><template #default="{ row }"
            ><el-button
              v-if="can('file.preview')"
              link
              type="primary"
              :disabled="busy"
              @click="preview(row)"
              >预览</el-button
            ><el-button
              v-if="can('file.download')"
              link
              type="primary"
              :disabled="busy"
              @click="download(row)"
              >下载</el-button
            ></template
          ></DataTable
        ></el-tab-pane
      >
      <el-tab-pane label="变更记录" name="changes"
        ><el-collapse
          ><el-collapse-item
            v-for="row in changes"
            :key="row.id"
            :title="`${display(row.created_at)} · ${row.changed_fields.map((key: string) => detailLabels[key] ?? key).join('、')}`"
            ><DataTable
              :rows="changeRows(row)"
              :columns="[
                { key: 'field', label: '变更字段' },
                { key: 'before', label: '修改前', width: 200 },
                { key: 'after', label: '修改后', width: 200 },
              ]"
            /> </el-collapse-item></el-collapse
        ><el-empty v-if="!changes.length" description="暂无变更记录"
      /></el-tab-pane>
      <el-tab-pane v-if="can('archive.share')" label="共享授权" name="grants"
        ><p class="empty-note">
          共享只扩大档案范围；接收账号仍须具有对应功能权限。查看、预览、下载分别授权。
        </p>
        <div class="toolbar">
          <el-select v-model="grantType" aria-label="共享对象类型"
            ><el-option label="人员" value="user" /><el-option
              label="部门"
              value="dept" /></el-select
          ><el-button type="primary" :disabled="busy" @click="grantOpen = true"
            >新增授权</el-button
          >
        </div>
        <el-table :data="grants"
          ><el-table-column label="授权对象"
            ><template #default="{ row }">{{
              subject(row)
            }}</template></el-table-column
          ><el-table-column label="允许操作"
            ><template #default="{ row }">{{
              grantPermissions[row.permission_code] ?? row.permission_code
            }}</template></el-table-column
          ><el-table-column
            prop="expires_at"
            label="截止时间"
          /><el-table-column label="操作"
            ><template #default="{ row }"
              ><el-button
                link
                type="danger"
                :disabled="busy"
                @click="revoke(row)"
                >撤销</el-button
              ></template
            ></el-table-column
          ></el-table
        ></el-tab-pane
      >
    </el-tabs>
  </el-drawer>
  <FormDialog
    v-model="grantOpen"
    title="新增共享授权"
    :fields="grantFields"
    :busy="busy"
    @save="saveGrant"
  />
  <el-dialog
    v-model="previewOpen"
    title="附件预览"
    width="1000px"
    destroy-on-close
  >
    <pre v-if="previewType === 'text/plain'" class="preview-text">{{
      previewText
    }}</pre>
    <img
      v-else-if="previewType.startsWith('image/')"
      :src="previewUrl"
      alt="档案附件预览"
      class="preview-image" /><iframe
      v-else-if="previewType === 'application/pdf'"
      :src="previewUrl"
      title="PDF附件预览"
      class="preview-frame"
  /></el-dialog>
</template>
