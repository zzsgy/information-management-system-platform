<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { api, can, query, session } from "../lib/api";
import { confirmAction, useAction } from "../lib/ui";
import { display, type Row } from "../lib/model";
import DataTable from "./DataTable.vue";
/** 从流程编号或业务编号定位流程；两个入口使用同一权限校验。 */
const props = defineProps<{
  flowId?: number;
  bizType?: string;
  bizId?: number;
}>();
/** 审批后通知业务列表刷新，不自行改变业务状态。 */
const emit = defineEmits<{ changed: [] }>();
/** 服务端流程快照和当前意见。 */
const flow = ref<Row | null>(null);
const opinion = ref("");
/** 当前审批提交状态。 */
const { busy, run } = useAction();
/** 严格按用户编号判断会签完成情况，姓名重名不影响按钮授权。 */
const actionable = computed(
  () =>
    flow.value?.status === "pending" &&
    can(`${flow.value.biz_type}.approve`) &&
    flow.value.nodes?.some(
      (node: Row) =>
        node.node_order === flow.value?.current_node &&
        String(node.approver_user_ids)
          .split(",")
          .includes(String(session.user?.user_id)) &&
        !node.acted_user_ids
          ?.map(String)
          .includes(String(session.user?.user_id)),
    ),
);
/** 读取服务端独立流程快照和历史。 */
async function load() {
  flow.value = await api(
    props.flowId
      ? `/approval/flows/${props.flowId}`
      : "/approval/flow-by-biz" +
          query({ biz_type: props.bizType, biz_id: props.bizId }),
  );
}
onMounted(() => run(load));
/** 每个动作都重新通过服务器权限、当前节点与会签校验。 */
async function act(action: string) {
  await run(async () => {
    await confirmAction(`确认${action === "agree" ? "同意" : "驳回"}此申请？`);
    await api(`/approval/flows/${flow.value?.flow_id}/act`, "POST", {
      action,
      opinion: opinion.value,
    });
    opinion.value = "";
    await load();
    emit("changed");
  }, "审批已提交");
}
</script>
<template>
  <section v-loading="busy">
    <template v-if="flow"
      ><h3>
        {{ flow.title }} <el-tag>{{ display(flow.status) }}</el-tag>
      </h3>
      <p class="muted">申请人：{{ flow.applicant_name }}</p>
      <div
        v-for="node in flow.nodes"
        :key="node.id"
        :class="[
          'flow-node',
          { current: node.node_order === flow.current_node },
        ]"
      >
        <b
          >{{ node.node_order }}. {{ node.node_name }} ·
          {{ display(node.node_status) }}</b
        >
        <p>
          审批人：{{ display(node.approver_names) }}
          {{ node.is_countersign ? "（全部会签）" : "（任一人办理）" }}
        </p>
        <p>已办理：{{ display(node.acted_names) }}</p>
      </div>
      <div v-if="actionable" class="panel">
        <el-input
          v-model="opinion"
          type="textarea"
          :maxlength="10000"
          :rows="3"
          placeholder="填写审批意见"
          aria-label="审批意见"
        />
        <div class="action-row">
          <el-button type="primary" :loading="busy" @click="act('agree')"
            >同意申请</el-button
          ><el-button
            type="danger"
            :disabled="busy"
            plain
            @click="act('reject')"
            >驳回申请</el-button
          >
        </div>
      </div>
      <h3>审批记录</h3>
      <DataTable
        :rows="flow.records ?? []"
        :columns="[
          { key: 'operator', label: '办理人' },
          { key: 'action', label: '动作' },
          { key: 'opinion', label: '意见', width: 220 },
          { key: 'acted_at', label: '时间', width: 180 },
        ]" /></template
    ><el-empty v-else-if="!busy" description="暂无可查看的审批流程" />
  </section>
</template>
