<script setup lang="ts">
import { computed } from "vue";
/** 审批和历史页面共用前后对比，只展示服务端获权快照。 */
export interface CatalogChange {
  /** 服务端唯一标识。 */
  id: string;
  /** 盒或文件的目录类型。 */
  objectType: string;
  /** 绑定的目录对象。 */
  objectId: string;
  /** 普通编辑、更正或撤下事项类型。 */
  kind: string;
  /** 当前业务状态。 */
  state: string;
  /** 并发校验版本，每次提交须匹配。 */
  rowVersion: number;
  /** 原申请人，用于本人撤回和质量确认。 */
  createdBy: string;
  /** 明确交接后的经办人；为空时由原创建人办理。 */ managedBy?: string | null;
  /** 申请人填写的可追溯原因。 */
  reason: string;
  /** 固定的新 PDF 候选，空值表示未替换原件。 */
  candidatePdfId: string | null;
  /** 服务器解析的 PDF 总页数。 */
  pageCount: number;
  /** 逐页质量确认时间。 */
  qualityConfirmedAt: string | null;
  /** 绑定的独立审批流程。 */
  workflowId: string | null;
  /** 已获权快照的易读名称，不参与版本比较。 */
  beforeDisplay: Record<string, unknown>;
  afterDisplay: Record<string, unknown>;
  /** 整盒更正涉及的固定文件数量。 */
  memberCount: number;
  /** 不可变的更正前快照。 */
  before: Record<string, unknown>;
  /** 不可变的更正后快照。 */
  after: Record<string, unknown>;
}
const props = defineProps<{ change: CatalogChange }>();
const labels: Record<string, string> = {
  title: "名称",
  safeTitle: "安全题名",
  classification: "保密等级",
  departmentId: "所属部门",
  boxId: "所属盒",
  fileNo: "文件编号",
  boxNo: "盒号",
  itemNo: "盒内件号",
  ownerId: "责任人",
  retentionCode: "保管期限",
  archiveDate: "实际存档日期",
  expiryDate: "保管到期日",
  categoryCode: "档案类别",
  templateVersionId: "模板版本",
  extensionValues: "分类专属字段",
  keywords: "关键词",
  abstract: "摘要",
  notes: "备注",
  formedDate: "形成日期",
};
const changed = computed(() =>
  Object.keys(labels).filter(
    (k) =>
      JSON.stringify(props.change.before[k]) !==
      JSON.stringify(props.change.after[k]),
  ),
);
/** 等级与期限采用中文，嵌套字段保留完整值用于逐项核对。 */
function display(value: unknown): string {
  if (value == null || value === "") return "—";
  if (Array.isArray(value)) return value.map(display).join("、");
  if (typeof value === "object")
    return (
      Object.entries(value as Record<string, unknown>)
        .map(([name, item]) => `${name}：${display(item)}`)
        .join("；") || "—"
    );
  if (typeof value === "boolean") return value ? "是" : "否";
  return (
    (
      {
        ADMIN: "行政档案",
        SALES_CONTRACT: "销售合同",
        PURCHASE_CONTRACT: "采购合同",
        PROJECT_CONTRACT: "项目建设合同",
        QUALITY: "质量文件",
        WORK_RECORD: "工作记录",
        GENERAL: "一般",
        IMPORTANT: "重要保密",
        CORE: "核心保密",
        YJ: "永久",
        "05": "5年",
        "10": "10年",
        "30": "30年",
      } as Record<string, string>
    )[String(value)] || String(value)
  );
}
</script>
<template>
  <section class="change-review">
    <p><strong>办理原因：</strong>{{ change.reason }}</p>
    <p v-if="change.kind === 'WITHDRAWAL'">
      复核通过后停止正常检索和使用，原件、历史版本与在途实物借还记录继续保留。
    </p>
    <p v-if="change.objectType === 'BOX' && change.memberCount">
      本次整盒事项包含
      {{ change.memberCount }}
      件文件；部门或密级更正会同步作用于这些文件，原授权需重新核定。
    </p>
    <table v-if="changed.length">
      <thead>
        <tr>
          <th>字段</th>
          <th>更正前</th>
          <th>更正后</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="key in changed" :key="key">
          <th>{{ labels[key] }}</th>
          <td>
            {{ display(change.beforeDisplay?.[key] ?? change.before[key]) }}
          </td>
          <td>
            {{ display(change.afterDisplay?.[key] ?? change.after[key]) }}
          </td>
        </tr>
      </tbody>
    </table>
    <p v-if="change.candidatePdfId">
      完整 PDF 更正：新候选共 {{ change.pageCount }} 页。{{
        change.qualityConfirmedAt
          ? "提交人已登记逐页质量确认。"
          : "尚待逐页质量确认。"
      }}旧版保留，旧批准不会扩展至新版。
    </p>
  </section>
</template>
<style scoped>
.change-review {
  background: var(--surface-muted);
  padding: 16px;
  border-radius: 12px;
  margin: 14px 0;
}
.change-review p {
  line-height: 1.7;
}
.change-review table {
  width: 100%;
  table-layout: fixed;
}
.change-review th,
.change-review td {
  padding: 10px;
  text-align: left;
  vertical-align: top;
  border-bottom: 1px solid var(--line);
  overflow-wrap: anywhere;
  white-space: normal;
  word-break: break-word;
}
.change-review th:first-child {
  width: 120px;
}
</style>
