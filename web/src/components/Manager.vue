<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, query } from "../lib/api";
import { confirmAction, useAction } from "../lib/ui";
import type { Row, Field, Column, Page } from "../lib/model";
import DataTable from "./DataTable.vue";
import FormDialog from "./FormDialog.vue";
/** 基础目录管理配置；路径仅由固定页面声明，不能来自用户输入。 */
const props = defineProps<{
  title: string;
  path: string;
  fields: Field[];
  createFields?: Field[];
  columns: Column[];
  writable: boolean;
  removable?: boolean;
  createOnly?: boolean;
  pageable?: boolean;
  detailEndpoint?: boolean;
  extraQuery?: Row;
  initial?: Row;
}>();
/** 变更后通知父级重新加载关联目录。 */
const emit = defineEmits<{ changed: [] }>();
/** 当前页数据、总量和编辑副本。 */
const rows = ref<Row[]>([]);
const total = ref(0);
const page = ref(1);
const editing = ref<Row | null>(null);
const open = ref(false);
/** 当前管理表的网络提交状态。 */
const { busy, run } = useAction();
/** 分页资源与全量目录使用明确不同的返回结构。 */
async function load() {
  const data = await api<Row[] | Page>(
    props.path +
      query({
        ...props.extraQuery,
        ...(props.pageable ? { page: page.value, page_size: 20 } : {}),
      }),
  );
  if (Array.isArray(data)) {
    rows.value = data;
    total.value = data.length;
  } else {
    rows.value = data.items;
    total.value = data.total;
  }
}
onMounted(() => run(load));
/** 角色等关系字段需要详情接口时，获取后再打开编辑表单。 */
async function edit(row: Row | null) {
  await run(async () => {
    editing.value =
      row && props.detailEndpoint ? await api(`${props.path}/${row.id}`) : row;
    open.value = true;
  });
}
/** 只提交字段配置允许的属性，角色/目录只读字段不会透传。 */
async function save(value: Row) {
  await run(async () => {
    await api(
      props.path + (editing.value ? `/${editing.value.id}` : ""),
      editing.value ? "PUT" : "POST",
      value,
    );
    open.value = false;
    await load();
    emit("changed");
  }, "已保存");
}
/** 引用中的分类和基础记录仍由后端拒绝删除。 */
async function remove(row: Row) {
  await run(async () => {
    await confirmAction(
      `确认删除这条${props.title}记录？被引用的记录无法删除。`,
    );
    await api(`${props.path}/${row.id}`, "DELETE");
    await load();
    emit("changed");
  }, "记录已删除");
}
</script>
<template>
  <div class="toolbar">
    <h3>{{ title }}</h3>
    <span class="spacer" /><el-button :loading="busy" @click="run(load)"
      >刷新</el-button
    ><el-button
      v-if="writable"
      type="primary"
      :disabled="busy"
      @click="edit(null)"
      >新增{{ title }}</el-button
    >
  </div>
  <DataTable :rows="rows" :columns="columns" :loading="busy"
    ><template v-if="writable && (!createOnly || removable)" #default="{ row }"
      ><el-button
        v-if="!createOnly"
        type="primary"
        link
        :disabled="busy"
        @click="edit(row)"
        >编辑</el-button
      ><el-button
        v-if="removable"
        type="danger"
        link
        :disabled="busy"
        @click="remove(row)"
        >删除</el-button
      ></template
    ></DataTable
  ><el-pagination
    :disabled="busy"
    v-if="pageable"
    class="pagination"
    v-model:current-page="page"
    :total="total"
    :page-size="20"
    layout="total, prev, pager, next"
    @current-change="run(load)"
  /><FormDialog
    v-model="open"
    :title="(editing ? '编辑' : '新增') + title"
    :fields="editing ? fields : (createFields ?? fields)"
    :initial="editing ?? initial"
    :busy="busy"
    @save="save"
  />
</template>
