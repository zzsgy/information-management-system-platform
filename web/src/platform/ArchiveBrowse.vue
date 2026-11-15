<script setup lang="ts">
import EmptyState from "./EmptyState.vue";
import { computed, inject, onMounted, onUnmounted, reactive, ref } from "vue";
import Icon from "../preview/PlatformIcon.vue";
import { api, can, COLLECTION, mutate, profile, query } from "./api";
import {
  archiveStates,
  classifications,
  uploadArchivePdf,
  type ArchiveItem,
  type ArchiveRecord,
} from "./archiveFiles";
import CatalogFields, { type CatalogField } from "./CatalogFields.vue";
import ArchiveReader from "./ArchiveReader.vue";
import ArchiveExports from "./ArchiveExports.vue";
import ArchiveBorrowDialog from "./ArchiveBorrowDialog.vue";
import Catalog from "./Catalog.vue";
import Imports from "./Imports.vue";

/** 目录选择仅包含必要业务标识和名称。 */
interface Option {
  /** 稳定标识。 */ id: string;
  /** 显示名称。 */ name: string;
  /** 业务代码。 */ code?: string;
}
interface Template extends Option {
  /** 固定模板的扩展定义。 */ fields: CatalogField[];
  /** 适用类别。 */ categoryCode: string;
  /** 盒或文件。 */ objectType: string;
}
interface BorrowableBox {
  id: string;
  label: string;
  boxNo: string | null;
  electronic: boolean;
  paper: boolean;
  /** 整盒或其中一件纸质原件已占用。 */ paperOccupied?: boolean;
}
interface Box {
  /** 盒的真实载体约束件登记。 */ carrierType: string;
  /** 盒标识。 */ id: string;
  /** 公司盒号。 */ boxNo: string;
  /** 盒名称。 */ title: string;
  /** 部门。 */ departmentId: string;
  /** 状态。 */ state: string;
  /** 并发版本。 */ rowVersion: number;
  /** 待审修订。 */ candidateRevisionId: string;
}
/** 身份再验证只在用户确认关键操作时取得，口令不进入页面缓存。 */
const askPassword =
    inject<(title: string) => Promise<string | null>>("askPassword")!,
  inform = inject<(message: string) => void>("inform")!;
/** 按当前层分页，安全题名与完整目录使用同一服务端授权结果。 */
const items = ref<ArchiveItem[]>([]),
  boxes = ref<Box[]>([]),
  borrowableBoxes = ref<BorrowableBox[]>([]),
  keyword = ref(
    new URLSearchParams(location.hash.split("?")[1] || "")
      .get("keyword")
      ?.slice(0, 100) || "",
  ),
  state = ref(""),
  page = ref(1),
  total = ref(0),
  loading = ref(false),
  error = ref(""),
  busy = ref(false),
  progress = ref("");
const listTab = ref("RECORD"),
  boxPage = ref(1),
  boxTotal = ref(0),
  boxKeyword = ref("");
const boxOptions = ref<Box[]>([]),
  boxOptionKeyword = ref("");
const maintenance = ref<InstanceType<typeof Catalog> | null>(null);
const importDialog = ref<HTMLDialogElement | null>(null),
  importOpen = ref(false);
const selected = ref<string[]>([]),
  reader = ref<InstanceType<typeof ArchiveReader> | null>(null),
  fileInput = ref<HTMLInputElement | null>(null),
  uploadTarget = ref<ArchiveRecord | null>(null);
/** 管理表单的当前选项，普通员工不请求目录管理数据。 */
const departments = ref<Option[]>([]),
  templates = ref<Template[]>([]),
  owners = ref<Option[]>([]),
  ownerKeyword = ref("");
/** 资料状态筛选仅向档案管理角色和平台 IT 展示。 */
const canFilterArchiveState = computed(
  () =>
    profile.value?.roles.some(
      (role) =>
        (role.module === "ARCHIVE" &&
          ["DEPARTMENT_ARCHIVIST", "SUPER_ADMIN"].includes(role.code)) ||
        (role.module === "PLATFORM" && role.code === "IT_ADMIN"),
    ) ?? false,
);
const canManage = computed(() => can("ARCHIVE.CATALOG_MANAGE")),
  canConfigure = computed(() => can("ARCHIVE.CONFIG_MANAGE"));
/** 所有日期按中国公司时区呈现，独立保留原存档日和编号月份。 */
const today = new Intl.DateTimeFormat("en-CA", {
  timeZone: "Asia/Shanghai",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
}).format(new Date());
const entryDialog = ref<HTMLDialogElement | null>(null),
  qualityDialog = ref<HTMLDialogElement | null>(null),
  policyDialog = ref<HTMLDialogElement | null>(null);
const entry = reactive({
  /** 纸质/混合登记必须有实际库位。 */ carrierType: "ELECTRONIC",
  location: "",
  kind: "BOX",
  departmentId: "",
  boxId: "",
  title: "",
  safeTitle: "",
  classification: "GENERAL",
  retentionCode: "10",
  archiveDate: today,
  numberMonth: today.slice(0, 7),
  categoryCode: "COMMON",
  itemNo: 1,
  ownerId: "",
  templateVersionId: "",
  extensionValues: {} as Record<string, unknown>,
  keywords: "",
});
/** 人工质量责任确认必须由用户主动勾选，不能在渲染完成时自动填写。 */
const qualityTarget = ref<{
    type: "BOX" | "RECORD";
    id: string;
    name: string;
    revision: string;
    pdf: string | null;
    pages: number;
    version: number;
  } | null>(null),
  qualityConfirmed = ref(false),
  qualityNotes = ref("");
const borrowDialog = ref<InstanceType<typeof ArchiveBorrowDialog> | null>(null),
  exportsPanel = ref<InstanceType<typeof ArchiveExports> | null>(null);
const policyTarget = ref<ArchiveRecord | null>(null),
  audiences = ref<Option[]>([]),
  policyDepartments = ref<string[]>([]),
  safeSearch = ref(true),
  ownerReading = ref(false),
  policyReason = ref(""),
  policyEnd = ref(""),
  policyPermanent = ref(false);
/** 人员名单只保存稳定ID，提交后仍需真实必要节点批准且必须有截止时间。 */
const policyUsers = ref<string[]>([]),
  policySubjectKeyword = ref(""),
  policySubjects = ref<{ id: string; name: string; employeeNo: string }[]>([]);
/** 跨检索结果保留已选人员名称，提交前能核对和移除完整名单。 */
const policySubjectNames = ref<Record<string, string>>({});
/** 取消或切页后旧响应不会覆盖新表格；组件销毁后停止后续自动挂接。 */
let generation = 0,
  ownerGeneration = 0,
  alive = true;
const categoryNames: Record<string, string> = {
  COMMON: "通用档案（所有部门）",
  ADMIN: "行政档案",
  SALES_CONTRACT: "销售合同",
  PURCHASE_CONTRACT: "采购合同",
  PROJECT_CONTRACT: "项目合同",
  QUALITY: "质量档案",
  WORK_RECORD: "工作记录",
};
const matchingTemplates = computed(() =>
  templates.value.filter(
    (item) =>
      item.objectType === entry.kind &&
      (item.categoryCode === "COMMON" ||
        item.categoryCode === entry.categoryCode),
  ),
);
/** 取得服务端投影中的稳定对象标识，安全题名表示不要求正文元数据。 */
function id(item: ArchiveItem) {
  return item.record?.id || item.summary!.objectId;
}
/** 严格使用获准题名，不由浏览器补查隐藏字段。 */
function name(item: ArchiveItem) {
  return item.record?.title || item.summary!.safeTitle;
}
/** 只有本页可见且已选择的固定版本才能交给副本对话框。 */
const selectedRecords = computed(() =>
  items.value
    .filter((item) => selected.value.includes(id(item)) && item.record)
    .map((item) => item.record!),
);
/** 页面不根据管理角色自作主张开放正文，canView只来自服务端。 */
function canBorrowRecord(item: ArchiveItem) {
  const paper = can("ARCHIVE.PHYSICAL_BORROW");
  if (item.record)
    return (
      item.record.state === "PUBLISHED" &&
      (item.record.currentPdfVersionId ||
        (paper && item.record.carrierType !== "ELECTRONIC" && !item.record.paperOccupied))
    );
  return item.summary?.canApply || (paper && item.summary?.canBorrowPaper);
}
function summaryLabel(item: ArchiveItem) {
  const { canApply, canBorrowPaper, paperOccupied } = item.summary!;
  if (paperOccupied) return canApply ? "可申请电子使用 · 纸质已借出" : "纸质已借出";
  if (canBorrowPaper) {
    return canApply ? "已批准检索 · 电子+纸质资料" : "已批准检索 · 纸质资料";
  }
  return canApply ? "已批准检索 · 可申请电子使用" : "已批准检索";
}
function readable(item: ArchiveItem) {
  return item.record?.canView === true;
}
/** 按当前筛选分页加载；请求代次防止旧响应覆盖新搜索。 */
async function load(activity = true) {
  const current = ++generation;
  loading.value = true;
  error.value = "";
  try {
    if (listTab.value === "BOX" && !canManage.value) {
      const result = await api<{ items: BorrowableBox[]; total: number }>(
        `/archive/borrowable-boxes?${query({ keyword: boxKeyword.value, page: boxPage.value, pageSize: 20 })}`,
        {},
        activity,
      );
      if (current !== generation || !alive) return;
      borrowableBoxes.value = result.items;
      boxTotal.value = result.total;
      return;
    }
    const data = await api<{ items: ArchiveItem[]; total: number }>(
      `/archive/records?${query({ keyword: keyword.value, classification: classificationFilter.value, state: state.value, page: page.value, pageSize: 20 })}`,
      {},
      activity,
    );
    if (current !== generation || !alive) return;
    items.value = data.items;
    total.value = data.total;
    selected.value = [];
    if (canManage.value) {
      const result = await api<{ items: Box[]; total: number }>(
        `/archive/boxes?${query({ keyword: boxKeyword.value, state: state.value, page: boxPage.value, pageSize: 20 })}`,
        {},
        activity,
      );
      if (current !== generation || !alive) return;
      boxes.value = result.items;
      boxTotal.value = result.total;
    }
  } catch (failure) {
    if (current === generation) error.value = (failure as Error).message;
  } finally {
    if (current === generation) loading.value = false;
  }
}
/** 普通查看绑定服务端返回的当前批准修订与PDF。 */
async function read(item: ArchiveItem, unwatermarked = false) {
  const record = item.record;
  if (!record) return;
  await reader.value?.open(
    {
      recordRevisionId: record.currentRevisionId,
      pdfVersionId: record.currentPdfVersionId,
      ...(unwatermarked ? { unwatermarked: true } : {}),
    },
    unwatermarked ? record.title + " · 无水印" : record.title,
  );
}
/** 阅读失效后重查目录投影，不能继续显示过期来源留下的正文按钮或完整题名。 */
async function readerFailure(message: string) {
  if (!alive) return;
  await load(false);
  if (alive) error.value = message + "，档案列表已按最新权限刷新。";
}
/** 待审检查绑定当前整理或复核上下文，不能混用普通借阅版本。 */
async function inspect(record: ArchiveRecord) {
  if (!record.candidatePdfVersionId) return;
  await reader.value?.open(
    {
      reviewContext: {
        type: record.state === "DRAFT" ? "SCAN_QUALITY" : "REGISTRATION",
        id: record.state === "DRAFT" ? record.id : record.reviewContextId,
        candidatePdfVersionId: record.candidatePdfVersionId,
      },
    },
    record.title,
  );
}
/** 管理选项经过领域范围过滤；创建人默认不是业务责任人，表单仍须明确选择。 */
async function syncEntry() {
  if (entry.kind === "RECORD") {
    const box = boxOptions.value.find((item) => item.id === entry.boxId);
    if (box) {
      entry.departmentId = box.departmentId;
      // 单一载体盒默认采用同一载体，混合盒保留经办人的选择。
      if (box.carrierType !== "MIXED") entry.carrierType = box.carrierType;
    }
  }
  if (
    !matchingTemplates.value.some((item) => item.id === entry.templateVersionId)
  )
    entry.templateVersionId = matchingTemplates.value[0]?.id || "";
  await loadOwners();
}
/** 仅检索当前可维护部门的有效责任人，避免开放全公司人员资料。 */
async function loadOwners() {
  if (!entry.departmentId) return;
  const current = ++ownerGeneration;
  try {
    const data = await api<{ items: Option[] }>(
      `/archive/owners?${query({ departmentId: entry.departmentId, keyword: ownerKeyword.value })}`,
    );
    if (current !== ownerGeneration || !alive) return;
    owners.value = data.items;
    if (!owners.value.some((item) => item.id === entry.ownerId))
      entry.ownerId =
        owners.value.find((item) => item.id === profile.value?.user.id)?.id ||
        owners.value[0]?.id ||
        "";
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
/** 新建盒件时重置登记表单，并按对象类型匹配基础模板。 */
async function newEntry(kind: string) {
  error.value = "";
  try {
    const options = await api<{ departments: Option[]; templates: Template[] }>(
      "/archive/catalog-options",
    );
    departments.value = options.departments;
    templates.value = options.templates;
    boxOptionKeyword.value = "";
    await loadBoxOptions();
    Object.assign(entry, {
      categoryCode: "COMMON",
      carrierType: "ELECTRONIC",
      location: "",
      kind,
      departmentId:
        departments.value.find(
          (item) => item.id === profile.value?.user.departmentId,
        )?.id ||
        departments.value[0]?.id ||
        "",
      boxId:
        boxOptions.value.find((item) =>
          ["DRAFT", "PUBLISHED"].includes(item.state),
        )?.id || "",
      title: "",
      safeTitle: "",
      itemNo: 1,
      ownerId: "",
      templateVersionId: "",
      extensionValues: {},
      keywords: "",
    });
    await syncEntry();
    entryDialog.value?.showModal();
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
async function loadBoxOptions() {
  boxOptions.value = (
    await api<{ items: Box[] }>(
      `/archive/boxes?${query({ keyword: boxOptionKeyword.value, pageSize: 100 })}`,
    )
  ).items;
}
function openImport() {
  entryDialog.value?.close();
  importOpen.value = true;
  importDialog.value?.showModal();
}
function closeImport() {
  importDialog.value?.close();
  importOpen.value = false;
  void load();
}
/** 创建固定基础模板不接受任意扩展字段，配置动作仍需本人再验证。 */
async function createTemplate() {
  const password = await askPassword("创建当前类别的基础目录模板");
  if (password === null) return;
  try {
    const template = await mutate<Template>(
      "/admin/archive/directory-templates",
      "POST",
      {
        templateKey: `${entry.categoryCode}_${entry.kind}_${crypto.randomUUID()}`,
        name: `${categoryNames[entry.categoryCode]}${entry.kind === "BOX" ? "盒" : "文件"}基础模板`,
        categoryCode: entry.categoryCode,
        objectType: entry.kind,
        fields: [],
      },
      COLLECTION,
      "ARCHIVE_COLLECTION",
      "CREATE_DIRECTORY_TEMPLATE",
      password,
    );
    templates.value.push(template);
    entry.templateVersionId = template.id;
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
/** 提交固定目录字段，编号和盒件约束由服务端最终分配与校验。 */
async function saveEntry() {
  const password = await askPassword(
    entry.kind === "BOX" ? "登记档案盒草稿" : "登记盒内文件草稿",
  );
  if (password === null) return;
  busy.value = true;
  try {
    const common = {
      title: entry.title,
      safeTitle: entry.safeTitle || undefined,
      categoryCode: entry.categoryCode,
      carrierType: entry.carrierType,
      ...(entry.carrierType === "ELECTRONIC"
        ? {}
        : { location: entry.location }),
      ownerId: entry.ownerId,
      archiveDate: entry.archiveDate,
      templateVersionId: entry.templateVersionId,
      extensionValues: entry.extensionValues,
      keywords: entry.keywords
        .split(/[，,；;\n]/)
        .map((value) => value.trim())
        .filter(Boolean),
    };
    if (entry.kind === "BOX")
      await mutate(
        "/archive/boxes",
        "POST",
        {
          ...common,
          departmentId: entry.departmentId,
          classification: entry.classification,
          retentionCode: entry.retentionCode,
          numberMonth: `${entry.numberMonth}-01`,
          physicalFileCount: 0,
        },
        COLLECTION,
        "ARCHIVE_COLLECTION",
        "CREATE_BOX",
        password,
      );
    else
      await mutate(
        "/archive/records",
        "POST",
        { ...common, boxId: entry.boxId, itemNo: entry.itemNo },
        entry.boxId,
        "ARCHIVE_BOX",
        "CREATE_RECORD",
        password,
      );
    entryDialog.value?.close();
    inform("已登记草稿，完成质量确认及归档复核后才会发布。");
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 检测成功才挂接PDF，尚未取得质量确认或业务批准。 */
async function upload(event: Event) {
  const input = event.target as HTMLInputElement,
    file = input.files?.[0],
    record = uploadTarget.value;
  input.value = "";
  if (!file || !record) return;
  busy.value = true;
  error.value = "";
  try {
    const uploadId = await uploadArchivePdf(
      record.id,
      file,
      () => alive,
      (message) => (progress.value = message),
    );
    if (!alive) return;
    const password = await askPassword("将检测通过的完整PDF挂接为待检查候选");
    if (password === null) return;
    await mutate(
      `/archive/records/${record.id}/pdf-versions`,
      "POST",
      {
        uploadSessionId: uploadId,
        source: "UPLOAD",
        correctionReason: record.candidatePdfVersionId
          ? "重新提交完整候选PDF"
          : undefined,
      },
      record.id,
      "ARCHIVE_RECORD",
      "BIND_PDF",
      password,
      record.rowVersion,
    );
    inform("候选PDF已挂接，请逐页检查后确认质量。");
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
    progress.value = "";
  }
}
/** 人工确认绑定此件的精确候选修订和PDF页数。 */
function qualityRecord(record: ArchiveRecord) {
  qualityTarget.value = {
    type: "RECORD",
    id: record.id,
    name: record.title,
    revision: record.candidateRevisionId!,
    pdf: record.candidatePdfVersionId!,
    pages: record.candidatePageCount || 0,
    version: record.rowVersion!,
  };
  qualityConfirmed.value = false;
  qualityNotes.value = "";
  qualityDialog.value?.showModal();
}
/** 盒目录检查不冒充PDF检查，盒成员在送审时冻结。 */
function qualityBox(box: Box) {
  qualityTarget.value = {
    type: "BOX",
    id: box.id,
    name: box.title,
    revision: box.candidateRevisionId,
    pdf: null,
    pages: 0,
    version: box.rowVersion,
  };
  qualityConfirmed.value = false;
  qualityNotes.value = "";
  qualityDialog.value?.showModal();
}
/** 本人确认人工检查后才写入质量证明，不能自动勾选通过。 */
async function confirmQuality() {
  const target = qualityTarget.value;
  if (!target || !qualityConfirmed.value) return;
  const password = await askPassword("确认人工检查结论并留存责任记录");
  if (password === null) return;
  busy.value = true;
  try {
    const proof = await mutate<{ id: string }>(
      "/archive/quality-checks",
      "POST",
      {
        objectType: target.type,
        objectId: target.id,
        candidateRevisionId: target.revision,
        candidatePdfVersionId: target.pdf,
        checkedPageCount: target.pages,
        confirmed: true,
        notes: qualityNotes.value,
      },
      target.id,
      target.type === "BOX" ? "ARCHIVE_BOX" : "ARCHIVE_RECORD",
      "CONFIRM_QUALITY",
      password,
    );
    const result = target.type === "BOX" ? await registration(target, proof.id, password) : null;
    qualityDialog.value?.close();
    if (result) inform(result.state === "PUBLISHED" ? "档案盒已归档。" : "档案盒已送独立归档复核。");
    else inform("人工质量确认已保存，可以提交归档。");
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 提交固定对象及实际人工质量证明，按返回结果提示归档或送审。 */
async function registration(
  target: {
    type: string;
    id: string;
    revision: string;
    pdf: string | null;
    version: number;
  },
  proof: string,
  password: string,
) {
  return mutate<{ state: string }>(
    "/archive/registration-reviews",
    "POST",
    {
      objectRefs: [
        {
          objectType: target.type,
          objectId: target.id,
          expectedVersion: target.version,
          candidateRevisionId: target.revision,
          candidatePdfVersionId: target.pdf,
        },
      ],
      qualityEvidenceRef: proof,
    },
    target.id,
    target.type === "BOX" ? "ARCHIVE_BOX" : "ARCHIVE_RECORD",
    "SUBMIT_REGISTRATION",
    password,
  );
}
/** 仅对仍匹配的件候选送审，已变化的候选必须重新检查。 */
async function submitRecord(record: ArchiveRecord) {
  if (!record.qualityCheckId) return;
  const password = await askPassword("提交当前已核对的候选归档");
  if (password === null) return;
  try {
    const result = await registration(
      {
        type: "RECORD",
        id: record.id,
        revision: record.candidateRevisionId!,
        pdf: record.candidatePdfVersionId!,
        version: record.rowVersion!,
      },
      record.qualityCheckId,
      password,
    );
    inform(result.state === "PUBLISHED" ? "文件已归档。" : "已送归档复核，请到流程中心查看进度。");
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
function openBorrow() {
  const targets = items.value
    .filter((item) => selected.value.includes(id(item)))
    .map((item) => ({
      objectType: "RECORD" as const,
      objectId: id(item),
      label: name(item),
    }));
  void borrowDialog.value?.open(targets, true);
}
/** 基础开放先明确安全题名受众及本部门查看范围，再进入真实审批。 */
async function openPolicy(record: ArchiveRecord) {
  policyTarget.value = record;
  safeSearch.value = !!record.safeTitle;
  ownerReading.value = false;
  policyDepartments.value = [record.departmentId];
  policyReason.value = "";
  policyEnd.value = new Date(Date.now() + 30 * 86400000)
    .toISOString()
    .slice(0, 10);
  policyPermanent.value = false;
  policyUsers.value = [];
  policySubjectKeyword.value = "";
  policySubjects.value = [];
  policySubjectNames.value = {};
  try {
    audiences.value = (
      await api<{ items: Option[] }>("/archive/department-options")
    ).items;
    policyDialog.value?.showModal();
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
/** 人员候选按具体文件管理范围检索，查询不会直接赋权。 */
async function findPolicySubjects() {
  if (!policyTarget.value) return;
  try {
    policySubjects.value = (
      await api<{ items: typeof policySubjects.value }>(
        `/archive/access-policy-subjects?${query({ recordId: policyTarget.value.id, keyword: policySubjectKeyword.value })}`,
      )
    ).items;
    for (const person of policySubjects.value)
      policySubjectNames.value[person.id] =
        person.name + " · " + person.employeeNo;
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
/** 只提交当前文件版本的开放申请，不在前端直接创建生效授权。 */
async function proposePolicy() {
  const record = policyTarget.value;
  if (!record) return;
  if (policyUsers.value.length > 50) {
    error.value = "具体人员名单最多50人";
    return;
  }
  if (policyUsers.value.length && policyPermanent.value) {
    error.value = "具体人员查看名单须指定失效日期";
    return;
  }
  const password = await askPassword("提交基础开放申请，审批通过后才生效");
  if (password === null) return;
  try {
    await mutate(
      "/archive/access-policy-requests",
      "POST",
      {
        objectType: "RECORD",
        objectId: record.id,
        departmentScope: policyDepartments.value,
        titleVisibility: safeSearch.value ? "SAFE_TITLE" : "HIDDEN",
        safeSearchFields: safeSearch.value ? ["safeTitle"] : [],
        allowOwnerDepartmentView: ownerReading.value,
        recordRevisionId: record.currentRevisionId,
        pdfVersionId: record.currentPdfVersionId,
        userIds: policyUsers.value,
        validFrom: new Date().toISOString(),
        validUntil: policyPermanent.value
          ? null
          : new Date(`${policyEnd.value}T23:59:59+08:00`).toISOString(),
        reason: policyReason.value,
      },
      record.id,
      "ARCHIVE_POLICY",
      "REQUEST_ACCESS_POLICY",
      password,
    );
    policyDialog.value?.close();
    inform("基础开放申请已送审，尚未改变正文权限。");
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
onMounted(() => void load());
onUnmounted(() => {
  alive = false;
  generation++;
  ownerGeneration++;
});
/** 文件目录的保密级别筛选只匹配可见的完整目录字段。 */
const classificationFilter = ref("");
</script>
<template>
  <section class="archive-heading">
    <div>
      <span class="eyebrow">档案中心</span>
      <h1>档案查询</h1>
      <p class="muted">在授权范围内检索资料，按实际用途申请与查阅。</p>
    </div>
    <div class="archive-heading-actions">
      <button v-if="canManage" class="secondary" @click="openImport">
        <Icon name="upload" :size="16" />批量登记
      </button>
      <button v-if="canManage" class="secondary" @click="newEntry('BOX')">
        登记档案盒</button
      ><button v-if="canManage" class="primary" @click="newEntry('RECORD')">
        登记文件
      </button>
    </div>
  </section>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  <p v-if="busy && progress" class="archive-progress" role="status">
    {{ progress }}
  </p>
  <div class="archive-layout">
    <section
      v-if="canFilterArchiveState"
      class="panel archive-filters"
      aria-label="资料范围筛选"
    >
      <h2>资料范围</h2>
      <button
        :class="{ chosen: !state }"
        @click="
          state = '';
          page = 1;
          boxPage = 1;
          load();
        "
      >
        <Icon name="file" :size="18" />全部获权资料</button
      ><button
        :class="{ chosen: state === 'PUBLISHED' }"
        @click="
          state = 'PUBLISHED';
          page = 1;
          boxPage = 1;
          load();
        "
      >
        已归档资料</button
      ><button
        v-if="canManage"
        :class="{ chosen: state === 'DRAFT' }"
        @click="
          state = 'DRAFT';
          page = 1;
          boxPage = 1;
          load();
        "
      >
        草稿与待检查</button
      ><button
        v-if="canManage"
        :class="{ chosen: state === 'PENDING_REVIEW' }"
        @click="
          state = 'PENDING_REVIEW';
          page = 1;
          boxPage = 1;
          load();
        "
      >
        归档复核中
      </button>
    </section>
    <nav class="archive-list-tabs" aria-label="档案列表类型">
      <button
        :class="{ active: listTab === 'RECORD' }"
        @click="
          listTab = 'RECORD';
          load();
        "
      >
        <Icon name="file" :size="18" />文件目录
      </button>
      <button
        :class="{ active: listTab === 'BOX' }"
        @click="
          listTab = 'BOX';
          load();
        "
      >
        <Icon name="archive" :size="18" />档案盒
      </button>
    </nav>
    <section v-if="canManage && listTab === 'BOX'" class="panel archive-main">
      <div class="archive-query-area">
        <div class="archive-tools">
          <form
            @submit.prevent="
              boxPage = 1;
              load();
            "
          >
            <label class="archive-inline-field list-filter-field"
              >档案盒<input
                v-model="boxKeyword"
                placeholder="按盒名称或盒号搜索"
                maxlength="200" /></label
            ><button class="secondary">搜索</button>
          </form>
        </div>
      </div>
      <div class="archive-table-scroll">
        <table class="archive-table box-table">
          <thead>
            <tr>
              <th>档案盒名称 / 盒号</th>
              <th>档案载体</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="box in boxes" :key="box.id">
              <td>
                <div class="archive-name">
                  <span class="archive-file-icon"
                    ><Icon name="archive" :size="22"
                  /></span>
                  <div>
                    <strong>{{ box.title }}</strong
                    ><small>{{ box.boxNo }}</small>
                  </div>
                </div>
              </td>
              <td>
                {{
                  {
                    ELECTRONIC: "电子",
                    PAPER: "纸质原件",
                    MIXED: "纸质与电子混合",
                  }[box.carrierType] || box.carrierType
                }}
              </td>
              <td>
                <span class="archive-status" :class="box.state.toLowerCase()">{{
                  archiveStates[box.state]
                }}</span>
              </td>
              <td>
                <div class="archive-row-actions">
                  <button
                    v-if="box.state === 'DRAFT'"
                    class="text-button"
                    @click="maintenance?.open('BOX', box.id)"
                  >
                    修改
                  </button>
                  <button
                    v-if="box.state === 'DRAFT'"
                    class="text-button danger-text"
                    @click="maintenance?.open('BOX', box.id, true)"
                  >
                    删除
                  </button>
                  <button
                    v-if="box.state === 'DRAFT'"
                    class="text-button"
                    @click="qualityBox(box)"
                  >
                    确认目录并送审
                  </button>
                  <button
                    v-if="box.state === 'PUBLISHED'"
                    class="text-button"
                    @click="maintenance?.open('BOX', box.id)"
                  >
                    更正
                  </button>
                  <button
                    v-if="box.state === 'PUBLISHED'"
                    class="text-button danger-text"
                    @click="maintenance?.open('BOX', box.id, true)"
                  >
                    申请撤下
                  </button>
                  <span v-if="box.state === 'PENDING_REVIEW'" class="muted"
                    >复核中</span
                  >
                </div>
              </td>
            </tr>
            <tr v-if="!boxes.length">
              <td colspan="4">
                <EmptyState
                  title="暂无符合条件的档案盒"
                  description="可登记档案盒，或通过 Excel 批量登记。"
                />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="list-footer">
        <button
          type="button"
          class="secondary list-refresh"
          :disabled="loading"
          @click="load()"
        >
          刷新列表
        </button>
        <div class="list-pagination">
          <span class="list-total">共 {{ boxTotal }} 个档案盒</span
          ><button
            class="secondary"
            :disabled="boxPage <= 1 || loading"
            @click="
              boxPage--;
              load();
            "
          >
            上一页</button
          ><span
            >{{ boxPage }} / {{ Math.max(1, Math.ceil(boxTotal / 20)) }}</span
          ><button
            class="secondary"
            :disabled="boxPage * 20 >= boxTotal || loading"
            @click="
              boxPage++;
              load();
            "
          >
            下一页
          </button>
        </div>
      </footer>
    </section>
    <section v-else-if="listTab === 'BOX'" class="panel archive-main">
      <div class="archive-query-area">
        <div class="archive-tools">
          <form
            @submit.prevent="
              boxPage = 1;
              load();
            "
          >
            <label class="archive-inline-field list-filter-field"
              >档案盒
              <input
                v-model="boxKeyword"
                maxlength="100"
                placeholder="搜索档案盒名称或编号"
              />
            </label>
            <button class="primary" :disabled="loading">搜索</button>
          </form>
        </div>
      </div>
      <div class="table-scroll" v-if="borrowableBoxes.length">
        <table class="archive-table">
          <thead>
            <tr>
              <th>档案盒</th>
              <th>可借方式</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="box in borrowableBoxes" :key="box.id">
              <td>
                <div class="archive-name">
                  <span class="archive-file-icon"
                    ><Icon name="archive" :size="22"
                  /></span>
                  <div>
                    <strong>{{ box.label }}</strong
                    ><small v-if="box.boxNo">{{ box.boxNo }}</small>
                  </div>
                </div>
              </td>
              <td>
                {{
                  box.paperOccupied ? (box.electronic ? "电子 · 纸质已借出" : "纸质已借出")
                    : box.electronic ? (box.paper ? "电子、纸质" : "电子") : "纸质"
                }}
              </td>
              <td>
                <button
                  v-if="box.electronic || box.paper"
                  class="text-button"
                  @click="
                    borrowDialog?.open([
                      { objectType: 'BOX', objectId: box.id, label: box.label },
                    ])
                  "
                >
                  借阅
                </button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="muted" role="status">
        {{ loading ? "正在加载档案盒…" : "暂无符合条件的可借档案盒。" }}
      </p>
      <footer class="list-footer">
        <div class="list-pagination">
          <span class="list-total">共 {{ boxTotal }} 个档案盒</span>
          <button
            class="secondary"
            :disabled="loading || boxPage <= 1"
            @click="
              boxPage--;
              load();
            "
          >
            上一页
          </button>
          <span>{{ boxPage }}</span>
          <button
            class="secondary"
            :disabled="loading || boxPage * 20 >= boxTotal"
            @click="
              boxPage++;
              load();
            "
          >
            下一页
          </button>
        </div>
      </footer>
    </section>
    <section v-else class="panel archive-main">
      <div class="archive-query-area">
        <div class="archive-tools">
          <form
            @submit.prevent="
              page = 1;
              load();
            "
          >
            <label class="archive-inline-field list-filter-field"
              >档案检索<input
                v-model="keyword"
                maxlength="200"
                placeholder="检索获准目录与安全题名"
                aria-label="检索档案"
            /></label>
            <label class="list-filter-field"
              >保密级别<select v-model="classificationFilter">
                <option value="">全部级别</option>
                <option value="GENERAL">一般</option>
                <option value="IMPORTANT">重要保密</option>
                <option value="CORE">核心保密</option>
              </select></label
            >
            <button class="secondary">搜索</button>
          </form>
          <span class="archive-search-note">正文使用以实际批准为准</span>
        </div>
        <div class="archive-export-toolbar">
          <span class="archive-selection-count"
            >已选择 <strong>{{ selected.length }}</strong> 份资料</span
          >
          <div class="archive-export-actions">
            <button
              class="secondary"
              :disabled="
                !selectedRecords.length ||
                selectedRecords.length !== selected.length ||
                selectedRecords.some((record) => !record.canDownload)
              "
              @click="exportsPanel?.open(selectedRecords, 'DOWNLOAD')"
            >
              受批下载所选</button
            ><button
              class="secondary"
              :disabled="
                !selectedRecords.length ||
                selectedRecords.length !== selected.length ||
                selectedRecords.some((record) => !record.canPrint)
              "
              @click="exportsPanel?.open(selectedRecords, 'PRINT')"
            >
              受批打印所选</button
            ><button class="text-button" @click="exportsPanel?.loadHistory()">
              交付记录
            </button>
          </div>
          <button
            class="primary archive-apply"
            :disabled="!selected.length || busy"
            @click="openBorrow"
          >
            申请电子使用<span v-if="selected.length"> · {{ selected.length }}</span>
          </button>
        </div>
      </div>

      <div class="table-scroll">
        <table class="archive-table">
          <thead>
            <tr>
              <th class="select-column">选择</th>
              <th>档案名称</th>
              <th>等级与状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in items"
              :key="id(item)"
              :data-record-id="id(item)"
            >
              <td>
                <input
                  v-model="selected"
                  type="checkbox"
                  :value="id(item)"
                  :disabled="
                    item.record
                      ? item.record.state !== 'PUBLISHED' ||
                        !item.record.currentPdfVersionId
                      : !item.summary?.canApply
                  "
                  :aria-label="`选择${name(item)}`"
                />
              </td>
              <td>
                <div class="archive-name">
                  <span class="archive-file-icon"
                    ><Icon name="file" :size="22"
                  /></span>
                  <div>
                    <strong>{{ name(item) }}</strong
                    ><small v-if="item.record"
                      >{{ item.record.fileNo }} · {{ item.record.boxNo }}</small
                    ><small v-else>{{ summaryLabel(item) }}</small>
                  </div>
                </div>
              </td>
              <td>
                <span
                  v-if="item.record"
                  class="archive-grade"
                  :class="item.record.classification.toLowerCase()"
                  >{{ classifications[item.record.classification] }}</span
                ><small class="row-state">{{
                  item.record ? archiveStates[item.record.state] : "可申请资料"
                }}</small>
                <small v-if="item.record?.paperOccupied" class="row-state">纸质已借出</small>
              </td>
              <td>
                <div class="archive-row-actions">
                  <button
                    v-if="canBorrowRecord(item)"
                    class="text-button"
                    @click="
                      borrowDialog?.open([
                        {
                          objectType: 'RECORD',
                          objectId: id(item),
                          label: name(item),
                        },
                      ])
                    "
                  >
                    借阅
                  </button>
                  <button
                    v-if="readable(item)"
                    class="text-button"
                    @click="read(item)"
                  >
                    打开阅读</button
                  ><button
                    v-if="item.record?.canDownload"
                    class="text-button"
                    @click="exportsPanel?.open([item.record!], 'DOWNLOAD')"
                  >
                    下载副本</button
                  ><button
                    v-if="item.record?.canPrint"
                    class="text-button"
                    @click="exportsPanel?.open([item.record!], 'PRINT')"
                  >
                    打印副本</button
                  ><button
                    v-if="item.record?.unwatermarkedActions?.includes('VIEW')"
                    class="text-button"
                    @click="read(item, true)"
                  >
                    无水印查看</button
                  ><button
                    v-if="item.record?.unwatermarkedActions?.includes('DOWNLOAD')"
                    class="text-button"
                    @click="exportsPanel?.open([item.record!], 'DOWNLOAD', true)"
                  >
                    无水印下载</button
                  ><button
                    v-if="item.record?.canPrint && item.record.unwatermarkedActions?.includes('PRINT')"
                    class="text-button"
                    @click="exportsPanel?.open([item.record!], 'PRINT', true)"
                  >
                    无水印打印</button
                  ><template
                    v-if="item.representation === 'MANAGE' && item.record"
                    ><button
                      v-if="
                        item.record.state === 'DRAFT' &&
                        item.record.carrierType !== 'PAPER'
                      "
                      class="text-button"
                      :disabled="busy"
                      @click="
                        uploadTarget = item.record;
                        fileInput?.click();
                      "
                    >
                      上传PDF</button
                    ><button
                      v-if="
                        ['DRAFT', 'PENDING_REVIEW'].includes(
                          item.record.state,
                        ) && item.record.candidatePdfVersionId
                      "
                      class="text-button"
                      @click="inspect(item.record)"
                    >
                      检查候选</button
                    ><button
                      v-if="
                        item.record.state === 'DRAFT' &&
                        (item.record.candidatePdfVersionId ||
                          item.record.carrierType === 'PAPER')
                      "
                      class="text-button"
                      @click="qualityRecord(item.record)"
                    >
                      确认质量</button
                    ><button
                      v-if="
                        item.record.state === 'DRAFT' &&
                        item.record.qualityCheckId
                      "
                      class="text-button"
                      @click="submitRecord(item.record)"
                    >
                      送归档复核</button
                    ><button
                      v-if="item.record.state === 'DRAFT'"
                      class="text-button"
                      @click="maintenance?.open('RECORD', item.record.id)"
                    >
                      修改
                    </button>
                    <button
                      v-if="item.record.state === 'DRAFT'"
                      class="text-button danger-text"
                      @click="maintenance?.open('RECORD', item.record.id, true)"
                    >
                      删除
                    </button>
                    <button
                      v-if="item.record.state === 'PUBLISHED'"
                      class="text-button"
                      @click="maintenance?.open('RECORD', item.record.id)"
                    >
                      更正
                    </button>
                    <button
                      v-if="item.record.state === 'PUBLISHED'"
                      class="text-button danger-text"
                      @click="maintenance?.open('RECORD', item.record.id, true)"
                    >
                      申请撤下
                    </button>
                    <button
                      v-if="item.record.state === 'PUBLISHED'"
                      class="text-button"
                      @click="openPolicy(item.record)"
                    >
                      申请开放
                    </button></template
                  >
                </div>
              </td>
            </tr>
            <tr v-if="!items.length">
              <td colspan="4" class="archive-empty">
                <span v-if="loading">正在读取获权资料…</span
                ><EmptyState
                  v-else
                  icon="archive"
                  title="当前范围暂无可见档案"
                  hint="可调整检索条件或资料范围"
                />
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="list-footer">
        <button
          type="button"
          class="secondary list-refresh"
          :disabled="loading"
          @click="load()"
        >
          刷新列表
        </button>
        <div class="list-pagination">
          <span class="list-total">共 {{ total }} 份获权目录</span>
          <button
            class="secondary"
            :disabled="page <= 1"
            @click="
              page--;
              load();
            "
          >
            上一页</button
          ><span>{{ page }} / {{ Math.max(1, Math.ceil(total / 20)) }}</span
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
        </div>
      </footer>
    </section>
  </div>
  <input
    ref="fileInput"
    type="file"
    accept="application/pdf,.pdf"
    hidden
    @change="upload"
  />
  <Catalog v-if="canManage" ref="maintenance" embedded @changed="load()" />
  <dialog
    ref="importDialog"
    class="form-dialog flow-dialog archive-import"
    aria-labelledby="browse-import-title"
    @cancel.prevent="closeImport"
  >
    <div class="flow-dialog-shell">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="upload" :size="22" /></span>
        <div class="flow-dialog-title">
          <span class="eyebrow">档案登记</span>
          <h2 id="browse-import-title">Excel 批量登记</h2>
        </div>
        <button
          class="icon-button"
          aria-label="关闭批量登记"
          @click="closeImport"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <Imports v-if="importOpen" archive-only @changed="load()" />
      </div>
      <footer class="flow-dialog-footer">
        <button type="button" class="secondary" @click="closeImport">
          关闭
        </button>
      </footer>
    </div>
  </dialog>
  <dialog
    ref="entryDialog"
    class="form-dialog flow-dialog archive-entry registration-dialog"
    aria-labelledby="browse-entry-title"
  >
    <form class="flow-dialog-shell" @submit.prevent="saveEntry">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="archive" :size="22" /></span>
        <h2 id="browse-entry-title">
          {{ entry.kind === "BOX" ? "登记档案盒" : "登记盒内文件" }}
        </h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭登记"
          @click="entryDialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section class="flow-dialog-overview registration-intro">
          <h3>登记说明</h3>
          <p class="muted">先保存草稿，再完成质量确认与独立归档复核。</p>
          <button type="button" class="text-button" @click="openImport">
            多条资料？使用 Excel 批量登记 →
          </button>
        </section>
        <div class="form-grid entry-grid">
          <h3 class="entry-section-title">基础信息</h3>
          <label
            >档案载体<select v-model="entry.carrierType">
              <option value="ELECTRONIC">电子</option>
              <option value="PAPER">纸质原件</option>
              <option value="MIXED">纸质与电子混合</option>
            </select></label
          ><label v-if="entry.carrierType !== 'ELECTRONIC'"
            >实际存放位置<input
              v-model="entry.location"
              maxlength="512"
              required
              placeholder="如库房、柜号、层号"
          /></label>
          <label v-if="entry.kind === 'BOX'"
            >所属部门<select
              v-model="entry.departmentId"
              required
              @change="syncEntry"
            >
              <option
                v-for="option in departments"
                :key="option.id"
                :value="option.id"
              >
                {{ option.name }}
              </option>
            </select></label
          ><label v-else class="wide box-picker"
            >所属档案盒
            <div>
              <div class="box-option-search">
                <input
                  v-model="boxOptionKeyword"
                  placeholder="按盒名称或盒号查找"
                  maxlength="200"
                  aria-label="查找所属档案盒"
                /><button
                  type="button"
                  class="secondary"
                  @click="loadBoxOptions"
                >
                  查找
                </button>
              </div>
              <select v-model="entry.boxId" required @change="syncEntry">
                <option value="" disabled>请选择档案盒</option>
                <option
                  v-for="box in boxOptions.filter((value) =>
                    ['DRAFT', 'PUBLISHED'].includes(value.state),
                  )"
                  :key="box.id"
                  :value="box.id"
                >
                  {{ box.title }} · {{ box.boxNo }}
                </option>
              </select>
            </div></label
          ><label
            >档案类别<select v-model="entry.categoryCode" @change="syncEntry">
              <option
                v-for="(label, code) in categoryNames"
                :key="code"
                :value="code"
              >
                {{ label }}
              </option>
            </select></label
          ><label
            >完整题名<input
              v-model="entry.title"
              required
              maxlength="512" /></label
          ><label
            >安全题名<input
              v-model="entry.safeTitle"
              maxlength="512"
              placeholder="可供批准开放检索的脱敏题名" /></label
          ><label v-if="entry.kind === 'BOX'"
            >保密等级<select v-model="entry.classification">
              <option
                v-for="(label, code) in classifications"
                :key="code"
                :value="code"
              >
                {{ label }}
              </option>
            </select></label
          ><label v-else
            >盒内件号<input
              v-model.number="entry.itemNo"
              type="number"
              min="1"
              required
          /></label>
          <h3 class="entry-section-title">保管与责任</h3>
          <label
            >原始存档日<input
              v-model="entry.archiveDate"
              type="date"
              required /></label
          ><label v-if="entry.kind === 'BOX'"
            >保管期限<select v-model="entry.retentionCode">
              <option value="05">5年</option>
              <option value="10">10年</option>
              <option value="30">30年</option>
              <option value="YJ">永久</option>
            </select></label
          ><label v-if="entry.kind === 'BOX'"
            >编号月份<input
              v-model="entry.numberMonth"
              type="month"
              required /></label
          ><label
            >业务责任人<select v-model="entry.ownerId" required>
              <option value="" disabled>请选择责任人</option>
              <option v-for="owner in owners" :key="owner.id" :value="owner.id">
                {{ owner.name }}
              </option>
            </select></label
          ><label
            >关键词<input
              v-model="entry.keywords"
              required
              placeholder="多个关键词以逗号分隔" /></label
          ><label class="wide"
            >固定目录模板<select v-model="entry.templateVersionId" required>
              <option value="" disabled>请选择目录模板</option>
              <option
                v-for="template in matchingTemplates"
                :key="template.id"
                :value="template.id"
              >
                {{ template.name }}
              </option>
            </select></label
          >
          <p
            class="wide hint"
            v-if="
              matchingTemplates.find((t) => t.id === entry.templateVersionId)
                ?.categoryCode === 'COMMON'
            "
          >
            通用台账模板适用于所有部门和档案类别，所属部门只记录档案归属及维护权限。
          </p>
        </div>
        <section
          class="flow-dialog-section"
          v-if="
            matchingTemplates.find((t) => t.id === entry.templateVersionId)
              ?.fields.length
          "
        >
          <h3>台账字段</h3>
          <CatalogFields
            :fields="
              matchingTemplates.find((t) => t.id === entry.templateVersionId)
                ?.fields || []
            "
            v-model="entry.extensionValues"
          />
        </section>
        <button
          v-if="canConfigure && !matchingTemplates.length"
          type="button"
          class="text-button"
          @click="createTemplate"
        >
          创建该类别基础模板
        </button>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button type="button" class="secondary" @click="entryDialog?.close()">
          取消</button
        ><button class="primary" :disabled="busy || !entry.templateVersionId">
          保存草稿
        </button>
      </footer>
    </form>
  </dialog>
  <dialog
    ref="qualityDialog"
    class="form-dialog flow-dialog flow-dialog-compact"
    aria-labelledby="browse-quality-title"
  >
    <form class="flow-dialog-shell" @submit.prevent="confirmQuality">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="task" :size="22" /></span>
        <h2 id="browse-quality-title">确认人工检查</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭检查确认"
          @click="qualityDialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section class="flow-dialog-overview">
          <h3>检查范围</h3>
          <p>
            <strong>{{ qualityTarget?.name }}</strong>
          </p>
          <p class="muted">
            {{
              qualityTarget?.pdf
                ? `请确认已逐页检查完整性、页序和清晰度，共${qualityTarget.pages}页。`
                : "请核对目录、实际原件数量、附件和完好情况，确认后送独立归档复核。"
            }}
          </p>
        </section>
        <section class="flow-dialog-section">
          <h3>检查确认</h3>
          <label
            >人工检查结论<textarea
              v-model="qualityNotes"
              required
              maxlength="2000"
              rows="3"
            /></label
          ><label class="quality-checkbox"
            ><input
              v-model="qualityConfirmed"
              type="checkbox"
              required
            />我已完成上述人工检查并确认结论</label
          >
        </section>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button type="button" class="secondary" @click="qualityDialog?.close()">
          取消</button
        ><button class="primary" :disabled="busy || !qualityConfirmed">
          确认并保存
        </button>
      </footer>
    </form>
  </dialog>
  <ArchiveBorrowDialog ref="borrowDialog" @submitted="selected = []" />
  <dialog
    ref="policyDialog"
    class="form-dialog flow-dialog archive-entry"
    aria-labelledby="browse-policy-title"
  >
    <form class="flow-dialog-shell" @submit.prevent="proposePolicy">
      <header class="flow-dialog-header">
        <span class="flow-dialog-icon"><Icon name="lock" :size="22" /></span>
        <h2 id="browse-policy-title">申请基础开放</h2>
        <button
          type="button"
          class="icon-button"
          aria-label="关闭开放申请"
          @click="policyDialog?.close()"
        >
          ×
        </button>
      </header>
      <div class="flow-dialog-body">
        <section class="flow-dialog-overview">
          <h3>开放对象</h3>
          <p>{{ policyTarget?.title }}</p>
        </section>
        <section class="flow-dialog-section">
          <h3>开放范围</h3>
          <label class="quality-checkbox"
            ><input
              v-model="safeSearch"
              type="checkbox"
              :disabled="!policyTarget?.safeTitle"
            />开放已登记的安全题名检索</label
          >
          <div v-if="safeSearch" class="audience-options">
            <label v-for="department in audiences" :key="department.id"
              ><input
                v-model="policyDepartments"
                type="checkbox"
                :value="department.id"
              />{{ department.name }}</label
            >
          </div>
          <label
            v-if="
              policyTarget?.classification === 'GENERAL' &&
              policyTarget?.carrierType !== 'PAPER'
            "
            class="quality-checkbox"
            ><input
              v-model="ownerReading"
              type="checkbox"
            />批准本部门成员查看该一般文件的当前版本</label
          >
          <p class="muted">
            题名可检索不等于可以阅读，重要及核心资料仍按具体人员和流程批准。
          </p>
          <p v-if="policyTarget?.carrierType === 'PAPER'" class="muted">
            纯纸质文件仅开放安全题名，原件使用须另行申请纸质借阅。
          </p>
        </section>
        <section
          v-if="policyTarget?.carrierType !== 'PAPER'"
          class="flow-dialog-section"
        >
          <h3>具体人员查看名单</h3>
          <p class="muted">
            可选，最多50人；选择名单后须指定失效日期。批准只绑定当前文件和PDF版本。
          </p>
          <div class="policy-person-search">
            <input
              v-model="policySubjectKeyword"
              aria-label="检索基础授权人员"
              placeholder="输入姓名或工号"
              maxlength="100"
            /><button
              type="button"
              class="secondary"
              @click="findPolicySubjects"
            >
              检索人员
            </button>
          </div>
          <p class="muted">已选择{{ policyUsers.length }}人</p>
          <div class="policy-selected">
            <button
              v-for="id in policyUsers"
              :key="id"
              type="button"
              class="secondary"
              @click="policyUsers = policyUsers.filter((value) => value !== id)"
            >
              {{ policySubjectNames[id] || id }} ×
            </button>
          </div>
          <div class="audience-options">
            <label v-for="person in policySubjects" :key="person.id"
              ><input
                v-model="policyUsers"
                type="checkbox"
                :value="person.id"
                @change="policyPermanent = false"
              />{{ person.name }} · {{ person.employeeNo }}</label
            >
          </div>
        </section>
        <section class="flow-dialog-section">
          <h3>有效期与原因</h3>
          <div class="flow-dialog-fields">
            <label class="flow-inline-field"
              >开放有效至<input
                v-model="policyEnd"
                type="date"
                :required="!policyPermanent"
                :disabled="policyPermanent" /></label
            ><label class="quality-checkbox"
              ><input
                v-model="policyPermanent"
                type="checkbox"
              />申请长期有效</label
            >
          </div>
          <label
            >业务原因<textarea
              v-model="policyReason"
              required
              maxlength="1000"
              rows="3"
            />
          </label>
        </section>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      </div>
      <footer class="flow-dialog-footer">
        <button type="button" class="secondary" @click="policyDialog?.close()">
          取消</button
        ><button class="primary">提交业务审批</button>
      </footer>
    </form>
  </dialog>
  <ArchiveExports ref="exportsPanel" @changed="load(false)" />
  <ArchiveReader ref="reader" @failure="readerFailure" />
</template>
<style scoped>
.archive-export-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid var(--line);
  align-items: center;
}
.archive-export-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.archive-selection-count,
.archive-search-note {
  color: var(--text-secondary);
  font-size: 13px;
}
.archive-selection-count {
  padding-right: 8px;
}
.archive-selection-count strong {
  color: var(--ink);
  margin: 0 3px;
}
.archive-apply {
  margin-left: auto;
}
.borrow-action-options {
  display: flex;
  gap: 20px;
  border: 1px solid var(--line);
  margin: 14px 0;
  padding: 12px;
}
.borrow-action-options label {
  display: flex;
  align-items: center;
  gap: 7px;
}
.borrow-action-options input {
  width: auto;
}

/* 模块导航由外层统一承载，查询页仅保留横向业务筛选，避免重复左侧栏。 */
.archive-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 0 0 22px;
}
.archive-heading h1 {
  font-size: 24px;
  margin: 6px 0;
}
.archive-heading-actions {
  display: flex;
  gap: 12px;
}
/* 人员名单跨检索保留可核对名称，宽屏下清晰区分候选和已选名单。 */
.policy-person-search {
  display: flex;
  gap: 14px;
  align-items: center;
}
.policy-person-search input {
  flex: 1;
}
.policy-selected {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 12px 0;
}
.policy-selected button {
  font-size: 12px;
  padding: 7px 10px;
}
.archive-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
}
.archive-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 14px 18px;
}
.archive-filters h2 {
  font-size: 13px;
  margin: 0 12px 0 0;
}
.archive-filters > button {
  display: flex;
  gap: 8px;
  align-items: center;
  border: 0;
  border-radius: 10px;
  padding: 9px 12px;
  background: none;
  text-align: left;
  color: var(--ink);
  cursor: pointer;
}
.archive-filters > button.chosen {
  background: var(--surface-muted);
  color: var(--ink);
  font-weight: 600;
}
.archive-main {
  padding: 24px;
  overflow: hidden;
}
.archive-query-area {
  padding: 20px;
  margin-bottom: 24px;
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 10px;
}
.archive-tools {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 18px;
}
.archive-tools form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
}
section.archive-main .archive-tools input {
  width: clamp(220px, 24vw, 360px);
  max-width: 100%;
}
.archive-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.archive-table th {
  font-weight: 400;
  color: var(--text-secondary);
  text-align: left;
  background: var(--surface-muted);
  padding: 14px 10px;
}
.archive-table td {
  padding: 21px 10px;
  border-bottom: 1px solid var(--line);
}
.archive-table .select-column {
  width: 52px;
}
.archive-table input[type="checkbox"] {
  width: 16px;
  accent-color: var(--ink);
}
.archive-name {
  display: flex;
  align-items: center;
  gap: 12px;
}
.archive-name strong {
  font-weight: 500;
}
.archive-name small,
.row-state {
  display: block;
  color: var(--text-secondary);
  font-size: 11px;
  margin-top: 7px;
}
.archive-file-icon {
  display: grid;
  place-items: center;
  width: 38px;
  height: 44px;
  background: var(--surface-muted);
  border-radius: 8px;
  color: var(--ink);
  flex-shrink: 0;
}
.archive-grade {
  font-size: 10px;
  padding: 4px 7px;
  background: var(--surface-muted);
  color: var(--text-secondary);
  border-radius: 4px;
}
.archive-grade.important {
  background: #faf0df;
  color: #a37f46;
}
.archive-grade.core {
  background: #f7e9e5;
  color: #ae715b;
}
.archive-row-actions {
  display: flex;
  gap: 9px;
  flex-wrap: wrap;
  max-width: 220px;
}
.archive-row-actions button {
  font-size: 12px;
  padding: 3px 0;
}
.archive-empty {
  text-align: center;
  color: var(--text-secondary);
  height: 160px;
}
.archive-entry {
  --flow-dialog-width: 800px;
}
.entry-grid .wide {
  grid-column: 1/-1;
}
.quality-checkbox {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-direction: row;
  margin: 18px 0;
  font-size: 13px;
}
.quality-checkbox input {
  width: 16px;
  accent-color: var(--brand);
}
.audience-options {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  max-height: 170px;
  overflow: auto;
  padding: 15px;
  background: var(--surface-muted);
  border-radius: 10px;
}
.audience-options label {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-direction: row;
  font-size: 12px;
  margin: 0;
}
.audience-options input {
  width: 15px;
  accent-color: var(--brand);
}
.archive-progress {
  padding: 12px 18px;
  background: var(--surface-muted);
  border-radius: 10px;
  color: var(--ink);
}

.archive-filters {
  padding: 12px 18px;
  gap: 8px;
}
.archive-filters h2 {
  margin-right: 8px;
  color: var(--text-secondary);
  font-size: 13px;
}
.archive-layout {
  gap: 16px;
}
.archive-list-tabs {
  display: flex;
  gap: 24px;
  border-bottom: 1px solid var(--line);
  padding: 0 4px;
}
.archive-list-tabs button {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 4px;
  border-radius: 0;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--text-secondary);
}
.archive-list-tabs button.active {
  border-bottom-color: var(--brand);
  color: var(--brand);
  font-weight: 600;
}
.archive-list-tabs span {
  padding: 1px 7px;
  border-radius: 12px;
  background: var(--surface-muted);
  font-size: 12px;
}
.archive-table-scroll {
  overflow-x: auto;
}
.box-table {
  min-width: 720px;
}
.box-table th:first-child {
  width: 48%;
}
.box-table td {
  padding: 18px 12px;
}
section.archive-main label.archive-inline-field {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 10px;
  white-space: nowrap;
}
.archive-status {
  padding: 5px 9px;
  border-radius: 5px;
  font-size: 12px;
  background: var(--surface-muted);
  color: var(--text-secondary);
}
.archive-status.published {
  background: #e8f5ee;
  color: #28714e;
}
.archive-status.pending_review {
  background: #fff2dc;
  color: #95630d;
}
.danger-text {
  color: #b54d40;
}
.registration-dialog {
  --flow-dialog-width: 960px;
}
.registration-intro p {
  margin: 0 0 4px;
  font-size: 13px;
}
.registration-intro button {
  padding: 0;
  font-size: 13px;
}
.registration-dialog .entry-grid {
  gap: 18px 28px;
  margin: 0;
}
dialog.registration-dialog .entry-grid > label {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  font-size: 13px;
  margin: 0;
}
.registration-dialog .entry-grid > label > :is(input, select) {
  min-width: 0;
  width: 100%;
  margin: 0;
  height: 42px;
}
.entry-section-title {
  grid-column: 1 / -1;
  font-size: 14px;
  padding-bottom: 10px;
  margin: 0;
  border-bottom: 1px solid var(--line);
}
.box-option-search {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.box-option-search input {
  flex: 1;
  min-width: 0;
}
.box-picker select {
  width: 100%;
}
.archive-import {
  --flow-dialog-width: 1320px;
}
@media (max-width: 760px) {
  .registration-dialog .entry-grid {
    grid-template-columns: 1fr;
  }
  .archive-heading-actions {
    flex-wrap: wrap;
  }
}

section.archive-main label.archive-inline-field {
  margin: 0;
  gap: 14px;
}
section.archive-main .archive-tools :is(input, button),
.archive-export-toolbar :is(.secondary, .primary) {
  height: 44px;
  min-height: 44px;
}
section.archive-main .archive-tools form > button {
  padding: 8px 20px;
}
@media (max-width: 760px) {
  .archive-query-area {
    padding: 16px;
  }
  .archive-search-note {
    flex-basis: 100%;
  }
  .archive-export-toolbar {
    gap: 12px;
  }
  .archive-selection-count {
    flex-basis: 100%;
  }
  section.archive-main label.archive-inline-field {
    max-width: 100%;
  }
  section.archive-main label.archive-inline-field input {
    flex: 1;
    min-width: 0;
  }
}
</style>
