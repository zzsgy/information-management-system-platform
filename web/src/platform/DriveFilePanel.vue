<script setup lang="ts">
import { computed, inject, onMounted, onUnmounted, ref } from "vue";
import { api, mutate, dateTime, query } from "./api";
import { driveAccept, uploadDrive } from "./driveUpload";
import { isDrivePreviewable } from "./drivePreview";
import OfficeEditor from "./OfficeEditor.vue";
import DriveManagement from "./DriveManagement.vue";
import OfficeStatus from "./OfficeStatus.vue";
import Icon from "../preview/PlatformIcon.vue";

interface Doc {
  id: string;
  name: string;
  createdAt: string;
  uploadedByName: string;
  rowVersion: number;
  folderId: string;
  collaborationEnabled: boolean;
  actions: string[];
  currentVersion: {
    id: string;
    versionNo: number;
    mediaType: string;
    byteSize: number;
  };
}
interface Version {
  id: string;
  versionNo: number;
  sourceType: string;
  createdAt: string;
  mediaType: string;
  byteSize: number;
  createdByName?: string;
}
interface Folder {
  id: string;
  name: string;
  canUpload: boolean;
}
const props = defineProps<{ id: string }>(),
  emit = defineEmits<{ close: []; changed: []; read: [document: Doc] }>();
const ask = inject<(title: string) => Promise<string | null>>("askPassword")!;
const doc = ref<Doc>(),
  versions = ref<Version[]>([]),
  error = ref(""),
  notice = ref(""),
  loading = ref(false),
  busy = ref(false),
  mode = ref(""),
  name = ref(""),
  reason = ref(""),
  source = ref(""),
  target = ref<Folder>(),
  locations = ref<Folder[]>([]),
  locationTrail = ref<Folder[]>([]),
  confirmation = ref<{
    confirmationDigest: string;
    sourceZone: string;
    targetZone: string;
  }>(),
  versionPage = ref(1),
  versionTotal = ref(0),
  picker = ref<HTMLInputElement>(),
  uploadLabel = ref(""),
  office = ref(false),
  editing = ref(false);
const abort = new AbortController();
let alive = true;
const management = ref(false);
const fileFormat = computed(
  () => doc.value?.name.split(".").at(-1)?.toUpperCase() || "FILE",
);
const fileSize = computed(() => {
  const bytes = doc.value?.currentVersion.byteSize || 0;
  return bytes < 1048576
    ? `${(bytes / 1024).toFixed(1)} KB`
    : `${(bytes / 1048576).toFixed(1)} MB`;
});
const versionSources: Record<string, string> = {
  LOCAL_UPLOAD: "本地上传",
  HISTORY_RESTORE: "历史恢复",
  OFFICE_SAVE: "在线保存",
  OFFICE_RESOLVE: "候选处理",
  OFFICE_CONVERSION: "格式转换",
};
async function load() {
  loading.value = true;
  try {
    doc.value = await api<Doc>(`/drive/documents/${props.id}`);
    name.value = doc.value.name;
    await history();
  } finally {
    loading.value = false;
  }
}
async function history() {
  const data = await api<{ items: Version[]; total: number }>(
    `/drive/documents/${props.id}/versions?${query({ page: versionPage.value, pageSize: 10 })}`,
  );
  versions.value = data.items;
  versionTotal.value = data.total;
}
function choose(action: string, version?: Version) {
  mode.value = action;
  reason.value = "";
  error.value = "";
  source.value = version?.id || "";
  confirmation.value = undefined;
  if (action === "MOVE") {
    locationTrail.value = [];
    void browse();
  }
}
async function browse(folder?: Folder) {
  try {
    if (folder) locationTrail.value.push(folder);
    const data = await api<{ items: Folder[] }>(
      `/drive/folders?${query({ parentId: locationTrail.value.at(-1)?.id })}`,
    );
    locations.value = data.items;
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
async function selectTarget(folder: Folder) {
  try {
    confirmation.value = await api(
      `/drive/documents/${props.id}/move-check?${query({ targetFolderId: folder.id })}`,
    );
    target.value = folder;
  } catch (failure) {
    error.value = (failure as Error).message;
  }
}
async function submit() {
  if (!doc.value) return;
  busy.value = true;
  error.value = "";
  try {
    const current = doc.value;
    let path = `/drive/documents/${props.id}`,
      method = "PATCH",
      action = mode.value,
      body: Record<string, unknown> = { reason: reason.value };
    if (action === "RENAME") {
      action = "EDIT";
      body.name = name.value;
    } else if (action === "DELETE") method = "DELETE";
    else if (action === "COLLABORATE")
      body.collaborationEnabled = !current.collaborationEnabled;
    else if (action === "CONVERT") {
      action = "EDIT";
      path += "/convert";
      method = "POST";
      body.sourceVersionId = current.currentVersion.id;
    } else if (action === "RESTORE_VERSION") {
      action = "EDIT";
      path += "/restore-version";
      method = "POST";
      body.sourceVersionId = source.value;
    } else if (action === "MOVE") {
      path += "/move";
      method = "POST";
      body.targetFolderId = target.value?.id;
      body.confirmationDigest = confirmation.value?.confirmationDigest;
    }
    const password = await ask("确认本次公盘操作");
    if (!password) return;
    await mutate(
      path,
      method,
      body,
      current.id,
      "DRIVE_DOCUMENT",
      action,
      password,
      current.rowVersion,
    );
    notice.value = "操作已完成。";
    mode.value = "";
    emit("changed");
    if (action === "DELETE") {
      emit("close");
      return;
    }
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}
async function newVersion(event: Event) {
  const input = event.target as HTMLInputElement,
    file = input.files?.[0];
  input.value = "";
  if (!file || !doc.value) return;
  busy.value = true;
  error.value = "";
  try {
    const current = doc.value,
      upload = await uploadDrive(
        file,
        current.id,
        "DRIVE_VERSION",
        (label, value) =>
          (uploadLabel.value = value === null ? label : `${label} ${value}%`),
        abort.signal,
      );
    if (!alive) return;
    const password = await ask("将检测通过的文件发布为新版本");
    if (!password) return;
    await mutate(
      `/drive/documents/${current.id}/versions`,
      "POST",
      { uploadSessionId: upload, reason: "本地文件更新" },
      current.id,
      "DRIVE_DOCUMENT",
      "EDIT",
      password,
      current.rowVersion,
    );
    notice.value = "新版本已发布，历史版本已保留。";
    emit("changed");
    await load();
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
    uploadLabel.value = "";
  }
}
function read(version: Version) {
  if (doc.value)
    emit("read", {
      ...doc.value,
      currentVersion: {
        id: version.id,
        versionNo: version.versionNo,
        mediaType: version.mediaType,
        byteSize: version.byteSize,
      },
    });
}
onMounted(async () => {
  try {
    await load();
    office.value = (
      await api<{ enabled: boolean }>("/drive/office/status")
    ).enabled;
  } catch (failure) {
    error.value = (failure as Error).message;
  }
});
onUnmounted(() => {
  alive = false;
  abort.abort();
});
</script>
<template>
  <div class="drive-detail-shade" @click.self="!busy && emit('close')">
    <section
      class="drive-detail panel"
      role="dialog"
      aria-modal="true"
      aria-label="文件详情"
    >
      <header class="detail-heading">
        <span class="detail-file-icon"><Icon name="file" :size="28" /></span>
        <div class="detail-title">
          <span class="eyebrow">文件详情与版本</span>
          <h2>{{ doc?.name || "正在加载" }}</h2>
        </div>
        <button
          class="detail-close"
          aria-label="关闭文件详情"
          :disabled="busy"
          @click="emit('close')"
        >
          <Icon name="close" :size="22" />
        </button>
      </header>
      <div class="detail-body">
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <p v-if="notice" class="muted" role="status">{{ notice }}</p>
        <template v-if="doc">
          <div class="detail-summary">
            <span
              ><small>文件格式</small><strong>{{ fileFormat }}</strong></span
            >
            <span
              ><small>文件大小</small><strong>{{ fileSize }}</strong></span
            >
            <span
              ><small>当前版本</small
              ><strong>V{{ doc.currentVersion.versionNo }}</strong></span
            >
            <span
              ><small>上传人</small
              ><strong>{{ doc.uploadedByName }}</strong></span
            >
            <span
              ><small>上传时间</small
              ><strong>{{ dateTime(doc.createdAt) }}</strong></span
            >
          </div>
          <div class="detail-actions">
            <button
              v-if="isDrivePreviewable(doc.currentVersion.mediaType)"
              class="primary"
              @click="
                read({
                  ...doc.currentVersion,
                  sourceType: '',
                  createdAt: doc.createdAt,
                })
              "
            >
              <Icon name="eye" :size="17" />阅读文档
            </button>
            <button
              v-if="doc.actions.includes('COLLABORATE')"
              class="secondary"
              @click="management = true"
            >
              人员授权
            </button>
            <a
              v-if="doc.actions.includes('DOWNLOAD')"
              class="secondary"
              :href="`/api/v1/drive/documents/${doc.id}/download?versionId=${doc.currentVersion.id}`"
              >下载当前版</a
            >
            <button
              v-if="doc.actions.includes('EDIT')"
              class="secondary"
              :disabled="busy"
              @click="choose('RENAME')"
            >
              重命名
            </button>
            <button
              v-if="doc.actions.includes('EDIT')"
              class="secondary"
              :disabled="busy"
              @click="picker?.click()"
            >
              上传新版本
            </button>
            <button
              v-if="doc.actions.includes('MOVE')"
              class="secondary"
              :disabled="busy"
              @click="choose('MOVE')"
            >
              移动
            </button>
            <button
              v-if="doc.actions.includes('DELETE')"
              class="secondary"
              :disabled="busy"
              @click="choose('DELETE')"
            >
              移入回收站
            </button>
            <button
              v-if="
                doc.actions.includes('COLLABORATE') &&
                /\.(docx?|xlsx?|pptx?)$/i.test(doc.name)
              "
              class="secondary"
              :disabled="busy"
              @click="choose('COLLABORATE')"
            >
              {{ doc.collaborationEnabled ? "关闭协作" : "开启协作" }}
            </button>
            <button
              v-if="
                doc.collaborationEnabled &&
                doc.actions.includes('EDIT') &&
                office &&
                /\.(docx|xlsx|pptx)$/i.test(doc.name)
              "
              class="primary"
              :disabled="busy || loading"
              @click="editing = true"
            >
              在线编辑
            </button>
            <button
              v-if="
                office &&
                doc.actions.includes('EDIT') &&
                /\.(doc|xls|ppt)$/i.test(doc.name)
              "
              class="primary"
              :disabled="busy"
              @click="choose('CONVERT')"
            >
              转换为可编辑格式
            </button>
            <input
              ref="picker"
              type="file"
              :accept="driveAccept"
              hidden
              @change="newVersion"
            />
          </div>
          <p v-if="uploadLabel" role="status">{{ uploadLabel }}</p>
          <p
            v-if="!office && /\.(docx?|xlsx?|pptx?)$/i.test(doc.name)"
            class="muted"
          >
            Office在线服务尚未配置。当前可上传、下载与管理历史版本。
          </p>
          <form v-if="mode" class="detail-form" @submit.prevent="submit">
            <label v-if="mode === 'RENAME'"
              >文件名称<input v-model="name" maxlength="512" required
            /></label>
            <p v-if="mode === 'DELETE'">
              文件将进入回收站，原件和历史版本继续保留。
            </p>
            <p v-if="mode === 'RESTORE_VERSION'">
              恢复将追加一个新版本，不会删除当前版或中间版本。
            </p>
            <p v-if="mode === 'CONVERT'">
              将生成可在线编辑的新版本，旧格式原件保留在历史版本中。转换完成后请核对排版和公式。
            </p>
            <p v-if="mode === 'COLLABORATE'">
              {{
                doc.collaborationEnabled
                  ? "关闭后停止新增协作。"
                  : "开启后仍需给具体人员单独授予编辑权。"
              }}
            </p>
            <div v-if="mode === 'MOVE'" class="location-picker">
              <strong>选择目标目录</strong>
              <div>
                <button
                  type="button"
                  class="text-button"
                  @click="
                    locationTrail = [];
                    browse();
                  "
                >
                  公盘根目录</button
                ><span v-for="item in locationTrail" :key="item.id">
                  / {{ item.name }}</span
                >
              </div>
              <div v-for="folder in locations" :key="folder.id">
                <button
                  type="button"
                  class="text-button"
                  @click="browse(folder)"
                >
                  {{ folder.name }} ›</button
                ><button
                  v-if="folder.canUpload && folder.id !== doc.folderId"
                  type="button"
                  class="secondary"
                  @click="selectTarget(folder)"
                >
                  选择此目录
                </button>
              </div>
              <p v-if="confirmation">
                目标：{{
                  target?.name
                }}。移动后目录权限可能改变，请确认可见范围。
              </p>
            </div>
            <label
              >操作原因<textarea
                v-model="reason"
                maxlength="1000"
                rows="2"
                required
              />
            </label>
            <div class="detail-actions">
              <button
                class="primary"
                :disabled="busy || (mode === 'MOVE' && !confirmation)"
              >
                确认操作</button
              ><button
                type="button"
                class="secondary"
                :disabled="busy"
                @click="mode = ''"
              >
                取消
              </button>
            </div>
          </form>
          <OfficeStatus
            :key="doc.rowVersion"
            :document-id="doc.id"
            :document-version="doc.rowVersion"
            :download-allowed="doc.actions.includes('DOWNLOAD')"
            @changed="
              load();
              emit('changed');
            "
          />
          <div class="version-heading">
            <div>
              <h3><Icon name="history" :size="18" />历史版本</h3>
              <p class="muted">保留每次更新的记录，可查看或下载历史内容。</p>
            </div>
          </div>
          <div class="version-table">
            <table>
              <thead>
                <tr>
                  <th>版本</th>
                  <th>来源</th>
                  <th>创建人</th>
                  <th>创建时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="version in versions" :key="version.id">
                  <td>
                    <span class="version-number"
                      >V{{ version.versionNo
                      }}<small
                        v-if="version.id === doc.currentVersion.id"
                        class="current-version"
                        >当前版本</small
                      ></span
                    >
                  </td>
                  <td>
                    {{
                      versionSources[version.sourceType] || version.sourceType
                    }}
                  </td>
                  <td>{{ version.createdByName }}</td>
                  <td>{{ dateTime(version.createdAt) }}</td>
                  <td>
                    <button
                      v-if="isDrivePreviewable(version.mediaType)"
                      class="text-button"
                      @click="read(version)"
                    >
                      阅读</button
                    ><a
                      v-if="doc.actions.includes('DOWNLOAD')"
                      class="text-button"
                      :href="`/api/v1/drive/documents/${doc.id}/download?versionId=${version.id}`"
                      >下载</a
                    ><button
                      v-if="
                        doc.actions.includes('EDIT') &&
                        version.id !== doc.currentVersion.id
                      "
                      class="text-button"
                      :disabled="busy"
                      @click="choose('RESTORE_VERSION', version)"
                    >
                      恢复
                    </button>
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
              @click="history()"
            >
              刷新列表
            </button>
            <div class="list-pagination">
              <span class="list-total">共 {{ versionTotal }} 个版本</span
              ><template v-if="versionTotal > 10">
                <button
                  class="secondary"
                  :disabled="versionPage <= 1"
                  @click="
                    versionPage--;
                    history();
                  "
                >
                  上一页</button
                ><span
                  >{{ versionPage }} / {{ Math.ceil(versionTotal / 10) }}</span
                ><button
                  class="secondary"
                  :disabled="versionPage * 10 >= versionTotal"
                  @click="
                    versionPage++;
                    history();
                  "
                >
                  下一页
                </button>
              </template>
            </div>
          </footer>
        </template>
      </div>
    </section>
  </div>
  <OfficeEditor
    v-if="editing && doc"
    :document="doc"
    @close="
      editing = false;
      load();
      emit('changed');
    "
  />
  <DriveManagement
    v-if="management && doc"
    :document-id="doc.id"
    :document-name="doc.name"
    :document-folder-id="doc.folderId"
    @close="management = false"
    @changed="load"
  />
</template>
<style scoped>
.drive-detail-shade {
  position: fixed;
  inset: 0;
  background: #172b3b60;
  z-index: 70;
  display: flex;
  justify-content: center;
  align-items: center;
  padding: 32px;
  backdrop-filter: blur(3px);
}
.drive-detail {
  width: min(1060px, 100%);
  max-height: calc(100dvh - 64px);
  overflow: hidden;
  border-radius: 16px;
  padding: 0;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  box-shadow: 0 20px 80px #152d4533;
}
.detail-heading {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  padding: 24px 28px;
  border-bottom: 1px solid var(--line);
  flex-shrink: 0;
}
.drive-detail h2 {
  font-size: 20px;
  line-height: 1.5;
  overflow-wrap: anywhere;
  margin: 5px 0 0;
}
.detail-title {
  flex: 1;
  min-width: 0;
}
.detail-file-icon {
  width: 54px;
  height: 62px;
  background: var(--brand-soft);
  color: var(--brand-hover);
  display: grid;
  place-items: center;
  border-radius: 10px;
  flex-shrink: 0;
}
.detail-close {
  border: 0;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  align-self: flex-start;
  flex-shrink: 0;
  display: grid;
  place-items: center;
}
.detail-close:hover {
  background: var(--surface-muted);
}
.detail-body {
  padding: 24px 28px 28px;
  overflow: auto;
  min-height: 0;
}
.detail-summary {
  display: grid;
  grid-template-columns: 0.7fr 0.8fr 0.7fr 0.9fr 1.6fr;
  gap: 20px;
  padding: 16px 20px;
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 10px;
  margin-bottom: 20px;
}
.detail-summary span {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.detail-summary small {
  color: var(--text-secondary);
  font-size: 12px;
}
.detail-summary strong {
  font-size: 13px;
  font-weight: 500;
}
.version-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-top: 24px;
  margin-top: 24px;
  border-top: 1px solid var(--line);
}
.version-heading h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  margin: 0 0 7px;
}
.version-heading p {
  margin: 0 0 18px;
  font-size: 12px;
}
.version-heading > span {
  color: var(--text-secondary);
  font-size: 12px;
  flex-shrink: 0;
}
.version-table {
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: auto;
}
.version-number {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  font-weight: 600;
  white-space: nowrap;
}
.detail-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
  margin: 14px 0;
}
.detail-actions a {
  text-decoration: none;
}
.detail-actions > button,
.detail-actions > a {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 38px;
  padding: 8px 14px;
  font-size: 13px;
}
.detail-form {
  padding: 20px;
  background: var(--surface-muted);
  border-radius: 12px;
  margin: 22px 0;
}
.detail-form label {
  display: grid;
  gap: 8px;
  margin: 14px 0;
}
.drive-detail table {
  width: 100%;
  border-collapse: collapse;
}
.drive-detail th,
.drive-detail td {
  padding: 15px 8px;
  border-bottom: 1px solid var(--line);
  text-align: left;
  font-size: 13px;
}
.drive-detail td .text-button {
  margin-right: 10px;
}
.location-picker {
  display: grid;
  gap: 10px;
}
.location-picker > div {
  display: flex;
  justify-content: space-between;
  gap: 10px;
}
.drive-detail .current-version {
  display: inline-block;
  font-weight: 400;
  font-size: 11px;
  color: var(--brand);
  background: var(--brand-soft);
  padding: 3px 7px;
  border-radius: 4px;
}
@media (max-width: 760px) {
  .drive-detail-shade {
    padding: 16px;
  }
  .drive-detail {
    max-height: calc(100dvh - 32px);
  }
  .detail-heading,
  .detail-body {
    padding: 20px;
  }
  .detail-file-icon {
    display: none;
  }
  .detail-summary {
    grid-template-columns: 1fr 1fr;
  }
  .drive-detail h2 {
    font-size: 17px;
  }
  .version-table table {
    min-width: 570px;
  }
}
</style>
