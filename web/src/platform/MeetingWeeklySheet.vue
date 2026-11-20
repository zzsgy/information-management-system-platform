<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { api } from "./api";
import { portalTime } from "./portal";
import type {
  WeeklyDepartment,
  WeeklyPreview,
  WeeklySheet,
} from "./weeklyReport";

const props = defineProps<{ meetingId: string; open: boolean }>();
const emit = defineEmits<{ extracted: [] }>();
const base = "/meetings/" + props.meetingId + "/weekly-report";
const sheet = ref<WeeklySheet>();
const preview = ref<WeeklyPreview>();
const error = ref("");
const status = ref("");
const busy = ref(false);
const active = computed(
  () => sheet.value?.departments.filter(({ active }) => active) ?? [],
);
const own = computed(() =>
  active.value.find(
    (unit) => unit.departmentId === sheet.value?.ownDepartmentId,
  ),
);
let timer: number | undefined;

/** 直接在用户点击时打开新标签页，工作簿由页面再向服务器鉴权。 */
function excelLink(department: string, version?: number) {
  const params = new URLSearchParams({ id: props.meetingId, department });
  if (version) params.set("version", String(version));
  return "#/meeting-weekly-excel?" + params;
}

/** 回到会议页时刷新已提交状态，历史数据由服务器确认。 */
async function refresh(background = false) {
  try {
    sheet.value = await api<WeeklySheet>(base);
  } catch (failure) {
    if (!background) error.value = (failure as Error).message;
  }
}
onMounted(() => {
  void refresh();
  timer = window.setInterval(() => {
    if (props.open && !document.hidden && !busy.value) void refresh(true);
  }, 5000);
});
onUnmounted(() => window.clearInterval(timer));

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

function departmentAction(unit: WeeklyDepartment, action: "submit" | "reopen") {
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
</script>

<template>
  <section class="flow-dialog-section weekly-sheet" aria-label="在线周会表">
    <header>
      <div>
        <h2>在线周会表</h2>
        <p v-if="sheet?.canExtract">
          {{ active.filter((unit) => unit.submittedVersion).length }} /
          {{ active.length }} 个部门已提交
        </p>
        <p v-else-if="own?.canEdit">
          点击在线编辑，在新标签页填写本部门的工作簿。
        </p>
      </div>
      <div class="weekly-sheet-actions">
        <a
          v-if="own?.canEdit"
          class="primary"
          :href="excelLink(own.departmentId)"
          target="_blank"
          rel="noopener"
        >
          在线编辑 Excel
        </a>
        <button
          v-if="sheet?.canExtract"
          type="button"
          class="primary"
          :disabled="busy"
          @click="showPreview"
        >
          提取本场材料
        </button>
        <a
          v-if="sheet?.departments.some((unit) => unit.submittedVersion)"
          class="secondary"
          :href="'/api/v1' + base + '/summary.xlsx'"
          >下载周会汇总 Excel</a
        >
      </div>
    </header>

    <p v-if="error" role="alert" class="form-error">{{ error }}</p>
    <p v-if="status" role="status">{{ status }}</p>
    <p v-if="!sheet && !error" role="status">正在加载…</p>

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
      <ul v-if="preview.ready.length">
        <li v-for="unit in preview.ready" :key="unit.departmentId">
          <a
            :href="excelLink(unit.departmentId, unit.submittedVersion)"
            target="_blank"
            rel="noopener"
          >
            {{ unit.name }} · 查看第 {{ unit.submittedVersion }} 版 Excel
          </a>
        </li>
      </ul>
      <p v-else>暂无新的已提交材料。</p>
      <p v-if="preview.missing.length">
        未提交 {{ preview.missing.length }} 个部门 ·
        {{ preview.missing.map((unit) => unit.name).join("、") }}
      </p>
      <button
        type="button"
        class="primary"
        :disabled="busy || !preview.ready.length || !!preview.errors.length"
        @click="extract"
      >
        确认提取
      </button>
    </section>

    <ul v-if="sheet?.departments.length" class="weekly-departments">
      <li v-for="unit in sheet.departments" :key="unit.departmentId">
        <div>
          <h3>{{ unit.name }}</h3>
          <p>
            {{
              unit.headName ? "负责人 " + unit.headName : "尚未配置部门负责人"
            }}
            <template v-if="unit.headName && !unit.headInvited && open">
              · 负责人尚未受邀</template
            >
            ·
            {{
              unit.active
                ? unit.submittedVersion
                  ? "已提交第 " + unit.submittedVersion + " 版"
                  : "待提交"
                : "暂无有效受邀人"
            }}
            <template v-if="unit.extractedVersion">
              · 已提取第 {{ unit.extractedVersion }} 版</template
            >
          </p>
        </div>
        <div class="weekly-sheet-actions">
          <a
            v-if="!unit.canEdit"
            class="secondary"
            :href="excelLink(unit.departmentId)"
            target="_blank"
            rel="noopener"
            >查看 Excel</a
          >
          <button
            v-if="unit.canSubmit"
            type="button"
            class="primary"
            :disabled="busy"
            @click="departmentAction(unit, 'submit')"
          >
            提交部门周报
          </button>
          <button
            v-if="unit.canReopen"
            type="button"
            class="secondary"
            :disabled="busy"
            @click="departmentAction(unit, 'reopen')"
          >
            重新开放填写
          </button>
        </div>
        <details
          v-if="
            sheet.versions.some(
              ({ departmentId }) => unit.departmentId === departmentId,
            )
          "
        >
          <summary>提交历史</summary>
          <ul>
            <li
              v-for="version in sheet.versions.filter(
                ({ departmentId }) => unit.departmentId === departmentId,
              )"
              :key="version.version"
            >
              <a
                :href="excelLink(unit.departmentId, version.version)"
                target="_blank"
                rel="noopener"
              >
                第 {{ version.version }} 版 · {{ version.submittedBy }} ·
                {{ portalTime(version.submittedAt) }}
              </a>
            </li>
          </ul>
        </details>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.weekly-sheet {
  display: grid;
  gap: 14px;
}
.weekly-sheet :is(h2, h3, p) {
  margin: 0;
}
header,
.weekly-sheet-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
}
header p,
.weekly-departments p {
  color: var(--text-secondary);
  margin-block-start: 4px;
}
.weekly-departments {
  padding: 0;
  margin: 0;
  list-style: none;
}
.weekly-departments > li {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-block: 12px;
  border-top: 1px solid var(--line);
}
.weekly-departments details {
  width: 100%;
}
.extraction-preview {
  padding: 14px;
  display: grid;
  gap: 10px;
  background: var(--surface-muted);
  border-radius: 8px;
}
.extraction-preview > button {
  justify-self: start;
}
</style>
