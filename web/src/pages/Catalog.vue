<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api, can, query, session } from "../lib/api";
import { useAction } from "../lib/ui";
import { options, type Row, type Field } from "../lib/model";
import Manager from "../components/Manager.vue";
/** 基础配置页签、编号模式和字典类型。 */
const tab = ref("categories");
const mode = ref("auto");
const dictType = ref("file_type");
/** 分类引用的密级、期限及编号规则。 */
const categories = ref<Row[]>([]);
const dicts = ref<Row>({ security_levels: [], retention_periods: [] });
const rules = ref<Row[]>([]);
const preview = ref("");
const previewCategory = ref<number>();
/** 配置提交状态，失败时保持当前表单。 */
const { busy, run } = useAction();
/** 布尔型目录使用0/1保持数据库约束。 */
const enabled = [
  { value: 1, label: "启用" },
  { value: 0, label: "停用" },
];
/** 加载通用目录，不访问普通用户无权维护的专用接口。 */
async function load() {
  [categories.value, dicts.value, rules.value] = await Promise.all([
    api<Row[]>("/categories"),
    api("/system/dicts"),
    api<Row[]>("/system/numbering-rules"),
  ]);
  mode.value = (await api("/system/archive-no-mode")).mode;
}
onMounted(() => run(load));
/** 分类维护字段，保留层级和默认属性。 */
const categoryFields = computed<Field[]>(() => [
  { key: "category_code", label: "分类编码", required: true },
  { key: "category_name", label: "分类名称", required: true },
  {
    key: "parent_id",
    label: "上级分类",
    type: "select",
    options: options(categories.value, "category_name"),
  },
  {
    key: "default_security_level_id",
    label: "默认密级",
    type: "select",
    options: options(dicts.value.security_levels, "name"),
  },
  {
    key: "default_retention_id",
    label: "默认保管期限",
    type: "select",
    options: options(dicts.value.retention_periods, "name"),
  },
  {
    key: "numbering_rule_id",
    label: "编号规则",
    type: "select",
    options: options(rules.value, "rule_name"),
  },
  { key: "sort_order", label: "排序", type: "number", initial: 0 },
  {
    key: "status",
    label: "状态",
    type: "select",
    initial: 1,
    options: enabled,
  },
]);
/** 传统编号模板编辑；已有分段配置不在这里被无意清空。 */
const ruleFields: Field[] = [
  { key: "rule_code", label: "规则编码", required: true },
  { key: "rule_name", label: "规则名称", required: true },
  {
    key: "pattern",
    label: "编号格式",
    required: true,
    initial: "{DEPT}-{CATEGORY}-{YEAR}-{SEQ4}",
    help: "可用 {DEPT} 部门、{CATEGORY} 分类、{YEAR} 年度、{SEQ4} 四位流水。已有分段规则仍优先使用分段。",
  },
  {
    key: "scope_type",
    label: "流水范围",
    type: "select",
    initial: "dept_category",
    options: [
      { value: "global", label: "全公司" },
      { value: "dept", label: "部门" },
      { value: "category", label: "分类" },
      { value: "dept_category", label: "部门与分类" },
    ],
  },
  {
    key: "reset_cycle",
    label: "重置周期",
    type: "select",
    initial: "YEAR",
    options: [
      { value: "YEAR", label: "每年" },
      { value: "MONTH", label: "每月" },
      { value: "NEVER", label: "不重置" },
    ],
  },
  {
    key: "sequence_length",
    label: "流水位数",
    type: "number",
    initial: 4,
    min: 1,
    max: 18,
  },
  {
    key: "priority",
    label: "优先级",
    type: "number",
    initial: 0,
    min: -100000,
    max: 100000,
  },
  {
    key: "enabled",
    label: "状态",
    type: "select",
    initial: 1,
    options: enabled,
  },
];
/** 密级、保管期限和常用字典编辑器。 */
const securityFields: Field[] = [
  { key: "code", label: "编码", required: true },
  { key: "name", label: "密级名称", required: true },
  { key: "level", label: "级别", type: "number", initial: 1 },
  { key: "description", label: "说明", type: "textarea" },
];
const retentionFields: Field[] = [
  { key: "code", label: "编码", required: true },
  { key: "name", label: "保管期限名称", required: true },
  {
    key: "years",
    label: "年数",
    type: "number",
    min: 0,
    initial: 10,
    help: "0表示永久保管。",
  },
];
const dictFields = computed<Field[]>(() => [
  {
    key: "type_code",
    label: "字典类型",
    required: true,
    initial: dictType.value,
  },
  { key: "item_code", label: "项目编码", required: true },
  { key: "item_name", label: "项目名称", required: true },
  { key: "sort_order", label: "排序", type: "number", initial: 0 },
  {
    key: "enabled",
    label: "状态",
    type: "select",
    initial: 1,
    options: enabled,
  },
]);
/** 修改编号模式影响后续新建档案，不重编已有编号。 */
async function saveMode() {
  await run(async () => {
    await api("/system/archive-no-mode", "PUT", { mode: mode.value });
  }, "编号模式已保存");
}
/** 只预览所属部门与分类对应编号，不消耗正式流水。 */
async function showPreview() {
  await run(async () => {
    if (!previewCategory.value) throw new Error("请选择预览分类");
    preview.value = (
      await api(
        "/system/numbering-rules/preview" +
          query({
            dept_id: session.user?.dept_id,
            category_id: previewCategory.value,
          }),
      )
    ).archive_no;
  });
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>基础设置</h1>
      <p>维护档案分类、保管属性与编号规则</p>
    </div>
  </div>
  <section class="panel">
    <el-tabs v-model="tab"
      ><el-tab-pane name="categories" label="档案分类" /><el-tab-pane
        name="numbering"
        label="档案编号" /><el-tab-pane
        v-if="can('dict.manage')"
        name="security"
        label="密级" /><el-tab-pane
        v-if="can('dict.manage')"
        name="retention"
        label="保管期限" /><el-tab-pane
        v-if="can('dict.manage')"
        name="dict"
        label="常用字典"
    /></el-tabs>
    <Manager
      v-if="tab === 'categories'"
      key="categories"
      title="分类"
      path="/categories"
      :fields="categoryFields"
      :columns="[
        { key: 'category_code', label: '分类编码' },
        { key: 'category_name', label: '分类名称' },
        { key: 'parent_id', label: '上级编号' },
      ]"
      :writable="can('archive.admin')"
      removable
      @changed="run(load)"
    />
    <template v-else-if="tab === 'numbering'"
      ><div class="toolbar">
        <el-select
          v-model="mode"
          aria-label="编号模式"
          :disabled="!can('archive.admin')"
          ><el-option label="自动编号" value="auto" /><el-option
            label="手工编号"
            value="manual" /></el-select
        ><el-button
          v-if="can('archive.admin')"
          :loading="busy"
          @click="saveMode"
          >保存编号模式</el-button
        >
      </div>
      <Manager
        title="编号规则"
        path="/system/numbering-rules"
        :fields="ruleFields"
        :columns="[
          { key: 'rule_code', label: '规则编码' },
          { key: 'rule_name', label: '规则名称' },
          { key: 'pattern', label: '编号格式', width: 300 },
        ]"
        :writable="can('archive.admin')"
        removable
        @changed="run(load)"
      />
      <div class="toolbar">
        <el-select
          v-model="previewCategory"
          placeholder="选择预览分类"
          aria-label="预览分类"
          ><el-option
            v-for="row in categories"
            :key="row.id"
            :value="row.id"
            :label="row.category_name" /></el-select
        ><el-button :loading="busy" @click="showPreview">预览编号</el-button
        ><b>{{ preview }}</b>
      </div></template
    >
    <Manager
      v-else-if="tab === 'security' && can('dict.manage')"
      key="security"
      title="密级"
      path="/system/security-levels"
      :fields="securityFields"
      :columns="[
        { key: 'code', label: '编码' },
        { key: 'name', label: '名称' },
        { key: 'level', label: '级别' },
      ]"
      writable
      removable
      @changed="run(load)"
    />
    <Manager
      v-else-if="tab === 'retention' && can('dict.manage')"
      key="retention"
      title="保管期限"
      path="/system/retention-periods"
      :fields="retentionFields"
      :columns="[
        { key: 'code', label: '编码' },
        { key: 'name', label: '名称' },
        { key: 'years', label: '年数' },
      ]"
      writable
      removable
      @changed="run(load)"
    />
    <template v-else-if="tab === 'dict' && can('dict.manage')"
      ><div class="toolbar">
        <el-select v-model="dictType" aria-label="字典类型"
          ><el-option value="file_type" label="文件类型" /><el-option
            value="storage_type"
            label="保存类型"
        /></el-select>
      </div>
      <Manager
        :key="dictType"
        title="字典项"
        path="/system/dict-items"
        :extra-query="{ type_code: dictType, include_disabled: true }"
        :fields="dictFields"
        :columns="[
          { key: 'type_code', label: '类型' },
          { key: 'item_code', label: '编码' },
          { key: 'item_name', label: '名称' },
          { key: 'enabled', label: '启用（1是0否）' },
        ]"
        writable
        removable
    /></template>
  </section>
</template>
