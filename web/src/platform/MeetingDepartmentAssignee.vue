<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElOption, ElSelect } from "element-plus";
import "element-plus/es/components/select/style/css";
import { pinyin } from "pinyin-pro";
import { api } from "./api";
import type { MeetingPerson } from "./meeting";

const props = defineProps<{
  departmentId?: string | null;
  selectedName?: string | null;
  /** 发起人在原生对话框内编辑时，下拉面板也需要挂在该对话框内。 */
  appendTo?: HTMLElement;
  label: string;
}>();
const assignee = defineModel<string>({ required: true });
const candidates = ref<MeetingPerson[]>([]);
const loading = ref(false);
const error = ref("");
const keyword = ref("");

/** 一次生成姓名拼音和首字母，输入时在完整名单内筛选。 */
const options = computed(() =>
  candidates.value.map((person) => {
    const syllables = pinyin(person.name, {
      type: "array",
      toneType: "none",
      surname: "head",
      v: true,
    });
    return {
      person,
      label: `${person.name} · ${person.employeeNo}`,
      fullPinyin: syllables.join("").toLowerCase(),
      initials: syllables
        .map((syllable) => syllable[0])
        .join("")
        .toLowerCase(),
    };
  }),
);
const matches = computed(() => {
  const query = keyword.value
    .toLowerCase()
    .replace(/[\s'-]+/g, "")
    .replace(/ü/g, "v");
  return query
    ? options.value.filter(
        ({ person, fullPinyin, initials }) =>
          person.name.toLowerCase().includes(query) ||
          person.employeeNo
            .toLowerCase()
            .includes(keyword.value.trim().toLowerCase()) ||
          fullPinyin.includes(query) ||
          initials.includes(query),
      )
    : options.value;
});
const missingSelection = computed(
  () =>
    assignee.value &&
    !candidates.value.some((person) => person.id === assignee.value),
);

/** 按配合范围读取完整候选名单，切换部门后忽略上一请求的结果。 */
watch(
  () => props.departmentId,
  async (department, _, onCleanup) => {
    candidates.value = [];
    keyword.value = "";
    error.value = "";
    loading.value = !!department;
    if (!department) return;
    const request = new AbortController();
    onCleanup(() => request.abort());
    try {
      const people: MeetingPerson[] = [];
      let hasNext = true;
      for (let page = 1; hasNext; page++) {
        const params = new URLSearchParams({
          departmentId: department,
          scope: "COORDINATION",
          page: String(page),
        });
        const result = await api<{ items: MeetingPerson[]; hasNext: boolean }>(
          "/meetings/people?" + params,
          { signal: request.signal },
        );
        people.push(...result.items);
        hasNext = result.hasNext;
      }
      if (!request.signal.aborted) candidates.value = people;
    } catch (e) {
      if (!request.signal.aborted) error.value = (e as Error).message;
    } finally {
      if (!request.signal.aborted) loading.value = false;
    }
  },
  { immediate: true },
);
</script>

<template>
  <ElSelect
    v-model="assignee"
    class="meeting-assignee"
    :aria-label="label"
    :append-to="appendTo"
    :disabled="!departmentId || !!error"
    :loading="loading"
    :placeholder="departmentId ? '姓名、拼音或工号' : '请先选配合部门'"
    filterable
    default-first-option
    loading-text="正在加载员工…"
    :no-data-text="keyword.trim() ? '未找到匹配员工' : '暂无可选员工'"
    :filter-method="(value: string) => (keyword = value)"
    @visible-change="keyword = ''"
  >
    <template #label="{ label: selectedLabel }">
      <span :title="selectedLabel">{{ selectedLabel }}</span>
    </template>
    <ElOption
      v-if="missingSelection && !keyword"
      :value="assignee"
      :label="selectedName || '原责任人'"
      disabled
    />
    <ElOption
      v-for="{ person, label: optionLabel } in matches"
      :key="person.id"
      :value="person.id"
      :label="optionLabel"
    />
  </ElSelect>
  <small v-if="error" role="alert">{{ error }}</small>
</template>

<style scoped>
.meeting-assignee {
  width: 100%;
  min-width: 0;
  --el-color-primary: var(--brand);
  --el-border-color: var(--line);
  --el-border-radius-base: 7px;
  --el-fill-color-blank: var(--surface);
  --el-text-color-regular: var(--ink);
}
.meeting-assignee :deep(.el-select__wrapper) {
  min-height: 40px;
}
/* 表格里的选择框与其他单行字段等高，只由外框显示焦点。 */
.meeting-assignee :deep(.el-select__input) {
  height: 24px;
  min-height: 0;
  padding: 0;
  background: transparent;
  box-shadow: none;
}
</style>
