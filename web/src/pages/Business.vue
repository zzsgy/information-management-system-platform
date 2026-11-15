<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import type { FormInstance } from "element-plus";
import { api, can, query } from "../lib/api";
import { confirmAction, useAction } from "../lib/ui";
import {
  display,
  type Row,
  type Page,
  type Field,
  type Column,
} from "../lib/model";
import DataTable from "../components/DataTable.vue";
import FormDialog from "../components/FormDialog.vue";
import ArchivePicker from "../components/ArchivePicker.vue";
import Fields from "../components/Fields.vue";
import FlowPanel from "../components/FlowPanel.vue";
/** 页面业务类型由固定路由传入，不允许用户传入任意 API 前缀。 */
const props = defineProps<{ kind: "borrow" | "destroy" | "damage" }>();
/** 三种业务的中文语义与服务端路径。 */
const title = computed(
  () =>
    ({ borrow: "借阅管理", destroy: "销毁管理", damage: "异常登记" })[
      props.kind
    ],
);
const path = computed(
  () =>
    ({ borrow: "/borrows", destroy: "/destroys", damage: "/damages" })[
      props.kind
    ],
);
/** 过滤条件、分页结果与当前详情。 */
const filter = reactive({ status: "", page: 1, page_size: 20 });
const result = ref<Page>({ items: [], total: 0 });
const detail = ref<Row | null>(null);
const detailOpen = ref(false);
/** 新申请表单及独立的受控档案选择。 */
const createOpen = ref(false);
const model = ref<Row>({});
const archiveIds = ref<number[]>([]);
const form = ref<FormInstance>();
/** 办理弹窗记录明确动作，销毁额外要求核对整批范围。 */
const action = ref("");
const actionOpen = ref(false);
const checked = ref(false);
/** 同一页面的提交锁。 */
const { busy, run } = useAction();
/** 页面允许发起新申请的精确权限。 */
const mayCreate = computed(() =>
  can(props.kind === "damage" ? "archive.write" : `${props.kind}.apply`),
);
/** 新申请字段；档案主键由专门控件选取。 */
const fields = computed<Field[]>(() =>
  props.kind === "borrow"
    ? [
        {
          key: "borrow_mode",
          label: "借阅形式",
          type: "select",
          required: true,
          options: ["physical", "electronic", "both"].map((value) => ({
            value,
            label: display(value),
          })),
        },
        {
          key: "expected_return_date",
          label: "预计归还日期",
          type: "date",
          required: true,
        },
        { key: "purpose", label: "借阅用途", type: "textarea", required: true },
      ]
    : props.kind === "destroy"
      ? [
          {
            key: "reason",
            label: "销毁原因",
            type: "textarea",
            required: true,
          },
          { key: "scope_desc", label: "范围说明", type: "textarea" },
        ]
      : [
          {
            key: "damage_type",
            label: "异常类型",
            required: true,
            help: "例如：破损、遗失、附件缺失或无法读取",
          },
          {
            key: "damage_desc",
            label: "异常描述",
            type: "textarea",
            required: true,
          },
        ],
);
/** 根据真实状态机提供可选下一状态，关闭后的记录不能再修改。 */
const actionFields = computed<Field[]>(() =>
  action.value === "return"
    ? [
        {
          key: "return_condition",
          label: "归还情况",
          type: "textarea",
          required: true,
          length: 500,
        },
      ]
    : action.value === "execute"
      ? [
          { key: "witness", label: "见证人", required: true },
          { key: "method", label: "销毁方法", required: true },
        ]
      : [
          {
            key: "status",
            label: "处理后状态",
            type: "select",
            required: true,
            options: (detail.value?.status === "resolved"
              ? ["closed"]
              : ["processing", "resolved"]
            ).map((value) => ({ value, label: display(value) })),
          },
          {
            key: "handling_method",
            label: "处理方式",
            required: true,
            length: 100,
          },
          {
            key: "handling_result",
            label: "处理结果",
            type: "textarea",
            required: true,
          },
        ],
);
/** 列表展示对应的业务主体，不使用裸 JSON 编辑。 */
const columns = computed<Column[]>(() =>
  props.kind === "borrow"
    ? [
        { key: "borrow_no", label: "借阅编号", width: 200 },
        { key: "archive_names", label: "申请档案", width: 230 },
        { key: "applicant_name", label: "申请人" },
        { key: "expected_return_date", label: "预计归还" },
        { key: "status", label: "状态" },
      ]
    : props.kind === "destroy"
      ? [
          { key: "id", label: "申请号" },
          { key: "reason", label: "销毁原因", width: 260 },
          { key: "applicant_name", label: "申请人" },
          { key: "status", label: "状态" },
        ]
      : [
          { key: "archive_name", label: "档案名称", width: 220 },
          { key: "damage_type", label: "异常类型" },
          { key: "damage_desc", label: "异常描述", width: 260 },
          { key: "status", label: "状态" },
        ],
);
/** 各页面可用状态筛选项。 */
const statuses = computed(() =>
  props.kind === "damage"
    ? ["reported", "processing", "resolved", "closed"]
    : props.kind === "borrow"
      ? ["pending", "approved", "rejected", "borrowed", "returned"]
      : ["pending", "approved", "rejected", "executed"],
);
/** 服务端过滤后的当前页。 */
async function load() {
  result.value = await api<Page>(path.value + query(filter));
}
onMounted(() => run(load));
/** 获取完整业务明细，执行销毁只使用这里的原批准范围。 */
async function show(row: Row) {
  await run(async () => {
    detail.value = await api(`${path.value}/${row.id}`);
    checked.value = false;
    detailOpen.value = true;
  });
}
/** 新建时复位旧表单，不能遗留上次选择的档案。 */
function create() {
  model.value = props.kind === "borrow" ? { borrow_mode: "physical" } : {};
  archiveIds.value = [];
  createOpen.value = true;
}
/** 先完成本地必填校验，再提交实际档案编号。 */
async function submit() {
  if (!(await form.value?.validate().catch(() => false))) return;
  await run(async () => {
    if (!archiveIds.value.length) throw new Error("请至少选择一份档案");
    if (props.kind === "damage" && archiveIds.value.length !== 1)
      throw new Error("一次异常登记请选择一份档案");
    await api(path.value, "POST", {
      ...model.value,
      ...(props.kind === "damage"
        ? { archive_id: archiveIds.value[0] }
        : { archive_ids: archiveIds.value }),
    });
    createOpen.value = false;
    await load();
  }, "申请已提交");
}
/** 办理借出只适用于批准单，后端核验是否存在并发借出或销毁冻结。 */
async function checkout() {
  await run(async () => {
    await confirmAction("确认已核对申请中的全部档案并办理借出？");
    await api(`/borrows/${detail.value?.id}/checkout`, "POST");
    await refreshDetail();
  }, "借出已登记");
}
/** 在审批和办理后刷新详情及列表，不在前端猜测状态。 */
async function refreshDetail() {
  if (detail.value)
    detail.value = await api(`${path.value}/${detail.value.id}`);
  await load();
}
/** 显式动作表单开关，只有选定销毁范围后才允许开启执行。 */
function openAction(value: string) {
  action.value = value;
  actionOpen.value = true;
}
/** 执行销毁使用批准清单的完整ID集合；禁止从分页列表拼接或自动扩大范围。 */
async function perform(value: Row) {
  await run(async () => {
    if (action.value === "execute") {
      if (!checked.value || !detail.value?.archive_ids?.length)
        throw new Error("请先核对并确认批准范围");
      await confirmAction(
        `确定执行这 ${detail.value.archive_ids.length} 份档案的销毁登记？请确认实际处置和见证记录。`,
      );
      value.archive_ids = [...detail.value.archive_ids];
    }
    await api(
      `${path.value}/${detail.value?.id}/${action.value}`,
      "POST",
      value,
    );
    actionOpen.value = false;
    await refreshDetail();
  }, "办理结果已记录");
}
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>{{ title }}</h1>
      <p>
        {{
          kind === "destroy"
            ? "按批准范围办理销毁，保存完整审批与执行记录"
            : kind === "borrow"
              ? "申请、审批、借出与归还分别留痕"
              : "登记异常、记录处理过程并跟踪结果"
        }}
      </p>
    </div>
    <el-button
      v-if="mayCreate"
      type="primary"
      :disabled="busy"
      @click="create"
      >{{ kind === "damage" ? "登记异常" : "新增申请" }}</el-button
    >
  </div>
  <section class="panel">
    <div class="toolbar">
      <el-select
        v-model="filter.status"
        placeholder="全部状态"
        clearable
        aria-label="业务状态"
        ><el-option
          v-for="value in statuses"
          :key="value"
          :value="value"
          :label="display(value)" /></el-select
      ><el-button
        :loading="busy"
        @click="
          filter.page = 1;
          run(load);
        "
        >查询</el-button
      >
    </div>
    <DataTable :rows="result.items" :loading="busy" :columns="columns"
      ><template #default="{ row }"
        ><el-button type="primary" link :disabled="busy" @click="show(row)"
          >查看办理</el-button
        ></template
      ></DataTable
    ><el-pagination
      :disabled="busy"
      class="pagination"
      v-model:current-page="filter.page"
      :total="result.total"
      :page-size="20"
      layout="total, prev, pager, next"
      @current-change="run(load)"
    />
  </section>
  <el-dialog
    v-model="createOpen"
    :title="kind === 'damage' ? '登记异常' : '新增申请'"
    width="780px"
    :close-on-click-modal="false"
    :show-close="!busy"
    ><el-form ref="form" :model="model" label-position="top"
      ><el-form-item label="关联档案" required
        ><ArchivePicker v-model="archiveIds" /></el-form-item
      ><Fields :fields="fields" :model="model" /></el-form
    ><template #footer
      ><el-button :disabled="busy" @click="createOpen = false">取消</el-button
      ><el-button type="primary" :loading="busy" @click="submit"
        >提交申请</el-button
      ></template
    ></el-dialog
  >
  <el-drawer
    v-model="detailOpen"
    :title="title + ' · 详情'"
    size="min(900px, 100%)"
    destroy-on-close
    ><template v-if="detail"
      ><p>
        状态：<el-tag>{{ display(detail.status) }}</el-tag>
      </p>
      <dl class="detail-grid">
        <div
          v-for="column in columns.filter((item) => item.key !== 'status')"
          :key="column.key"
        >
          <dt>{{ column.label }}</dt>
          <dd>{{ display(detail[column.key]) }}</dd>
        </div>
        <div v-if="kind === 'borrow'">
          <dt>借阅形式与用途</dt>
          <dd>{{ display(detail.borrow_mode) }} · {{ detail.purpose }}</dd>
        </div>
        <div v-if="kind === 'destroy'">
          <dt>范围说明</dt>
          <dd>{{ display(detail.scope_desc) }}</dd>
        </div>
      </dl>
      <DataTable
        v-if="kind !== 'damage'"
        :rows="detail.archives ?? detail.details ?? []"
        :columns="[
          { key: 'archive_no', label: '档案编号', width: 200 },
          { key: 'archive_name', label: '申请档案', width: 260 },
        ]"
      />
      <div class="action-row" v-if="kind === 'borrow' && can('borrow.approve')">
        <el-button
          v-if="
            detail.status === 'approved' && detail.borrow_mode !== 'electronic'
          "
          type="primary"
          :loading="busy"
          @click="checkout"
          >办理借出</el-button
        ><el-button
          v-if="
            detail.status === 'borrowed' ||
            (detail.status === 'approved' &&
              detail.borrow_mode === 'electronic')
          "
          type="primary"
          :disabled="busy"
          @click="openAction('return')"
          >办理归还</el-button
        >
      </div>
      <div
        v-if="
          kind === 'destroy' &&
          detail.status === 'approved' &&
          can('destroy.execute')
        "
        class="panel"
      >
        <el-alert
          title="执行范围必须与上述批准清单完全一致"
          type="warning"
          :closable="false"
        /><el-checkbox v-model="checked"
          >我已核对上述全部
          {{ detail.archive_ids?.length }} 份档案及批准范围</el-checkbox
        >
        <div class="action-row">
          <el-button
            type="danger"
            :disabled="!checked || busy"
            @click="openAction('execute')"
            >登记销毁执行</el-button
          >
        </div>
      </div>
      <div v-if="kind === 'damage'">
        <el-button
          v-if="detail.status !== 'closed' && can('archive.write')"
          type="primary"
          :disabled="busy"
          @click="openAction('handle')"
          >登记处理结果</el-button
        >
        <h3 class="action-row">处理历史</h3>
        <DataTable
          :rows="detail.history ?? []"
          :columns="[
            { key: 'handler_name', label: '处理人' },
            { key: 'handling_method', label: '处理方式' },
            { key: 'handling_result', label: '处理结果', width: 250 },
            { key: 'status', label: '状态' },
          ]"
        />
      </div>
      <FlowPanel
        v-else-if="can('approval.todo.view')"
        :key="`${kind}:${detail.id}`"
        :biz-type="kind"
        :biz-id="detail.id"
        @changed="run(refreshDetail)"
      /> </template
  ></el-drawer>
  <FormDialog
    v-model="actionOpen"
    :title="
      action === 'return'
        ? '办理归还'
        : action === 'execute'
          ? '登记销毁执行'
          : '登记处理结果'
    "
    :fields="actionFields"
    :busy="busy"
    @save="perform"
  />
</template>
