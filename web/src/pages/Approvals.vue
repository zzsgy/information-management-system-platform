<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, query } from "../lib/api";
import { useAction } from "../lib/ui";
import type { Row } from "../lib/model";
import DataTable from "../components/DataTable.vue";
import FlowPanel from "../components/FlowPanel.vue";
/** 本人待办与可见流程分开呈现，不把管理员视为每个节点的审批人。 */
const tab = ref("my-tasks");
const kind = ref("");
const rows = ref<Row[]>([]);
const flowId = ref<number | null>(null);
/** 加载状态及错误处理。 */
const { busy, run } = useAction();
/** 每次查询服务端重新判断参与关系及档案范围。 */
async function load() {
  rows.value = await api<Row[]>(
    `/approval/${tab.value}` + query({ biz_type: kind.value }),
  );
}
onMounted(() => run(load));
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>审批中心</h1>
      <p>查看本人待办与有权访问的流程；已办理的会签不会重复出现</p>
    </div>
  </div>
  <section class="panel">
    <el-tabs v-model="tab" @tab-change="run(load)"
      ><el-tab-pane name="my-tasks" label="我的待办" /><el-tab-pane
        name="flows"
        label="流程记录（最近200条）"
    /></el-tabs>
    <div class="toolbar">
      <el-select
        v-model="kind"
        placeholder="全部业务"
        clearable
        aria-label="审批业务类型"
        ><el-option value="borrow" label="借阅" /><el-option
          value="destroy"
          label="销毁" /></el-select
      ><el-button :loading="busy" @click="run(load)">查询</el-button>
    </div>
    <DataTable
      :rows="rows"
      :loading="busy"
      :columns="[
        { key: 'title', label: '申请标题', width: 260 },
        { key: 'biz_type', label: '业务类型' },
        { key: 'applicant_name', label: '申请人' },
        {
          key: tab === 'my-tasks' ? 'current_node_name' : 'status',
          label: tab === 'my-tasks' ? '当前环节' : '状态',
        },
        { key: 'created_at', label: '发起时间', width: 180 },
      ]"
      ><template #default="{ row }"
        ><el-button type="primary" link @click="flowId = row.flow_id"
          >查看流程</el-button
        ></template
      ></DataTable
    >
  </section>
  <el-drawer
    :model-value="flowId !== null"
    title="审批详情"
    size="min(850px, 100%)"
    destroy-on-close
    @close="flowId = null"
    ><FlowPanel
      v-if="flowId"
      :key="flowId"
      :flow-id="flowId"
      @changed="run(load)"
  /></el-drawer>
</template>
