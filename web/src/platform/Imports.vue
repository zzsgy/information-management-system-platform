<script setup lang="ts">
import ListFilters from "./ListFilters.vue";
import { recentWeekFilters } from "./listDates";
import { computed, inject, onMounted, onUnmounted, ref, watch } from "vue";
import { api, can, COLLECTION, dateTime, mutate, profile, query } from "./api";
import { interactionRequest } from "./interaction";
import { uploadArchivePdf } from "./archiveFiles";
import { companyEmailFromName } from "./companyEmail";
const props = defineProps<{
  archiveOnly?: boolean;
  /** 人员管理弹窗仅处理员工账号，不展示导入领域和历史切换。 */
  employeeOnly?: boolean;
  /** 从业务入口进入时预选导入类型，仍以当前账号可用类型为准。 */
  initialType?: string;
}>();
const emit = defineEmits<{ changed: [] }>();
/** 导入批次只返回汇总，逐行内容独立分页。 */
interface Batch {
  /** 批次标识。 */ id: string;
  /** 导入领域。 */ importType: string;
  /** 当前状态。 */ state: string;
  /** 有数据的原表行数。 */ rowCount: number;
  /** 错误行数。 */ errorCount: number;
  /** 预检规范摘要。 */ validationDigest: string;
  /** 并发版本。 */ rowVersion: number;
  /** 创建时刻。 */ createdAt: string;
}
interface ImportRow {
  /** 原表行号。 */ rowNo: number;
  /** 原表字段。 */ input: Record<string, string>;
  /** 精确字段错误。 */ errors: { field: string; message: string }[];
  /** 处理结果。 */ result: string;
  /** 新增对象。 */ objectId: string | null;
}
/** 最小部门及模板选择信息。 */
interface Department {
  /** 标识。 */ id: string;
  /** 编码。 */ code: string;
  /** 名称。 */ name: string;
}
/** 支持直接保存的浏览器只请求本次选中文件的句柄，不持久保存文件权限。 */
interface SavePickerWindow extends Window {
  showSaveFilePicker?: (options: {
    suggestedName: string;
    types: { description: string; accept: Record<string, string[]> }[];
  }) => Promise<FileSystemFileHandle>;
}
const ask = inject<(title: string) => Promise<string | null>>("askPassword")!;
/** 当前领域切换清空旧对象，避免不同模板和部门混用。 */
const types = [
  { code: "ARCHIVE", name: "档案台账", visible: can("ARCHIVE.CATALOG_MANAGE") },
  { code: "EMPLOYEE", name: "员工账号", visible: can("PLATFORM.USERS_MANAGE") },
  {
    code: "DEPARTMENT",
    name: "组织部门",
    visible: can("PLATFORM.DEPARTMENTS_MANAGE"),
  },
].filter(
  (item) =>
    item.visible &&
    (!props.archiveOnly || item.code === "ARCHIVE") &&
    (!props.employeeOnly || item.code === "EMPLOYEE"),
);
/** 两个页签保留各自输入，记录页可独立选择导入类型。 */
const view = ref<"create" | "history">("create");
const type = ref(
    types.find((item) => item.code === props.initialType)?.code ??
      types[0]?.code ??
      "ARCHIVE",
  ),
  historyType = ref(type.value),
  archiveFormat = ref("BOX_LEDGER"),
  departments = ref<Department[]>([]),
  scope = ref<string[]>([]),
  columns = ref<string[]>([]),
  version = ref(""),
  templates = ref<{ id: string; name: string; objectType: string }[]>([]);
/** 员工预检错误使用模板中的中文列名。 */
const employeeColumnLabels = computed(
  () => new Map(columns.value.map((column) => column.split("|") as [string, string])),
);
/** 文件检测状态、批次及错误分页，页面只在内存保存已检测对象。 */
const file = ref<File | null>(null),
  uploadObject = ref(""),
  localEmployeeWorkbook = ref(false),
  busy = ref(false),
  error = ref(""),
  progress = ref(""),
  batches = ref<Batch[]>([]),
  /** 批次单独分页，历史记录不受逐行结果分页影响。 */
  batchPage = ref(1),
  batchTotal = ref(0),
  chosen = ref<Batch | null>(null),
  chosenView = ref(view.value),
  preflightBatch = ref<Batch | null>(null),
  rows = ref<ImportRow[]>([]),
  rowTotal = ref(0),
  page = ref(1),
  onlyErrors = ref(false),
  confirmed = ref(false),
  reason = ref("");
const downloading = ref(false),
  downloadNotice = ref(""),
  /** 按实际保存方式提示用户如何打开文件，取消或失败时不展示。 */
  downloadOpenHint = ref(""),
  templateUrl = ref(""),
  templateName = ref("");
const states: Record<string, string> = {
  READY: "预检通过",
  INVALID: "预检未通过",
  CONFIRMED: "已新增",
  WITHDRAWN: "已撤回",
  VALIDATED: "已校验",
  CREATED: "已创建",
  AWAITING_REVIEW: "待质量检查与复核",
};
const selectedNames = computed(() =>
  departments.value
    .filter((item) => scope.value.includes(item.id))
    .map((item) => item.name)
    .join("、"),
);
/** 异步结果代次防止切换领域后显示旧部门，关闭页面停止上传轮询。 */
let generation = 0,
  alive = true,
  operation = crypto.randomUUID();
async function load() {
  const current = ++generation;
  error.value = "";
  preflightBatch.value = null;
  progress.value = "";
  if (chosenView.value === "create") {
    chosen.value = null;
    rows.value = [];
  }
  uploadObject.value = "";
  file.value = null;
  confirmed.value = false;
  downloadNotice.value = "";
  downloadOpenHint.value = "";
  if (templateUrl.value) URL.revokeObjectURL(templateUrl.value);
  templateUrl.value = "";
  try {
    const options = await api<{
      version: string;
      columns: string[];
      departments: Department[];
      employeeImportScopeId?: string;
      localEmployeeWorkbook?: boolean;
    }>(
      `/imports/options/${type.value}?${query({ format: archiveFormat.value })}`,
    );
    let units = options.departments;
    if (type.value === "ARCHIVE") {
      const catalog = await api<{
        departments: Department[];
        templates: typeof templates.value;
      }>("/archive/catalog-options");
      units = catalog.departments;
      templates.value = catalog.templates;
    }
    if (current !== generation || !alive) return;
    version.value = options.version;
    localEmployeeWorkbook.value = options.localEmployeeWorkbook === true;
    columns.value = options.columns;
    departments.value = units;
    scope.value =
      type.value === "ARCHIVE"
        ? units.map((unit) => unit.id)
        : type.value === "EMPLOYEE"
          ? options.employeeImportScopeId ? [options.employeeImportScopeId] : []
          : units.length ? [units[0].id] : [];
  } catch (e) {
    if (current === generation) error.value = (e as Error).message;
  }
}
/** 批次摘要按领域查询，完整台账不随列表下载。 */
async function refresh() {
  const importType = historyType.value;
  try {
    const result = await api<{ items: Batch[]; total: number }>(
      `/imports/batches?${query({ importType, page: batchPage.value, pageSize: 20, ...listFilters.value })}`,
    );
    if (alive && importType === historyType.value) {
      batches.value = result.items;
      batchTotal.value = result.total;
    }
  } catch (e) {
    if (alive && importType === historyType.value) error.value = (e as Error).message;
  }
}
/** 切回导入时恢复本次预检批次，文件选择由原表单保留。 */
function openCreate() {
  if (view.value === "create") return;
  error.value = "";
  view.value = "create";
  if (preflightBatch.value && chosenView.value !== "create") void select(preflightBatch.value);
}
/** 首次打开记录时按最近使用的导入类型查询。 */
function openHistory() {
  if (view.value === "history") return;
  error.value = "";
  view.value = "history";
  if (!historyOpened) {
    historyOpened = true;
    if (historyType.value !== type.value) {
      historyType.value = type.value;
      return;
    }
  }
  void refresh();
}
let historyOpened = false;
/** 选件或范围变化后撤销页面中的旧预检确认。 */
function chooseFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] || null;
  uploadObject.value = "";
  operation = crypto.randomUUID();
}
/** 员工模板在本地另存权限受限时，可交由浏览器下载。取消仍停止本次操作。 */
function canFallbackTemplateDownload(failure: unknown) {
  return type.value === "EMPLOYEE" && failure instanceof DOMException &&
    ["NoModificationAllowedError", "NotAllowedError", "SecurityError", "InvalidStateError", "NotReadableError"].includes(failure.name);
}

/** 点击时立即由当前窗口选择保存位置；实际写入完成、取消与失败分别反馈。 */
async function downloadTemplate(event: MouseEvent) {
  if (downloading.value) return;
  const button = event.currentTarget as HTMLButtonElement;
  const name =
    type.value === "ARCHIVE"
      ? archiveFormat.value === "BOX_LEDGER"
        ? "档案盒通用登记模板.xlsx"
        : "文件关联登记模板.xlsx"
      : type.value === "EMPLOYEE"
        ? "员工账号导入模板.xlsx"
        : "组织部门导入模板.xlsx";
  downloading.value = true;
  downloadNotice.value = "";
  downloadOpenHint.value = "";
  error.value = "";
  let fallback = false;
  try {
    window.focus();
    const picker = (window as SavePickerWindow).showSaveFilePicker;
    // 原生保存框必须在用户点击时发起，先等待网络会丢失当前手势和窗口上下文。
    let handle: FileSystemFileHandle | null = null;
    if (picker) {
      downloadNotice.value = "请选择保存位置；若要取消，请关闭保存窗口。";
      try {
        handle = await picker.call(window, {
          suggestedName: name,
          types: [
            {
              description: "Excel 工作簿",
              accept: {
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":
                  [".xlsx"],
              },
            },
          ],
        });
      } catch (failure) {
        if (!canFallbackTemplateDownload(failure)) throw failure;
        fallback = true;
      }
    }
    downloadNotice.value = "正在获取并保存模板…";
    const response = await fetch(
      `/api/v1/imports/templates/${type.value}?${query({ format: archiveFormat.value })}`,
      {
        credentials: "same-origin",
        cache: "no-store",
        headers: {
          "X-Request-Id": interactionRequest(),
          "X-CSRF-Token": profile.value?.csrfToken ?? "",
          "X-User-Activity": "1",
        },
      },
    );
    if (!response.ok) {
      const result = await response.json();
      throw new Error(result.message || "模板下载失败，请重试。");
    }
    if (
      response.headers.get("Content-Type")?.split(";")[0] !==
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )
      throw new Error("模板响应不是 Excel 文件，请刷新页面后重试。");
    const blob = await response.blob();
    if (!blob.size) throw new Error("模板文件为空，请重试。");
    if (handle) {
      try {
        await blob.stream().pipeTo(await handle.createWritable());
        downloadNotice.value = `模板已保存：${handle.name}`;
        downloadOpenHint.value = "打开刚才选择的保存文件夹，双击模板文件即可打开。";
        return;
      } catch (failure) {
        if (!canFallbackTemplateDownload(failure)) throw failure;
        fallback = true;
      }
    }
    if (templateUrl.value) URL.revokeObjectURL(templateUrl.value);
    const url = URL.createObjectURL(blob),
      link = document.createElement("a");
    templateUrl.value = url;
    templateName.value = name;
    link.href = url;
    link.download = name;
    link.hidden = true;
    button.after(link);
    link.click();
    link.remove();
    downloadNotice.value = fallback
      ? "无法另存到所选位置，已改用浏览器下载。若未开始，可点击下方保存模板文件。"
      : "模板已准备。若未开始下载，可点击下方保存文件。";
    downloadOpenHint.value = "下载完成后，按 Ctrl+J 打开浏览器下载记录。点击文件名打开，或选择「在文件夹中显示」。";
  } catch (failure) {
    downloadNotice.value = "";
    if (failure instanceof DOMException && failure.name === "AbortError")
      downloadNotice.value = "已取消保存，可重新点击下载。";
    else if (
      failure instanceof DOMException &&
      failure.name === "SecurityError"
    )
      error.value = "请在当前浏览器窗口重新点击下载，选择保存位置。";
    else error.value = (failure as Error).message;
  } finally {
    downloading.value = false;
  }
}
/** 顺序上传和检测通过后才建立预检，任何错误不写业务目录。 */
async function preflight() {
  if (busy.value || !file.value || !scope.value.length) return;
  busy.value = true;
  error.value = "";
  progress.value = "";
  try {
    const selectedFile = file.value;
    const singleNode = type.value === "EMPLOYEE" && localEmployeeWorkbook.value;
    let workbook: string | undefined;
    if (singleNode) {
      if (selectedFile.size > 4 * 1024 * 1024)
        throw new Error("员工 Excel 文件须在 4 MB 以内");
      progress.value = "正在读取员工表";
      const bytes = new Uint8Array(await selectedFile.arrayBuffer());
      const sections: string[] = [];
      for (let offset = 0; offset < bytes.length; offset += 32768)
        sections.push(String.fromCharCode(...bytes.subarray(offset, offset + 32768)));
      workbook = btoa(sections.join(""));
    } else if (!uploadObject.value) {
      const upload = await uploadArchivePdf(
        scope.value[0],
        selectedFile,
        () => alive,
        (message) => (progress.value = message),
        "IMPORT_XLSX",
        type.value === "ARCHIVE" ? "ARCHIVE" : "PLATFORM",
      );
      uploadObject.value = (
        await api<{ objectId: string }>(`/storage/upload-sessions/${upload}`)
      ).objectId;
    }
    const generatedEmployeeEmails: Record<number, string> = {};
    if (type.value === "EMPLOYEE") {
      const names = singleNode
        ? await api<{ rowNo: number; name: string }[]>("/imports/employee-workbook/inspect", {
            method: "POST",
            headers: { "Content-Type": "application/octet-stream" },
            body: selectedFile,
          })
        : await api<{ rowNo: number; name: string }[]>(
            `/imports/employee-email-sources/${uploadObject.value}`,
          );
      for (const { rowNo, name } of names) {
        const email = companyEmailFromName(name);
        if (email && email.length <= 254) generatedEmployeeEmails[rowNo] = email;
      }
    }
    const body = singleNode
      ? { workbook, templateVersion: version.value, generatedEmployeeEmails }
      : {
          importType: type.value,
          uploadObjectId: uploadObject.value,
          templateVersion: version.value,
          departmentScope: scope.value,
          ...(type.value === "EMPLOYEE" ? { generatedEmployeeEmails } : {}),
        };
    let batch: Batch;
    if (type.value === "ARCHIVE")
      batch = await api<Batch>("/imports/batches", {
        method: "POST",
        headers: { "X-Operation-Id": operation },
        body: JSON.stringify(body),
      });
    else {
      progress.value = "";
      const password = await ask("确认创建台账预检批次");
      if (password === null || !alive) return;
      progress.value = "正在预检员工或部门数据";
      batch = await mutate<Batch>(
        singleNode ? "/imports/employee-workbook/batches" : "/imports/batches",
        "POST",
        body,
        COLLECTION,
        "IMPORT_BATCH",
        "CREATE_IMPORT",
        password,
      );
    }
    if (!alive) return;
    preflightBatch.value = batch;
    await select(batch);
    progress.value =
      batch.state === "READY"
        ? batch.importType === "EMPLOYEE" && batch.errorCount
          ? `可导入 ${batch.rowCount - batch.errorCount} 条，失败 ${batch.errorCount} 条。确认时仅创建通过的账号。`
          : "预检通过，请核对后确认导入。"
        : "预检发现问题，业务记录未创建。";
  } catch (e) {
    progress.value = "";
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
/** 打开批次后重新取得当前版本，操作不使用列表里的过期摘要。 */
async function select(batch: Batch) {
  const currentView = view.value;
  try {
    const result = await api<Batch>(`/imports/batches/${batch.id}`);
    if (!alive || view.value !== currentView) return;
    chosen.value = result;
    chosenView.value = currentView;
    rows.value = [];
    rowTotal.value = 0;
  } catch (e) {
    error.value = (e as Error).message;
    return;
  }
  confirmed.value = false;
  page.value = 1;
  await loadRows();
}
/** 错误分页每页二十行，可精确回到 Excel 原表修改。 */
async function loadRows() {
  const batch = chosen.value;
  if (!batch) return;
  try {
    const result = await api<{ items: ImportRow[]; total: number }>(
      `/imports/batches/${batch.id}/rows?${query({ page: page.value, pageSize: 20, onlyErrors: String(onlyErrors.value) })}`,
    );
    if (chosen.value?.id !== batch.id) return;
    rows.value = result.items;
    rowTotal.value = result.total;
  } catch (e) {
    error.value = (e as Error).message;
  }
}
/** 确认及撤回都使用当前摘要、版本和本人再次验证。 */
async function commit(withdraw = false) {
  if (!chosen.value || busy.value || !confirmed.value) return;
  const target = chosen.value;
  busy.value = true;
  error.value = "";
  try {
    const password = await ask(
      withdraw ? "确认撤回未发布的整批档案" : "确认导入已核对的台账",
    );
    if (password === null) return;
    const result = await mutate<Batch>(
      `/imports/batches/${target.id}/${withdraw ? "withdraw" : "confirm"}`,
      "POST",
      withdraw
        ? { reason: reason.value }
        : { validationDigest: target.validationDigest },
      target.id,
      "IMPORT_BATCH",
      withdraw ? "WITHDRAW_IMPORT" : "CONFIRM_IMPORT",
      password,
      target.rowVersion,
    );
    if (preflightBatch.value?.id === result.id) preflightBatch.value = result;
    await select(result);
    if (view.value === "history") await refresh();
    emit("changed");
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    busy.value = false;
  }
}
watch([type, archiveFormat], load);
watch(historyType, () => {
  if (chosenView.value === "history") chosen.value = null;
  batchPage.value = 1;
  batches.value = [];
  batchTotal.value = 0;
  void refresh();
});
watch(
  scope,
  () => {
    uploadObject.value = "";
    operation = crypto.randomUUID();
  },
  { deep: true },
);
watch(onlyErrors, () => {
  page.value = 1;
  void loadRows();
});
onMounted(load);
onUnmounted(() => {
  if (templateUrl.value) URL.revokeObjectURL(templateUrl.value);
  alive = false;
  generation++;
});
/** 当前列表已提交的筛选条件。 */
const listFilters = ref(recentWeekFilters("created"));
</script>

<template>
  <section class="intake-page" :class="{ 'intake-embedded': archiveOnly || employeeOnly, 'employee-import': employeeOnly }">
    <header>
      <div>
        <h2 v-if="!employeeOnly">{{ props.archiveOnly ? "档案盒与文件批量登记" : "批量导入" }}</h2>
        <p v-if="type === 'ARCHIVE'">
          档案只新增，全部预检通过后才可提交。已导入目录仍须质量检查和独立归档复核。
        </p>
        <p v-else-if="type === 'EMPLOYEE'">
          所有员工共用一份模板。同一 Excel 可填写多个部门，所属部门按每行内容识别。
        </p>
        <p v-else>上传组织部门模板，核对预检结果后确认导入。</p>
      </div>
    </header>
    <nav v-if="!employeeOnly" class="module-tabs import-tabs" aria-label="批量导入页面">
      <button type="button" :class="{ selected: view === 'create' }"
        :aria-pressed="view === 'create'" :disabled="busy || downloading" @click="openCreate">
        批量导入
      </button>
      <button type="button" :class="{ selected: view === 'history' }"
        :aria-pressed="view === 'history'" :disabled="busy || downloading" @click="openHistory">
        我的导入记录
      </button>
    </nav>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <div class="workspace">
      <section v-show="view === 'create'" class="panel import-form" aria-label="建立导入批次">
        <label v-if="!employeeOnly"
          >导入类型<select v-model="type" :disabled="busy || downloading">
            <option v-for="item in types" :key="item.code" :value="item.code">
              {{ item.name }}
            </option>
          </select></label
        >
        <label v-if="type === 'ARCHIVE' && !archiveOnly"
          >登记内容<select
            v-model="archiveFormat"
            :disabled="busy || downloading"
          >
            <option value="BOX_LEDGER">档案盒登记（通用台账）</option>
            <option value="STANDARD">文件登记（关联档案盒）</option>
          </select></label
        >
        <p v-if="type === 'ARCHIVE'" class="template-summary">
          所有部门共用同一模板。每行所属部门以 Excel
          内容为准，系统按当前登记权限核验。
        </p>
        <label v-else-if="type === 'DEPARTMENT'"
          >批准的部门范围<select
            v-model="scope"
            multiple
            :disabled="busy"
            size="4"
          >
            <option v-for="unit in departments" :key="unit.id" :value="unit.id">
              {{ unit.code }} · {{ unit.name }}
            </option>
          </select></label
        >
        <p v-if="type === 'DEPARTMENT'" class="hint">
          {{ selectedNames || "请选择至少一个部门" }}
        </p>
        <button
          type="button"
          class="download"
          :disabled="downloading"
          @click="downloadTemplate"
        >
          {{
            downloading
              ? "等待选择或保存…"
              : type === "ARCHIVE" && archiveFormat === "BOX_LEDGER"
                ? "下载档案盒通用 Excel 模板"
                : type === "EMPLOYEE" ? "下载员工 Excel 模板" : "下载当前 Excel 模板"
          }}
        </button>
        <p v-if="downloadNotice" class="hint" role="status">
          {{ downloadNotice }}
        </p>
        <a
          v-if="templateUrl"
          class="download"
          :href="templateUrl"
          :download="templateName"
          >保存模板文件</a
        >
        <details v-if="type === 'EMPLOYEE' && downloadOpenHint" class="download-open-hint">
          <summary>打开文件或所在文件夹</summary>
          <p class="hint">{{ downloadOpenHint }}</p>
        </details>
        <label
          >{{ type === "EMPLOYEE" ? "上传 Excel 文件" : "台账文件" }}<input
            :key="`${type}-${archiveFormat}`"
            type="file"
            accept=".xlsx"
            :disabled="busy"
            @change="chooseFile"
        /></label>
        <p
          v-if="type === 'ARCHIVE' && archiveFormat === 'BOX_LEDGER'"
          class="hint"
        >
          沿用原表第 2、3 行中文表头，从第 4 行填写，每行登记一个档案盒。支持
          Excel 日期及原表到期日期公式，可保留“部门及档案负责人”参考表。
          保存类型填写纸质、电子或纸质+电子；含纸质时须在新增“纸质标签”列填写纸质原件、纸质复印件，两个标签用顿号分隔。
        </p>
        <p v-else-if="type === 'EMPLOYEE'" class="hint">
          公司工号、姓名和所属部门必填，部门可填名称或编码。职位、手机号和直属主管可留空。主管可填在职或同批员工工号，姓名唯一时也可填写姓名。
          联系邮箱可留空，公司邮箱留空时按姓名自动生成。支持 .xlsx，最多 1,000 行、{{ localEmployeeWorkbook ? 4 : 50 }} MB。
        </p>
        <p v-else class="hint">
          最多 1,000 行、50
          MB。保留前两行模板标识；工号、编号和日期请用文本格式。不接受公式、外部链接或多个工作表。
        </p>
        <button
          class="primary"
          :disabled="busy || !file || !scope.length"
          @click="preflight"
        >
          {{ busy ? "处理中…" : "上传并预检" }}
        </button>
        <p v-if="type === 'ARCHIVE' && archiveFormat === 'BOX_LEDGER'" class="hint">最多 1,000 条、50 MB。</p>
        <p role="status" class="hint">{{ progress }}</p>
        <details>
          <summary>{{ type === "EMPLOYEE" ? "查看模板字段" : "填写说明与目录模板" }}</summary>
          <p v-for="column in columns" :key="column" class="column-note">
            {{ type === "EMPLOYEE" ? column.split("|")[1] : column }}
          </p>
          <p
            v-for="item in archiveFormat === 'STANDARD' ? templates : []"
            :key="item.id"
            class="template-note"
          >
            {{ item.name }}（{{ item.objectType }}）<br /><code>{{
              item.id
            }}</code>
          </p>
          <p
            v-if="type === 'ARCHIVE' && archiveFormat === 'BOX_LEDGER'"
            class="hint"
          >
            部门和负责人填写系统中的名称。档案编号可空，原有编号会保留，系统另分配盒号。保存份数按台账保留，不代表已登记的盒内文件数。原台账到期日期、档案状态和最后更新时间保留为来源信息，不代替系统复核状态及操作时间。
          </p>
          <p v-else class="hint">
            档案盒行填写 BOX；文件行填写
            RECORD，并填写本批盒行号或已有系统盒号之一。MIXED / PAPER
            必须填写原件位置。扩展字段使用对应目录模板的 JSON。
          </p>
        </details>
      </section>
      <section v-if="view === 'history'" class="panel" aria-label="我的导入记录">
        <label v-if="types.length > 1" class="history-type">
          导入类型
          <select v-model="historyType" :disabled="busy">
            <option v-for="item in types" :key="item.code" :value="item.code">{{ item.name }}</option>
          </select>
        </label>
        <ListFilters
          :fields="[
            {
              key: 'state',
              label: '批次状态',
              options: {
                READY: states.READY!,
                INVALID: states.INVALID!,
                CONFIRMED: states.CONFIRMED!,
                WITHDRAWN: states.WITHDRAWN!,
              },
            },
            { key: 'created', label: '建立日期', type: 'date' },
          ]"
          :values="listFilters"
          :busy="busy"
          @apply="
            listFilters = $event;
            batchPage = 1;
            refresh();
          "
        />
        <div class="table-scroll">
          <table>
            <thead>
              <tr>
                <th>建立时间</th>
                <th>行数</th>
                <th>错误</th>
                <th>状态</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="batch in batches"
                :key="batch.id"
                :class="{ selected: chosen?.id === batch.id }"
              >
                <td>{{ dateTime(batch.createdAt) }}</td>
                <td>{{ batch.rowCount }}</td>
                <td>{{ batch.errorCount }}</td>
                <td>{{ batch.importType === "EMPLOYEE" && batch.errorCount && batch.state === "READY"
                  ? "部分通过"
                  : batch.importType === "EMPLOYEE" && batch.errorCount && batch.state === "CONFIRMED"
                    ? "部分已导入"
                    : states[batch.state] }}</td>
                <td><button @click="select(batch)">查看</button></td>
              </tr>
              <tr v-if="!batches.length">
                <td colspan="5" class="empty">当前尚无导入批次</td>
              </tr>
            </tbody>
          </table>
        </div>
        <footer class="list-footer">
          <button
            type="button"
            class="secondary list-refresh"
            :disabled="busy"
            @click="refresh()"
          >
            刷新列表
          </button>
          <div class="list-pagination">
            <button
              :disabled="batchPage <= 1 || busy"
              @click="
                batchPage--;
                refresh();
              "
            >
              较新批次
            </button>
            <span>第 {{ batchPage }} 页</span
            ><span class="list-total">共 {{ batchTotal }} 批</span>
            <button
              :disabled="batchPage * 20 >= batchTotal || busy"
              @click="
                batchPage++;
                refresh();
              "
            >
              较早批次
            </button>
          </div>
        </footer>
      </section>
      <section v-if="chosen && chosenView === view" class="panel details">
          <div class="row">
            <h3>预检与逐行结果</h3>
            <label class="check"
              ><input v-model="onlyErrors" type="checkbox" />只看错误</label
            >
          </div>
          <p v-if="chosen.importType === 'EMPLOYEE' && chosen.state === 'CONFIRMED'" class="success">
            已导入 {{ chosen.rowCount - chosen.errorCount }} 条，未导入 {{ chosen.errorCount }} 条。
          </p>
          <p v-else-if="chosen.state === 'CONFIRMED'" class="success">
            {{
              chosen.importType === "ARCHIVE"
                ? "本批全部新增，等待人工质量检查与归档复核，尚未发布。"
                : "本批账号 / 部门已创建；员工临时凭据已进入受控投递渠道。"
            }}
          </p>
          <div class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th>原表行</th>
                  <th>内容摘要</th>
                  <th>结果 / 字段错误</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in rows" :key="row.rowNo">
                  <td>{{ row.rowNo || "工作簿" }}</td>
                  <td>
                    {{
                      row.input.title ||
                      row.input.name ||
                      row.input.rowKey ||
                      "模板结构"
                    }}
                    <div v-if="chosen.importType === 'EMPLOYEE' && row.input.companyEmail" class="hint">
                      {{ row.input.companyEmail }}
                    </div>
                  </td>
                  <td>
                    <template v-if="row.errors.length"
                      ><p
                        v-for="(issue, index) in row.errors"
                        :key="index"
                        class="error-text"
                      >
                        {{ chosen.importType === "EMPLOYEE" ? employeeColumnLabels.get(issue.field) || issue.field : issue.field }}：{{ issue.message }}
                      </p></template
                    ><span v-else>{{ states[row.result] || row.result }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <footer class="list-footer">
            <button
              type="button"
              class="secondary list-refresh"
              :disabled="busy"
              @click="loadRows()"
            >
              刷新列表
            </button>
            <div class="list-pagination">
              <button
                :disabled="page <= 1"
                @click="
                  page--;
                  loadRows();
                "
              >
                上一页</button
              ><span>第 {{ page }} 页 · 共 {{ rowTotal }} 行</span
              ><button
                :disabled="page * 20 >= rowTotal"
                @click="
                  page++;
                  loadRows();
                "
              >
                下一页
              </button>
            </div>
          </footer>
          <div
            v-if="
              chosen.state === 'READY' ||
              (chosen.state === 'CONFIRMED' && chosen.importType === 'ARCHIVE')
            "
            class="confirm-area"
          >
            <label v-if="chosen.state === 'CONFIRMED'"
              >撤回原因<input v-model="reason" maxlength="1000" /></label
            ><label class="check"
              ><input v-model="confirmed" type="checkbox" />{{
                chosen.state === "READY"
                  ? chosen.importType === "EMPLOYEE"
                    ? "我已核对预检结果，确认仅导入通过的员工"
                    : "我已核对本批人员、编号、原件及部门范围"
                  : "我已确认本批新增资料尚未发布，并需要整体撤回"
              }}</label
            ><button
              class="primary"
              :disabled="
                busy ||
                !confirmed ||
                (chosen.state === 'CONFIRMED' && !reason.trim())
              "
              @click="commit(chosen.state === 'CONFIRMED')"
            >
              {{
                chosen.state === "READY"
                  ? chosen.importType === "EMPLOYEE"
                    ? `导入通过的 ${chosen.rowCount - chosen.errorCount} 条`
                    : "确认整批导入"
                  : "撤回本批新增档案"
              }}
            </button>
          </div>
      </section>
    </div>
  </section>
</template>
<style scoped>
.intake-page {
  min-width: 0;
  color: var(--ink);
}
h2 {
  font-size: 24px;
  margin: 6px 0 10px;
}
header p,
.hint {
  color: var(--text-secondary);
  line-height: 1.65;
  font-size: 13px;
}
.platform-shell .import-tabs {
  margin-top: 20px;
}
.workspace {
  display: grid;
  gap: 18px;
}
.import-form {
  width: min(100%, 720px);
}
.employee-import .import-form {
  width: 100%;
  padding: 4px 0 0;
  border: 0;
}
.import-form select[multiple] {
  min-height: 112px;
}
.history-type {
  max-width: 240px;
  margin-top: 0;
}
.panel {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 24px;
  min-width: 0;
}
h3 {
  font-size: 17px;
  margin: 0 0 18px;
}
label {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 13px;
  margin: 15px 0;
}
button,
.download {
  border: 1px solid var(--line);
  background: #fff;
  color: var(--brand);
  padding: 9px 14px;
  border-radius: 7px;
  cursor: pointer;
  font-size: 13px;
}
.template-summary {
  margin: 4px 0 18px;
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--surface-muted);
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.7;
}
button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.primary {
  background: var(--brand);
  color: #fff;
  border-color: var(--brand);
}
.download {
  display: inline-block;
  text-decoration: none;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}
.row h3 {
  margin: 0;
}
.check {
  flex-direction: row;
  align-items: center;
}
.check input {
  /* 复选框不继承文本输入框的整行宽度，保持桌面筛选标签完整。 */
  width: 16px;
  height: 16px;
  min-height: 0;
  flex: 0 0 16px;
  padding: 0;
  margin: 0;
}
.details > .row .check {
  white-space: nowrap;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  margin-top: 16px;
}
th {
  text-align: left;
  color: var(--text-secondary);
  font-weight: 500;
  background: var(--surface-muted);
}
td,
th {
  padding: 12px 10px;
  border-bottom: 1px solid var(--line);
}
.selected td {
  background: var(--surface-muted);
}
.empty {
  padding: 40px;
  text-align: center;
  color: var(--text-secondary);
}
.error {
  padding: 12px;
  background: #fff1ee;
  color: #a43827;
  border-radius: 8px;
}
.error-text {
  margin: 3px 0;
  color: #a43827;
}
.success {
  padding: 12px;
  background: #edf8f3;
  color: #2a6852;
  font-size: 13px;
}
.confirm-area {
  margin-top: 22px;
  background: var(--surface-muted);
  padding: 16px;
  border-radius: 10px;
}
.column-note,
.template-note {
  font-size: 12px;
  overflow-wrap: anywhere;
  margin: 8px 0;
}
details {
  margin-top: 20px;
}
.download-open-hint {
  margin-top: 8px;
  color: var(--brand);
}
.download-open-hint p {
  margin: 6px 0 0;
}
summary {
  font-size: 13px;
  cursor: pointer;
}
@media (max-width: 900px) {
  .intake-embedded .panel {
    padding: 16px;
  }
  .intake-embedded .row {
    flex-wrap: wrap;
  }
}
</style>
