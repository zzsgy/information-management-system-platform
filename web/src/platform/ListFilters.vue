<script setup lang="ts">
import { nextTick, reactive, ref } from "vue";
import DateRangePreset from "./DateRangePreset.vue";

/** 按业务列表表头配置筛选控件，日期字段自动生成同名前缀的起止条件。 */
export interface ListFilterField {
  key: string;
  label: string;
  type?: "date";
  options?: Record<string, string>;
  /** 下拉选项变化时提交整组条件，沿用表单校验。 */
  applyOnChange?: boolean;
  placeholder?: string;
}
const props = defineProps<{
  fields: ListFilterField[];
  values?: Record<string, string>;
  defaults?: Record<string, string>;
  busy?: boolean;
}>();
const emit = defineEmits<{ apply: [filters: Record<string, string>] }>();
/** 草稿与已提交条件分开，翻页和刷新只使用后者。 */
function initialDraft() {
  const values: Record<string, string> = {};
  for (const field of props.fields) {
    if (field.type === "date") {
      values[field.key + "From"] = "";
      values[field.key + "To"] = "";
    } else {
      values[field.key] = "";
    }
  }
  return { ...values, ...props.defaults };
}
const draft = reactive({ ...initialDraft(), ...props.values });
const filterForm = ref<HTMLFormElement | null>(null);
function apply() {
  emit(
    "apply",
    Object.fromEntries(
      Object.entries(draft).map(([key, value]) => [key, value.trim()]),
    ),
  );
}
/** 等待下拉值写入草稿，再通过表单提交。 */
function submitSelection(event: Event) {
  const form = (event.target as HTMLSelectElement).form;
  void nextTick(() => form?.requestSubmit());
}
/** 快捷范围与其他已填筛选条件一起提交，继续使用日期控件的表单校验。 */
function applyDatePreset(key: string, range: { from: string; to: string }) {
  draft[key + "From"] = range.from;
  draft[key + "To"] = range.to;
  void nextTick(() => filterForm.value?.requestSubmit());
}
function reset() {
  for (const key of Object.keys(draft)) delete draft[key];
  Object.assign(draft, initialDraft());
  apply();
}
</script>

<template>
  <form
    ref="filterForm"
    class="list-toolbar field-filters"
    aria-label="列表筛选"
    @submit.prevent="apply"
  >
    <template v-for="field in fields" :key="field.key">
      <template v-if="field.type === 'date'">
        <DateRangePreset
          :label="`${field.label}范围`"
          :from="draft[field.key + 'From']"
          :to="draft[field.key + 'To']"
          :busy="busy"
          @apply="applyDatePreset(field.key, $event)"
        />
        <label class="list-filter-field">
          {{ field.label }}开始
          <input
            v-model="draft[field.key + 'From']"
            type="date"
            :max="draft[field.key + 'To'] || undefined"
          />
        </label>
        <label class="list-filter-field">
          {{ field.label }}结束
          <input
            v-model="draft[field.key + 'To']"
            type="date"
            :min="draft[field.key + 'From'] || undefined"
          />
        </label>
      </template>
      <label v-else class="list-filter-field">
        {{ field.label }}
        <select
          v-if="field.options"
          v-model="draft[field.key]"
          :disabled="field.applyOnChange && busy"
          @change="field.applyOnChange && submitSelection($event)"
        >
          <option value="">全部</option>
          <option
            v-for="(label, value) in field.options"
            :key="value"
            :value="value"
          >
            {{ label }}
          </option>
        </select>
        <input
          v-else
          v-model="draft[field.key]"
          type="search"
          maxlength="200"
          :placeholder="field.placeholder"
        />
      </label>
    </template>
    <div class="filter-buttons">
      <button class="primary" :disabled="busy">查询</button>
      <button class="secondary" type="button" :disabled="busy" @click="reset">
        重置
      </button>
    </div>
  </form>
</template>

<style scoped>
.platform-shell .field-filters > .list-filter-field {
  flex: 0 1 220px;
  min-width: min(220px, 100%);
}
.filter-buttons {
  display: flex;
  gap: 8px;
}
</style>
