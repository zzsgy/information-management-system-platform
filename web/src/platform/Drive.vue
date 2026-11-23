<script setup lang="ts">
import EmptyState from "./EmptyState.vue";
import { computed, onMounted, onUnmounted, ref } from "vue";
import { ApiError, api, query, profile, dateTime, type Department, type Page } from "./api";
import Icon from "../preview/PlatformIcon.vue";
import DriveFilePanel from "./DriveFilePanel.vue";
import DriveRecycle from "./DriveRecycle.vue";
import DriveManagement from "./DriveManagement.vue";
import DateRangePreset from "./DateRangePreset.vue";
import { recentDateRange } from "./listDates";
import DocumentReader from "./DocumentReader.vue";
import { driveAccept, uploadDrive } from "./driveUpload";
import { isDrivePreviewable } from "./drivePreview";

/** 只接收服务端已投影的业务目录，不保存存储键或实体路径。 */
interface Folder {
  departmentId: string;
  parentId: string | null;
  rowVersion: number;
  canManage: boolean;
  /** 目录标识。 */ id: string;
  /** 显示名称。 */ name: string;
  /** 区域。 */ zone: string;
  /** 当前上传允许。 */ canUpload: boolean;
}
/** 正式文档摘要；待检测对象不会进入此列表。 */
interface Document {
  /** 文档标识。 */ id: string;
  /** 显示名称。 */ name: string;
  /** 创建时刻。 */ createdAt: string;
  rowVersion: number;
  folderId: string;
  folderName?: string | null;
  uploadedByName: string;
  collaborationEnabled: boolean;
  actions: string[];
  /** 当前版本。 */ currentVersion: {
    /** 版本标识。 */ id: string;
    versionNo: number;
    /** 文件字节数。 */ byteSize: number;
    /** 已核验类型。 */ mediaType: string;
  };
}
/** 本人预览状态，页数与失效时刻来自服务端。 */
interface Preview {
  /** 会话标识。 */ previewId: string;
  /** 处理状态。 */ state: string;
  /** 实际页数。 */ pageCount: number;
}
/** 目录、文档和分页独立维护，当前层不加载整棵公司文件树。 */
const folders = ref<Folder[]>([]),
  selected = ref<Folder | null>(null),
  documents = ref<Document[]>([]),
  trail = ref<Folder[]>([]),
  page = ref(1),
  total = ref(0);
/** 用户主动操作与后台轮询区分，轮询不延长登录会话。 */
const error = ref(""),
  notice = ref(""),
  busy = ref(false),
  keyword = ref(
    new URLSearchParams(location.hash.split("?")[1] || "")
      .get("keyword")
      ?.slice(0, 100) || "",
  ),
  progress = ref<number | null>(0),
  uploadLabel = ref("");
/** 同一组件最多一个上传，文件选择器限制浏览器摘要内存规模。 */
const picker = ref<HTMLInputElement | null>(null);
const detailId = ref(""),
  recycle = ref(false),
  fileType = ref(""),
  management = ref(false);
/** 工作台全局搜索保留全公盘范围，直接进入公盘时默认查询本部门内部资料。 */
const defaultSpace = profile.value?.user.departmentId
    ? "OWN_INTERNAL"
    : "COMPANY_SHARED",
  space = ref(keyword.value ? "ALL" : defaultSpace);
/** 卡片仅映射现有空间筛选值；展示顺序与名称不改变服务端权限及默认空间。 */
const spaceGroups = [
  { value: "COMPANY_SHARED", label: "企业共享", icon: "grid", description: "查看企业范围内的共享资料" },
  { value: "OTHER_DEPARTMENT_SHARED", label: "部门共享", icon: "people", description: "查看其他部门共享的资料" },
  { value: "OWN_INTERNAL", label: "部门内部", icon: "lock", description: "查看本部门的内部资料" },
] as const;
/** 共享部门使用新增员工同源的组织目录，文件和目录仍按公盘权限筛选。 */
const sharedDepartment = ref(""),
  sharedDepartments = ref<Pick<Department, "id" | "name">[]>([]);
const uploadRange = recentDateRange(7);
const uploader = ref(""),
  folderName = ref(""),
  uploadedFrom = ref(uploadRange.from),
  uploadedTo = ref(uploadRange.to);
const applied = ref({
  keyword: keyword.value,
  fileType: "",
  uploader: "",
  folderName: "",
  uploadedFrom: uploadRange.from,
  uploadedTo: uploadRange.to,
});
const folderPage = ref(1),
  moreFolders = ref(false);
const canManage = computed(
  () =>
    !!selected.value?.canManage ||
    profile.value?.roles.some(
      (role) => ["PLATFORM", "DRIVE"].includes(role.module) && role.code === "IT_ADMIN",
    ),
);
const uploadAbort = new AbortController();
/** 阅读会话元数据与连续阅读器分开，正文由阅读器按可见范围加载。 */
const reading = ref<Document | null>(null),
  preview = ref<Preview | null>(null),
  readingBusy = ref(false);
/** 导航序号阻止较慢的旧请求覆盖新目录或已关闭的阅读窗口。 */
let generation = 0,
  readerGeneration = 0,
  alive = true,
  refreshTimer: ReturnType<typeof setInterval> | undefined;
/** 文件大小统一按二进制单位显示。 */
function size(value: number) {
  return value < 1048576
    ? `${(value / 1024).toFixed(1)} KB`
    : `${(value / 1048576).toFixed(1)} MB`;
}
/** 目录和文件列表使用同一空间及部门条件。 */
function listFolders(parentId?: string, page = 1) {
  return api<Page<Folder>>(
    `/drive/folders?${query({ parentId, space: space.value, departmentId: sharedDepartment.value, page })}`,
  );
}
/** 先清除原目录内容，失败也不会把旧内容显示在新目录名下。 */
async function open(folder: Folder | null) {
  const current = ++generation;
  selected.value = folder;
  recycle.value = false;
  documents.value = [];
  folders.value = [];
  total.value = 0;
  moreFolders.value = false;
  error.value = "";
  page.value = 1;
  folderPage.value = 1;
  try {
    const data = await listFolders(folder?.id);
    if (current !== generation || !alive) return;
    folders.value = data.items;
    moreFolders.value = data.hasNext;
    if (!folder && space.value === "OTHER_DEPARTMENT_SHARED" && !sharedDepartment.value) {
      const options: typeof sharedDepartments.value = [];
      for (let number = 1; ; number++) {
        const result = await api<Page<{ selector: Pick<Department, "id" | "name"> }>>(
          `/admin/departments?${query({ projection: "SELECTOR", purpose: "DRIVE_SHARED_FILTER", page: number, pageSize: 100 })}`,
        );
        if (current !== generation || !alive) return;
        options.push(...result.items.map((item) => item.selector));
        if (!result.hasNext) break;
      }
      sharedDepartments.value = options
        .filter((department) => department.id !== profile.value?.user.departmentId)
        .sort((a, b) => a.name.localeCompare(b.name, "zh-CN"));
    }
    await load();
  } catch (failure) {
    if (current === generation) error.value = (failure as Error).message;
  }
}
async function nextFolders() {
  const current = generation;
  try {
    const result = await listFolders(selected.value?.id, folderPage.value + 1);
    if (current !== generation || !alive) return;
    folders.value.push(...result.items);
    folderPage.value++;
    moreFolders.value = result.hasNext;
  } catch (failure) {
    if (current === generation && alive)
      error.value = (failure as Error).message;
  }
}
/** 进入、返回和面包屑都只改变当前层，服务端每次重验目录。 */
function enter(folder: Folder) {
  trail.value.push(folder);
  void open(folder);
}
/** 下拉选只导航到服务端返回的当前层目录。 */
function chooseFolder(event: Event) {
  const field = event.target as HTMLSelectElement;
  const folder = folders.value.find((item) => item.id === field.value);
  field.value = "";
  if (folder) enter(folder);
}
function back(index: number) {
  trail.value = trail.value.slice(0, index + 1);
  void open(trail.value.at(-1) || null);
}
function managementChanged(
  update?: { id: string; name: string; rowVersion: number } | null,
) {
  if (update === null) {
    trail.value = [];
    void open(null);
    return;
  }
  if (update && selected.value?.id === update.id) {
    selected.value = { ...selected.value, ...update };
    trail.value = trail.value.map((folder) =>
      folder.id === update.id ? { ...folder, ...update } : folder,
    );
  }
  void open(selected.value);
}
/** 顶层目录可能建在任意部门的分区，创建后清除原空间和部门限制。 */
function revealCreatedFolder(folder: Pick<Folder, "name">) {
  space.value = "ALL";
  changeSpace();
  notice.value = `目录「${folder.name}」已创建。`;
}
/** 按新目录的部门和分区读取已授权的目录摘要，再使用常规目录入口。 */
async function openCreatedFolder(created: Pick<Folder, "id" | "name" | "departmentId" | "zone">) {
  management.value = false;
  const current = generation;
  try {
    const result = await api<Page<Folder>>(
      `/drive/folders?${query({ space: "ALL", departmentId: created.departmentId, zone: created.zone })}`,
    );
    if (current !== generation || !alive) return;
    const folder = result.items.find((item) => item.id === created.id);
    if (folder) enter(folder);
    else error.value = `已创建「${created.name}」，当前账号无法查看该目录。`;
  } catch (failure) {
    if (current === generation && alive) error.value = (failure as Error).message;
  }
}
/** 查询参数编码后提交，关键字不会拼接为路径或SQL。 */
async function load() {
  const current = generation;
  try {
    const result = await api<Page<Document> & { folderCanUpload: boolean }>(
      `/drive/documents?${query({ folderId: selected.value?.id, space: space.value, departmentId: sharedDepartment.value, ...applied.value, page: page.value, pageSize: 20 })}`,
    );
    if (current !== generation || !alive) return;
    documents.value = result.items;
    total.value = result.total;
    if (selected.value) selected.value.canUpload = result.folderCanUpload;
  } catch (failure) {
    if (current === generation && alive) {
      documents.value = [];
      if (selected.value) selected.value.canUpload = false;
      error.value = (failure as Error).message;
    }
  }
}
function search(resetDirectory = false) {
  applied.value = {
    keyword: keyword.value,
    fileType: fileType.value,
    uploader: uploader.value,
    folderName: folderName.value,
    uploadedFrom: uploadedFrom.value,
    uploadedTo: uploadedTo.value,
  };
  if (resetDirectory) back(-1);
  else {
    page.value = 1;
    void load();
  }
}
function resetSearch() {
  keyword.value =
    fileType.value =
    uploader.value =
    folderName.value =
    uploadedFrom.value =
    uploadedTo.value =
      "";
  space.value = defaultSpace;
  changeSpace();
}
/** 切换空间时清空上一个空间的部门筛选。 */
function changeSpace() {
  sharedDepartment.value = "";
  sharedDepartments.value = [];
  search(true);
}
/** 阅读任务只报告预览结果，不把服务故障当作上传或发布失败。 */
async function waitJob(id: string, current: number) {
  for (
    let attempt = 0;
    attempt < 180 && alive && current === readerGeneration;
    attempt++
  ) {
    const task = await api<{
      state: string;
      errorCode: string;
      errorMessage?: string;
      requestId?: string;
      result: { previewId: string } | null;
    }>(`/jobs/${id}`, {}, false);
    if (task.state === "SUCCEEDED") return task.result;
    if (["FAILED", "CANCELLED", "UNKNOWN"].includes(task.state))
      throw new ApiError(task.errorCode || "PREVIEW_FAILED",
        `${task.errorMessage || "文档预览生成失败，请联系 IT 管理员"}。文件已发布，无需重新上传。\n任务编号：${id}`,
        task.requestId);

    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  if (!alive || current !== readerGeneration) return null;
  throw new Error(
    "阅读预览仍在处理中，请稍后重新打开。文件已发布，无需重新上传。",
  );
}
/** 当前选择的目录固定为上传上下文，检测通过后再正式发布。 */
async function upload(event: Event) {
  const input = event.target as HTMLInputElement,
    file = input.files?.[0],
    folder = selected.value;
  input.value = "";
  if (!file || !folder) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  progress.value = 0;
  try {
    const uploadId = await uploadDrive(
      file,
      folder.id,
      "DRIVE_FILE",
      (label, value) => {
        uploadLabel.value = label;
        progress.value = value;
      },
      uploadAbort.signal,
    );
    if (!alive) return;
    await api("/drive/documents", {
      method: "POST",
      headers: { "X-Operation-Id": crypto.randomUUID() },
      body: JSON.stringify({
        folderId: folder.id,
        uploadSessionId: uploadId,
        name: file.name,
      }),
    });
    notice.value = "文件已通过检测并发布。";
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
    uploadLabel.value = "";
  }
}
function closeReader() {
  readerGeneration++;
  reading.value = null;
  preview.value = null;
}
/** 阅读创建和元数据准备均来自真实接口，不在浏览器解析原件。 */
async function read(document: Document) {
  closeReader();
  // 旧预览准备失败也不能关闭用户随后打开的新文档。
  const current = readerGeneration;
  reading.value = document;
  readingBusy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const task = await api<{ jobId: string }>(
      `/drive/documents/${document.id}/previews`,
      {
        method: "POST",
        headers: { "X-Operation-Id": crypto.randomUUID() },
        body: JSON.stringify({ versionId: document.currentVersion.id }),
      },
    );
    const result = await waitJob(task.jobId, current);
    if (
      current !== readerGeneration ||
      reading.value?.id !== document.id ||
      !result
    )
      return;
    const manifest = await api<Preview>(
      `/drive/documents/${document.id}/previews/${result.previewId}`,
      {},
      false,
    );
    if (current !== readerGeneration || !alive) return;
    preview.value = manifest;
  } catch (failure) {
    if (current === readerGeneration) {
      closeReader();
      error.value = (failure as Error).message;
    }
  } finally {
    if (current === readerGeneration) readingBusy.value = false;
  }
}
/** 状态轮询只用于及时清屏，服务端每次交付的实时授权才是权限边界。 */
onMounted(() => {
  void open(null);
  refreshTimer = setInterval(async () => {
    if (!preview.value) return;
    const watched = preview.value.previewId;
    try {
      await api(
        `/drive/documents/${reading.value?.id}/previews/${preview.value.previewId}`,
        {},
        false,
      );
    } catch (failure) {
      if (preview.value?.previewId === watched) {
        closeReader();
        error.value = (failure as Error).message;
      }
    }
  }, 5000);
});
onUnmounted(() => {
  alive = false;
  uploadAbort.abort();
  generation++;
  closeReader();
  if (refreshTimer) clearInterval(refreshTimer);
});
</script>

<template>
  <section class="drive-heading">
    <div>
      <span class="eyebrow">文档协作</span>
      <h1>企业公盘</h1>
      <p class="muted">在获准的空间内，查阅与共享工作资料。</p>
    </div>
    <span class="drive-seal"
      ><Icon name="shield" :size="22" />受控资料空间</span
    >
  </section>
  <p v-if="error" class="form-error" role="alert">{{ error }}</p>
  <p v-if="notice" class="drive-notice" role="status">{{ notice }}</p>
  <div>
    <nav class="module-tabs" aria-label="企业公盘视图">
      <button
        type="button"
        :class="{ active: !recycle }"
        :aria-pressed="!recycle"
        @click="back(-1)"
      >
        <Icon name="file" :size="18" />企业公盘
      </button>
      <button
        type="button"
        :class="{ active: recycle }"
        :aria-pressed="recycle"
        @click="
          recycle = true;
          detailId = '';
        "
      >
        <Icon name="trash" :size="18" />回收站
      </button>
    </nav>
    <section class="panel drive-files">
      <DriveRecycle v-if="recycle" @changed="load" />
      <template v-else>
        <div class="drive-search">
          <div class="drive-filter-heading">
            <h3><Icon name="search" :size="17" />筛选文件</h3>
          </div>
          <form
            id="drive-file-filters"
            @submit.prevent="search()"
            aria-label="公盘文件筛选"
          >
            <label class="list-filter-field"
              >文件名称<input
                v-model="keyword"
                maxlength="200"
                placeholder="按文件名称搜索"
                aria-label="按文件名称搜索" /></label
            ><label class="list-filter-field"
              >文件格式<select v-model="fileType" aria-label="文件类型">
                <option value="">全部格式</option>
                <option
                  v-for="type in driveAccept
                    .split(',')
                    .map((extension) => extension.slice(1))"
                  :key="type"
                  :value="type"
                >
                  {{ type.toUpperCase() }}
                </option>
              </select></label
            >
            <label class="list-filter-field"
              >上传人<input
                v-model="uploader"
                maxlength="100"
                placeholder="姓名或工号"
            /></label>
            <label class="list-filter-field"
              >所在目录<input
                v-model="folderName"
                maxlength="128"
                placeholder="输入目录名称"
            /></label>
            <DateRangePreset
              label="上传日期范围"
              :from="uploadedFrom"
              :to="uploadedTo"
              @apply="
                uploadedFrom = $event.from;
                uploadedTo = $event.to;
                search();
              "
            />
            <label class="list-filter-field"
              >上传开始日期<input
                v-model="uploadedFrom"
                aria-label="上传开始日期"
                type="date"
                :max="uploadedTo || undefined" /></label
            ><label class="list-filter-field"
              >上传结束日期<input
                v-model="uploadedTo"
                aria-label="上传结束日期"
                type="date"
                :min="uploadedFrom || undefined"
            /></label>
            <div class="drive-filter-actions">
              <button class="secondary" type="button" @click="resetSearch">重置</button>
              <button class="primary" type="submit">
                <Icon name="search" :size="15" />搜索
              </button>
            </div>
          </form>
        </div>
        <section class="drive-spaces" aria-labelledby="drive-spaces-title">
          <div class="drive-spaces-heading">
            <div>
              <h2 id="drive-spaces-title">资料空间</h2>
            </div>
            <button
              type="button"
              class="drive-all-spaces"
              :class="{ active: space === 'ALL' }"
              :aria-pressed="space === 'ALL'"
              @click="space = 'ALL'; changeSpace();"
            >
              <Icon name="eye" :size="16" />全部可见
            </button>
          </div>
          <div class="drive-space-grid" role="group" aria-label="资料空间分组">
            <button
              v-for="group in spaceGroups"
              :key="group.value"
              type="button"
              class="drive-space-card"
              :class="{ selected: space === group.value }"
              :aria-label="group.label"
              :aria-describedby="`drive-space-${group.value}`"
              :aria-pressed="space === group.value"
              @click="space = group.value; changeSpace();"
            >
              <span class="drive-space-icon"><Icon :name="group.icon" :size="20" /></span>
              <span class="drive-space-copy">
                <strong>{{ group.label }}</strong>
                <span class="drive-space-description" :id="`drive-space-${group.value}`">{{ group.description }}</span>
              </span>
              <span class="drive-space-indicator">
                <Icon v-if="space === group.value" name="check" :size="16" />
              </span>
            </button>
          </div>
          <!-- 沿用原部门目录和查询值，仅将下拉选项平铺为单行按钮。 -->
          <div v-if="space === 'OTHER_DEPARTMENT_SHARED'" class="drive-departments">
            <span class="drive-departments-label">共享部门</span>
            <div class="drive-department-options" role="group" aria-label="共享部门">
              <button
                type="button"
                class="drive-department-option"
                :class="{ selected: sharedDepartment === '' }"
                :aria-pressed="sharedDepartment === ''"
                @click="sharedDepartment = ''; search(true);"
              >全部部门</button>
              <button
                v-for="item in sharedDepartments"
                :key="item.id"
                type="button"
                class="drive-department-option"
                :class="{ selected: sharedDepartment === item.id }"
                :aria-pressed="sharedDepartment === item.id"
                @click="sharedDepartment = item.id; search(true);"
              >{{ item.name }}</button>
            </div>
          </div>
        </section>
        <div class="drive-toolbar">
          <div class="drive-crumb">
            <Icon name="folder" :size="18" />
            <button @click="back(-1)">公盘</button
            ><template v-for="(folder, index) in trail" :key="folder.id"
              ><span>/</span
              ><button @click="back(index)">{{ folder.name }}</button></template
            >
            <select
              v-if="folders.length"
              aria-label="浏览目录"
              value=""
              @change="chooseFolder"
            >
              <option value="">浏览目录</option>
              <option v-for="folder in folders" :key="folder.id" :value="folder.id">
                {{ folder.name }}
              </option>
            </select>
            <button v-if="moreFolders" type="button" @click="nextFolders">
              加载更多目录
            </button>
          </div>
          <div class="drive-toolbar-actions">
            <span v-if="selected && !selected.canUpload && !error" class="muted">当前账号没有此目录的上传权限</span>
            <button v-if="canManage" class="secondary" @click="management = true">
              管理配置
            </button>
            <button
              v-if="selected?.canUpload"
              class="primary"
              :disabled="busy"
              @click="picker?.click()"
            >
              {{ busy ? uploadLabel : "上传文件" }}
            </button>
          </div>
          <input
            ref="picker"
            type="file"
            :accept="driveAccept"
            hidden
            @change="upload"
          />
        </div>
        <div v-if="busy" class="drive-progress" role="status">
          <span
            >{{ uploadLabel
            }}<template v-if="progress !== null">
              {{ progress }}%</template
            ></span
          ><progress :value="progress ?? undefined" max="100" />
        </div>
        <div class="drive-table-wrap">
          <table class="drive-table">
            <thead>
              <tr>
                <th>文件名称</th>
                <th>文件大小</th>
                <th>上传人</th>
                <th>上传时间</th>
                <th>所在目录</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="document in documents" :key="document.id">
                <td class="drive-name-cell">
                  <div class="drive-file-title">
                    <span class="drive-pdf">{{
                      document.name.split(".").at(-1)?.toUpperCase()
                    }}</span
                    ><button class="file-name" @click="detailId = document.id">
                      {{ document.name }}
                    </button>
                  </div>
                </td>
                <td class="muted">
                  {{ size(document.currentVersion.byteSize) }}
                </td>
                <td class="drive-uploader">{{ document.uploadedByName }}</td>
                <td class="drive-uploaded-at muted">
                  {{ dateTime(document.createdAt) }}
                </td>
                <td>
                  <span
                    class="drive-location"
                    :title="document.folderName || '目录未授权'"
                    ><Icon name="folder" :size="16" /><span>{{
                      document.folderName || "目录未授权"
                    }}</span></span
                  >
                </td>
                <td class="drive-row-actions">
                  <button
                    v-if="isDrivePreviewable(document.currentVersion.mediaType)"
                    class="text-button"
                    @click="read(document)"
                  >
                    打开阅读</button
                  ><button class="text-button" @click="detailId = document.id">
                    文件与版本
                  </button>
                </td>
              </tr>
              <tr v-if="!documents.length">
                <td colspan="6" class="drive-empty">
                  <EmptyState
                    icon="folder"
                    title="暂无匹配的已发布文件"
                    hint="试试切换目录或调整搜索条件"
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
            :disabled="busy"
            @click="load()"
          >
            刷新列表
          </button>
          <div class="list-pagination">
            <span class="list-total">共 {{ total }} 份文件</span
            ><template v-if="total > 20">
              <button
                class="secondary"
                :disabled="page <= 1"
                @click="
                  page--;
                  load();
                "
              >
                上一页</button
              ><span>{{ page }} / {{ Math.ceil(total / 20) }}</span
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
            </template>
          </div>
        </footer>
      </template>
    </section>
  </div>
  <DriveFilePanel
    v-if="detailId"
    :key="detailId"
    :id="detailId"
    @close="detailId = ''"
    @changed="load"
    @read="
      (document) => {
        detailId = '';
        read(document);
      }
    "
  />
  <DriveManagement
    v-if="management"
    :folder="selected || undefined"
    @close="management = false"
    @changed="managementChanged"
    @root-created="revealCreatedFolder"
    @open-folder="openCreatedFolder"
  />
  <DocumentReader
    v-if="reading"
    :title="reading.name"
    :version="reading.currentVersion.versionNo"
    :page-count="preview?.pageCount || 0"
    :page-path="`/drive/documents/${reading.id}/previews/${preview?.previewId}/pages`"
    :loading="readingBusy"
    @close="closeReader"
    @error="
      (message) => {
        closeReader();
        error = message;
      }
    "
  />
</template>

<style scoped>
.file-name {
  border: 0;
  background: none;
  text-align: left;
  font: inherit;
  cursor: pointer;
  color: var(--ink);
  word-break: break-all;
}
.drive-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 0 0 22px;
}
.drive-heading h1 {
  font-size: 24px;
  margin: 6px 0;
}
.drive-seal {
  display: flex;
  gap: 10px;
  align-items: center;
  color: var(--ink);
  background: var(--surface-muted);
  border-radius: 30px;
  padding: 8px 12px;
  font-size: 13px;
}
.drive-files {
  /* 分组强调色跟随外观；暖纸主题沿用金色，不影响全局主要按钮。 */
  --drive-accent: var(--paper-gold, var(--brand));
  --drive-accent-text: var(--paper-gold-text, var(--brand));
  padding: 20px 24px;
  overflow: hidden;
}
.drive-spaces {
  margin-bottom: 12px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}
.drive-spaces-heading,
.drive-filter-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.drive-spaces-heading {
  margin-bottom: 12px;
}
.drive-spaces-heading h2 {
  margin: 0;
  color: var(--ink);
  font-size: 15px;
  font-weight: 600;
}
.drive-all-spaces {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  gap: 6px;
  min-height: 34px;
  padding: 6px 10px;
  border: 1px solid transparent;
  border-radius: 7px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 12px;
}
.drive-all-spaces:hover,
.drive-all-spaces.active {
  color: var(--drive-accent-text);
  background: var(--brand-soft);
}
.drive-all-spaces.active {
  border-color: color-mix(in srgb, var(--drive-accent) 35%, var(--line));
}
.drive-space-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.drive-space-card {
  display: flex;
  align-items: center;
  flex: 0 1 260px;
  gap: 12px;
  min-width: 0;
  min-height: 78px;
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface);
  color: var(--ink);
  text-align: left;
  transition: border-color 0.16s, background 0.16s;
}
.drive-space-card:hover {
  border-color: color-mix(in srgb, var(--drive-accent) 55%, var(--line));
  background: var(--surface-muted);
}
.drive-space-card.selected {
  border-color: var(--drive-accent);
  background: var(--brand-soft);
  color: var(--drive-accent-text);
}
.drive-space-icon {
  display: grid;
  place-items: center;
  flex: 0 0 24px;
  height: 24px;
  color: var(--text-secondary);
}
.selected .drive-space-icon {
  color: var(--drive-accent-text);
}
.drive-space-copy {
  display: grid;
  gap: 4px;
  min-width: 0;
}
.drive-space-copy strong {
  font-size: 14px;
  font-weight: 600;
}
.drive-space-description {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 400;
  line-height: 1.5;
}
.drive-space-indicator {
  display: grid;
  place-items: center;
  flex: 0 0 16px;
  height: 16px;
  margin-left: auto;
  color: var(--drive-accent-text);
}
/* 部门始终单行平铺，空间不足时仅在部门行内滚动，不撑宽页面。 */
.drive-departments {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}
.drive-departments-label {
  flex-shrink: 0;
  color: var(--text-secondary);
  font-size: 12px;
}
.drive-department-options {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  overflow-x: auto;
  padding: 2px 1px 5px;
  margin-bottom: -5px;
  scrollbar-width: thin;
  scrollbar-color: var(--line) transparent;
}
.drive-department-option {
  flex-shrink: 0;
  min-height: 34px;
  padding: 6px 12px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--surface-muted);
  color: var(--ink);
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
}
.drive-department-option:hover {
  color: var(--drive-accent-text);
  border-color: color-mix(in srgb, var(--drive-accent) 55%, var(--line));
  background: var(--brand-soft);
}
.drive-department-option.selected {
  color: var(--drive-accent-text);
  border-color: var(--drive-accent);
  background: var(--brand-soft);
  font-weight: 600;
}
.drive-toolbar,
.drive-crumb,
.drive-toolbar-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.drive-toolbar {
  justify-content: space-between;
  flex-wrap: wrap;
  min-height: 44px;
  margin-bottom: 16px;
}
.drive-toolbar-actions {
  flex-wrap: wrap;
  justify-content: flex-end;
  margin-left: auto;
}
.drive-toolbar-actions > .muted {
  font-size: 12px;
}
.drive-crumb {
  flex-wrap: wrap;
  min-width: 0;
  color: var(--text-secondary);
}
.drive-crumb button {
  border: 0;
  background: none;
  color: var(--ink);
  cursor: pointer;
  padding: 0;
}
.drive-crumb span {
  color: var(--text-secondary);
}
.drive-crumb select {
  width: auto;
  min-height: 38px;
  max-width: 100%;
}
.drive-search {
  margin-bottom: 20px;
  padding: 14px 18px 16px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface-muted);
}
.drive-filter-heading {
  margin-bottom: 12px;
}
.drive-filter-heading h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: var(--ink);
  font-size: 13px;
  font-weight: 600;
}
.drive-filter-heading h3 .p-icon {
  color: var(--text-secondary);
}
.drive-search form {
  display: grid;
  /* 按用途约束字段宽度，不随宽屏拉长；常规桌面分成两行。 */
  grid-template-columns: minmax(180px, 240px) minmax(130px, 170px) minmax(140px, 180px) minmax(160px, 200px);
  gap: 14px 16px;
  align-items: end;
}
/* 覆盖共享字段的并排布局，快捷日期组件同样使用标签上置的统一栅格。 */
#drive-file-filters :deep(.list-filter-field) {
  margin: 0;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  min-width: 0;
  gap: 6px;
  font-size: 12px;
  font-weight: 500;
  white-space: normal;
}
#drive-file-filters :deep(input),
#drive-file-filters :deep(select) {
  flex: none;
  min-width: 0;
  width: 100%;
  box-sizing: border-box;
  height: 36px;
  min-height: 36px;
  font: inherit;
  font-size: 13px;
}
.drive-filter-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  min-height: 36px;
}
.drive-filter-actions button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 36px;
  padding: 6px 12px;
  font-size: 12px;
}
@media (min-width: 1600px) {
  .drive-search form {
    /* 宽屏七个短字段与操作同排，避免原来的大面积空白和超长输入框。 */
    grid-template-columns: minmax(180px, 240px) 140px 150px 170px 130px 150px 150px 132px;
  }
}
@media (max-width: 960px) {
  .drive-search form {
    grid-template-columns: repeat(2, minmax(150px, 240px));
  }
}
@media (max-width: 600px) {
  .drive-heading {
    flex-wrap: wrap;
    gap: 12px;
  }
  .drive-files {
    padding: 16px;
  }
  .drive-spaces-heading {
    align-items: flex-start;
  }
  .drive-search form {
    grid-template-columns: minmax(0, 1fr);
  }
  .drive-space-card {
    flex: 1 1 140px;
  }
  .drive-search {
    padding: 16px;
  }
}
.drive-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}
.drive-table-wrap {
  overflow-x: auto;
}
.drive-table {
  min-width: 980px;
  table-layout: fixed;
}
.drive-table th:nth-child(2) {
  width: 90px;
}
.drive-table th:nth-child(3) {
  width: 100px;
}
.drive-table th:nth-child(4) {
  width: 168px;
}
.drive-table th:nth-child(5) {
  width: 145px;
}
.drive-table th:last-child {
  width: 165px;
}
.drive-file-title {
  display: flex;
  gap: 10px;
  align-items: center;
}
.drive-file-title .drive-pdf {
  flex-shrink: 0;
  margin: 0;
}
.drive-file-title .file-name {
  min-width: 0;
  padding: 0;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.drive-uploader {
  overflow-wrap: anywhere;
}
.drive-uploaded-at {
  white-space: nowrap;
  font-size: 12px;
}
.drive-location {
  display: flex;
  gap: 7px;
  align-items: center;
  color: var(--text-secondary);
  overflow-wrap: anywhere;
  font-size: 12px;
  min-width: 0;
}
.drive-location > span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.drive-location .p-icon {
  flex-shrink: 0;
}
.drive-row-actions .text-button {
  white-space: nowrap;
}
.drive-table th {
  text-align: left;
  color: var(--text-secondary);
  font-weight: 400;
  background: var(--surface-muted);
  padding: 14px 12px;
}
.drive-table td {
  padding: 22px 12px;
  border-bottom: 1px solid var(--line);
}
.drive-pdf {
  font-size: 9px;
  letter-spacing: 0.3px;
  background: #faece7;
  color: #ae694e;
  border-radius: 6px;
  padding: 8px 6px;
  margin-right: 12px;
}
.drive-empty {
  text-align: center;
  height: 180px;
  color: var(--text-secondary);
}
.drive-notice {
  color: #537349;
  padding: 12px 16px;
  background: #f0f6ed;
  border-radius: 10px;
}
.drive-progress {
  margin: 12px 0 20px;
  color: var(--ink);
}
.drive-progress progress {
  display: block;
  width: 100%;
  height: 6px;
  margin-top: 8px;
  accent-color: var(--text-secondary);
}
</style>
