<script setup lang="ts">
import Icon from "../preview/PlatformIcon.vue";
import { computed, inject, onMounted, onUnmounted, ref } from "vue";
import { api, mutate, profile, query, type PasswordPromptOptions } from "./api";
import { classifications, uploadArchivePdf } from "./archiveFiles";
import ArchiveReader from "./ArchiveReader.vue";
import ArchiveBoxFields, { type ArchiveBoxForm } from "./ArchiveBoxFields.vue";
import { archiveLocation } from "./archiveChecklist";
import CatalogFields, { type CatalogField } from "./CatalogFields.vue";
import CatalogChangeReview, {
  type CatalogChange,
} from "./CatalogChangeReview.vue";
import DirectoryTemplates from "./DirectoryTemplates.vue";
import EmptyState from "./EmptyState.vue";
const props = defineProps<{ embedded?: boolean }>();
const emit = defineEmits<{ changed: [] }>();
/** 维护列表仅使用当前有权目录的摘要。 */
interface Row {
  /** 服务端唯一标识。 */
  id: string;
  /** 完整档案题名，只用于获权范围。 */
  title: string;
  /** 长期保留的文件编号。 */
  fileNo?: string;
  /** 长期保留的盒号。 */
  boxNo?: string;
  /** 当前业务状态。 */
  state: string;
  /** 并发校验版本，每次提交须匹配。 */
  rowVersion: number;
  /** 所属部门，决定维护范围。 */
  departmentId: string;
  /** 模板允许在维护列表显示的分类字段。 */
  displayFields: { name: string; value: unknown }[];
}
interface Template {
  /** 服务端唯一标识。 */
  id: string;
  /** 中文显示名称。 */
  name: string;
  /** 盒或文件的目录类型。 */
  objectType: string;
  /** 档案分类代码。 */
  categoryCode: string;
  /** 当前版本的字段定义。 */
  fields: CatalogField[];
}
interface Metadata extends Record<string, unknown> {
  /** 服务端唯一标识。 */
  id: string;
  /** 并发校验版本，每次提交须匹配。 */
  rowVersion: number;
  /** 完整档案题名，只用于获权范围。 */
  title: string;
  /** 所属部门，决定维护范围。 */
  departmentId: string;
  /** 责任人，须属于所选部门。 */
  ownerId: string;
  /** 不可变字段模板版本。 */
  templateVersionId: string;
  /** 档案分类代码。 */
  categoryCode: string;
  /** 与固定模板对应的分类字段值。 */
  extensionValues: Record<string, unknown>;
  /** 辅助检索关键词。 */
  keywords: string[];
  /** 解释当前字段值的固定模板。 */
  template: Template;
  /** 尚未结束的事项，存在时禁止覆盖。 */
  pendingChange: { id: string } | null;
  /** 草稿盒撤回确认范围；附件按当前文件计数，历史版本继续保留。 */
  draftDeletion?: {
    draftCount: number;
    attachmentCount: number;
    blockedCount: number;
  };
}
const tab = ref("RECORD"),
  rows = ref<Row[]>([]),
  page = ref(1),
  total = ref(0),
  keyword = ref(""),
  stateFilter = ref(""),
  error = ref(""),
  busy = ref(false),
  progress = ref("");
const selected = ref<Metadata | null>(null),
  draft = ref<Record<string, unknown> & { abstract: string; notes: string }>({
    abstract: "",
    notes: "",
  }),
  extensions = ref<Record<string, unknown>>({}),
  words = ref(""),
  reason = ref(""),
  kind = ref("CORRECTION"),
  pdf = ref<File | null>(null),
  changes = ref<Pick<CatalogChange, "id" | "reason" | "state">[]>([]),
  chosen = ref<CatalogChange | null>(null),
  historyPage = ref(1),
  historyTotal = ref(0);
const options = ref<{
    departments: { id: string; name: string }[];
    templates: Template[];
    fileTypes: string[];
  }>({ departments: [], templates: [], fileTypes: [] }),
  owners = ref<{ id: string; name: string }[]>([]),
  boxes = ref<Row[]>([]),
  retentions = ref<
    {
      id: string;
      objectType: string;
      number: string;
      title: string;
      expiryDate: string;
    }[]
  >([]);
/** 盒维护沿用登记模型，文件目录继续使用自身字段。 */
const boxForm = ref<ArchiveBoxForm>();
const boxFields = ref<InstanceType<typeof ArchiveBoxFields>>();
const editor = ref<HTMLDialogElement | null>(null),
  historyDialog = ref<HTMLDialogElement | null>(null),
  reader = ref<InstanceType<typeof ArchiveReader> | null>(null),
  confirmed = ref(false);
/** 不可变修订摘要与更正历史独立分页，历史记录不授予旧原件读取权限。 */
const versions = ref<
    {
      id: string;
      revisionNo: number;
      pdfVersionNo: number | null;
      createdAt: string;
    }[]
  >([]),
  versionPage = ref(1),
  versionTotal = ref(0);
/** 查询当前维护对象的历史修订摘要，每次由服务端重验部门范围。 */
async function loadVersions() {
  if (!selected.value) return;
  const data = await api<{ items: typeof versions.value; total: number }>(
    `/archive/${currentType.value === "BOX" ? "boxes" : "records"}/${selected.value.id}/revisions?page=${versionPage.value}&pageSize=10`,
  );
  versions.value = data.items;
  versionTotal.value = data.total;
}
const ask =
    inject<
      (title: string, options?: PasswordPromptOptions) => Promise<string | null>
    >("askPassword")!,
  inform = inject<(message: string) => void>("inform")!;
/** 页内异步响应只对当前打开对象生效；离开页面取消后续显示。 */
let alive = true,
  generation = 0;
const currentType = ref("RECORD");
const template = computed(
  () =>
    options.value.templates.find(
      (t) => t.id === draft.value.templateVersionId,
    ) || selected.value?.template,
);
const categoryNames: Record<string, string> = {
  COMMON: "通用档案（所有部门）",
  ADMIN: "行政档案",
  SALES_CONTRACT: "销售合同",
  PURCHASE_CONTRACT: "采购合同",
  PROJECT_CONTRACT: "项目建设合同",
  QUALITY: "质量文件",
  WORK_RECORD: "工作记录",
};
const stateNames: Record<string, string> = {
  DRAFT: "待送审",
  PENDING: "复核中",
  APPLIED: "已生效",
  REJECTED: "已驳回",
  WITHDRAWN: "已撤回",
  PUBLISHED: "已发布",
  PENDING_REVIEW: "入库复核中",
};
const editableKeys = [
  "title",
  "safeTitle",
  "classification",
  "categoryCode",
  "ownerId",
  "retentionCode",
  "archiveDate",
  "formedDate",
  "abstract",
  "notes",
  "templateVersionId",
  "departmentId",
  "fileNo",
  "boxNo",
  "itemNo",
  "boxId",
];
/** 分页请求带序号，忽略切换范围后返回的旧结果。 */
async function load() {
  const gen = ++generation;
  error.value = "";
  try {
    if (tab.value === "RETENTION") {
      const r = await api<{ items: typeof retentions.value; total: number }>(
        `/archive/retention?${query({ page: page.value, pageSize: 20 })}`,
      );
      if (alive && gen === generation) {
        retentions.value = r.items;
        total.value = r.total;
      }
    } else if (["BOX", "RECORD"].includes(tab.value)) {
      const r = await api<{ items: Row[]; total: number }>(
        `/archive/catalog/${tab.value}?${query({ keyword: keyword.value, state: stateFilter.value, page: page.value, pageSize: 20 })}`,
      );
      if (alive && gen === generation) {
        rows.value = r.items;
        total.value = r.total;
      }
    }
  } catch (e) {
    if (gen === generation) error.value = (e as Error).message;
  }
}
/** 切换维护范围时重置页码，避免跨范围沿用选中对象。 */
function switchTab(value: string) {
  tab.value = value;
  page.value = 1;
  rows.value = [];
  load();
}
/** 责任人选项限制为当前选定部门的有效人员。 */
async function getOwners(departmentChanged = false) {
  const departmentId = boxForm.value?.departmentId ?? draft.value.departmentId;
  try {
    owners.value = (
      await api<{ items: typeof owners.value }>(
        `/archive/owners?${query({ departmentId: String(departmentId) })}`,
      )
    ).items;
    if (
      departmentChanged &&
      boxForm.value &&
      !owners.value.some((owner) => owner.id === boxForm.value?.ownerId)
    )
      boxForm.value.ownerId = owners.value[0]?.id ?? "";
  } catch (e) {
    error.value = (e as Error).message;
  }
}
/** 已发布资料通过维护入口准备候选；表单不直接更新原记录。 */
async function open(
  row: Pick<Row, "id">,
  type = tab.value,
  withdrawal = false,
) {
  busy.value = true;
  error.value = "";
  try {
    currentType.value = type;
    const m = await api<Metadata>(`/archive/catalog/${type}/${row.id}`);
    selected.value = m;
    draft.value = {
      ...structuredClone(m),
      abstract: String(m.abstract ?? ""),
      notes: String(m.notes ?? ""),
    };
    extensions.value = structuredClone(m.extensionValues);
    boxForm.value =
      type === "BOX"
        ? {
            title: m.title,
            departmentId: m.departmentId,
            ownerId: m.ownerId,
            classification: String(m.classification),
            carrierType: String(m.carrierType),
            archiveDate: String(m.archiveDate),
            retentionCode: String(m.retentionCode),
            numberMonth: String(m.numberMonth ?? "").slice(0, 7),
            safeTitle: String(m.safeTitle ?? ""),
            notes: String(m.notes ?? ""),
            keywords: m.keywords.join("，"),
            extensionValues: extensions.value,
          }
        : undefined;
    words.value = m.keywords.join("，");
    reason.value = "";
    kind.value = withdrawal ? "WITHDRAWAL" : "CORRECTION";
    pdf.value = null;
    progress.value = "";
    options.value = await api("/archive/catalog-options");
    if (type === "RECORD")
      boxes.value = (await api<{ items: Row[] }>("/archive/boxes")).items;
    await getOwners();
    editor.value?.showModal();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 提交只发送实际改变的字段；普通字段由服务端决定直接生效或转复核。 */
/** 保存绑定本人确认、稳定操作号和原目录版本。 */
async function save() {
  if (!selected.value) return;
  const isDraft = selected.value.state === "DRAFT";
  if (kind.value === "CORRECTION" && boxForm.value) {
    const form = boxForm.value;
    if (form.carrierType !== "ELECTRONIC") {
      if (!(form.extensionValues.paperTags as string[] | undefined)?.length) {
        error.value = "请选择纸质类别：原件或复印件。";
        boxFields.value?.showPaperChoices();
        return;
      }
      if (
        archiveLocation(
          form.extensionValues,
          String(selected.value.location || "未登记"),
        ) === "未登记"
      ) {
        error.value = "请选择存放柜号、列号和层号，并确认位置。";
        return;
      }
    }
    const { keywords, extensionValues, ...fields } = form;
    Object.assign(draft.value, fields);
    extensions.value = extensionValues;
    words.value = keywords;
  }
  const removing = isDraft && kind.value === "WITHDRAWAL";
  if (removing && currentType.value === "BOX") {
    try {
      selected.value = await api<Metadata>(
        `/archive/catalog/BOX/${selected.value.id}`,
      );
      if (
        selected.value.state !== "DRAFT" ||
        selected.value.draftDeletion!.blockedCount > 0
      ) {
        error.value =
          "盒或盒内资料已归档或正在审批，请按原审批流程办理撤下或撤回。";
        return;
      }
    } catch (failure) {
      error.value = (failure as Error).message;
      return;
    }
  }
  const deletionMessage =
    currentType.value === "BOX"
      ? `确定删除草稿盒“${selected.value.title}”吗？将同时撤回盒内 ${selected.value.draftDeletion?.draftCount ?? 0} 条草稿，其中有 ${selected.value.draftDeletion?.attachmentCount ?? 0} 份已上传电子附件。历史记录和附件原件全部保留，不会物理删除文件。`
      : `确定删除文件草稿“${selected.value.title}”吗？历史记录和附件原件全部保留，不会物理删除文件。`;
  const password = await ask(
    removing
      ? deletionMessage
      : isDraft
        ? "确认修改登记草稿"
        : "确认保存目录更正事项",
    removing
      ? {
          heading:
            currentType.value === "BOX"
              ? "再次确认删除草稿盒"
              : "再次确认删除文件草稿",
          confirmLabel: "确认撤回草稿",
          danger: true,
        }
      : undefined,
  );
  if (password === null) return;
  busy.value = true;
  error.value = "";
  try {
    const patch: Record<string, unknown> = {};
    if (kind.value !== "WITHDRAWAL") {
      for (const key of editableKeys)
        if (
          key in draft.value &&
          JSON.stringify(draft.value[key]) !==
            JSON.stringify(selected.value[key])
        )
          patch[key] =
            ["safeTitle", "abstract", "notes", "formedDate"].includes(key) &&
            draft.value[key] === ""
              ? null
              : draft.value[key];
      const keys = words.value
        .split(/[，,；;\n]/)
        .map((x) => x.trim())
        .filter(Boolean);
      if (boxForm.value && !keys.length) keys.push("档案台账");
      if (JSON.stringify(keys) !== JSON.stringify(selected.value.keywords))
        patch.keywords = keys;
      if (
        JSON.stringify(extensions.value) !==
        JSON.stringify(selected.value.extensionValues)
      )
        patch.extensionValues = extensions.value;
    }
    const body: Record<string, unknown> = {
      kind: kind.value,
      changes: patch,
      reason: reason.value,
    };
    if (pdf.value && kind.value !== "WITHDRAWAL") {
      body.uploadSessionId = await uploadArchivePdf(
        selected.value.id,
        pdf.value,
        () => alive,
        (message) => (progress.value = message),
      );
      body.source = "MANUAL_CORRECTION";
    }
    if (isDraft) {
      await mutate(
        `/archive/catalog/${currentType.value}/${selected.value.id}/${removing ? "delete" : "draft"}`,
        removing ? "POST" : "PUT",
        removing
          ? { reason: reason.value }
          : { changes: patch, reason: reason.value },
        selected.value.id,
        currentType.value === "BOX" ? "ARCHIVE_BOX" : "ARCHIVE_RECORD",
        removing ? "DELETE_DRAFT" : "UPDATE_DRAFT",
        password,
        selected.value.rowVersion,
      );
      editor.value?.close();
      inform(
        removing
          ? currentType.value === "BOX"
            ? "草稿盒及盒内草稿已撤回，历史记录和附件原件保留。"
            : "文件草稿已撤回，历史记录和附件原件保留。"
          : "草稿已更新，请重新确认质量后送审。",
      );
      emit("changed");
      if (!props.embedded) await load();
      return;
    }
    const result = await mutate<CatalogChange>(
      `/archive/catalog/${currentType.value}/${selected.value.id}/changes`,
      "POST",
      body,
      selected.value.id,
      currentType.value === "BOX" ? "ARCHIVE_BOX" : "ARCHIVE_RECORD",
      "CREATE_CATALOG_CHANGE",
      password,
      selected.value.rowVersion,
    );
    editor.value?.close();
    inform(
      result.state === "APPLIED"
        ? "普通目录字段已更新并记录历史。"
        : "更正候选已保存，请核对后送审。",
    );
    await history({ id: selected.value.id } as Row, currentType.value);
    chosen.value = result;
    emit("changed");
    if (!props.embedded) await load();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 历史与修订分开分页，打开时重新查询当前权限。 */
async function history(row: Row, type = tab.value) {
  error.value = "";
  currentType.value = type;
  try {
    selected.value = await api<Metadata>(`/archive/catalog/${type}/${row.id}`);
    historyPage.value = 1;
    versionPage.value = 1;
    await loadVersions();
    chosen.value = null;
    await loadHistory();
    historyDialog.value?.showModal();
  } catch (e) {
    error.value = (e as Error).message;
  }
}
/** 更正列表只取状态摘要，完整对比按需读取。 */
async function loadHistory() {
  if (!selected.value) return;
  const result = await api<{ items: typeof changes.value; total: number }>(
    `/archive/catalog/${currentType.value}/${selected.value.id}/changes?${query({ page: historyPage.value, pageSize: 10 })}`,
  );
  changes.value = result.items;
  historyTotal.value = result.total;
}
/** 打开一条事项后才获取完整快照，避免列表为每条记录展开关联查询。 */
async function choose(id: string) {
  try {
    chosen.value = await api<CatalogChange>(`/archive/catalog-changes/${id}`);
    confirmed.value = false;
  } catch (e) {
    error.value = (e as Error).message;
  }
}
/** 更正命令均再次确认本人身份，失败时保留候选供修正。 */
async function action(verb: "quality" | "submit" | "cancel") {
  if (!chosen.value) return;
  const c = chosen.value;
  if (verb === "quality" && !confirmed.value) {
    error.value = "请先逐页检查候选PDF，再勾选质量确认。";
    return;
  }
  const password = await ask(
    {
      quality: "确认候选PDF质量",
      submit: "提交目录复核",
      cancel: "撤回更正事项",
    }[verb],
  );
  if (password === null) return;
  busy.value = true;
  try {
    chosen.value = await mutate(
      `/archive/catalog-changes/${c.id}/${verb}`,
      "POST",
      verb === "quality"
        ? {
            checkedPageCount: c.pageCount,
            confirmed: true,
            notes: "已逐页核对内容、页序、清晰度及附件对应",
          }
        : verb === "cancel"
          ? { reason: "申请人撤回更正事项" }
          : {},
      c.id,
      "ARCHIVE_REVISION",
      {
        quality: "CONFIRM_CATALOG_QUALITY",
        submit: "SUBMIT_CATALOG_CHANGE",
        cancel: "CANCEL_CATALOG_CHANGE",
      }[verb],
      password,
      c.rowVersion,
    );
    confirmed.value = false;
    await loadHistory();
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 候选阅读通过专用复核上下文，不取得正式使用授权。 */
async function inspect() {
  if (chosen.value?.candidatePdfId) {
    historyDialog.value?.close();
    await reader.value?.open(
      {
        reviewContext: {
          type: "CATALOG_CHANGE",
          id: chosen.value.id,
          candidatePdfVersionId: chosen.value.candidatePdfId,
        },
      },
      "更正候选PDF",
    );
  }
}
defineExpose({
  open: (type: string, id: string, withdrawal = false) =>
    open({ id }, type, withdrawal),
});
onMounted(() => {
  if (!props.embedded) void load();
});
onUnmounted(() => {
  alive = false;
  generation++;
});
</script>
<template>
  <section class="catalog-page" :class="{ embedded: props.embedded }">
    <template v-if="!props.embedded">
      <header class="page-heading">
        <div>
          <span class="eyebrow">档案管理</span>
          <h1>目录维护与保管</h1>
          <p class="muted">更正有依据，版本可追溯，保管到期及时提醒。</p>
        </div>
      </header>
      <nav class="module-tabs" aria-label="档案目录分类">
        <button
          v-for="(label, key) in {
            RECORD: '文件目录',
            BOX: '档案盒',
            RETENTION: '保管到期',
            TEMPLATES: '目录模板',
          }"
          :key="key"
          :class="{ active: tab === key }"
          @click="switchTab(key)"
        >
          {{ label }}
        </button>
      </nav>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <section class="panel catalog-list">
        <DirectoryTemplates v-if="tab === 'TEMPLATES'" /><template v-else
          ><form
            v-if="tab !== 'RETENTION'"
            class="list-toolbar"
            @submit.prevent="
              page = 1;
              load();
            "
          >
            <label class="list-filter-field"
              >关键词<input
                v-model="keyword"
                placeholder="按有权目录检索"
                aria-label="目录查询关键词"
                maxlength="100"
            /></label>
            <label class="list-filter-field"
              >档案状态<select v-model="stateFilter">
                <option value="">全部状态</option>
                <option value="DRAFT">草稿</option>
                <option value="PENDING_REVIEW">归档审批中</option>
                <option value="PUBLISHED">已归档</option>
                <option value="WITHDRAWN">已撤回</option>
              </select></label
            ><button class="secondary">查询</button>
          </form>
          <p v-else class="retention-note muted">
            显示已到期及未来30天到期的档案。到期仅提醒，原件和历史版本继续保留。
          </p>
          <table>
            <thead>
              <tr>
                <th>编号</th>
                <th>名称</th>
                <th>{{ tab === "RETENTION" ? "保管到期" : "状态" }}</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody v-if="tab === 'RETENTION'">
              <tr v-for="row in retentions" :key="row.id">
                <td>{{ row.number }}</td>
                <td>{{ row.title }}</td>
                <td>{{ row.expiryDate }}</td>
                <td>
                  <button
                    class="text-button"
                    @click="open(row as unknown as Row, row.objectType)"
                  >
                    维护目录
                  </button>
                </td>
              </tr>
            </tbody>
            <tbody v-else>
              <tr v-for="row in rows" :key="row.id">
                <td>{{ row.fileNo || row.boxNo }}</td>
                <td>
                  {{ row.title
                  }}<small
                    v-for="field in row.displayFields"
                    :key="field.name"
                    class="catalog-extra"
                    >{{ field.name }}：{{
                      typeof field.value === "boolean"
                        ? field.value
                          ? "是"
                          : "否"
                        : field.value
                    }}</small
                  >
                </td>
                <td>{{ stateNames[row.state] || row.state }}</td>
                <td>
                  <button
                    v-if="['DRAFT', 'PUBLISHED'].includes(row.state)"
                    class="text-button"
                    :disabled="busy"
                    @click="open(row)"
                  >
                    {{
                      row.state === "DRAFT" ? "修改 / 删除" : "更正 / 撤下"
                    }}</button
                  ><button class="text-button" @click="history(row)">
                    历史记录
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
          <EmptyState v-if="!total" title="当前范围暂无记录" />
          <footer class="list-footer">
            <button
              type="button"
              class="secondary list-refresh"
              @click="load()"
            >
              刷新列表
            </button>
            <div class="list-pagination">
              <button
                class="secondary"
                :disabled="page <= 1"
                @click="
                  page--;
                  load();
                "
              >
                上一页</button
              ><span>{{ page }}</span
              ><button
                class="secondary"
                :disabled="page * 20 >= total"
                @click="
                  page++;
                  load();
                "
              >
                下一页
              </button>
              <span class="list-total">共 {{ total }} 条</span>
            </div>
          </footer></template
        >
      </section>
    </template>
    <dialog
      ref="editor"
      class="form-dialog flow-dialog catalog-dialog"
      aria-labelledby="catalog-editor-title"
    >
      <form v-if="selected" class="flow-dialog-shell" @submit.prevent="save">
        <header class="flow-dialog-header">
          <span class="flow-dialog-icon"
            ><Icon name="archive" :size="22"
          /></span>
          <h2 id="catalog-editor-title">
            {{ selected.state === "DRAFT" ? "维护登记草稿" : "更正与撤下" }} ·
            {{ selected.title }}
          </h2>
          <button
            type="button"
            class="icon-button"
            aria-label="关闭登记维护"
            @click="editor?.close()"
          >
            ×
          </button>
        </header>
        <div class="flow-dialog-body">
          <section class="flow-dialog-overview">
            <h3>办理事项</h3>
            <p v-if="selected.state === 'DRAFT'" class="muted">
              草稿修改后须重新确认质量。删除草稿盒会一并撤回盒内草稿，历史记录和附件原件保留；已归档资料仍按原审批流程处理。
            </p>
            <p v-if="error" class="form-error" role="alert">{{ error }}</p>
            <p v-if="selected.pendingChange" class="form-error">
              已有未完成事项，请从历史记录继续办理或撤回。
            </p>
            <label class="catalog-kind">
              >选择事项<select v-model="kind">
                <option value="CORRECTION">
                  {{
                    selected.state === "DRAFT"
                      ? "修改登记信息"
                      : "目录或完整PDF更正"
                  }}
                </option>
                <option value="WITHDRAWAL">
                  {{ selected.state === "DRAFT" ? "删除登记草稿" : "申请撤下" }}
                </option>
              </select></label
            >
          </section>
          <ArchiveBoxFields
            v-if="kind === 'CORRECTION' && boxForm"
            :key="selected.id"
            ref="boxFields"
            v-model="boxForm"
            :fields="selected.template.fields"
            :file-types="options.fileTypes"
            :departments="options.departments"
            :owners="owners"
            :location-fallback="String(selected.location ?? '')"
            :lock-department-and-classification="selected.state === 'DRAFT'"
            editing
            @department-change="getOwners(true)"
          >
            <template v-if="embedded" #checklist>
              <div class="register-location-field">
                <span>电子清单</span>
                <span class="muted">{{
                  selected.state === "DRAFT"
                    ? "在档案盒列表查看或补录"
                    : "在档案盒列表查看"
                }}</span>
              </div>
            </template>
          </ArchiveBoxFields>
          <section
            v-else-if="kind === 'CORRECTION'"
            class="flow-dialog-section"
          >
            <h3>登记信息</h3>
            <div class="form-grid catalog-form">
              <label
                >名称<input
                  v-model="draft.title"
                  required
                  maxlength="512" /></label
              ><label
                >安全题名<input
                  v-model="draft.safeTitle"
                  maxlength="512" /></label
              ><label
                >保密等级<select
                  v-model="draft.classification"
                  :disabled="selected.state === 'DRAFT'"
                >
                  <option
                    v-for="(name, key) in classifications"
                    :key="key"
                    :value="key"
                  >
                    {{ name }}
                  </option>
                </select></label
              ><label
                >类别<select v-model="draft.categoryCode">
                  <option
                    v-for="(name, key) in categoryNames"
                    :key="key"
                    :value="key"
                  >
                    {{ name }}
                  </option>
                </select></label
              ><label
                >所属部门<select
                  v-model="draft.departmentId"
                  :disabled="selected.state === 'DRAFT'"
                  @change="getOwners()"
                >
                  <option
                    v-for="d in options.departments"
                    :key="d.id"
                    :value="d.id"
                  >
                    {{ d.name }}
                  </option>
                </select></label
              ><label
                >责任人<select v-model="draft.ownerId">
                  <option v-for="p in owners" :key="p.id" :value="p.id">
                    {{ p.name }}
                  </option>
                </select></label
              ><template
                ><label
                  >文件编号<input
                    v-model="draft.fileNo"
                    :readonly="selected.state === 'DRAFT'"
                    required /></label
                ><label
                  >所属盒<select
                    v-model="draft.boxId"
                    :disabled="selected.state === 'DRAFT'"
                  >
                    <option v-for="b in boxes" :key="b.id" :value="b.id">
                      {{ b.boxNo }} · {{ b.title }}
                    </option>
                  </select></label
                ><label
                  >盒内件号<input
                    v-model.number="draft.itemNo"
                    type="number"
                    min="1"
                    max="1000000" /></label></template
              ><label
                >实际存档日期<input
                  v-model="draft.archiveDate"
                  type="date"
                  required /></label
              ><label
                >形成日期<input v-model="draft.formedDate" type="date" /></label
              ><label
                >保管期限<select v-model="draft.retentionCode">
                  <option value="05">5年</option>
                  <option value="10">10年</option>
                  <option value="30">30年</option>
                  <option value="YJ">永久</option>
                </select></label
              ><label
                >关键词<input
                  v-model="words"
                  required
                  placeholder="使用逗号分隔" /></label
              ><label
                >模板版本<select v-model="draft.templateVersionId">
                  <option :value="selected.templateVersionId">
                    当前：{{ selected.template.name }}
                  </option>
                  <option
                    v-for="t in options.templates.filter(
                      (t) =>
                        t.objectType === currentType &&
                        (t.categoryCode === 'COMMON' ||
                          t.categoryCode === draft.categoryCode) &&
                        t.id !== selected?.templateVersionId,
                    )"
                    :key="t.id"
                    :value="t.id"
                  >
                    {{ t.name }}
                  </option></select
                ><small
                  class="template-hint"
                  v-if="template?.categoryCode === 'COMMON'"
                  >通用台账模板适用于所有部门、所有档案类别，无需随类别更换。</small
                ></label
              ><label class="span-all"
                >摘要<textarea
                  v-model="draft.abstract"
                  rows="2"
                  maxlength="20000"
                /></label
              ><label class="span-all"
                >备注<textarea
                  v-model="draft.notes"
                  rows="2"
                  maxlength="20000"
                /></label
              ><CatalogFields
                v-if="template?.fields.length"
                class="span-all"
                :fields="template.fields"
                v-model="extensions"
              /><label
                v-if="
                  currentType === 'RECORD' &&
                  draft.carrierType !== 'PAPER' &&
                  selected.state !== 'DRAFT'
                "
                class="span-all"
                >更正完整 PDF（可选）<input
                  type="file"
                  accept=".pdf,application/pdf"
                  @change="
                    pdf = ($event.target as HTMLInputElement).files?.[0] || null
                  "
                /><small
                  >候选检测通过后仍须逐页检查并送审。电脑端单次选择上限50
                  MB。</small
                ></label
              >
            </div>
          </section>
          <p v-else class="muted">
            {{
              selected.state === "DRAFT"
                ? "确认后从正常列表移除草稿；草稿盒内的草稿条目会一并撤回。编号、附件原件和历史记录全部保留。"
                : "复核通过后停止正常检索和使用。在途实物借还继续办理，原件和历史证据保留。"
            }}
          </p>
          <section class="flow-dialog-section">
            <h3>办理说明</h3>
            <label
              >办理原因<textarea
                v-model="reason"
                required
                maxlength="2000"
                rows="3"
              />
            </label>
          </section>
          <p v-if="progress" class="muted">{{ progress }}</p>
          <p v-if="error" class="form-error">{{ error }}</p>
        </div>
        <footer class="flow-dialog-footer">
          <button type="button" class="secondary" @click="editor?.close()">
            取消</button
          ><button class="primary" :disabled="busy || !!selected.pendingChange">
            {{
              busy
                ? "处理中…"
                : selected.state === "DRAFT"
                  ? kind === "WITHDRAWAL"
                    ? "确认删除"
                    : "保存修改"
                  : "保存事项"
            }}
          </button>
        </footer>
      </form>
    </dialog>
    <dialog
      ref="historyDialog"
      class="form-dialog flow-dialog catalog-dialog"
      aria-labelledby="catalog-history-title"
    >
      <div class="flow-dialog-shell">
        <header class="flow-dialog-header">
          <span class="flow-dialog-icon"
            ><Icon name="history" :size="22"
          /></span>
          <h2 id="catalog-history-title">更正与撤下历史</h2>
          <button
            type="button"
            class="icon-button"
            aria-label="关闭历史记录"
            @click="historyDialog?.close()"
          >
            ×
          </button>
        </header>
        <div class="flow-dialog-body">
          <p v-if="error" class="form-error">{{ error }}</p>
          <section class="flow-dialog-section">
            <h3>办理记录</h3>
            <table>
              <thead>
                <tr>
                  <th>原因</th>
                  <th>状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="c in changes" :key="c.id">
                  <td>{{ c.reason }}</td>
                  <td>{{ stateNames[c.state] }}</td>
                  <td>
                    <button class="text-button" @click="choose(c.id)">
                      查看
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
            <p v-if="!changes.length" class="empty-state">暂无更正记录。</p>
            <footer class="list-footer">
              <button
                type="button"
                class="secondary list-refresh"
                :disabled="busy"
                @click="loadHistory()"
              >
                刷新列表
              </button>
              <div class="list-pagination">
                <span class="list-total">共 {{ historyTotal }} 条</span>
                <button
                  class="secondary"
                  :disabled="historyPage <= 1"
                  @click="
                    historyPage--;
                    loadHistory();
                  "
                >
                  上一页</button
                ><span>{{ historyPage }}</span
                ><button
                  class="secondary"
                  :disabled="historyPage * 10 >= historyTotal"
                  @click="
                    historyPage++;
                    loadHistory();
                  "
                >
                  下一页
                </button>
              </div>
            </footer>
          </section>
          <section class="flow-dialog-section">
            <h3>目录与 PDF 版本</h3>
            <table>
              <thead>
                <tr>
                  <th>目录修订</th>
                  <th>PDF 版本</th>
                  <th>登记时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="v in versions" :key="v.id">
                  <td>V{{ v.revisionNo }}</td>
                  <td>{{ v.pdfVersionNo ? "V" + v.pdfVersionNo : "—" }}</td>
                  <td>{{ new Date(v.createdAt).toLocaleString("zh-CN") }}</td>
                </tr>
              </tbody>
            </table>
            <footer class="list-footer">
              <button
                type="button"
                class="secondary list-refresh"
                :disabled="busy"
                @click="loadVersions()"
              >
                刷新列表
              </button>
              <div class="list-pagination">
                <span class="list-total">共 {{ versionTotal }} 条</span>
                <button
                  class="secondary"
                  :disabled="versionPage <= 1"
                  @click="
                    versionPage--;
                    loadVersions();
                  "
                >
                  上一页版本</button
                ><span>{{ versionPage }}</span
                ><button
                  class="secondary"
                  :disabled="versionPage * 10 >= versionTotal"
                  @click="
                    versionPage++;
                    loadVersions();
                  "
                >
                  下一页版本
                </button>
              </div>
            </footer>
          </section>
          <section v-if="chosen" class="flow-dialog-section">
            <h3>所选事项</h3>
            <CatalogChangeReview :change="chosen" />
            <label
              v-if="
                (chosen.managedBy || chosen.createdBy) === profile?.user.id &&
                chosen.state === 'DRAFT' &&
                chosen.candidatePdfId &&
                !chosen.qualityConfirmedAt
              "
              class="confirm-check"
            >
              <input v-model="confirmed" type="checkbox" />已完成全部
              {{ chosen.pageCount }} 页质量检查
            </label>
            <p v-if="chosen.state === 'PENDING'" class="muted">
              复核人在流程中心办理，批准前现行目录继续有效。
            </p>
          </section>
        </div>
        <footer class="flow-dialog-footer">
          <button
            type="button"
            class="secondary"
            @click="historyDialog?.close()"
          >
            关闭
          </button>
          <template
            v-if="
              chosen &&
              (chosen.managedBy || chosen.createdBy) === profile?.user.id &&
              ['DRAFT', 'PENDING'].includes(chosen.state)
            "
          >
            <button
              class="secondary"
              :disabled="busy"
              @click="action('cancel')"
            >
              撤回事项
            </button>
            <template v-if="chosen.state === 'DRAFT'">
              <button
                v-if="chosen.candidatePdfId"
                class="secondary"
                @click="inspect"
              >
                逐页检查候选PDF
              </button>
              <button
                v-if="chosen.candidatePdfId && !chosen.qualityConfirmedAt"
                class="secondary"
                :disabled="busy || !confirmed"
                @click="action('quality')"
              >
                确认质量
              </button>
              <button
                class="primary"
                :disabled="
                  busy ||
                  (!!chosen.candidatePdfId && !chosen.qualityConfirmedAt)
                "
                @click="action('submit')"
              >
                提交复核
              </button>
            </template>
          </template>
        </footer>
      </div>
    </dialog>
    <ArchiveReader ref="reader" @failure="error = $event" />
  </section>
</template>
<style scoped>
.catalog-extra {
  display: block;
  color: var(--text-secondary);
  margin-top: 6px;
  overflow-wrap: anywhere;
}
.catalog-list {
  min-width: 0;
  overflow-x: auto;
}
.retention-note {
  padding: 16px 20px;
  margin: 0;
  font-size: 13px;
  border-bottom: 1px solid var(--line);
}
table {
  width: 100%;
  border-collapse: collapse;
}
td,
th {
  padding: 12px 20px;
  border-bottom: 1px solid var(--line);
  text-align: left;
  overflow-wrap: anywhere;
  white-space: normal;
}
td:first-child {
  max-width: 250px;
}
.catalog-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 20px;
  flex-wrap: wrap;
}
.catalog-dialog {
  --flow-dialog-width: 960px;
}
.catalog-form {
  margin: 0;
}
.catalog-dialog label {
  display: grid;
  gap: 7px;
}
.catalog-dialog input,
.catalog-dialog select,
.catalog-dialog textarea {
  min-width: 0;
  width: 100%;
}
.span-all {
  grid-column: 1/-1;
}
.catalog-dialog .confirm-check {
  display: flex;
  align-items: center;
}
.confirm-check input {
  width: auto;
}
.catalog-actions {
  padding: 16px 0;
}
small {
  color: var(--text-secondary);
}
</style>

<style scoped>
dialog.catalog-dialog .catalog-form > label:not(.span-all) {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  margin: 0;
}
.template-hint {
  grid-column: 2;
  color: var(--muted);
  line-height: 1.6;
}
dialog.catalog-dialog
  .catalog-form
  > label:not(.span-all)
  > :is(input, select) {
  height: 42px;
  margin: 0;
}
.catalog-kind {
  margin-top: 12px;
}
@media (max-width: 760px) {
  .catalog-form {
    grid-template-columns: 1fr;
  }
}
</style>
