<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { api, can, query, saveBlob, session } from "../lib/api";
import { csv, useAction } from "../lib/ui";
import {
  display,
  options,
  type Row,
  type Field,
  type Page,
} from "../lib/model";
import DataTable from "../components/DataTable.vue";
import FormDialog from "../components/FormDialog.vue";
import ArchiveDetail from "../components/ArchiveDetail.vue";
/** 分页与过滤条件，在查询按钮触发时统一提交。 */
const filter = reactive({
  keyword: "",
  category_id: "",
  dept_id: "",
  status: "",
  page: 1,
  page_size: 20,
});
/** 当前可见结果和表单需要的目录。 */
const result = ref<Page>({ items: [], total: 0 });
const categories = ref<Row[]>([]);
const departments = ref<Row[]>([]);
const dicts = ref<Row>({ security_levels: [], retention_periods: [] });
const mode = ref("auto");
/** 编辑目标和抽屉目标独立，避免关闭编辑时丢失详情。 */
const editing = ref<Row | null>(null);
const editOpen = ref(false);
const detailId = ref<number | null>(null);
const importResult = ref<Row | null>(null);
/** 页面写请求串行处理，失败保留输入。 */
const { busy, run } = useAction();
/** 真实后端枚举，已归档值为 archive。 */
const statuses = ["draft", "archive", "borrowed", "damaged", "destroyed"];
/** 档案元数据白名单；内部状态、创建人等由后端填写。 */
const fields = computed<Field[]>(() => [
  { key: "archive_name", label: "档案名称", required: true, length: 300 },
  ...(!editing.value && mode.value === "manual"
    ? [{ key: "archive_no", label: "档案编号", required: true }]
    : []),
  {
    key: "dept_id",
    label: "所属部门",
    type: "select",
    required: true,
    options: options(departments.value, "dept_name"),
    initial: session.user?.dept_id,
  },
  {
    key: "category_id",
    label: "档案分类",
    type: "select",
    required: true,
    options: options(categories.value, "category_name"),
  },
  {
    key: "security_level_id",
    label: "密级",
    type: "select",
    options: options(dicts.value.security_levels, "name"),
  },
  {
    key: "retention_id",
    label: "保管期限",
    type: "select",
    options: options(dicts.value.retention_periods, "name"),
  },
  { key: "owner_name", label: "档案负责人" },
  {
    key: "archive_year",
    label: "档案年度",
    type: "number",
    min: 1900,
    max: 9999,
    initial: new Date().getFullYear(),
  },
  { key: "formation_date", label: "形成日期", type: "date" },
  { key: "filing_date", label: "归档日期", type: "date" },
  { key: "archive_date", label: "存档日期", type: "date" },
  { key: "validity_months", label: "有效期（月）", type: "number" },
  { key: "file_type", label: "文件类型" },
  { key: "storage_type", label: "保存类型" },
  { key: "physical_location", label: "原件位置" },
  { key: "cabinet_no", label: "柜号" },
  { key: "column_no", label: "列号" },
  { key: "layer_no", label: "层号" },
  { key: "copy_count", label: "保存份数", type: "number", initial: 1 },
  { key: "volume_no", label: "卷号" },
  { key: "page_count", label: "页数", type: "number", initial: 0 },
  { key: "keyword", label: "关键词" },
  { key: "summary", label: "内容摘要", type: "textarea" },
  { key: "remark", label: "备注", type: "textarea" },
]);
/** 当前过滤范围内的分页结果。 */
async function load() {
  result.value = await api<Page>("/archives" + query(filter));
}
/** 初次加载并行读取互不依赖的只读目录。 */
onMounted(() =>
  run(async () => {
    [categories.value, departments.value, dicts.value] = await Promise.all([
      api<Row[]>("/categories"),
      api<Row[]>("/departments"),
      api("/system/dicts"),
    ]);
    mode.value = (await api("/system/archive-no-mode")).mode;
    await load();
  }),
);
/** 保存编辑副本，响应成功后才关闭弹窗。 */
async function save(value: Row) {
  await run(async () => {
    await api(
      editing.value ? `/archives/${editing.value.id}` : "/archives",
      editing.value ? "PUT" : "POST",
      value,
    );
    editOpen.value = false;
    detailId.value = null;
    await load();
  }, "档案已保存");
}
/** 新建或编辑共用表单，详情来源保持服务器最新数据。 */
function edit(row: Row | null) {
  editing.value = row;
  editOpen.value = true;
}
/** 导出限定当前筛选范围，超过5000份要求用户缩小条件，不静默截断。 */
async function exportCsv() {
  await run(async () => {
    const data = await api<Page>(
      "/archives" + query({ ...filter, page: 1, page_size: 5000 }),
    );
    if (data.total > 5000)
      throw new Error("结果超过5000份，请缩小筛选范围后导出。");
    saveBlob(
      csv(data.items, {
        archive_no: "档案编号",
        archive_name: "档案名称",
        category_name: "档案类别",
        dept_name: "部门",
        security_name: "密级",
        retention_name: "保管期限",
        owner_name: "档案负责人",
        archive_date: "存档日期",
        remark: "备注",
      }),
      "档案台账.csv",
    );
  });
}
/** 导入前检查容量；后端逐行校验并返回部分失败明细，界面不伪报全部成功。 */
async function importCsv(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  await run(async () => {
    if (file.size > 10 * 1024 * 1024) throw new Error("导入文件不得超过10MB");
    importResult.value = await api("/archives/import", "POST", {
      content: await file.text(),
    });
    await load();
  });
  input.value = "";
}
/** 下载与真实导入字段一致的空模板，避免用户猜测列名。 */
function template() {
  saveBlob(
    csv([], {
      archive_name: "档案名称",
      category: "档案类别",
      department: "部门",
      security: "密级",
      retention: "保管期限",
      owner_name: "档案负责人",
      archive_date: "存档日期",
      remark: "备注",
    }),
    "档案导入模板.csv",
  );
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>档案台账</h1>
      <p>统一查询、归档与管理，共 {{ result.total }} 份可见档案</p>
    </div>
    <el-button
      v-if="can('archive.write')"
      type="primary"
      :disabled="busy"
      @click="edit(null)"
      >新增档案</el-button
    >
  </div>
  <section class="panel">
    <form
      class="toolbar"
      @submit.prevent="
        filter.page = 1;
        run(load);
      "
    >
      <el-input
        v-model="filter.keyword"
        placeholder="搜索名称、编号或关键词"
        aria-label="档案搜索"
        clearable
      /><el-select
        v-model="filter.category_id"
        placeholder="全部分类"
        aria-label="分类筛选"
        clearable
        ><el-option
          v-for="row in categories"
          :key="row.id"
          :value="row.id"
          :label="row.category_name" /></el-select
      ><el-select
        v-model="filter.dept_id"
        placeholder="全部可见部门"
        aria-label="部门筛选"
        clearable
        ><el-option
          v-for="row in departments"
          :key="row.id"
          :value="row.id"
          :label="row.dept_name" /></el-select
      ><el-select
        v-model="filter.status"
        placeholder="全部状态"
        aria-label="状态筛选"
        clearable
        ><el-option
          v-for="status in statuses"
          :key="status"
          :value="status"
          :label="display(status)" /></el-select
      ><el-button native-type="submit" type="primary" :loading="busy"
        >查询</el-button
      ><el-button :disabled="busy" @click="exportCsv">导出台账</el-button>
    </form>
    <DataTable
      :rows="result.items"
      :loading="busy"
      :columns="[
        { key: 'archive_no', label: '档案编号', width: 200 },
        { key: 'archive_name', label: '档案名称', width: 220 },
        { key: 'category_name', label: '分类' },
        { key: 'dept_name', label: '部门' },
        { key: 'status', label: '状态', width: 95 },
        { key: 'updated_at', label: '最近更新', width: 180 },
      ]"
      ><template #default="{ row }"
        ><el-button
          link
          type="primary"
          :disabled="busy"
          @click="detailId = row.id"
          >查看详情</el-button
        ></template
      ></DataTable
    >
    <el-pagination
      :disabled="busy"
      class="pagination"
      v-model:current-page="filter.page"
      :page-size="filter.page_size"
      :total="result.total"
      layout="total, prev, pager, next"
      @current-change="run(load)"
    />
  </section>
  <section v-if="can('archive.write')" class="panel">
    <h3>批量导入</h3>
    <p class="empty-note">
      使用 UTF-8
      CSV，最多5000行、10MB。部门和分类填写已有名称。每行独立导入，失败行可修正后重试，请勿重复提交成功行。
    </p>
    <div class="toolbar">
      <el-button @click="template">下载空白模板</el-button
      ><input
        class="file-input"
        type="file"
        accept=".csv,text/csv"
        aria-label="导入CSV"
        :disabled="busy"
        @change="importCsv"
      />
    </div>
    <el-alert
      v-if="importResult"
      :type="importResult.failed ? 'warning' : 'success'"
      :closable="false"
      :title="`导入结果：成功 ${importResult.created}，失败 ${importResult.failed}，总计 ${importResult.total}`"
    /><DataTable
      v-if="importResult?.errors?.length"
      :rows="importResult.errors"
      :columns="[
        { key: 'row', label: '行号' },
        { key: 'error', label: '失败原因', width: 500 },
      ]"
    />
  </section>
  <FormDialog
    v-model="editOpen"
    :title="editing ? '修改档案' : '新增档案'"
    :fields="fields"
    :initial="editing ?? {}"
    :busy="busy"
    @save="save"
  />
  <ArchiveDetail v-model="detailId" @changed="run(load)" @edit="edit" />
</template>
