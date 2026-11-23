<script setup lang="ts">
import { computed } from "vue";
import MeetingPeoplePicker from "./MeetingPeoplePicker.vue";
import type { MeetingOption } from "./meeting";

const props = defineProps<{
  departments: MeetingOption[];
  /** 协调事项发起人和配合部门责任人由协调结果带入。 */
  defaults?: MeetingOption[];
}>();
const trackers = defineModel<MeetingOption[]>({ required: true });
const additionalTrackers = computed(() =>
  trackers.value.filter((person) => !props.defaults?.some((tracker) => tracker.id === person.id)),
);
</script>

<template>
  <div class="trackers-picker">
    <span>跟踪人员</span>
    <ul v-if="defaults?.length || trackers.length" class="trackers" aria-label="已选跟踪人员">
      <li v-for="person in defaults" :key="person.id">{{ person.name }}</li>
      <li v-for="person in additionalTrackers" :key="person.id">
        {{ person.name }}
        <button
          type="button"
          class="icon-button"
          :aria-label="'移除' + person.name"
          @click="trackers = trackers.filter((tracker) => tracker.id !== person.id)"
        >×</button>
      </li>
    </ul>
    <MeetingPeoplePicker
      :departments="departments"
      :excluded="[...(defaults ?? []), ...trackers].map((person) => person.id)"
      label="添加跟踪人员"
      multiple
      @confirm="(people) => (trackers = [...trackers, ...people])"
    />
  </div>
</template>

<style scoped>
.trackers-picker {
  display: grid;
  justify-items: start;
  gap: 6px;
}
.trackers {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  list-style: none;
  margin: 0;
  padding: 0;
}
.trackers li {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
}
.trackers .icon-button {
  width: 24px;
  min-height: 24px;
}
</style>
