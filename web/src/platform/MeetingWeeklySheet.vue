<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { api } from "./api";
import { coordinationLabels, type MeetingOption } from "./meeting";
import { portalTime } from "./portal";
import MarkdownImageView from "./MarkdownImageView.vue";
import WeeklyReportRowEditor from "./WeeklyReportRowEditor.vue";
import {
  weeklyLabels,
  type WeeklyKind,
  type WeeklyPreview,
  type WeeklyRow,
  type WeeklySheet,
} from "./weeklyReport";

const props = defineProps<{
  meetingId: string;
  departments: MeetingOption[];
  open: boolean;
}>();
const emit = defineEmits<{ extracted: [] }>();
const base = "/meetings/" + props.meetingId + "/weekly-report";
const sheet = ref<WeeklySheet>();
const selection = ref("COORDINATION");
const historicalVersion = ref("");
const history = ref<WeeklyRow[]>();
const preview = ref<WeeklyPreview>();
const editor = ref<{ kind: WeeklyKind; row?: WeeklyRow }>();
const error = ref("");
const status = ref("");
const busy = ref(false);
let refreshing = false;
let interval: number | undefined;
let mounted = true;

const own = computed(() =>
  sheet.value?.departments.find(
    (unit) => unit.departmentId === sheet.value?.ownDepartmentId,
  ),
);
const activeUnits = computed(
  () => sheet.value?.departments.filter((unit) => unit.active) ?? [],
);
const department = computed(() =>
  sheet.value?.departments.find(
    (unit) => unit.departmentId === selection.value,
  ),
);
const versions = computed(
  () =>
    sheet.value?.versions.filter(
      (version) => version.departmentId === selection.value,
    ) ?? [],
);
const rows = computed(() => history.value ?? sheet.value?.rows ?? []);
const coordination = computed(() =>
  rows.value.filter((row) => row.kind === "COORDINATION"),
);
const narrative = computed(() =>
  rows.value.filter((row) => row.departmentId === selection.value),
);
const narrativeKinds = ["REPORT", "WEEKLY_PLAN"] as const;

/** 部门页在服务器更新后同步，正在编辑的行由编辑器持有自己的草稿。 */
async function refresh(background = false) {
  if (refreshing) return;
  refreshing = true;
  try {
    const data = await api<WeeklySheet>(base);
    if (!mounted) return;
    sheet.value = data;
    if (
      !data.departments.some((unit) => unit.departmentId === selection.value) &&
      selection.value !== "COORDINATION"
    )
      selection.value = "COORDINATION";
  } catch (failure) {
    if (!background) error.value = (failure as Error).message;
  } finally {
    refreshing = false;
  }
}

onMounted(() => {
  void refresh();
  // 已结束的会议只读，填写期间页面可见时定期同步同事的改动。
  if (props.open)
    interval = window.setInterval(() => {
      if (props.open && !document.hidden && !busy.value && !editor.value)
        void refresh(true);
    }, 4000);
});
onUnmounted(() => {
  mounted = false;
  window.clearInterval(interval);
});

/** 切换部门时回到当前版本，选择历史版本后保持只读。 */
watch(selection, () => {
  historicalVersion.value = "";
  history.value = undefined;
});
watch(historicalVersion, async (version, _, onCleanup) => {
  history.value = undefined;
  if (!version || !department.value) return;
  const controller = new AbortController();
  onCleanup(() => controller.abort());
  try {
    history.value = await api<WeeklyRow[]>(
      base + "/departments/" + selection.value + "/versions/" + version,
      {
        signal: controller.signal,
      },
    );
  } catch (failure) {
    if (!controller.signal.aborted) {
      error.value = (failure as Error).message;
      historicalVersion.value = "";
    }
  }
});

/** 草稿入口只对应自己的部门，允许同部门受邀人更新同一行。 */
function editable(row: WeeklyRow) {
  return (
    !historicalVersion.value &&
    !!own.value?.canEdit &&
    row.departmentId === own.value.departmentId
  );
}

function imageBase(row: WeeklyRow) {
  return "/api/v1" + base + "/rows/" + row.id + "/images";
}

/** 同一个操作区展示请求结果，处理后刷新部门状态及行列表。 */
async function act(action: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  status.value = "";
  try {
    await action();
  } catch (failure) {
    error.value = (failure as Error).message;
  } finally {
    busy.value = false;
  }
}

function departmentAction(action: "submit" | "reopen") {
  const unit = department.value;
  if (!unit) return;
  return act(async () => {
    await api(base + "/departments/" + unit.departmentId + "/" + action, {
      method: "POST",
    });
    status.value =
      action === "submit"
        ? unit.name + "周报已提交"
        : unit.name + "已重新开放填写";
    await refresh();
  });
}

function remove(row: WeeklyRow) {
  return act(async () => {
    await api(base + "/rows/" + row.id, {
      method: "DELETE",
      headers: { "X-Expected-Version": String(row.version) },
    });
    await refresh();
  });
}

function showPreview() {
  return act(async () => {
    preview.value = await api<WeeklyPreview>(base + "/extract");
  });
}

function extract() {
  const token = preview.value?.token;
  if (!token) return;
  return act(async () => {
    const result = await api<{ extracted: number }>(base + "/extract", {
      method: "POST",
      body: JSON.stringify({ token }),
    });
    status.value = "已提取 " + result.extracted + " 个部门的材料";
    preview.value = undefined;
    await refresh();
    emit("extracted");
  });
}

/** 预览可按部门直接查看对应提交版本。 */
async function inspectVersion(unit: {
  departmentId: string;
  submittedVersion: number;
}) {
  selection.value = unit.departmentId;
  await nextTick();
  historicalVersion.value = String(unit.submittedVersion);
}
</script>

<template>
  <section class="flow-dialog-section weekly-sheet" aria-label="在线周会表">
    <header class="sheet-header">
      <div>
        <h2>在线周会表</h2>
        <p v-if="sheet?.canExtract">
          {{ activeUnits.filter((unit) => unit.submittedVersion).length }} /
          {{ activeUnits.length }} 个部门已提交
        </p>
        <p v-else-if="open">同部门受邀人协作填写，部门负责人统一提交。</p>
      </div>
      <button
        v-if="sheet?.canExtract"
        type="button"
        class="primary"
        :disabled="busy"
        @click="showPreview"
      >
        提取本场材料
      </button>
    </header>
    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <p v-if="status" class="sheet-status" role="status">{{ status }}</p>

    <section v-if="preview" class="extraction-preview" aria-label="提取预览">
      <header>
        <h3>提取预览</h3>
        <button type="button" class="text-button" @click="preview = undefined">
          关闭预览
        </button>
      </header>
      <ul v-if="preview.errors.length" class="form-error">
        <li v-for="problem in preview.errors" :key="problem">{{ problem }}</li>
      </ul>
      <div class="extraction-departments">
        <div>
          <h4>待提取 {{ preview.ready.length }} 个部门</h4>
          <ul v-if="preview.ready.length">
            <li v-for="unit in preview.ready" :key="unit.departmentId">
              <button
                type="button"
                class="text-button"
                @click="inspectVersion(unit)"
              >
                {{ unit.name }} · 查看第 {{ unit.submittedVersion }} 版
              </button>
            </li>
          </ul>
          <p v-else>暂无新的已提交材料。</p>
        </div>
        <div v-if="preview.missing.length">
          <h4>未提交 {{ preview.missing.length }} 个部门</h4>
          <p>{{ preview.missing.map((unit) => unit.name).join("、") }}</p>
        </div>
      </div>
      <button
        type="button"
        class="primary"
        :disabled="busy || !preview.ready.length || !!preview.errors.length"
        @click="extract"
      >
        确认提取
      </button>
    </section>

    <p v-if="!sheet && !error" role="status">正在加载在线周会表…</p>
    <template v-if="sheet">
      <nav class="sheet-tabs" aria-label="工作表页签">
        <button
          type="button"
          :aria-pressed="!department"
          @click="selection = 'COORDINATION'"
        >
          协调事宜
        </button>
        <button
          v-for="unit in sheet.departments"
          :key="unit.departmentId"
          type="button"
          :aria-pressed="unit.departmentId === selection"
          @click="selection = unit.departmentId"
        >
          {{ unit.name }}
        </button>
      </nav>

      <template v-if="!department">
        <div class="sheet-toolbar">
          <h3>协调事宜</h3>
          <button
            v-if="own?.canEdit"
            type="button"
            class="secondary"
            @click="editor = { kind: 'COORDINATION' }"
          >
            新增事项
          </button>
        </div>
        <div
          class="sheet-grid"
          tabindex="0"
          role="region"
          aria-label="各部门协调事项"
        >
          <table>
            <thead>
              <tr>
                <th scope="col">序号</th>
                <th scope="col">具体事宜</th>
                <th scope="col">需协调或配合内容</th>
                <th scope="col">期望结果 / 验收标准</th>
                <th scope="col">填报部门</th>
                <th scope="col">填报人+工号</th>
                <th scope="col">配合部门</th>
                <th scope="col">责任人（唯一）</th>
                <th scope="col">事项类别</th>
                <th v-if="own?.canEdit" scope="col">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, index) in coordination" :key="row.id">
                <th scope="row">{{ index + 1 }}</th>
                <td>
                  <button
                    v-if="editable(row)"
                    type="button"
                    class="text-button cell-edit"
                    @click="editor = { kind: row.kind, row }"
                  >
                    {{ row.data.subject || "填写具体事宜" }}
                  </button>
                  <template v-else>{{ row.data.subject || "—" }}</template>
                </td>
                <td>{{ row.data.support || "—" }}</td>
                <td>{{ row.data.expected || "—" }}</td>
                <td>
                  {{
                    sheet.departments.find(
                      (unit) => unit.departmentId === row.departmentId,
                    )?.name
                  }}
                </td>
                <td>{{ row.authorName }} · {{ row.employeeNo }}</td>
                <td>{{ row.partner || "—" }}</td>
                <td>
                  {{
                    row.assigneeName
                      ? row.assigneeName + " · " + row.assigneeNo
                      : "—"
                  }}
                </td>
                <td>
                  {{ coordinationLabels[row.data.category ?? "DEPARTMENT"] }}
                </td>
                <td v-if="own?.canEdit">
                  <button
                    v-if="editable(row)"
                    type="button"
                    class="text-button"
                    :disabled="busy"
                    @click="remove(row)"
                  >
                    删除
                  </button>
                </td>
              </tr>
              <tr v-if="!coordination.length">
                <td :colspan="own?.canEdit ? 10 : 9" class="empty-cell">
                  暂无协调事项
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <template v-else>
        <div class="department-toolbar">
          <div>
            <h3>{{ department.name }}</h3>
            <p>
              {{
                department.headName
                  ? "负责人 " + department.headName
                  : "尚未配置部门负责人"
              }}
              <template
                v-if="department.headName && !department.headInvited && open"
              >
                · 负责人尚未受邀</template
              >
              ·
              {{
                department.active
                  ? department.submittedVersion
                    ? "已提交第 " + department.submittedVersion + " 版"
                    : "待提交"
                  : "暂无有效受邀人"
              }}
              <template v-if="department.extractedVersion">
                · 已提取第 {{ department.extractedVersion }} 版</template
              >
            </p>
          </div>
          <div class="sheet-actions">
            <label v-if="versions.length">
              提交版本
              <select v-model="historicalVersion">
                <option value="">当前表格</option>
                <option
                  v-for="version in versions"
                  :key="version.version"
                  :value="String(version.version)"
                >
                  第 {{ version.version }} 版 · {{ version.submittedBy }} ·
                  {{ portalTime(version.submittedAt) }}
                </option>
              </select>
            </label>
            <button
              v-if="department.canSubmit && !historicalVersion"
              type="button"
              class="primary"
              :disabled="busy"
              @click="departmentAction('submit')"
            >
              提交部门周报
            </button>
            <button
              v-if="department.canReopen && !historicalVersion"
              type="button"
              class="secondary"
              :disabled="busy"
              @click="departmentAction('reopen')"
            >
              重新开放填写
            </button>
          </div>
        </div>
        <p v-if="historicalVersion && !history" role="status">
          正在加载提交版本…
        </p>
        <template v-else>
          <section
            v-for="kind in narrativeKinds"
            :key="kind"
            class="narrative-sheet"
          >
            <div class="sheet-toolbar">
              <h4>{{ weeklyLabels[kind] }}</h4>
              <button
                v-if="department.canEdit && !historicalVersion"
                type="button"
                class="secondary"
                @click="editor = { kind }"
              >
                新增{{ weeklyLabels[kind] }}
              </button>
            </div>
            <div
              class="sheet-grid"
              tabindex="0"
              role="region"
              :aria-label="weeklyLabels[kind]"
            >
              <table>
                <thead>
                  <tr>
                    <th scope="col">序号</th>
                    <th scope="col">事项说明</th>
                    <th scope="col">备注</th>
                    <th scope="col">填报人</th>
                    <th
                      v-if="department.canEdit && !historicalVersion"
                      scope="col"
                    >
                      操作
                    </th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="(row, index) in narrative.filter(
                      (entry) => entry.kind === kind,
                    )"
                    :key="row.id"
                  >
                    <th scope="row">{{ index + 1 }}</th>
                    <td class="content-cell">
                      <MarkdownImageView
                        :value="row.data.markdown ?? '—'"
                        :image-base="imageBase(row)"
                        :image-keys="row.data.imageKeys"
                        numbered
                      />
                    </td>
                    <td>{{ row.data.note || "—" }}</td>
                    <td>
                      {{ row.authorName
                      }}<small v-if="row.updatedBy !== row.authorName"
                        >修改人 {{ row.updatedBy }}</small
                      >
                    </td>
                    <td v-if="editable(row)">
                      <button
                        type="button"
                        class="text-button"
                        :aria-label="
                          '编辑' +
                          weeklyLabels[kind] +
                          '第' +
                          (index + 1) +
                          '行'
                        "
                        @click="editor = { kind, row }"
                      >
                        编辑
                      </button>
                      <button
                        type="button"
                        class="text-button"
                        :disabled="busy"
                        @click="remove(row)"
                      >
                        删除
                      </button>
                    </td>
                  </tr>
                  <tr v-if="!narrative.some((row) => row.kind === kind)">
                    <td
                      :colspan="
                        department.canEdit && !historicalVersion ? 5 : 4
                      "
                      class="empty-cell"
                    >
                      暂无内容
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
          <section
            v-if="history?.some((row) => row.kind === 'COORDINATION')"
            class="narrative-sheet"
          >
            <h4>本版协调事宜</h4>
            <div
              class="sheet-grid"
              tabindex="0"
              role="region"
              aria-label="本版协调事宜"
            >
              <table>
                <thead>
                  <tr>
                    <th scope="col">事项</th>
                    <th scope="col">需协调或配合内容</th>
                    <th scope="col">期望结果</th>
                    <th scope="col">填报人</th>
                    <th scope="col">配合部门</th>
                    <th scope="col">责任人</th>
                    <th scope="col">事项类别</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in coordination" :key="row.id">
                    <td>{{ row.data.subject }}</td>
                    <td>{{ row.data.support || "—" }}</td>
                    <td>{{ row.data.expected }}</td>
                    <td>{{ row.authorName }}</td>
                    <td>{{ row.data.department }}</td>
                    <td>{{ row.data.assigneeName }}</td>
                    <td>
                      {{
                        coordinationLabels[row.data.category ?? "DEPARTMENT"]
                      }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
        </template>
      </template>
    </template>
    <WeeklyReportRowEditor
      v-if="editor"
      :base="base"
      :kind="editor.kind"
      :row="editor.row"
      :departments="departments"
      @saved="refresh(true)"
      @closed="
        editor = undefined;
        refresh();
      "
    />
  </section>
</template>

<style scoped>
.weekly-sheet {
  display: grid;
  gap: 14px;
}
.weekly-sheet :is(h2, h3, h4, p) {
  margin: 0;
}
.sheet-header,
.department-toolbar,
.sheet-toolbar,
.sheet-actions,
.extraction-preview > header {
  display: flex;
  align-items: center;
  gap: 12px;
  justify-content: space-between;
  flex-wrap: wrap;
}
.sheet-header p,
.department-toolbar p {
  color: var(--text-secondary);
  margin-block-start: 4px;
}
.sheet-actions label {
  display: flex;
  align-items: center;
  gap: 8px;
}
.sheet-tabs {
  display: flex;
  overflow: auto;
  gap: 4px;
  border-bottom: 1px solid var(--line);
}
.sheet-tabs button {
  border: 1px solid transparent;
  border-bottom: none;
  border-radius: 7px 7px 0 0;
  background: var(--surface-muted);
  color: var(--ink);
  white-space: nowrap;
  padding: 9px 14px;
}
.sheet-tabs button[aria-pressed="true"] {
  color: var(--brand);
  background: var(--brand-soft);
  border-color: var(--line);
  font-weight: 650;
}
.narrative-sheet {
  display: grid;
  gap: 8px;
}
.sheet-grid {
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow: auto;
  max-height: 58vh;
}
table {
  width: 100%;
  min-width: 640px;
  border-collapse: separate;
  border-spacing: 0;
  background: var(--surface);
}
thead th {
  position: sticky;
  top: 0;
  z-index: 1;
  white-space: nowrap;
  background: var(--surface-muted);
}
th,
td {
  padding: 9px 10px;
  border-block-end: 1px solid var(--line);
  border-inline-end: 1px solid var(--line);
  text-align: left;
  vertical-align: top;
}
th:last-child,
td:last-child {
  border-inline-end: 0;
}
tbody tr:last-child > * {
  border-block-end: 0;
}
td {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  min-width: 80px;
}
.content-cell {
  width: 50%;
  min-width: 200px;
}
.content-cell :deep(img) {
  max-height: 250px;
}
td small {
  display: block;
  color: var(--text-secondary);
}
.cell-edit {
  padding: 0;
  text-align: left;
  white-space: pre-wrap;
}
.empty-cell {
  text-align: center;
  color: var(--text-secondary);
}
.sheet-status {
  color: var(--success);
}
.extraction-preview {
  padding: 14px;
  display: grid;
  gap: 10px;
  background: var(--surface-muted);
  border-radius: 8px;
}
.extraction-departments {
  display: flex;
  gap: 20px;
  flex-wrap: wrap;
}
.extraction-preview > button {
  justify-self: start;
}
</style>
