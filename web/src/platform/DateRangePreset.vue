<script setup lang="ts">
import { computed } from "vue";
import { recentDateRange } from "./listDates";

/** 快捷选项由表单的真实日期反推，手动修改后自动显示自定义。 */
const props = defineProps<{
  label: string;
  from?: string;
  to?: string;
  busy?: boolean;
  required?: boolean;
}>();
const emit = defineEmits<{ apply: [range: { from: string; to: string }] }>();
const selection = computed(() => {
  if (!props.from && !props.to && !props.required) return "";
  for (const days of [7, 30] as const) {
    const range = recentDateRange(days);
    if (props.from === range.from && props.to === range.to) return String(days);
  }
  return "custom";
});
/** 套用快捷范围后由所属列表从第一页重新查询。 */
function choose(event: Event) {
  const value = (event.target as HTMLSelectElement).value;
  emit("apply", value === "" ? { from: "", to: "" } : recentDateRange(value === "7" ? 7 : 30));
}
</script>

<template>
  <label class="list-filter-field">
    {{ label }}
    <select :value="selection" :disabled="busy" @change="choose">
      <option v-if="!required" value="">不限</option>
      <option value="7">最近一周</option>
      <option value="30">最近一个月</option>
      <option v-if="selection === 'custom'" value="custom" disabled>自定义</option>
    </select>
  </label>
</template>
