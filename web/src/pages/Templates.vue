<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import type { FormInstance } from "element-plus";
import { api, can } from "../lib/api";
import { confirmAction, useAction } from "../lib/ui";
import { options, type Row, type Field, type Page } from "../lib/model";
import DataTable from "../components/DataTable.vue";
import Fields from "../components/Fields.vue";
/** 模板列表和节点编辑副本，保存不会改动历史审批快照。 */
const rows = ref<Row[]>([]);
const model = ref<Row>({ nodes: [] });
const open = ref(false);
const form = ref<FormInstance>();
/** 审批人来源目录，受组织查询权限限制。 */
const users = ref<Row[]>([]);
const roles = ref<Row[]>([]);
const permissions = ref<Row[]>([]);
/** 模板保存防重复提交状态。 */
const { busy, run } = useAction();
/** 条件字段固定白名单，不暴露任意数据库表达式。 */
const conditionFields = [
  { value: "archive_no", label: "档案编号" },
  { value: "archive_name", label: "档案名称" },
  { value: "keyword", label: "关键词" },
  { value: "category_name", label: "分类名称" },
  { value: "dept_name", label: "部门名称" },
];
const conditionOps = [
  { value: "eq", label: "等于" },
  { value: "contains", label: "包含" },
  { value: "startswith", label: "开头为" },
  { value: "endswith", label: "结尾为" },
];
/** 根据触发条件或节点条件前缀生成可复用输入定义。 */
function conditions(prefix: string): Field[] {
  return [
    {
      key: `${prefix}_field`,
      label: "条件字段",
      type: "select",
      options: conditionFields,
      help: "三项均留空表示不设条件。",
    },
    {
      key: `${prefix}_op`,
      label: "匹配方式",
      type: "select",
      options: conditionOps,
    },
    { key: `${prefix}_value`, label: "匹配内容", length: 200 },
  ];
}
/** 模板基础属性和自动路由条件。 */
const fields: Field[] = [
  { key: "name", label: "规则名称", required: true, length: 100 },
  {
    key: "biz_type",
    label: "适用业务",
    type: "select",
    required: true,
    options: [
      { value: "borrow", label: "借阅" },
      { value: "destroy", label: "销毁" },
    ],
  },
  {
    key: "priority",
    label: "优先级",
    type: "number",
    min: -100000,
    max: 100000,
    help: "匹配多条规则时使用优先级较高的规则。",
  },
  {
    key: "enabled",
    label: "状态",
    type: "select",
    options: [
      { value: 1, label: "启用" },
      { value: 0, label: "停用" },
    ],
  },
  { key: "description", label: "规则说明", type: "textarea" },
  ...conditions("trigger"),
];
/** 节点来源包含原审批引擎全部受支持模式。 */
const sources = [
  { value: "user", label: "指定人员" },
  { value: "role", label: "指定角色" },
  { value: "permission", label: "持有指定权限" },
  { value: "permission_level", label: "角色等级上限" },
  { value: "dept_leader", label: "档案所属部门负责人" },
  { value: "creator_leader", label: "建档人部门负责人" },
];
/** 指定人员保留数值主键，提交时由后端统一转为节点快照。 */
function nodeFields(node: Row): Field[] {
  const value: Field = {
    key: "approver_value",
    label: "审批人范围",
    required: true,
  };
  if (node.approver_type === "user") {
    value.type = users.value.length ? "select" : "number";
    value.options = options(users.value, "real_name");
    value.min = 1;
    value.help = users.value.length
      ? "选择人员后仍需具有业务审批权限。"
      : "填写已存在的用户编号。";
  } else if (node.approver_type === "role") {
    value.type = roles.value.length ? "select" : "text";
    value.options = roles.value.map((row) => ({
      value: row.role_code,
      label: row.role_name,
    }));
  } else if (node.approver_type === "permission") {
    value.type = permissions.value.length ? "select" : "text";
    value.options = permissions.value.map((row) => ({
      value: row.perm_code,
      label: row.perm_name,
    }));
  } else {
    value.type = "number";
    value.min = 0;
    value.max = 100;
  }
  return [
    { key: "node_name", label: "节点名称", required: true, length: 100 },
    {
      key: "is_countersign",
      label: "办理方式",
      type: "select",
      required: true,
      options: [
        { value: 0, label: "任一审批人办理" },
        { value: 1, label: "全部审批人会签" },
      ],
    },
    ...(["dept_leader", "creator_leader"].includes(node.approver_type)
      ? []
      : [value]),
    ...conditions("cond"),
  ];
}
/** 模板与可选审批人分别加载，未授权的组织目录不发请求。 */
async function load() {
  rows.value = await api<Row[]>("/approval/templates");
}
onMounted(() =>
  run(async () => {
    await load();
    if (can("user.read")) {
      [roles.value, permissions.value] = await Promise.all([
        api<Row[]>("/roles"),
        api<Row[]>("/permissions"),
      ]);
      let index = 1;
      let total = 0;
      do {
        const batch = await api<Page>(`/users?page=${index++}&page_size=200`);
        users.value.push(...batch.items);
        total = batch.total;
      } while (users.value.length < total && index <= 50);
    }
  }),
);
/** 新节点序号由数组顺序生成，减少重复序号错误。 */
function add() {
  model.value.nodes.push({
    node_name: "审批节点",
    approver_type: "user",
    is_countersign: 0,
  });
}
/** 编辑使用深副本；数字型审批人值需要还原以匹配选择控件。 */
function edit(row?: Row) {
  model.value = row
    ? JSON.parse(JSON.stringify(row))
    : { name: "", biz_type: "borrow", priority: 0, enabled: 1, nodes: [] };
  for (const node of model.value.nodes) {
    if (["user", "permission_level"].includes(node.approver_type))
      node.approver_value = Number(node.approver_value);
  }
  if (!row) add();
  open.value = true;
}
/** 调整节点顺序，不更改数据库中的历史流程。 */
function move(index: number, offset: number) {
  const target = index + offset;
  if (target < 0 || target >= model.value.nodes.length) return;
  [model.value.nodes[index], model.value.nodes[target]] = [
    model.value.nodes[target],
    model.value.nodes[index],
  ];
}
/** 清空条件时三项同时清理；部分填写交由服务端严格拒绝。 */
function cleanCondition(row: Row, prefix: string) {
  if (
    !row[`${prefix}_field`] &&
    !row[`${prefix}_op`] &&
    !row[`${prefix}_value`]
  ) {
    row[`${prefix}_field`] = null;
    row[`${prefix}_op`] = null;
    row[`${prefix}_value`] = null;
  }
}
/** 节点表单逐项校验，再传递白名单字段；拒绝空节点或缺失审批人。 */
async function save() {
  if (!(await form.value?.validate().catch(() => false))) return;
  await run(async () => {
    if (!model.value.nodes.length) throw new Error("至少添加一个审批节点");
    const body: Row = Object.fromEntries(
      fields.map((field) => [field.key, model.value[field.key]]),
    );
    cleanCondition(body, "trigger");
    body.nodes = model.value.nodes.map((node: Row, index: number) => {
      if (!node.node_name?.trim())
        throw new Error(`第${index + 1}个节点缺少名称`);
      if (
        !["dept_leader", "creator_leader"].includes(node.approver_type) &&
        String(node.approver_value ?? "").trim() === ""
      )
        throw new Error(`第${index + 1}个节点未选择审批人范围`);
      const item: Row = {
        node_order: index + 1,
        node_name: node.node_name,
        approver_type: node.approver_type,
        approver_value: ["dept_leader", "creator_leader"].includes(
          node.approver_type,
        )
          ? null
          : String(node.approver_value),
        is_countersign: node.is_countersign,
        cond_field: node.cond_field,
        cond_op: node.cond_op,
        cond_value: node.cond_value,
      };
      cleanCondition(item, "cond");
      return item;
    });
    await api(
      "/approval/templates" + (model.value.id ? `/${model.value.id}` : ""),
      model.value.id ? "PUT" : "POST",
      body,
    );
    open.value = false;
    await load();
  }, "审批规则已保存");
}
/** 删除定义不清理已发起业务单中的节点快照。 */
async function remove(row: Row) {
  await run(async () => {
    await confirmAction(`删除审批规则“${row.name}”？历史流程将保留。`);
    await api(`/approval/templates/${row.id}`, "DELETE");
    await load();
  }, "规则已删除");
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>审批规则</h1>
      <p>配置审批顺序、条件和会签方式；变更仅影响后续新申请</p>
    </div>
    <el-button type="primary" :disabled="busy" @click="edit()"
      >新增规则</el-button
    >
  </div>
  <section class="panel">
    <DataTable
      :rows="rows"
      :loading="busy"
      :columns="[
        { key: 'name', label: '规则名称', width: 260 },
        { key: 'biz_type', label: '业务类型' },
        { key: 'priority', label: '优先级' },
        { key: 'enabled', label: '启用（1是0否）' },
      ]"
      ><template #default="{ row }"
        ><el-button link type="primary" :disabled="busy" @click="edit(row)"
          >编辑</el-button
        ><el-button link type="danger" :disabled="busy" @click="remove(row)"
          >删除</el-button
        ></template
      ></DataTable
    >
  </section>
  <el-dialog
    v-model="open"
    title="审批规则配置"
    width="950px"
    :close-on-click-modal="false"
    :show-close="!busy"
    ><el-form ref="form" :model="model" label-position="top"
      ><Fields :fields="fields" :model="model"
    /></el-form>
    <h3>审批节点（按以下顺序办理）</h3>
    <div v-for="(node, index) in model.nodes" :key="index" class="node-editor">
      <div class="toolbar">
        <b>第 {{ Number(index) + 1 }} 步</b><span class="spacer" /><el-button
          :disabled="index === 0"
          @click="move(Number(index), -1)"
          >上移</el-button
        ><el-button
          :disabled="index === model.nodes.length - 1"
          @click="move(Number(index), 1)"
          >下移</el-button
        ><el-button
          type="danger"
          plain
          @click="model.nodes.splice(Number(index), 1)"
          >移除</el-button
        >
      </div>
      <el-form label-position="top" :model="node"
        ><el-form-item label="审批人来源"
          ><el-select
            v-model="node.approver_type"
            aria-label="审批人来源"
            @change="node.approver_value = undefined"
            ><el-option
              v-for="source in sources"
              :key="source.value"
              :value="source.value"
              :label="source.label" /></el-select></el-form-item
        ><Fields :model="node" :fields="nodeFields(node)"
      /></el-form>
    </div>
    <el-button :disabled="model.nodes.length >= 30" @click="add"
      >添加审批节点</el-button
    ><template #footer
      ><el-button :disabled="busy" @click="open = false">取消</el-button
      ><el-button type="primary" :loading="busy" @click="save"
        >保存规则</el-button
      ></template
    ></el-dialog
  >
</template>
