<script setup lang="ts">
import { onUnmounted, ref, useId, watch } from "vue";
import { api } from "./api";
import type { MeetingOption, MeetingPerson } from "./meeting";
import Icon from "../preview/PlatformIcon.vue";

const props = defineProps<{
  departments: MeetingOption[];
  excluded?: string[];
  label: string;
  multiple?: boolean;
}>();
const emit = defineEmits<{
  select: [person: MeetingPerson];
  confirm: [people: MeetingPerson[]];
}>();
const titleId = useId();
const dialog = ref<HTMLDialogElement>();
const selected = ref<MeetingPerson[]>([]);
watch(dialog, (element) => element?.showModal(), { flush: "post" });
const open = ref(false);
const department = ref("");
const keyword = ref("");
const page = ref(1);
const hasNext = ref(false);
const candidates = ref<MeetingPerson[]>([]);
const error = ref("");
const busy = ref(false);
const selectingDepartment = ref(false);
const departmentError = ref("");
let departmentRequest: AbortController | undefined;
onUnmounted(() => departmentRequest?.abort());
watch(
  [open, department],
  () => {
    departmentRequest?.abort();
    departmentError.value = "";
  },
  { flush: "sync" },
);

/** 全选读取部门全部分页，保留此前选中的人员，并跳过已在邀请名单中的员工。 */
async function selectDepartment() {
  if (!department.value || selectingDepartment.value) return;
  const request = new AbortController();
  departmentRequest = request;
  selectingDepartment.value = true;
  departmentError.value = "";
  const params = new URLSearchParams({ departmentId: department.value });
  const people: MeetingPerson[] = [];
  try {
    let next = true;
    for (let index = 1; next; index++) {
      params.set("page", String(index));
      const result = await api<{ items: MeetingPerson[]; hasNext: boolean }>(
        "/meetings/people?" + params,
        { signal: request.signal },
      );
      people.push(...result.items);
      next = result.hasNext;
    }
    if (request.signal.aborted) return;
    const existing = new Set([
      ...(props.excluded ?? []),
      ...selected.value.map((person) => person.id),
    ]);
    const additions = people.filter((person) => !existing.has(person.id));
    selected.value = [...selected.value, ...additions];
  } catch (e) {
    if (!request.signal.aborted) departmentError.value = (e as Error).message;
  } finally {
    selectingDepartment.value = false;
    departmentRequest = undefined;
  }
}

/** 仅在打开选择器时根据姓名、工号或部门查询，过期响应不能覆盖当前选择。 */
watch([open, department, keyword, page], async ([visible], _, onCleanup) => {
  if (!visible) return;
  const controller = new AbortController();
  onCleanup(() => controller.abort());
  busy.value = true;
  error.value = "";
  candidates.value = [];
  const params = new URLSearchParams({ page: String(page.value) });
  if (keyword.value.trim()) params.set("keyword", keyword.value.trim());
  if (department.value) params.set("departmentId", department.value);
  try {
    // 等输入停稳再发请求，换部门和翻页直接刷新。
    if (keyword.value.trim())
      await new Promise((resolve) => setTimeout(resolve, 180));
    if (controller.signal.aborted) return;
    const result = await api<{ items: MeetingPerson[]; hasNext: boolean }>(
      "/meetings/people?" + params,
      { signal: controller.signal },
    );
    candidates.value = result.items;
    hasNext.value = result.hasNext;
  } catch (e) {
    if (!controller.signal.aborted) error.value = (e as Error).message;
  } finally {
    if (!controller.signal.aborted) busy.value = false;
  }
});
function toggleOpen() {
  if (!open.value && props.multiple) {
    selected.value = [];
    department.value = "";
    keyword.value = "";
    page.value = 1;
  }
  open.value = !open.value;
}
function choose(person: MeetingPerson) {
  if (props.multiple) {
    if (selected.value.some((item) => item.id === person.id)) {
      selected.value = selected.value.filter((item) => item.id !== person.id);
    } else {
      selected.value.push(person);
    }
    return;
  }
  emit("select", person);
  open.value = false;
}
function confirm() {
  emit("confirm", selected.value);
  open.value = false;
}
</script>

<template>
  <div class="meeting-picker">
    <button
      type="button"
      class="secondary"
      :aria-expanded="open"
      @click="toggleOpen"
    >
      <Icon name="people" :size="16" />{{ label }}
    </button>
    <component
      :is="multiple ? 'dialog' : 'div'"
      v-if="open"
      :ref="
        (element: unknown) => {
          if (multiple) dialog = element as HTMLDialogElement | undefined;
        }
      "
      :class="
        multiple
          ? 'form-dialog flow-dialog invite-picker'
          : 'meeting-person-panel'
      "
      :aria-labelledby="multiple ? titleId : undefined"
      @cancel.prevent="open = false"
    >
      <div :class="{ 'flow-dialog-shell': multiple }">
        <header v-if="multiple" class="flow-dialog-header">
          <span class="flow-dialog-icon" aria-hidden="true"
            ><Icon name="people" :size="22"
          /></span>
          <h2 :id="titleId">选择受邀员工</h2>
          <button
            type="button"
            class="icon-button"
            aria-label="关闭选人弹窗"
            @click="open = false"
          >
            ×
          </button>
        </header>
        <div :class="{ 'flow-dialog-body': multiple }">
          <div class="meeting-form-row picker-filters">
            <label
              >部门<select v-model="department" @change="page = 1">
                <option value="">所有部门</option>
                <option v-for="d in departments" :key="d.id" :value="d.id">
                  {{ d.name }}
                </option>
              </select></label
            >
            <label
              >姓名或工号<input
                v-model="keyword"
                maxlength="128"
                type="search"
                placeholder="输入姓名或工号搜索"
                @input="page = 1"
            /></label>
          </div>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <div :class="{ 'picker-columns': multiple }">
            <section
              :aria-label="multiple ? '可选员工' : undefined"
              :class="{ 'picker-pane': multiple }"
            >
              <h3 v-if="multiple" class="picker-pane-title">
                可选员工
                <button
                  v-if="department"
                  type="button"
                  class="secondary department-all"
                  :disabled="selectingDepartment"
                  @click="selectDepartment"
                >
                  {{ selectingDepartment ? "正在选择…" : "全选本部门" }}
                </button>
              </h3>
              <p v-if="departmentError" class="form-error" role="alert">
                {{ departmentError }}
              </p>
              <p v-if="busy" class="picker-hint" role="status">加载员工中</p>
              <p v-else-if="!candidates.length && !error" class="picker-hint">
                未找到符合条件的员工。
              </p>
              <div
                v-else
                :class="
                  multiple
                    ? 'picker-rows'
                    : 'meeting-person-results meeting-picker-results'
                "
              >
                <button
                  v-for="person in candidates"
                  :key="person.id"
                  type="button"
                  :class="multiple ? 'picker-row' : 'secondary'"
                  :disabled="excluded?.includes(person.id)"
                  :aria-pressed="
                    multiple
                      ? selected.some((item) => item.id === person.id)
                      : undefined
                  "
                  @click="choose(person)"
                >
                  <span v-if="multiple" class="picker-mark" aria-hidden="true">
                    <Icon
                      v-if="selected.some((item) => item.id === person.id)"
                      name="check"
                      :size="14"
                    />
                  </span>
                  <span
                    ><strong>{{ person.name }}</strong
                    ><small>{{ person.employeeNo }}</small></span
                  >
                  <small v-if="multiple" class="picker-department">
                    {{
                      excluded?.includes(person.id)
                        ? "不可重复添加"
                        : departments.find((d) => d.id === person.departmentId)
                            ?.name
                    }}
                  </small>
                </button>
              </div>
              <div
                v-if="page > 1 || hasNext"
                class="meeting-actions picker-pagination"
              >
                <button
                  type="button"
                  class="secondary"
                  :disabled="page <= 1 || busy"
                  @click="page--"
                >
                  上一页
                </button>
                <span>第 {{ page }} 页</span>
                <button
                  type="button"
                  class="secondary"
                  :disabled="!hasNext || busy"
                  @click="page++"
                >
                  下一页
                </button>
              </div>
            </section>
            <section v-if="multiple" aria-label="已选员工" class="picker-pane">
              <h3>
                已选员工 <small>{{ selected.length }} 人</small>
              </h3>
              <p v-if="!selected.length" class="picker-hint">
                点击左侧员工加入名单。
              </p>
              <ul v-else class="picker-selected">
                <li v-for="person in selected" :key="person.id">
                  <span
                    ><strong>{{ person.name }}</strong
                    ><small>{{ person.employeeNo }}</small></span
                  >
                  <button
                    type="button"
                    class="icon-button"
                    :aria-label="'移除 ' + person.name"
                    @click="choose(person)"
                  >
                    ×
                  </button>
                </li>
              </ul>
            </section>
          </div>
        </div>
        <footer v-if="multiple" class="flow-dialog-footer">
          <button type="button" class="secondary" @click="open = false">
            取消
          </button>
          <button
            type="button"
            class="primary"
            :disabled="!selected.length || selectingDepartment"
            @click="confirm"
          >
            添加选中的 {{ selected.length }} 人
          </button>
        </footer>
      </div>
    </component>
  </div>
</template>

<style scoped>
.meeting-picker .meeting-person-panel {
  background: var(--surface-muted);
  padding: 14px;
}
.meeting-picker .meeting-picker-results {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 145px), 1fr));
  max-height: 210px;
  overflow-y: auto;
}
.meeting-picker-results button {
  display: grid;
  text-align: left;
  background: var(--surface);
}
.meeting-picker small {
  color: var(--text-secondary);
  font-size: 12px;
}
.meeting-picker strong + small {
  margin-left: 8px;
}
.invite-picker {
  --flow-dialog-width: 790px;
}
.picker-filters {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.6fr);
  gap: 12px;
}
.picker-filters label {
  margin: 0;
  min-width: 0;
}
.picker-columns {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr);
  gap: 12px;
}
.picker-pane {
  min-width: 0;
  border: 1px solid var(--line);
  border-radius: 9px;
  overflow: hidden;
}
.picker-pane h3 {
  padding: 10px 12px;
  margin: 0;
  background: var(--surface-muted);
}
.picker-pane-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}
.department-all {
  min-height: 28px;
  padding: 3px 8px;
  font-size: 12px;
}
.picker-hint {
  padding: 12px;
  color: var(--text-secondary);
}
.picker-rows,
.picker-selected {
  max-height: min(31vh, 260px);
  overflow-y: auto;
}
.picker-row {
  width: 100%;
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 0;
  background: var(--surface);
  color: inherit;
  min-height: 40px;
  text-align: left;
  padding: 7px 11px;
  font: inherit;
}
.picker-row + .picker-row,
.picker-selected li + li {
  border-top: 1px solid var(--line);
}
.picker-row:hover:not(:disabled),
.picker-row[aria-pressed="true"] {
  background: var(--brand-soft);
}
.picker-mark {
  display: grid;
  place-items: center;
  width: 16px;
  height: 16px;
  border: 1px solid var(--line);
  border-radius: 4px;
  background: var(--surface);
}
.picker-row[aria-pressed="true"] .picker-mark {
  background: var(--brand);
  color: white;
  border-color: var(--brand);
}
.picker-row span,
.picker-department,
.picker-selected li span {
  overflow-wrap: anywhere;
}
.picker-selected {
  margin: 0;
  padding: 0;
  list-style: none;
}
.picker-selected li {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 4px 10px;
}
.picker-pagination {
  justify-content: center;
  padding: 8px;
  font-size: 12px;
  border-top: 1px solid var(--line);
}
@media (max-width: 560px) {
  .picker-columns {
    grid-template-columns: minmax(0, 1fr);
  }
  .picker-selected {
    max-height: 105px;
  }
}
</style>
