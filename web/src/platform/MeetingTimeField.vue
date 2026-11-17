<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{ label: string; minimum: string }>();
const value = defineModel<string>({ required: true });
const date = computed(
  () => value.value.slice(0, 10) || props.minimum.slice(0, 10),
);
const hour = computed(() => value.value.slice(11, 13));
const minute = computed(() => value.value.slice(14, 16));
const minimumDate = computed(() => props.minimum.slice(0, 10));
const minimumHour = computed(() =>
  date.value === minimumDate.value ? Number(props.minimum.slice(11, 13)) : 0,
);
const hours = computed(() =>
  Array.from({ length: 24 - minimumHour.value }, (_, i) =>
    pad(i + minimumHour.value),
  ),
);
const minutes = computed(() => {
  const first =
    date.value === minimumDate.value && Number(hour.value) === minimumHour.value
      ? Number(props.minimum.slice(14, 16))
      : 0;
  return Array.from({ length: 60 - first }, (_, i) => pad(i + first));
});

function pad(n: number) {
  return String(n).padStart(2, "0");
}

/** 切换日期或小时后，将分钟移到该时段第一个有效选项。 */
function select(day: string, h: string, m: string) {
  if (!day || day < minimumDate.value) day = minimumDate.value;
  const candidate = day + "T" + (h || "00") + ":" + (m || "00") + ":00";
  value.value = candidate < props.minimum ? props.minimum : candidate;
}

function openCalendar(event: MouseEvent) {
  const input = event.currentTarget as HTMLInputElement;
  input.showPicker?.();
}
</script>

<template>
  <fieldset class="meeting-time">
    <legend>{{ label }}</legend>
    <div class="meeting-time-fields">
      <label>
        <span class="sr-only">{{ label }}日期</span>
        <input
          type="date"
          required
          :value="date"
          :min="minimumDate"
          @click="openCalendar"
          @change="
            select(($event.target as HTMLInputElement).value, hour, minute)
          "
        />
      </label>
      <label>
        <span class="sr-only">{{ label }}小时</span>
        <select
          required
          :value="hour"
          @change="
            select(date, ($event.target as HTMLSelectElement).value, minute)
          "
        >
          <option value="" disabled>时</option>
          <option v-for="h in hours" :key="h" :value="h">{{ h }} 时</option>
        </select>
      </label>
      <label>
        <span class="sr-only">{{ label }}分钟</span>
        <select
          required
          :value="minute"
          @change="
            select(date, hour, ($event.target as HTMLSelectElement).value)
          "
        >
          <option value="" disabled>分</option>
          <option v-for="m in minutes" :key="m" :value="m">{{ m }} 分</option>
        </select>
      </label>
    </div>
  </fieldset>
</template>

<style scoped>
.meeting-time {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
}
.meeting-time legend {
  padding: 0;
  margin-bottom: 8px;
  font-weight: 600;
}
.meeting-time-fields {
  display: grid;
  grid-template-columns: minmax(105px, 1fr) 70px 70px;
  gap: 6px;
}
.meeting-time-fields label {
  min-width: 0;
}
.meeting-time-fields input,
.meeting-time-fields select {
  width: 100%;
  min-width: 0;
  padding-inline: 6px;
}
@media (max-width: 420px) {
  .meeting-time-fields {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .meeting-time-fields label:first-child {
    grid-column: 1 / -1;
  }
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
</style>
