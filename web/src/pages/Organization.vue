<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api, can, session } from "../lib/api";
import { useAction } from "../lib/ui";
import { options, type Row, type Field, type Page } from "../lib/model";
import Manager from "../components/Manager.vue";
/** 当前组织管理页签。 */
const tab = ref("users");
/** 关系字段选项来自权限过滤后的目录。 */
const departments = ref<Row[]>([]);
const roles = ref<Row[]>([]);
const permissions = ref<Row[]>([]);
const users = ref<Row[]>([]);
/** 目录加载与错误提示状态。 */
const { busy, run } = useAction();
/** 刷新关系目录，用户逐页加载以避免前200条之外的负责人无法选择。 */
async function load() {
  [departments.value, roles.value, permissions.value] = await Promise.all([
    api<Row[]>("/departments"),
    api<Row[]>("/roles"),
    api<Row[]>("/permissions"),
  ]);
  const collected: Row[] = [];
  let page = 1;
  let count = 0;
  do {
    const data = await api<Page>(`/users?page=${page++}&page_size=200`);
    collected.push(...data.items);
    count = data.total;
  } while (collected.length < count && page <= 50);
  users.value = collected;
}
onMounted(() => run(load));
/** 编辑用户时保留当前真实角色集合；账号禁用与角色变更会让旧会话立即失效。 */
const userFields = computed<Field[]>(() => [
  { key: "real_name", label: "姓名", required: true, length: 100 },
  {
    key: "dept_id",
    label: "所属部门",
    type: "select",
    required: true,
    options: options(departments.value, "dept_name"),
    initial: session.user?.dept_id,
  },
  {
    key: "role_ids",
    label: "角色",
    type: "multi",
    options: options(roles.value, "role_name"),
    help: "更新角色后，该用户需要重新登录。",
  },
  { key: "employee_no", label: "工号" },
  { key: "phone", label: "联系电话" },
  { key: "email", label: "邮箱" },
  {
    key: "account_status",
    label: "账号状态",
    type: "select",
    required: true,
    options: [
      { value: "active", label: "正常" },
      { value: "disabled", label: "禁用" },
      { value: "locked", label: "锁定" },
    ],
  },
]);
/** 新建账号必须设置显式密码；密码修改不混入编辑用户请求。 */
const createUserFields = computed<Field[]>(() => [
  {
    key: "username",
    label: "登录账号",
    required: true,
    help: "英文字母、数字、点、短横线、下划线或@",
  },
  {
    key: "password",
    label: "初始密码",
    type: "password",
    required: true,
    length: 128,
    help: "至少12个字符；请通过公司认可的方式交付账号。",
  },
  ...userFields.value.filter((field) => field.key !== "account_status"),
]);
/** 部门负责人通过已存在账号选择，避免填写无法解析的名称。 */
const departmentFields = computed<Field[]>(() => [
  { key: "dept_code", label: "部门编码", required: true },
  { key: "dept_name", label: "部门名称", required: true },
  {
    key: "parent_id",
    label: "上级部门",
    type: "select",
    options: options(departments.value, "dept_name"),
  },
  {
    key: "leader_user_id",
    label: "负责人",
    type: "select",
    options: options(users.value, "real_name"),
  },
  { key: "sort_order", label: "排序", type: "number", initial: 0 },
  {
    key: "status",
    label: "状态",
    type: "select",
    initial: 1,
    options: [
      { value: 1, label: "启用" },
      { value: 0, label: "停用" },
    ],
  },
]);
/** 角色创建仅可选择自身拥有的权限，后端再次检查不能越权委派。 */
const roleFields = computed<Field[]>(() => [
  { key: "role_code", label: "角色编码", required: true },
  { key: "role_name", label: "角色名称", required: true },
  {
    key: "role_level",
    label: "角色等级",
    type: "number",
    initial: 50,
    min: 0,
    max: 100,
  },
  {
    key: "status",
    label: "状态",
    type: "select",
    initial: 1,
    options: [
      { value: 1, label: "启用" },
      { value: 0, label: "停用" },
    ],
  },
  {
    key: "permission_codes",
    label: "功能权限",
    type: "multi",
    options: permissions.value
      .filter((row) => can(row.perm_code))
      .map((row) => ({
        value: row.perm_code,
        label: `${row.perm_name}（${row.perm_code}）`,
      })),
  },
]);
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>组织与账号</h1>
      <p>部门、用户和角色统一维护，档案模块复用这套身份体系</p>
    </div>
  </div>
  <section class="panel" v-loading="busy">
    <el-tabs v-model="tab"
      ><el-tab-pane label="用户账号" name="users" /><el-tab-pane
        label="部门目录"
        name="departments" /><el-tab-pane
        label="角色目录"
        name="roles" /></el-tabs
    ><Manager
      v-if="tab === 'users'"
      key="users"
      title="用户"
      path="/users"
      :fields="userFields"
      :create-fields="createUserFields"
      :columns="[
        { key: 'username', label: '账号' },
        { key: 'real_name', label: '姓名' },
        { key: 'dept_id', label: '部门编号' },
        { key: 'account_status', label: '状态' },
      ]"
      :writable="can('user.write')"
      pageable
      detail-endpoint
      @changed="run(load)"
    /><Manager
      v-else-if="tab === 'departments'"
      key="departments"
      title="部门"
      path="/departments"
      :fields="departmentFields"
      :columns="[
        { key: 'dept_code', label: '部门编码' },
        { key: 'dept_name', label: '部门名称' },
        { key: 'parent_id', label: '上级编号' },
      ]"
      :writable="can('user.write') && can('user.manage.all')"
      @changed="run(load)"
    /><Manager
      v-else
      key="roles"
      title="角色"
      path="/roles"
      :fields="roleFields"
      :columns="[
        { key: 'role_code', label: '角色编码' },
        { key: 'role_name', label: '角色名称' },
        { key: 'role_level', label: '等级' },
      ]"
      :writable="can('user.write')"
      create-only
      @changed="run(load)"
    />
  </section>
</template>
