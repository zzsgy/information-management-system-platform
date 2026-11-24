<script setup lang="ts">
import { computed, inject, onMounted, onUnmounted, reactive, ref } from "vue";
import Icon from "../preview/PlatformIcon.vue";
import { api, COLLECTION, mutate, profile, query } from "./api";
import {
  archiveStates,
  classifications,
  uploadArchivePdf,
  type ArchiveRecord,
} from "./archiveFiles";
import { archiveLocation, matchChecklistFiles } from "./archiveChecklist";
import Catalog from "./Catalog.vue";
import type { CatalogField } from "./CatalogFields.vue";
import ArchiveBoxFields, {
  type ArchiveBoxForm,
  registrationCarriers,
} from "./ArchiveBoxFields.vue";
import Imports from "./Imports.vue";
import ArchiveReader from "./ArchiveReader.vue";
import ArchiveExports from "./ArchiveExports.vue";
import ArchiveBorrowDialog from "./ArchiveBorrowDialog.vue";
import DateRangePreset from "./DateRangePreset.vue";

/** 原台账补录进度独立于归档状态。 */
const legacyLedgerLabels = {
  PENDING: "待补录",
  COMPLETED: "已补录",
  NOT_REQUIRED: "无需补录",
} as const;
type LegacyLedgerStatus = keyof typeof legacyLedgerLabels;

/** 管理员只读取获准部门的完整台账，正文继续使用独立阅读和交付接口。 */
interface Box {
  id: string;
  boxNo: string;
  title: string;
  departmentId: string;
  classification: string;
  carrierType: string;
  location: string;
  retentionCode: string;
  expiryDate: string | null;
  state: string;
  legacyLedgerStatus: LegacyLedgerStatus;
  rowVersion: number;
  candidateRevisionId: string;
  recordCount: number;
  extensionValues: Record<string, unknown>;
}
interface Line extends ArchiveRecord {
  itemNo: number;
  formedDate: string | null;
  notes: string | null;
  pageCount: number;
  extensionValues: Record<string, unknown>;
  electronicVersionId: string | null;
  electronicState: string | null;
  errorMessage: string | null;
  originalName: string | null;
}
interface Contents {
  box: Box;
  items: Line[];
}
interface PreviewLine {
  row: number;
  sequence: number;
  title: string;
  date: string | null;
  year: string | null;
  signingEntity: string;
  contractNumber: string;
  paperCopies: number;
  notes: string;
}
interface Choice {
  id: string;
  name: string;
}
interface Template extends Choice {
  objectType: string;
  categoryCode: string;
  fields: CatalogField[];
}
interface Mapping {
  file: File;
  recordId: string;
  reason: string;
  status: string;
  uploadId: string;
}
const ask = inject<(title: string) => Promise<string | null>>("askPassword")!;
const inform = inject<(message: string) => void>("inform")!;
const boxes = ref<Box[]>([]),
  total = ref(0),
  page = ref(1);
/** 筛选字段与台账列一一对应；份数和日期允许只填写一端。 */
const emptyFilters = {
  keyword: "",
  boxNo: "",
  title: "",
  location: "",
  preservationType: "",
  classification: "",
  retentionCode: "",
  state: "",
  legacyLedgerStatus: "",
  copyCountMin: "" as string | number,
  copyCountMax: "" as string | number,
  expiryFrom: "",
  expiryTo: "",
};
const filters = reactive({ ...emptyFilters });
const preservationTypes = ref<string[]>([]);
const loading = ref(false),
  busy = ref(false),
  error = ref(""),
  progress = ref("");
const expanded = ref<string[]>([]),
  contents = reactive<Record<string, Line[]>>({}),
  fetching = ref<string[]>([]);
const selectedBox = ref<Box | null>(null);
/** 清单选择窗口在登记和草稿补录之间复用；登记附件只在保存时关联。 */
const checklistMode = ref<"registration" | "manual">("registration");
const checklistFile = ref<File | null>(null);
const preview = ref<{ uploadId: string; items: PreviewLine[] } | null>(null);
const stagedChecklist = ref<{
  uploadId: string;
  name: string;
  count: number;
} | null>(null);
const emptyChecklistItem = {
  title: "",
  date: "",
  signingEntity: "",
  contractNumber: "",
  paperCopies: 1,
  notes: "",
};
const checklistItem = reactive({ ...emptyChecklistItem });
/** 弹窗打开时读取档案盒已保存的清单。 */
const checklistRows = ref<Line[] | null>(null);
const checklistLoadError = ref("");
/** 补录保存失败时在按钮旁显示服务端提示。 */
const checklistSaveError = ref("");
const mappings = ref<Mapping[]>([]),
  importOpen = ref(false),
  detail = ref<Line | null>(null);
const checklistDialog = ref<HTMLDialogElement>(),
  fileDialog = ref<HTMLDialogElement>(),
  registerDialog = ref<HTMLDialogElement>(),
  importDialog = ref<HTMLDialogElement>(),
  reviewDialog = ref<HTMLDialogElement>(),
  detailDialog = ref<HTMLDialogElement>();
const maintenance = ref<InstanceType<typeof Catalog>>(),
  reader = ref<InstanceType<typeof ArchiveReader>>(),
  exports = ref<InstanceType<typeof ArchiveExports>>(),
  borrowDialog = ref<InstanceType<typeof ArchiveBorrowDialog>>();
const departments = ref<Choice[]>([]),
  owners = ref<Choice[]>([]),
  template = ref<Template | null>(null);
/** 管理中心维护的文件类型随每次打开登记表单加载。 */
const fileTypes = ref<string[]>([]);
const today = new Intl.DateTimeFormat("en-CA", {
  timeZone: "Asia/Shanghai",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
}).format(new Date());
/** 重新登记时重置共享表单的临时交互状态。 */
const registrationNumber = ref(0);
const registrationFields = ref<InstanceType<typeof ArchiveBoxFields>>();
const entry = ref<ArchiveBoxForm>({
  title: "",
  departmentId: "",
  ownerId: "",
  classification: "GENERAL",
  carrierType: "",
  archiveDate: today,
  retentionCode: "10",
  numberMonth: today.slice(0, 7),
  safeTitle: "",
  notes: "",
  keywords: "",
  extensionValues: {} as Record<string, unknown>,
});
const returnToReview = ref(false);
const checked = ref(false),
  reviewNotes = ref("");
let alive = true,
  generation = 0;
const hasDuplicateMapping = computed(
  () =>
    new Set(mappings.value.filter((x) => x.recordId).map((x) => x.recordId))
      .size !== mappings.value.filter((x) => x.recordId).length,
);
const readyMappings = computed(
  () =>
    mappings.value.length > 0 &&
    mappings.value.every((x) => x.recordId) &&
    !hasDuplicateMapping.value,
);
const reviewLines = computed(() =>
  selectedBox.value
    ? (contents[selectedBox.value.id] || []).filter(
        (x) => x.electronicVersionId || x.extensionValues.copyCount,
      )
    : [],
);
const missingDocuments = computed(() =>
  reviewLines.value.filter(
    (x) => x.electronicState !== "READY" || !x.candidatePdfVersionId,
  ),
);
function number(box: Box) {
  return String(box.extensionValues.originalNumber || box.boxNo);
}
function value(box: Box, key: string) {
  return box.extensionValues[key] ?? "—";
}
function replaceBox(box: Box) {
  const index = boxes.value.findIndex((x) => x.id === box.id);
  if (index >= 0) boxes.value[index] = box;
  if (selectedBox.value?.id === box.id) selectedBox.value = box;
}

/** 刷新保持已展开盒；切换检索页时不会把旧列表的响应塞回新范围。 */
async function load() {
  const current = ++generation;
  loading.value = true;
  error.value = "";
  try {
    const result = await api<{ items: Box[]; total: number }>(
      `/archive/boxes?${query({ ...filters, page: page.value, pageSize: 20 })}`,
    );
    if (!alive || current !== generation) return;
    // 标记后若最后一页已空，回到最后一个仍有结果的页面。
    const lastPage = Math.max(1, Math.ceil(result.total / 20));
    if (page.value > lastPage) {
      page.value = lastPage;
      await load();
      return;
    }
    boxes.value = result.items;
    total.value = result.total;
    for (const box of result.items.filter((x) => expanded.value.includes(x.id)))
      await loadContents(box);
  } catch (failure) {
    if (current === generation) error.value = (failure as Error).message;
  } finally {
    if (current === generation) loading.value = false;
  }
}
/** 保存后重查当前筛选，已标记的盒立即从待补录列表移出。 */
async function markLegacyLedger(box: Box, event: Event) {
  const select = event.target as HTMLSelectElement;
  busy.value = true;
  error.value = "";
  try {
    const updated = await api<Box>(
      `/archive/boxes/${box.id}/legacy-ledger-status`,
      {
        method: "PUT",
        headers: { "X-Operation-Id": crypto.randomUUID() },
        body: JSON.stringify({
          status: select.value,
          expectedStatus: box.legacyLedgerStatus,
        }),
      },
    );
    replaceBox(updated);
    await load();
    inform(
      `原台账已标记为「${legacyLedgerLabels[updated.legacyLedgerStatus]}」。`,
    );
  } catch (failure) {
    select.value = box.legacyLedgerStatus;
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
async function loadContents(box: Box) {
  const result = await api<Contents>(
    `/archive/boxes/${box.id}/checklist-items`,
  );
  if (alive) {
    contents[box.id] = result.items;
    replaceBox(result.box);
  }
  return result;
}
async function toggle(box: Box) {
  if (expanded.value.includes(box.id)) {
    expanded.value = expanded.value.filter((x) => x !== box.id);
    return;
  }
  fetching.value.push(box.id);
  try {
    await loadContents(box);
    expanded.value.push(box.id);
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    fetching.value = fetching.value.filter((x) => x !== box.id);
  }
}
async function changed() {
  await Promise.all([load(), loadFilterOptions()]);
}
/** 全部获权台账提供保存类型；登记或导入后刷新，搜索翻页不重复读取选项。 */
async function loadFilterOptions() {
  try {
    const result = await api<{ preservationTypes: string[] }>(
      "/archive/box-filter-options",
    );
    if (alive) preservationTypes.value = result.preservationTypes;
  } catch (failure) {
    if (alive) error.value = (failure as Error).message;
  }
}
function openImport() {
  importOpen.value = true;
  importDialog.value?.showModal();
}
function closeImport() {
  importDialog.value?.close();
  importOpen.value = false;
  void changed();
}

/** 单条登记采用与批量台账相同的通用字段，部门只是每条台账的属性。 */
async function openRegister() {
  error.value = "";
  try {
    const options = await api<{
      departments: Choice[];
      templates: Template[];
      fileTypes: string[];
    }>("/archive/catalog-options");
    departments.value = options.departments;
    fileTypes.value = options.fileTypes;
    template.value =
      options.templates.find(
        (x) => x.objectType === "BOX" && x.categoryCode === "COMMON",
      ) || null;
    if (!template.value)
      throw new Error("通用档案盒模板尚未启用，请联系管理员。");
    Object.assign(entry.value, {
      title: "",
      departmentId:
        options.departments.find(
          (x) => x.id === profile.value?.user.departmentId,
        )?.id ||
        options.departments[0]?.id ||
        "",
      ownerId: "",
      classification: "GENERAL",
      carrierType: "",
      archiveDate: today,
      retentionCode: "10",
      numberMonth: today.slice(0, 7),
      safeTitle: "",
      notes: "",
      keywords: "",
      extensionValues: {},
    });
    stagedChecklist.value = null;
    await loadOwners();
    registrationNumber.value++;
    registerDialog.value?.showModal();
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
async function loadOwners() {
  if (!entry.value.departmentId) return;
  owners.value = (
    await api<{ items: Choice[] }>(
      `/archive/owners?${query({ departmentId: entry.value.departmentId })}`,
    )
  ).items;
  entry.value.ownerId =
    owners.value.find((x) => x.id === profile.value?.user.id)?.id ||
    owners.value[0]?.id ||
    "";
}
/** 清单上传属于原部门，切换部门后必须在新部门重新检测。 */
async function changeEntryDepartment() {
  if (stagedChecklist.value) {
    stagedChecklist.value = null;
    error.value = "所属部门已变更，请重新上传电子清单。";
  }
  await loadOwners();
}
async function register() {
  const { carrierType, extensionValues } = entry.value;
  if (!carrierType) return;
  if (
    carrierType !== "ELECTRONIC" &&
    !(extensionValues.paperTags as string[] | undefined)?.length
  ) {
    error.value = "请选择纸质类别：原件或复印件。";
    registrationFields.value?.showPaperChoices();
    return;
  }
  const location = archiveLocation(entry.value.extensionValues);
  if (carrierType !== "ELECTRONIC" && location === "未登记") {
    error.value = "请选择存放柜号、列号和层号，并确认位置。";
    return;
  }
  const password = await ask("登记档案盒台账");
  if (password === null) return;
  busy.value = true;
  error.value = "";
  try {
    const box = await mutate<Box>(
      "/archive/boxes",
      "POST",
      {
        title: entry.value.title,
        departmentId: entry.value.departmentId,
        ownerId: entry.value.ownerId,
        classification: entry.value.classification,
        carrierType,
        archiveDate: entry.value.archiveDate,
        retentionCode: entry.value.retentionCode,
        numberMonth: `${entry.value.numberMonth}-01`,
        safeTitle: entry.value.safeTitle || undefined,
        notes: entry.value.notes || undefined,
        categoryCode: "COMMON",
        templateVersionId: template.value!.id,
        extensionValues: {
          ...entry.value.extensionValues,
          preservationType: registrationCarriers[carrierType],
          paperTags: extensionValues.paperTags ?? [],
        },
        keywords: entry.value.keywords
          .split(/[，,；;\n]/)
          .map((x) => x.trim())
          .filter(Boolean).length
          ? entry.value.keywords
              .split(/[，,；;\n]/)
              .map((x) => x.trim())
              .filter(Boolean)
          : ["档案台账"],
        ...(carrierType === "ELECTRONIC" ? {} : { location }),
        ...(stagedChecklist.value
          ? { checklistUploadSessionId: stagedChecklist.value.uploadId }
          : {}),
      },
      COLLECTION,
      "ARCHIVE_COLLECTION",
      "CREATE_BOX",
      password,
    );
    registerDialog.value?.close();
    if (stagedChecklist.value && !expanded.value.includes(box.id))
      expanded.value.push(box.id);
    const includedChecklist = !!stagedChecklist.value;
    stagedChecklist.value = null;
    await changed();
    inform(
      includedChecklist
        ? "档案盒与电子清单已保存。"
        : "档案盒已登记，可在列表补录电子清单。",
    );
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}

/** 登记上传窗口只预览候选，取消时保留之前已选用的清单。 */
function openRegistrationChecklist() {
  checklistMode.value = "registration";
  inputGeneration.value++;
  checklistFile.value = null;
  preview.value = null;
  error.value = "";
  progress.value = "";
  checklistDialog.value?.showModal();
}

/** 在已获权的草稿盒补录一条，同时保留查看已保存条目的入口。 */
async function openChecklist(box: Box) {
  const current = ++inputGeneration.value;
  checklistMode.value = "manual";
  selectedBox.value = box;
  Object.assign(checklistItem, emptyChecklistItem);
  error.value = "";
  progress.value = "";
  checklistRows.value = null;
  checklistLoadError.value = "";
  checklistSaveError.value = "";
  checklistDialog.value?.showModal();
  try {
    const result = await loadContents(box);
    if (alive && current === inputGeneration.value)
      checklistRows.value = result.items;
  } catch (failure) {
    if (alive && current === inputGeneration.value)
      checklistLoadError.value = (failure as Error).message;
  }
}
/** 先完成真实文件检测再读取；预检不写入清单条目。 */
async function previewChecklist() {
  const departmentId = entry.value.departmentId;
  const file = checklistFile.value;
  if (!departmentId || !file) return;
  busy.value = true;
  error.value = "";
  try {
    const uploadId = await uploadArchivePdf(
      departmentId,
      file,
      () => alive,
      (message) => (progress.value = message),
      "ARCHIVE_REGISTRATION_CHECKLIST",
    );
    const result = await api<{ items: PreviewLine[] }>(
      "/archive/registration-checklist-preview",
      {
        method: "POST",
        body: JSON.stringify({ departmentId, uploadSessionId: uploadId }),
      },
    );
    preview.value = { uploadId, items: result.items };
    progress.value = "";
  } catch (failure) {
    progress.value = "";
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 确认预览只把上传会话带回登记表单，保存登记时再提交。 */
function confirmChecklist() {
  if (!preview.value || !checklistFile.value) return;
  stagedChecklist.value = {
    uploadId: preview.value.uploadId,
    name: checklistFile.value.name,
    count: preview.value.items.length,
  };
  checklistDialog.value?.close();
}

/** 补录独立保存，版本号与编号均由当前草稿盒核定。 */
async function saveChecklistItem() {
  const box = selectedBox.value,
    current = boxes.value.find((item) => item.id === box?.id);
  if (checklistMode.value !== "manual" || !box) return;
  const password = await ask("补录电子清单");
  if (password === null) return;
  busy.value = true;
  checklistSaveError.value = "";
  try {
    const result = await mutate<Contents>(
      `/archive/boxes/${box.id}/checklist-items`,
      "POST",
      { ...checklistItem },
      box.id,
      "ARCHIVE_BOX",
      "APPEND_CHECKLIST_ITEM",
      password,
      (current || box).rowVersion,
    );
    contents[box.id] = result.items;
    replaceBox(result.box);
    if (!expanded.value.includes(box.id)) expanded.value.push(box.id);
    checklistDialog.value?.close();
    inform("电子清单条目已补录。");
  } catch (failure) {
    checklistSaveError.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
async function openFiles(box: Box, line?: Line) {
  inputGeneration.value++;
  selectedBox.value = box;
  mappings.value = [];
  boundDocuments.value = false;
  error.value = "";
  progress.value = "";
  try {
    await loadContents(box);
    uploadLine.value = line?.id || "";
    fileDialog.value?.showModal();
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
const inputGeneration = ref(0);
const uploadLine = ref(""),
  boundDocuments = ref(false);
function chooseDocuments(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files || []);
  const lines = contents[selectedBox.value!.id] || [];
  const matches = matchChecklistFiles(
    files.map((x) => x.name),
    lines,
  );
  mappings.value = files.map((file, index) => ({
    file,
    ...matches[index],
    ...(uploadLine.value && files.length === 1
      ? { recordId: uploadLine.value, reason: "手动选择的清单条目" }
      : {}),
    status: "待上传",
    uploadId: "",
  }));
}
/** 上传前明确对应；绑定一次原子完成，转换失败保留原件并支持单独重试。 */
async function uploadDocuments() {
  if (!selectedBox.value || !readyMappings.value) return;
  if (mappings.value.length > 100) {
    error.value = "每批最多100份电子文件。";
    return;
  }
  const password = await ask("确认文件对应并上传电子文档");
  if (password === null) return;
  busy.value = true;
  error.value = "";
  try {
    const box = (await loadContents(selectedBox.value)).box;
    const bindings: {
      recordId: string;
      uploadSessionId: string;
      expectedVersion: number;
    }[] = [];
    for (const [index, mapping] of mappings.value.entries()) {
      const line = contents[box.id].find((x) => x.id === mapping.recordId)!;
      if (!mapping.uploadId)
        mapping.uploadId = await uploadArchivePdf(
          line.id,
          mapping.file,
          () => alive,
          (message) => {
            mapping.status = message;
            progress.value = `${index + 1} / ${mappings.value.length} · ${mapping.file.name} · ${message}`;
          },
          "ARCHIVE_ATTACHMENT",
        );
      mapping.status = "检测通过，等待关联";
      bindings.push({
        recordId: line.id,
        uploadSessionId: mapping.uploadId,
        expectedVersion: line.rowVersion!,
      });
    }
    const result = await mutate<Contents>(
      `/archive/boxes/${box.id}/electronic-files`,
      "POST",
      { items: bindings },
      box.id,
      "ARCHIVE_BOX",
      "UPLOAD_CHECKLIST_DOCUMENTS",
      password,
      box.rowVersion,
    );
    contents[box.id] = result.items;
    replaceBox(result.box);
    boundDocuments.value = true;
    for (const mapping of mappings.value) {
      mapping.status = "正在准备阅读文档";
      progress.value = `${mapping.file.name} · 正在准备阅读文档`;
      try {
        const prepared = await api<Contents>(
          `/archive/records/${mapping.recordId}/prepare-electronic`,
          { method: "POST", body: "{}" },
        );
        contents[box.id] = prepared.items;
        replaceBox(prepared.box);
        mapping.status = "已完成";
      } catch (failure) {
        mapping.status = (failure as Error).message;
      }
    }
    if (!expanded.value.includes(box.id)) expanded.value.push(box.id);
    progress.value = "本批上传已结束，处理结果见下表。";
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
async function retry(line: Line, box: Box) {
  busy.value = true;
  error.value = "";
  progress.value = "正在重新准备阅读文档…";
  try {
    await api(`/archive/records/${line.id}/prepare-electronic`, {
      method: "POST",
      body: "{}",
    });
    await loadContents(box);
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
    progress.value = "";
  }
}
function view(line: Line, box: Box) {
  detail.value = line;
  selectedBox.value = box;
  detailDialog.value?.showModal();
}
function canRead(line: Line) {
  return line.state === "PUBLISHED"
    ? !!line.currentPdfVersionId
    : !!line.candidatePdfVersionId &&
        (!line.electronicVersionId || line.electronicState === "READY");
}
async function read(line: Line) {
  if (!canRead(line)) {
    error.value = "本次电子文件尚未处理完成，请先处理后再阅读。";
    return;
  }
  returnToReview.value = !!reviewDialog.value?.open;
  error.value = "";
  detailDialog.value?.close();
  reviewDialog.value?.close();
  try {
    if (line.state === "PUBLISHED")
      await reader.value?.open(
        {
          recordRevisionId: line.currentRevisionId,
          pdfVersionId: line.currentPdfVersionId,
        },
        line.title,
      );
    else if (line.state === "DRAFT" && line.candidatePdfVersionId)
      await reader.value?.open(
        {
          reviewContext: {
            type: "SCAN_QUALITY",
            id: line.id,
            candidatePdfVersionId: line.candidatePdfVersionId,
          },
        },
        line.title,
      );
    else throw new Error("文件正在审批中，请在流程中心检查本次固定候选。");
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
async function review(box: Box) {
  selectedBox.value = box;
  checked.value = false;
  reviewNotes.value = "";
  error.value = "";
  try {
    await loadContents(box);
    reviewDialog.value?.showModal();
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
/** 明确的人工确认覆盖整盒清单及每份真实候选，随后提交归档。 */
async function submitReview() {
  if (!selectedBox.value || !checked.value || missingDocuments.value.length)
    return;
  const password = await ask("确认整盒检查结论并提交归档");
  if (password === null) return;
  busy.value = true;
  error.value = "";
  try {
    const box = selectedBox.value;
    for (const line of reviewLines.value)
      await mutate(
        "/archive/quality-checks",
        "POST",
        {
          objectType: "RECORD",
          objectId: line.id,
          candidateRevisionId: line.candidateRevisionId,
          candidatePdfVersionId: line.candidatePdfVersionId,
          checkedPageCount: line.pageCount,
          confirmed: true,
          notes: reviewNotes.value,
        },
        line.id,
        "ARCHIVE_RECORD",
        "CONFIRM_QUALITY",
        password,
      );
    const proof = await mutate<{ id: string }>(
      "/archive/quality-checks",
      "POST",
      {
        objectType: "BOX",
        objectId: box.id,
        candidateRevisionId: box.candidateRevisionId,
        candidatePdfVersionId: null,
        checkedPageCount: 0,
        confirmed: true,
        notes: reviewNotes.value,
      },
      box.id,
      "ARCHIVE_BOX",
      "CONFIRM_QUALITY",
      password,
    );
    const result = await mutate<{ state: string }>(
      "/archive/registration-reviews",
      "POST",
      {
        objectRefs: [
          {
            objectType: "BOX",
            objectId: box.id,
            expectedVersion: box.rowVersion,
            candidateRevisionId: box.candidateRevisionId,
            candidatePdfVersionId: null,
          },
        ],
        qualityEvidenceRef: proof.id,
      },
      box.id,
      "ARCHIVE_BOX",
      "SUBMIT_REGISTRATION",
      password,
    );
    reviewDialog.value?.close();
    await load();
    inform(
      result.state === "PUBLISHED"
        ? "档案盒及本次清单文档已归档。"
        : "档案盒及本次清单文档已提交独立归档审批。",
    );
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
onMounted(changed);
onUnmounted(() => {
  alive = false;
  generation++;
});
</script>

<template>
  <div class="archive-ledger">
    <header class="ledger-heading page-heading">
      <div>
        <h1>档案查询</h1>
        <p>统一管理档案台账、电子清单与归档文档。</p>
      </div>
      <div class="heading-actions">
        <button class="secondary" :disabled="busy" @click="openImport">
          <Icon name="upload" :size="17" />批量导入</button
        ><button class="primary" :disabled="busy" @click="openRegister">
          档案登记
        </button>
      </div>
    </header>
    <section class="ledger-panel">
      <form
        class="ledger-filters"
        @submit.prevent="
          page = 1;
          load();
        "
      >
        <label class="list-filter-field"
          ><span>档案编号</span
          ><input
            v-model="filters.boxNo"
            placeholder="原档案编号或系统盒号"
            maxlength="200"
        /></label>
        <label class="list-filter-field"
          ><span>档案名称</span
          ><input
            v-model="filters.title"
            placeholder="输入档案名称"
            maxlength="200"
        /></label>
        <label class="list-filter-field"
          ><span>存放位置</span
          ><input
            v-model="filters.location"
            placeholder="如：13柜、A列或3层"
            maxlength="200"
        /></label>
        <label class="list-filter-field"
          ><span>保存类型</span
          ><select v-model="filters.preservationType">
            <option value="">全部类型</option>
            <option v-for="item in preservationTypes" :key="item" :value="item">
              {{ item }}
            </option>
          </select></label
        >
        <div
          class="ledger-filter-field"
          role="group"
          aria-labelledby="ledger-copies-label"
        >
          <span id="ledger-copies-label">保存份数</span>
          <div class="filter-range">
            <input
              v-model="filters.copyCountMin"
              type="number"
              min="0"
              step="1"
              :max="
                filters.copyCountMax === '' ? undefined : filters.copyCountMax
              "
              aria-label="最少保存份数"
              placeholder="最少"
            />
            <span>至</span>
            <input
              v-model="filters.copyCountMax"
              type="number"
              step="1"
              :min="filters.copyCountMin === '' ? 0 : filters.copyCountMin"
              aria-label="最多保存份数"
              placeholder="最多"
            />
          </div>
        </div>
        <label class="list-filter-field"
          ><span>保密级别</span
          ><select v-model="filters.classification">
            <option value="">全部级别</option>
            <option
              v-for="(label, code) in classifications"
              :key="code"
              :value="code"
            >
              {{ label }}
            </option>
          </select></label
        >
        <label class="list-filter-field"
          ><span>保管年限</span
          ><select v-model="filters.retentionCode">
            <option value="">全部年限</option>
            <option value="05">5年</option>
            <option value="10">10年</option>
            <option value="30">30年</option>
            <option value="YJ">永久</option>
          </select></label
        >
        <label class="list-filter-field"
          ><span>档案状态</span
          ><select v-model="filters.state">
            <option value="">全部状态</option>
            <option value="DRAFT">草稿</option>
            <option value="PENDING_REVIEW">归档审批中</option>
            <option value="PUBLISHED">已归档</option>
          </select></label
        >
        <label class="list-filter-field">
          <span>原台账补录</span>
          <select
            v-model="filters.legacyLedgerStatus"
            :disabled="loading || busy"
            @change="
              page = 1;
              load();
            "
          >
            <option value="">全部补录状态</option>
            <option
              v-for="(label, code) in legacyLedgerLabels"
              :key="code"
              :value="code"
            >
              {{ label }}
            </option>
          </select>
        </label>
        <DateRangePreset
          label="到期日期范围"
          :from="filters.expiryFrom"
          :to="filters.expiryTo"
          :busy="loading || busy"
          @apply="
            filters.expiryFrom = $event.from;
            filters.expiryTo = $event.to;
            page = 1;
            load();
          "
        />
        <label class="list-filter-field"
          ><span>到期开始</span
          ><input
            v-model="filters.expiryFrom"
            type="date"
            :max="filters.expiryTo || undefined"
            aria-label="到期开始日期" /></label
        ><label class="list-filter-field"
          ><span>到期结束</span
          ><input
            v-model="filters.expiryTo"
            type="date"
            :min="filters.expiryFrom || undefined"
            aria-label="到期结束日期"
        /></label>
        <div class="filter-actions">
          <label class="keyword-filter list-filter-field"
            ><span>综合检索</span
            ><input
              v-model="filters.keyword"
              placeholder="关键词或台账描述（可选）"
              maxlength="200"
          /></label>
          <button class="primary" :disabled="loading || busy">搜索</button
          ><button
            type="button"
            class="secondary"
            :disabled="busy"
            @click="
              Object.assign(filters, emptyFilters);
              page = 1;
              load();
            "
          >
            重置
          </button>
        </div>
      </form>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <p
        v-if="progress && !fileDialog?.open && !checklistDialog?.open"
        role="status"
      >
        {{ progress }}
      </p>
      <div class="ledger-caption">
        <div>
          <h2>档案盒台账</h2>
          <span>展开档案盒查看电子清单及附件</span>
        </div>
        <span class="location-rule"
          >位置规则：柜号＋柜 / 列号＋列 / 层号＋层</span
        >
      </div>
      <div class="ledger-table-scroll">
        <table class="box-table">
          <colgroup>
            <col class="expander-col" />
            <col class="number-col" />
            <col class="title-col" />
            <col />
            <col />
            <col />
            <col />
            <col />
            <col />
            <col class="legacy-ledger-col" />
            <col class="actions-col" />
          </colgroup>
          <thead>
            <tr>
              <th></th>
              <th>档案编号</th>
              <th>档案名称</th>
              <th>存放位置</th>
              <th>保存类型</th>
              <th>保存份数</th>
              <th>保密级别</th>
              <th>有效期</th>
              <th>档案状态</th>
              <th>原台账补录</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="box in boxes" :key="box.id">
              <tr :class="{ 'expanded-box': expanded.includes(box.id) }">
                <td>
                  <button
                    class="expand-control"
                    :disabled="fetching.includes(box.id)"
                    :aria-expanded="expanded.includes(box.id)"
                    :aria-label="`${expanded.includes(box.id) ? '折叠' : '展开'}${box.title}`"
                    @click="toggle(box)"
                  >
                    <Icon
                      name="chevron"
                      :class="{ rotated: expanded.includes(box.id) }"
                      :size="17"
                    />
                  </button>
                </td>
                <td class="box-number">
                  {{ number(box)
                  }}<small v-if="number(box) !== box.boxNo">{{
                    box.boxNo
                  }}</small>
                </td>
                <td>
                  <button class="box-title" @click="toggle(box)">
                    {{ box.title }}</button
                  ><small>{{ box.recordCount }} 条清单</small>
                </td>
                <td>
                  {{ archiveLocation(box.extensionValues, box.location) }}
                </td>
                <td>
                  {{
                    { PAPER: "纸质", ELECTRONIC: "电子", MIXED: "纸质+电子" }[
                      box.carrierType
                    ]
                  }}
                  <small
                    v-for="tag in (box.extensionValues.paperTags as string[]) ||
                    []"
                    :key="tag"
                    class="paper-tag"
                    >{{ tag }}</small
                  >
                </td>
                <td>{{ value(box, "copyCount") }}</td>
                <td>
                  <span class="classification">{{
                    classifications[box.classification]
                  }}</span>
                </td>
                <td>
                  {{
                    box.retentionCode === "YJ"
                      ? "永久"
                      : `${Number(box.retentionCode)}年`
                  }}<small v-if="box.expiryDate">至 {{ box.expiryDate }}</small>
                </td>
                <td>
                  <span class="state-badge" :data-state="box.state">{{
                    archiveStates[box.state] || box.state
                  }}</span
                  ><small v-if="box.extensionValues.sourceStatus"
                    >原台账：{{ box.extensionValues.sourceStatus }}</small
                  >
                </td>
                <td>
                  <select
                    class="legacy-ledger-select"
                    :aria-label="`原台账补录状态，${box.title}`"
                    :value="box.legacyLedgerStatus"
                    :disabled="busy || loading"
                    @change="markLegacyLedger(box, $event)"
                  >
                    <option
                      v-for="(label, code) in legacyLedgerLabels"
                      :key="code"
                      :value="code"
                    >
                      {{ label }}
                    </option>
                  </select>
                </td>
                <td>
                  <div class="row-actions">
                    <button
                      v-if="box.state === 'PUBLISHED'"
                      @click="
                        borrowDialog?.open([
                          {
                            objectType: 'BOX',
                            objectId: box.id,
                            label: box.title,
                          },
                        ])
                      "
                    >
                      借阅
                    </button>
                    <button @click="maintenance?.open('BOX', box.id)">
                      修改</button
                    ><button
                      class="danger-text"
                      @click="maintenance?.open('BOX', box.id, true)"
                    >
                      删除</button
                    ><button
                      :disabled="busy || box.state !== 'DRAFT'"
                      @click="review(box)"
                    >
                      归档审批</button
                    ><button
                      :disabled="busy || box.state !== 'DRAFT'"
                      @click="openChecklist(box)"
                    >
                      补录
                    </button>
                  </div>
                </td>
              </tr>
              <tr v-if="expanded.includes(box.id)" class="contents-row">
                <td colspan="11">
                  <section class="box-contents">
                    <header>
                      <div>
                        <h3>
                          电子清单
                          <span>{{ contents[box.id]?.length || 0 }} 条</span>
                        </h3>
                        <p>
                          存档份数为纸质档份数；同一条目仅需一份电子文件。按序号升序排列。
                        </p>
                      </div>
                      <button
                        class="secondary"
                        :disabled="
                          busy ||
                          box.state !== 'DRAFT' ||
                          !contents[box.id]?.length
                        "
                        @click="openFiles(box)"
                      >
                        <Icon name="upload" :size="16" />批量上传文档
                      </button>
                    </header>
                    <table class="attachment-table">
                      <thead>
                        <tr>
                          <th class="sequence-col">序号</th>
                          <th>名称</th>
                          <th>日期</th>
                          <th>签订主体</th>
                          <th>合同编号</th>
                          <th class="copies-col">份数</th>
                          <th class="notes-col">备注</th>
                          <th class="attachment-actions-col">操作</th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr v-for="line in contents[box.id]" :key="line.id">
                          <td class="sequence-number">{{ line.itemNo }}</td>
                          <td>
                            <strong>{{ line.title }}</strong
                            ><small v-if="line.electronicState === 'READY'"
                              >{{ line.originalName }} ·
                              {{ line.pageCount }} 页</small
                            ><small
                              v-else-if="line.electronicState === 'FAILED'"
                              class="error-text"
                              >{{ line.errorMessage }}
                              <button
                                :disabled="busy"
                                @click="retry(line, box)"
                              >
                                重新处理
                              </button></small
                            ><small v-else>{{
                              line.electronicVersionId
                                ? "文档待处理"
                                : line.candidatePdfVersionId
                                  ? "已有候选文档"
                                  : "待上传电子文件"
                            }}</small>
                          </td>
                          <td>
                            {{
                              line.formedDate ||
                              line.extensionValues.documentYear ||
                              "—"
                            }}
                          </td>
                          <td>
                            {{ line.extensionValues.signingEntity || "—" }}
                          </td>
                          <td>
                            {{ line.extensionValues.contractNumber || "—" }}
                          </td>
                          <td>{{ line.extensionValues.copyCount ?? 1 }}</td>
                          <td>{{ line.notes || "—" }}</td>
                          <td>
                            <div class="row-actions">
                              <button
                                v-if="
                                  line.state === 'PUBLISHED' &&
                                  box.state === 'PUBLISHED'
                                "
                                @click="
                                  borrowDialog?.open([
                                    {
                                      objectType: 'RECORD',
                                      objectId: line.id,
                                      label: line.title,
                                    },
                                  ])
                                "
                              >
                                借阅
                              </button>
                              <button @click="view(line, box)">查看</button
                              ><button
                                :disabled="!line.canDownload"
                                :title="
                                  line.canDownload
                                    ? '下载已批准的文档副本'
                                    : '归档并取得下载批准后可用'
                                "
                                @click="exports?.open([line], 'DOWNLOAD')"
                              >
                                下载</button
                              ><button
                                :disabled="!line.canPrint"
                                :title="
                                  line.canPrint
                                    ? '打印已批准的文档副本'
                                    : '归档并取得打印批准及额度后可用'
                                "
                                @click="exports?.open([line], 'PRINT')"
                              >
                                打印</button
                              ><button
                                :disabled="busy || box.state !== 'DRAFT'"
                                @click="openFiles(box, line)"
                              >
                                上传</button
                              ><button
                                class="danger-text"
                                :disabled="busy"
                                @click="
                                  maintenance?.open('RECORD', line.id, true)
                                "
                              >
                                删除
                              </button>
                            </div>
                          </td>
                        </tr>
                        <tr v-if="!contents[box.id]?.length">
                          <td colspan="8" class="empty-cell">
                            还没有清单条目。点击“电子清单”上传 Excel
                            清单后，再上传对应文档。
                          </td>
                        </tr>
                      </tbody>
                    </table>
                  </section>
                </td>
              </tr>
            </template>
            <tr v-if="!boxes.length">
              <td colspan="11" class="empty-cell">
                {{ loading ? "正在加载档案台账…" : "暂无符合条件的档案盒" }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="list-footer">
        <button
          type="button"
          class="secondary list-refresh"
          :disabled="loading || busy"
          @click="load"
        >
          刷新列表
        </button>
        <div class="list-pagination">
          <span class="list-total">共 {{ total }} 个档案盒</span
          ><button
            class="secondary"
            :disabled="page <= 1 || loading"
            @click="
              page--;
              load();
            "
          >
            上一页</button
          ><span>{{ page }} / {{ Math.max(1, Math.ceil(total / 20)) }}</span
          ><button
            class="secondary"
            :disabled="page * 20 >= total || loading"
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

    <dialog
      ref="registerDialog"
      class="form-dialog flow-dialog ledger-dialog"
      aria-labelledby="archive-register-title"
      @cancel="busy && $event.preventDefault()"
    >
      <form class="flow-dialog-shell" @submit.prevent="register">
        <header class="flow-dialog-header">
          <span class="flow-dialog-icon"
            ><Icon name="archive" :size="22"
          /></span>
          <div class="flow-dialog-title">
            <span class="eyebrow">档案登记</span>
            <h2 id="archive-register-title">新建档案盒台账</h2>
          </div>
          <button
            type="button"
            class="icon-button"
            aria-label="关闭弹窗"
            :disabled="busy"
            @click="registerDialog?.close()"
          >
            ×
          </button>
        </header>
        <div class="flow-dialog-body">
          <section class="flow-dialog-overview">
            <h3>登记说明</h3>
            <p class="helper">
              可选用 Excel 电子清单，保存登记时将清单及条目关联到本盒。
            </p>
          </section>
          <p v-if="error" class="form-error">{{ error }}</p>
          <ArchiveBoxFields
            :key="registrationNumber"
            ref="registrationFields"
            v-model="entry"
            :fields="template?.fields || []"
            :file-types="fileTypes"
            :departments="departments"
            :owners="owners"
            @department-change="changeEntryDepartment"
          >
            <template #checklist>
              <div class="register-location-field">
                <span>电子清单</span>
                <div class="registration-checklist">
                  <button
                    type="button"
                    class="secondary"
                    :disabled="busy || !entry.departmentId"
                    @click="openRegistrationChecklist"
                  >
                    {{
                      stagedChecklist
                        ? `已选用 ${stagedChecklist.count} 条`
                        : "上传电子清单"
                    }}
                  </button>
                  <small v-if="stagedChecklist" :title="stagedChecklist.name">{{
                    stagedChecklist.name
                  }}</small>
                </div>
              </div>
            </template>
          </ArchiveBoxFields>
        </div>
        <footer class="flow-dialog-footer">
          <button
            type="button"
            class="secondary"
            :disabled="busy"
            @click="registerDialog?.close()"
          >
            取消</button
          ><button class="primary" :disabled="busy">
            {{ busy ? "保存中…" : "保存登记" }}
          </button>
        </footer>
      </form>
    </dialog>

    <dialog
      ref="checklistDialog"
      class="form-dialog flow-dialog ledger-dialog"
      aria-labelledby="archive-checklist-title"
      @cancel="busy && $event.preventDefault()"
    >
      <form class="flow-dialog-shell" @submit.prevent="saveChecklistItem">
        <header class="flow-dialog-header">
          <span class="flow-dialog-icon"><Icon name="file" :size="22" /></span>
          <div class="flow-dialog-title">
            <span class="eyebrow">{{
              checklistMode === "manual" ? selectedBox?.boxNo : "档案登记"
            }}</span>
            <h2 id="archive-checklist-title">
              {{ checklistMode === "manual" ? "补录电子清单" : "上传电子清单" }}
            </h2>
          </div>
          <button
            type="button"
            class="icon-button"
            aria-label="关闭弹窗"
            :disabled="busy"
            @click="checklistDialog?.close()"
          >
            ×
          </button>
        </header>
        <div class="flow-dialog-body">
          <section
            v-if="checklistMode === 'manual'"
            class="flow-dialog-section"
          >
            <div
              class="entry-grid checklist-entry-grid"
              @input="checklistSaveError = ''"
            >
              <label>序号<input value="保存时自动编号" readonly /></label>
              <label>
                <span>存放内容<span aria-hidden="true"> *</span></span>
                <input
                  v-model.trim="checklistItem.title"
                  required
                  maxlength="512"
              /></label>
              <label
                >日期<input
                  v-model.trim="checklistItem.date"
                  placeholder="YYYY 或 YYYY-MM-DD"
                  maxlength="10"
              /></label>
              <label
                >签订主体<input
                  v-model.trim="checklistItem.signingEntity"
                  maxlength="512"
              /></label>
              <label
                >合同编号<input
                  v-model.trim="checklistItem.contractNumber"
                  maxlength="256"
              /></label>
              <label>
                <span>纸质份数<span aria-hidden="true"> *</span></span>
                <input
                  v-model.number="checklistItem.paperCopies"
                  type="number"
                  required
                  min="1"
                  max="1000000"
                  step="1"
              /></label>
              <label class="wide-field"
                >备注<textarea
                  v-model.trim="checklistItem.notes"
                  maxlength="2000"
                  rows="2"
                />
              </label>
            </div>
          </section>
          <section
            v-if="checklistMode === 'manual'"
            class="flow-dialog-section"
            aria-labelledby="saved-checklist-title"
          >
            <h3 id="saved-checklist-title">清单内容</h3>
            <p v-if="checklistLoadError" class="form-error" role="alert">
              {{ checklistLoadError }}
            </p>
            <p v-else-if="checklistRows === null" class="helper" role="status">
              正在读取清单…
            </p>
            <p v-else-if="!checklistRows.length" class="helper">
              当前档案盒还没有清单条目。
            </p>
            <template v-else>
              <p class="helper">共 {{ checklistRows.length }} 条</p>
              <div
                class="preview-table saved-checklist-table"
                tabindex="0"
                role="region"
                aria-label="已保存的清单条目"
              >
                <table>
                  <thead>
                    <tr>
                      <th scope="col">序号</th>
                      <th scope="col">存放内容</th>
                      <th scope="col">日期</th>
                      <th scope="col">签订主体</th>
                      <th scope="col">合同编号</th>
                      <th scope="col">纸质份数</th>
                      <th scope="col">备注</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="line in checklistRows" :key="line.id">
                      <td>{{ line.itemNo }}</td>
                      <td>{{ line.title }}</td>
                      <td>
                        {{
                          line.formedDate ||
                          line.extensionValues.documentYear ||
                          "—"
                        }}
                      </td>
                      <td>{{ line.extensionValues.signingEntity || "—" }}</td>
                      <td>{{ line.extensionValues.contractNumber || "—" }}</td>
                      <td>{{ line.extensionValues.copyCount ?? 1 }}</td>
                      <td>{{ line.notes || "—" }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </template>
          </section>
          <section
            v-if="checklistMode === 'registration'"
            class="flow-dialog-overview"
          >
            <h3>上传说明</h3>
            <p class="helper">
              清单表头：存放编号、序号、日期、签订主体、存放内容、合同编号、存档份数、备注。
              按表头名称对应，列顺序可调整；存放编号、签订主体和合同编号可省略或留空，兼容原六列清单。
              日期可填年份（如2021）或完整日期。新建档案盒时，存放编号可留空，填写时须与档案盒编号或原档案编号一致。支持
              WPS / Excel 保存的 .xlsx。
            </p>
            <p class="helper">
              可上传一条或多条，序号须唯一。保存档案登记时会将原件与清单条目关联到本盒。
            </p>
          </section>
          <section
            v-if="checklistMode === 'registration'"
            class="flow-dialog-section"
          >
            <label class="file-field"
              >选择清单文件<input
                :key="inputGeneration"
                type="file"
                accept=".xlsx"
                :disabled="busy"
                @change="
                  checklistFile =
                    ($event.target as HTMLInputElement).files?.[0] || null;
                  preview = null;
                "
            /></label>
          </section>
          <p v-if="progress" role="status">{{ progress }}</p>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <section
            v-if="checklistMode === 'registration' && preview"
            class="flow-dialog-section"
          >
            <h3>本次上传预览</h3>
            <div v-if="preview.items.length" class="preview-table">
              <table>
                <thead>
                  <tr>
                    <th>序号</th>
                    <th>存放内容</th>
                    <th>日期</th>
                    <th>签订主体</th>
                    <th>合同编号</th>
                    <th>纸质份数</th>
                    <th>备注</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="line in preview.items" :key="line.sequence">
                    <td>{{ line.sequence }}</td>
                    <td>{{ line.title }}</td>
                    <td>{{ line.date || line.year || "—" }}</td>
                    <td>{{ line.signingEntity || "—" }}</td>
                    <td>{{ line.contractNumber || "—" }}</td>
                    <td>{{ line.paperCopies }}</td>
                    <td>{{ line.notes || "—" }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
            <p class="helper">共 {{ preview.items.length }} 条。</p>
          </section>
        </div>
        <footer class="flow-dialog-footer">
          <p
            v-if="checklistMode === 'manual' && checklistSaveError"
            class="form-error checklist-save-error"
            role="alert"
          >
            {{ checklistSaveError }}
          </p>
          <button
            type="button"
            class="secondary"
            :disabled="busy"
            @click="checklistDialog?.close()"
          >
            取消
          </button>
          <button
            v-if="checklistMode === 'manual'"
            type="submit"
            class="primary"
            :disabled="busy"
          >
            保存补录
          </button>
          <template v-else>
            <button
              v-if="stagedChecklist"
              type="button"
              class="text-button"
              :disabled="busy"
              @click="
                stagedChecklist = null;
                checklistDialog?.close();
              "
            >
              移除已选清单
            </button>
            <button
              v-if="!preview"
              type="button"
              class="primary"
              :disabled="busy || !checklistFile"
              @click="previewChecklist"
            >
              {{ busy ? "读取中…" : "读取并预览" }}</button
            ><button
              v-else-if="preview.items.length"
              type="button"
              class="primary"
              :disabled="busy"
              @click="confirmChecklist"
            >
              使用这份清单
            </button>
          </template>
        </footer>
      </form>
    </dialog>

    <dialog
      ref="fileDialog"
      class="form-dialog flow-dialog ledger-dialog"
      aria-labelledby="archive-upload-title"
      @cancel="busy && $event.preventDefault()"
    >
      <div class="flow-dialog-shell">
        <header class="flow-dialog-header">
          <span class="flow-dialog-icon"
            ><Icon name="upload" :size="22"
          /></span>
          <div class="flow-dialog-title">
            <span class="eyebrow">{{ selectedBox?.boxNo }}</span>
            <h2 id="archive-upload-title">批量上传电子文档</h2>
          </div>
          <button
            type="button"
            class="icon-button"
            aria-label="关闭弹窗"
            :disabled="busy"
            @click="fileDialog?.close()"
          >
            ×
          </button>
        </header>
        <div class="flow-dialog-body">
          <section class="flow-dialog-overview">
            <h3>上传说明</h3>
            <p class="helper">
              按“序号_名称”命名可自动对应，例如“1_房屋租赁合同.docx”。仅名称也可对应唯一条目。纸质档有多份时，电子文件只选一份；替换会保留旧版本。
            </p>
          </section>
          <section class="flow-dialog-section">
            <label class="file-field"
              >选择电子文件<input
                :key="inputGeneration"
                type="file"
                :multiple="!uploadLine"
                accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx"
                :disabled="busy || boundDocuments"
                @change="chooseDocuments"
            /></label>
            <p class="helper">
              支持 PDF 和 WPS / Office 的 Word、Excel、PowerPoint
              常用格式；每份上限 50 MB，每批最多100份。
            </p>
          </section>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <p v-if="progress" role="status">{{ progress }}</p>
          <p v-if="hasDuplicateMapping" class="form-error">
            一条清单只能选择一份电子文件，请调整重复对应。
          </p>
          <section v-if="mappings.length" class="flow-dialog-section">
            <h3>文件与清单对应</h3>
            <div class="preview-table mapping-table">
              <table>
                <thead>
                  <tr>
                    <th>所选文件</th>
                    <th>对应清单条目</th>
                    <th>状态</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(mapping, index) in mappings" :key="index">
                    <td>
                      {{ mapping.file.name }}<small>{{ mapping.reason }}</small>
                    </td>
                    <td>
                      <select
                        v-model="mapping.recordId"
                        :disabled="busy || boundDocuments"
                        @change="mapping.uploadId = ''"
                      >
                        <option value="">请选择对应条目</option>
                        <option
                          v-for="line in contents[selectedBox!.id]"
                          :key="line.id"
                          :value="line.id"
                        >
                          {{ line.itemNo }} · {{ line.title
                          }}{{
                            line.electronicVersionId ? "（替换原文件）" : ""
                          }}
                        </option>
                      </select>
                    </td>
                    <td>{{ mapping.status }}</td>
                    <td>
                      <button
                        class="text-button"
                        :disabled="busy || boundDocuments"
                        @click="mappings.splice(index, 1)"
                      >
                        移除
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </div>
        <footer class="flow-dialog-footer">
          <button
            class="secondary"
            :disabled="busy"
            @click="fileDialog?.close()"
          >
            {{ boundDocuments ? "完成" : "取消" }}</button
          ><button
            class="primary"
            :disabled="busy || !readyMappings || boundDocuments"
            @click="uploadDocuments"
          >
            {{ busy ? "正在上传和处理…" : "确认对应并上传" }}
          </button>
        </footer>
      </div>
    </dialog>

    <dialog
      ref="detailDialog"
      class="form-dialog flow-dialog ledger-dialog detail-dialog"
      aria-labelledby="archive-file-detail-title"
    >
      <div class="flow-dialog-shell">
        <header class="flow-dialog-header">
          <span class="flow-dialog-icon"><Icon name="file" :size="22" /></span>
          <div class="flow-dialog-title">
            <span class="eyebrow"
              >存放编号 · {{ selectedBox ? number(selectedBox) : "" }}</span
            >
            <h2 id="archive-file-detail-title">{{ detail?.title }}</h2>
          </div>
          <button
            type="button"
            class="icon-button"
            aria-label="关闭弹窗"
            @click="detailDialog?.close()"
          >
            ×
          </button>
        </header>
        <div v-if="detail" class="flow-dialog-body">
          <section class="flow-dialog-section">
            <h3>文件登记与电子附件</h3>
            <dl class="details-grid">
              <div>
                <dt>序号</dt>
                <dd>{{ detail.itemNo }}</dd>
              </div>
              <div>
                <dt>文件编号</dt>
                <dd>{{ detail.fileNo }}</dd>
              </div>
              <div>
                <dt>日期</dt>
                <dd>{{ detail.formedDate || "未登记" }}</dd>
              </div>
              <div>
                <dt>纸质存档份数</dt>
                <dd>{{ detail.extensionValues.copyCount ?? 1 }} 份</dd>
              </div>
              <div>
                <dt>电子文件</dt>
                <dd>{{ detail.originalName || "尚未上传" }}</dd>
              </div>
              <div>
                <dt>阅读页数</dt>
                <dd>{{ detail.pageCount || "—" }}</dd>
              </div>
              <div class="wide-field">
                <dt>备注</dt>
                <dd>{{ detail.notes || "无" }}</dd>
              </div>
            </dl>
          </section>
          <p v-if="detail.errorMessage" class="form-error">
            {{ detail.errorMessage }}
          </p>
          <p class="helper">
            修改中可查看和维护完整登记信息；历史版本和操作记录按原规则保留。
          </p>
        </div>
        <footer class="flow-dialog-footer">
          <button
            class="secondary"
            @click="
              detailDialog?.close();
              maintenance?.open('RECORD', detail!.id);
            "
          >
            查看全部信息 / 修改</button
          ><button
            class="primary"
            :disabled="!detail || !canRead(detail)"
            @click="read(detail!)"
          >
            阅读文档
          </button>
        </footer>
      </div>
    </dialog>

    <dialog
      ref="reviewDialog"
      class="form-dialog flow-dialog ledger-dialog"
      aria-labelledby="archive-review-title"
      @cancel="busy && $event.preventDefault()"
    >
      <form class="flow-dialog-shell" @submit.prevent="submitReview">
        <header class="flow-dialog-header">
          <span class="flow-dialog-icon"><Icon name="task" :size="22" /></span>
          <div class="flow-dialog-title">
            <span class="eyebrow">{{ selectedBox?.boxNo }}</span>
            <h2 id="archive-review-title">
              归档审批 · {{ selectedBox?.title }}
            </h2>
          </div>
          <button
            type="button"
            class="icon-button"
            aria-label="关闭弹窗"
            :disabled="busy"
            @click="reviewDialog?.close()"
          >
            ×
          </button>
        </header>
        <div class="flow-dialog-body">
          <section class="flow-dialog-overview">
            <h3>送审说明</h3>
            <p class="helper">
              请先核对台账和纸质档，并逐份检查电子文件的内容、页序与清晰度。档案超级管理员本人登记的档案盒可直接归档，其余由部门负责人及档案超级管理员复核。
            </p>
          </section>
          <p v-if="missingDocuments.length" class="form-error">
            还有
            {{ missingDocuments.length }} 条清单未完成电子文件处理，请先补齐。
          </p>
          <p v-if="error" class="form-error">{{ error }}</p>
          <section v-if="reviewLines.length" class="flow-dialog-section">
            <h3>电子文档</h3>
            <div class="review-documents">
              <div v-for="line in reviewLines" :key="line.id">
                <span>{{ line.itemNo }} · {{ line.title }}</span
                ><span>{{
                  line.electronicState === "READY"
                    ? `${line.pageCount} 页`
                    : "待补齐"
                }}</span
                ><button
                  type="button"
                  class="text-button"
                  :disabled="line.electronicState !== 'READY'"
                  @click="read(line)"
                >
                  检查文档
                </button>
              </div>
            </div>
          </section>
          <section class="flow-dialog-section">
            <h3>检查确认</h3>
            <label class="long-field"
              >检查说明<textarea
                v-model="reviewNotes"
                required
                maxlength="2000"
                rows="3"
              /></label
            ><label class="check-label"
              ><input
                v-model="checked"
                type="checkbox"
              />我已检查本盒台账、清单及全部电子文档，确认信息对应、内容完整且清晰。</label
            >
          </section>
        </div>
        <footer class="flow-dialog-footer">
          <button
            type="button"
            class="secondary"
            :disabled="busy"
            @click="reviewDialog?.close()"
          >
            取消</button
          ><button
            class="primary"
            :disabled="busy || !checked || !!missingDocuments.length"
          >
            提交归档
          </button>
        </footer>
      </form>
    </dialog>
    <dialog
      ref="importDialog"
      class="form-dialog flow-dialog ledger-dialog import-dialog"
      aria-labelledby="archive-import-title"
      @cancel.prevent="closeImport"
    >
      <div class="flow-dialog-shell">
        <header class="flow-dialog-header">
          <span class="flow-dialog-icon"
            ><Icon name="upload" :size="22"
          /></span>
          <div class="flow-dialog-title">
            <span class="eyebrow">档案台账</span>
            <h2 id="archive-import-title">批量导入</h2>
          </div>
          <button
            type="button"
            class="icon-button"
            aria-label="关闭弹窗"
            @click="closeImport"
          >
            ×
          </button>
        </header>
        <div class="flow-dialog-body">
          <Imports v-if="importOpen" archive-only @changed="changed" />
        </div>
        <footer class="flow-dialog-footer">
          <button type="button" class="secondary" @click="closeImport">
            关闭
          </button>
        </footer>
      </div>
    </dialog>
    <Catalog ref="maintenance" embedded @changed="changed" /><ArchiveReader
      ref="reader"
      @failure="error = $event"
      @closed="
        if (returnToReview) reviewDialog?.showModal();
        returnToReview = false;
      "
    /><ArchiveExports ref="exports" @changed="changed" />
    <ArchiveBorrowDialog ref="borrowDialog" />
  </div>
</template>

<style scoped>
.archive-ledger {
  color: var(--ink);
  min-width: 0;
}
.ledger-heading {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 24px;
  margin-bottom: 28px;
}
.eyebrow {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 2px;
  color: var(--brand);
}
h1 {
  font-size: 28px;
  margin: 12px 0;
}
h2 {
  font-size: 18px;
  margin: 0;
}
h3 {
  font-size: 15px;
  margin: 0;
}
.ledger-heading p {
  color: var(--text-secondary);
  margin: 0;
}
.heading-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.heading-actions button,
.box-contents header button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.ledger-panel {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: 16px;
  padding: clamp(14px, 1.65vw, 24px);
  box-shadow: var(--shadow);
}
.ledger-filters {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  align-items: center;
  gap: 16px 24px;
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: clamp(14px, 1.65vw, 24px);
  margin-bottom: 20px;
}
.archive-ledger .ledger-panel .ledger-filters > label,
.ledger-filters > .ledger-filter-field {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  margin: 0;
  min-width: 0;
}
.ledger-filters label > span,
.ledger-filter-field > span {
  white-space: nowrap;
  font-size: 13px;
  color: var(--text-secondary);
}
.filter-range {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.filter-range > span {
  color: var(--text-secondary);
  font-size: 12px;
}
.filter-actions {
  grid-column: span 2;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}
.archive-ledger .ledger-panel .ledger-filters .keyword-filter {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  margin: 0 auto 0 0;
  flex: 1;
  min-width: 0;
}
.archive-ledger .ledger-panel .ledger-filters input,
.archive-ledger .ledger-panel .ledger-filters select {
  margin: 0;
  width: 100%;
  min-width: 0;
  box-sizing: border-box;
}
.filter-range input {
  flex: 1 1 0;
}
.ledger-caption {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin: 0 0 18px;
}
.ledger-caption > div {
  display: flex;
  align-items: center;
  gap: 16px;
}
.ledger-caption span,
.helper,
.location-rule {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.8;
}
.ledger-table-scroll {
  overflow-x: auto;
}
.box-table {
  width: 100%;
  table-layout: fixed;
  min-width: 1550px;
  border-collapse: collapse;
  font-size: 13px;
}
.expander-col {
  width: 40px;
}
.number-col {
  width: 210px;
}
.title-col {
  width: 235px;
}
.actions-col {
  width: 240px;
}
.legacy-ledger-col {
  width: 120px;
}
.legacy-ledger-select {
  min-height: 34px;
  padding: 5px 8px;
}
th {
  text-align: left;
  font-weight: 600;
  color: var(--text-secondary);
  background: var(--surface-muted);
  font-size: 13px;
}
th,
td {
  padding: 17px 10px;
  border-bottom: 1px solid var(--line);
  vertical-align: middle;
  overflow-wrap: anywhere;
}
.box-table > tbody > tr:not(.contents-row) > td {
  height: 72px;
}
.box-number {
  white-space: normal;
  word-break: break-all;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--text-secondary);
}
.box-title {
  white-space: normal;
  overflow-wrap: anywhere;
  font: inherit;
  font-size: 15px;
  font-weight: 600;
  color: var(--ink);
  background: none;
  border: 0;
  text-align: left;
  padding: 0;
  line-height: 1.6;
}
.expand-control .rotated {
  transform: rotate(90deg);
}
.expand-control {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 7px;
  background: var(--surface-muted);
  color: var(--text-secondary);
  padding: 0;
}
small {
  display: block;
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.6;
  margin-top: 7px;
}
.row-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.row-actions button,
.error-text button {
  border: 0;
  background: transparent;
  color: var(--brand);
  font: inherit;
  font-size: 13px;
  padding: 4px 0;
  white-space: nowrap;
}
.row-actions button:disabled {
  color: var(--text-secondary);
  cursor: not-allowed;
}
.row-actions .danger-text {
  color: var(--danger);
}
.classification,
.state-badge {
  display: inline-block;
  padding: 5px 8px;
  background: var(--surface-muted);
  border-radius: 5px;
  font-size: 12px;
  white-space: nowrap;
}
.state-badge[data-state="PUBLISHED"] {
  background: var(--success-soft);
  color: var(--success);
}
.state-badge[data-state="PENDING_REVIEW"] {
  background: var(--warning-soft);
  color: var(--warning);
}
.expanded-box {
  background: var(--brand-soft);
}
.contents-row > td {
  padding: 0 0 20px;
  background: var(--brand-soft);
}
.box-contents {
  margin: 0 16px 0 40px;
  border: 1px solid var(--line);
  background: var(--surface);
  border-radius: 10px;
  overflow: hidden;
}
.box-contents header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 20px 22px;
}
.box-contents header p {
  color: var(--text-secondary);
  font-size: 12px;
  margin: 7px 0 0;
}
.box-contents h3 span {
  margin-left: 8px;
  font-weight: 400;
  color: var(--text-secondary);
}
.attachment-table {
  width: 100%;
  min-width: 1120px;
  border-collapse: collapse;
  font-size: 13px;
}
.attachment-table th,
.attachment-table td {
  padding: 16px;
}
.sequence-col {
  width: 72px;
}
.copies-col {
  width: 65px;
}
.notes-col {
  width: 16%;
}
.attachment-actions-col {
  width: 265px;
}
.sequence-number {
  font-variant-numeric: tabular-nums;
  color: var(--brand);
  font-weight: 600;
}
.attachment-table strong {
  font-weight: 500;
}
.empty-cell {
  text-align: center;
  color: var(--text-secondary);
  padding: 60px 20px;
}
.error-text {
  color: var(--danger);
}
.ledger-dialog {
  --flow-dialog-width: 960px;
}
.entry-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 24px;
}
.checklist-entry-grid {
  --entry-label-width: 9em;
}
.checklist-save-error {
  flex-basis: 100%;
  margin: 0;
}
.archive-ledger dialog.ledger-dialog .entry-grid > label:not(.wide-field),
.register-location-field {
  display: grid;
  grid-template-columns: var(--entry-label-width, 105px) minmax(0, 1fr);
  align-items: center;
  align-self: start;
  gap: 12px;
  margin: 0;
  font-size: 14px;
}
.entry-grid input,
.entry-grid select {
  min-width: 0;
  width: 100%;
  margin: 0;
}
.wide-field {
  grid-column: 1/-1;
}
.ledger-dialog :deep(.catalog-fields) {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 24px;
  margin: 0;
}
.registration-checklist {
  min-width: 0;
}
.registration-checklist .secondary {
  width: 100%;
  justify-content: flex-start;
}
.registration-checklist small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-top: 4px;
}
.archive-ledger .ledger-dialog :deep(.catalog-fields > label) {
  display: grid;
  grid-template-columns: 105px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  margin: 0;
  font-size: 14px;
}
.ledger-dialog :deep(.catalog-fields small) {
  grid-column: 2;
  margin-top: 0;
}
.ledger-dialog :deep(.catalog-fields input) {
  min-width: 0;
  margin: 0;
}
.file-field {
  display: block;
  padding: 22px;
  background: var(--surface-muted);
  border: 1px dashed var(--brand);
  border-radius: 10px;
  margin: 0;
  font-weight: 500;
}
.file-field input {
  display: block;
  margin: 12px 0 0;
  width: 100%;
  background: var(--surface);
}
.preview-table {
  max-height: 390px;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 9px;
  margin-top: 22px;
}
.saved-checklist-table {
  max-height: min(32vh, 16rem);
}

.preview-table table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.preview-table th {
  position: sticky;
  top: 0;
}
.mapping-table th:first-child {
  width: 33%;
}
.mapping-table select {
  width: 100%;
  min-width: 220px;
  font-size: 13px;
}
.mapping-table td {
  vertical-align: top;
}
.details-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 28px;
  margin: 0;
}
.details-grid dt {
  font-size: 13px;
  color: var(--text-secondary);
  margin-bottom: 10px;
}
.details-grid dd {
  margin: 0;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.details-grid > div {
  padding: 12px;
  background: var(--surface-muted);
  border-radius: 8px;
}
.review-documents > div {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
  padding: 16px 0;
  border-bottom: 1px solid var(--line);
  font-size: 14px;
}
.review-documents > div > span:first-child {
  flex: 1;
}
.long-field {
  margin: 0;
}
.check-label {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  font-size: 13px;
  line-height: 1.7;
}
.check-label input {
  width: 17px;
  height: 17px;
  margin: 3px 0 0;
}
.import-dialog {
  --flow-dialog-width: 1320px;
}
.import-dialog :deep(.page-header) {
  display: none;
}
@media (max-width: 1700px) {
  .ledger-filters {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .filter-actions {
    grid-column: 1 / -1;
  }
}
@media (max-width: 1400px) {
  .ledger-filters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (max-width: 1200px) {
  .ledger-heading {
    align-items: flex-start;
  }
  .ledger-caption {
    align-items: flex-start;
    flex-direction: column;
    gap: 8px;
  }
}
@media (max-width: 800px) {
  .ledger-dialog :deep(.catalog-fields),
  .entry-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .ledger-filters {
    grid-template-columns: minmax(0, 1fr);
    padding: 18px;
    gap: 18px;
  }
  .filter-actions {
    flex-wrap: wrap;
  }
  .archive-ledger .ledger-panel .ledger-filters .keyword-filter {
    width: 100%;
    flex-basis: 100%;
  }
}
.paper-tag {
  display: inline-block;
  margin: 4px 4px 0 0;
  padding: 2px 7px;
  border-radius: 5px;
  background: var(--brand-soft);
  color: var(--brand-hover);
}
@media (max-width: 540px) {
  .ledger-filters .filter-range { flex-direction: column; align-items: stretch; }
  .ledger-filters .filter-range input { flex: auto; }
  .ledger-filters .filter-range > span { text-align: center; }
  .ledger-caption > div { flex-wrap: wrap; gap: 8px; }
}
</style>
